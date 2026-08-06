package com.owlitech.spatial.ar

class ArDiagnosticSessionController(
    private val sessionFactory: DiagnosticSessionFactory,
    private val observationConverter: DiagnosticObservationConverter = DiagnosticObservationConverter(),
    private val clockNanos: () -> Long = System::nanoTime,
    private val minimumPublishIntervalNanos: Long = 125_000_000L,
    private val onStateChanged: (SessionLifecycleState, DiagnosticObservation?) -> Unit,
) {
    private var prerequisitesReady = false
    private var session: DiagnosticSessionPort? = null
    private var sessionResumed = false
    private var terminallyClosed = false
    private var lifecycleState: SessionLifecycleState = SessionLifecycleState.WaitingForPrerequisites

    private var surfaceReady = false
    private var cameraTextureId = 0
    private var configuredTextureId = 0
    private var displayRotation = 0
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var displayGeometryDirty = true

    private var lastObservation: DiagnosticObservation? = null
    private var lastPublishedObservation: DiagnosticObservation? = null
    private var lastPublishedAtNanos = Long.MIN_VALUE

    init {
        publish(lifecycleState, null)
    }

    @Synchronized
    fun setPrerequisitesReady(ready: Boolean) {
        if (terminallyClosed || prerequisitesReady == ready) return
        prerequisitesReady = ready
        if (!ready) {
            releaseOwnedSession(SessionLifecycleState.WaitingForPrerequisites)
        } else if (session == null) {
            setLifecycle(SessionLifecycleState.Ready)
        }
    }

    @Synchronized
    fun resume() {
        if (terminallyClosed || !prerequisitesReady || sessionResumed) return
        val ownedSession = session ?: createSessionOrNull() ?: return
        setLifecycle(SessionLifecycleState.Resuming)
        try {
            ownedSession.resume()
            sessionResumed = true
            setLifecycle(SessionLifecycleState.Running)
        } catch (error: Throwable) {
            sessionResumed = false
            setError(SessionOperation.RESUME, error)
        }
    }

    @Synchronized
    fun onSurfaceCreated(textureId: Int) {
        if (terminallyClosed) return
        surfaceReady = textureId > 0
        cameraTextureId = textureId
        configuredTextureId = 0
        displayGeometryDirty = true
    }

    @Synchronized
    fun onSurfaceChanged(displayRotation: Int, width: Int, height: Int) {
        if (terminallyClosed) return
        this.displayRotation = displayRotation
        viewportWidth = width
        viewportHeight = height
        displayGeometryDirty = true
    }

    @Synchronized
    fun onDisplayRotationChanged(displayRotation: Int) {
        if (terminallyClosed || this.displayRotation == displayRotation) return
        this.displayRotation = displayRotation
        displayGeometryDirty = true
    }

    @Synchronized
    fun onSurfaceDestroyed() {
        surfaceReady = false
        cameraTextureId = 0
        configuredTextureId = 0
        viewportWidth = 0
        viewportHeight = 0
        displayGeometryDirty = true
    }

    /** Called exclusively from the GLSurfaceView render thread. */
    @Synchronized
    fun updateFrame() {
        if (!canUpdate()) return
        val ownedSession = session ?: return
        if (configuredTextureId != cameraTextureId) {
            try {
                ownedSession.setCameraTextureName(cameraTextureId)
                configuredTextureId = cameraTextureId
            } catch (error: Throwable) {
                stopAfterFrameError(SessionOperation.CAMERA_TEXTURE, error)
                return
            }
        }
        if (displayGeometryDirty) {
            try {
                ownedSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                displayGeometryDirty = false
            } catch (error: Throwable) {
                stopAfterFrameError(SessionOperation.DISPLAY_GEOMETRY, error)
                return
            }
        }
        try {
            val observation = observationConverter.convert(ownedSession.update())
            lastObservation = observation
            publishObservationIfDue(observation)
        } catch (error: Throwable) {
            stopAfterFrameError(SessionOperation.UPDATE, error)
        }
    }

    @Synchronized
    fun pause() {
        if (terminallyClosed || !sessionResumed) return
        sessionResumed = false
        val ownedSession = session
        try {
            ownedSession?.pause()
            lastObservation = null
            lastPublishedObservation = null
            setLifecycle(SessionLifecycleState.Paused)
        } catch (error: Throwable) {
            // A failed pause must not leave a Session eligible for further updates or foreground
            // reuse. Attempt terminal release while preserving the pause failure as the visible
            // diagnostic state.
            session = null
            configuredTextureId = 0
            lastObservation = null
            lastPublishedObservation = null
            try {
                ownedSession?.close()
            } catch (_: Throwable) {
                // Preserve the primary pause failure. The adapter reference is discarded either way.
            }
            setError(SessionOperation.PAUSE, error)
        }
    }

    @Synchronized
    fun close() {
        if (terminallyClosed) return
        if (sessionResumed) pause()
        terminallyClosed = true
        val ownedSession = session
        session = null
        if (ownedSession != null) {
            try {
                ownedSession.close()
            } catch (error: Throwable) {
                setError(SessionOperation.CLOSE, error)
                return
            }
        }
        lastObservation = null
        lastPublishedObservation = null
        setLifecycle(SessionLifecycleState.Closed)
    }

    @Synchronized
    fun currentLifecycleState(): SessionLifecycleState = lifecycleState

    @Synchronized
    fun hasOwnedSession(): Boolean = session != null

    private fun createSessionOrNull(): DiagnosticSessionPort? {
        setLifecycle(SessionLifecycleState.Creating)
        return try {
            sessionFactory.create().also { session = it }
        } catch (error: Throwable) {
            setError(SessionOperation.CREATE, error)
            null
        }
    }

    private fun releaseOwnedSession(nextState: SessionLifecycleState) {
        if (sessionResumed) {
            pause()
            if (lifecycleState is SessionLifecycleState.Error) return
        }
        val ownedSession = session
        session = null
        sessionResumed = false
        configuredTextureId = 0
        lastObservation = null
        lastPublishedObservation = null
        if (ownedSession != null) {
            try {
                ownedSession.close()
            } catch (error: Throwable) {
                setError(SessionOperation.CLOSE, error)
                return
            }
        }
        setLifecycle(nextState)
    }

    private fun canUpdate(): Boolean =
        !terminallyClosed &&
            sessionResumed &&
            lifecycleState == SessionLifecycleState.Running &&
            surfaceReady &&
            cameraTextureId > 0 &&
            viewportWidth > 0 &&
            viewportHeight > 0

    private fun publishObservationIfDue(observation: DiagnosticObservation) {
        val now = clockNanos()
        val stateChanged = lastPublishedObservation?.let {
            it.trackingState != observation.trackingState ||
                it.trackingFailureReason != observation.trackingFailureReason ||
                (it.worldFromArCoreCamera == null) != (observation.worldFromArCoreCamera == null) ||
                (it.nativeImageIntrinsics == null) != (observation.nativeImageIntrinsics == null)
        } ?: true
        val intervalElapsed = lastPublishedAtNanos == Long.MIN_VALUE ||
            now - lastPublishedAtNanos >= minimumPublishIntervalNanos
        if (stateChanged || intervalElapsed) {
            lastPublishedAtNanos = now
            lastPublishedObservation = observation
            publish(lifecycleState, observation)
        }
    }

    private fun setLifecycle(state: SessionLifecycleState) {
        if (lifecycleState == state) return
        lifecycleState = state
        publish(state, lastObservation)
    }

    private fun setError(operation: SessionOperation, error: Throwable) {
        val runtimeError = error as? ArRuntimeException
        lifecycleState = SessionLifecycleState.Error(
            operation = operation,
            failure = runtimeError?.failure ?: SessionFailure.UNEXPECTED_RUNTIME_ERROR,
            detail = error.message?.take(160),
        )
        publish(lifecycleState, null)
    }

    private fun stopAfterFrameError(operation: SessionOperation, error: Throwable) {
        val ownedSession = session
        var pauseFailed = false
        if (sessionResumed) {
            try {
                ownedSession?.pause()
            } catch (_: Throwable) {
                pauseFailed = true
            }
        }
        sessionResumed = false
        if (pauseFailed) {
            session = null
            configuredTextureId = 0
            try {
                ownedSession?.close()
            } catch (_: Throwable) {
                // Preserve the original frame-stage failure as the visible diagnostic.
            }
        }
        lastObservation = null
        lastPublishedObservation = null
        setError(operation, error)
    }

    private fun publish(
        state: SessionLifecycleState,
        observation: DiagnosticObservation?,
    ) = onStateChanged(state, observation)
}

class DiagnosticLifecycleCoordinator(
    private val sessionController: ArDiagnosticSessionController,
    private val surfacePort: DiagnosticSurfacePort,
) {
    private var activityResumed = false
    private var prerequisitesReady = false
    private var surfaceResumed = false

    @Synchronized
    fun onActivityResume() {
        if (activityResumed) return
        activityResumed = true
        resumeRuntimeIfReady()
    }

    @Synchronized
    fun onPrerequisitesChanged(ready: Boolean) {
        if (prerequisitesReady == ready) return
        prerequisitesReady = ready
        if (!ready) {
            pauseSurfaceIfNeeded()
            sessionController.setPrerequisitesReady(false)
            return
        }
        sessionController.setPrerequisitesReady(true)
        resumeRuntimeIfReady()
    }

    @Synchronized
    fun onActivityPause() {
        if (!activityResumed) return
        activityResumed = false
        pauseSurfaceIfNeeded()
        sessionController.pause()
    }

    @Synchronized
    fun close() {
        activityResumed = false
        pauseSurfaceIfNeeded()
        sessionController.close()
    }

    private fun resumeRuntimeIfReady() {
        if (!activityResumed || !prerequisitesReady) return
        // ARCore's sample lifecycle resumes Session before GLSurfaceView. This prevents the render
        // thread from issuing update() against a paused Session.
        sessionController.resume()
        if (!surfaceResumed && sessionController.currentLifecycleState() == SessionLifecycleState.Running) {
            surfacePort.resumeSurface()
            surfaceResumed = true
        }
    }

    private fun pauseSurfaceIfNeeded() {
        if (!surfaceResumed) return
        surfacePort.pauseSurface()
        surfaceResumed = false
    }
}
