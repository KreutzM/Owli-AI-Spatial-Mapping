package com.owlitech.spatial.ar

/**
 * Owns at most one diagnostic Session adapter and never executes native close synchronously.
 *
 * All active-use state is protected by [lock]. Native pause and the close-scheduler handoff run
 * after leaving that lock. Frame-stage failures revoke update eligibility immediately and ask the
 * lifecycle coordinator to stop the surface before pausing and releasing the Session on main.
 */
class ArDiagnosticSessionController(
    private val sessionFactory: DiagnosticSessionFactory,
    private val sessionCloseScheduler: DiagnosticSessionCloseScheduler,
    private val observationConverter: DiagnosticObservationConverter = DiagnosticObservationConverter(),
    private val clockNanos: () -> Long = System::nanoTime,
    private val minimumPublishIntervalNanos: Long = 125_000_000L,
    private val onRuntimeReleaseRequested: (SessionOperation, Throwable) -> Unit = { _, _ -> },
    private val onSessionSlotAvailable: (() -> Unit)? = null,
    private val onStateChanged: (SessionLifecycleState, DiagnosticObservation?) -> Unit,
) {
    private data class ReleasePlan(
        val session: DiagnosticSessionPort,
        val pauseBeforeClose: Boolean,
        val finalState: SessionLifecycleState,
        val primaryError: Pair<SessionOperation, Throwable>? = null,
    )

    private val lock = Any()
    private var prerequisitesReady = false
    private var session: DiagnosticSessionPort? = null
    private var sessionResumed = false
    private var updateEligible = false
    private var resumeRequested = false
    private var terminallyClosed = false
    private var waitingForSessionSlot = false
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

    fun setPrerequisitesReady(ready: Boolean) {
        var releasePlan: ReleasePlan? = null
        synchronized(lock) {
            if (terminallyClosed || prerequisitesReady == ready) return
            prerequisitesReady = ready
            if (!ready) {
                resumeRequested = false
                releasePlan = detachOwnedSessionLocked(
                    finalState = SessionLifecycleState.WaitingForPrerequisites,
                )
                if (releasePlan == null) {
                    setLifecycleLocked(SessionLifecycleState.WaitingForPrerequisites)
                }
            } else if (session == null) {
                setLifecycleLocked(SessionLifecycleState.Ready)
            }
        }
        releasePlan?.let(::executeReleasePlan)
    }

    fun resume() = synchronized(lock) {
        if (terminallyClosed || !prerequisitesReady || sessionResumed) return@synchronized
        resumeRequested = true
        val ownedSession = session ?: createSessionOrWaitLocked() ?: return@synchronized
        setLifecycleLocked(SessionLifecycleState.Resuming)

        try {
            // Lifecycle resume is serialized with detachment. Native close is never performed here.
            ownedSession.resume()
            if (session !== ownedSession || terminallyClosed || !prerequisitesReady || !resumeRequested) {
                return@synchronized
            }
            sessionResumed = true
            updateEligible = true
            setLifecycleLocked(SessionLifecycleState.Running)
        } catch (error: Throwable) {
            if (session === ownedSession) {
                sessionResumed = false
                updateEligible = false
                clearCurrentObservationLocked()
                setErrorLocked(SessionOperation.RESUME, error)
            }
        }
    }

    fun onSurfaceCreated(textureId: Int) = synchronized(lock) {
        if (terminallyClosed) return@synchronized
        surfaceReady = textureId > 0
        cameraTextureId = textureId
        configuredTextureId = 0
        displayGeometryDirty = true
    }

    fun onSurfaceChanged(displayRotation: Int, width: Int, height: Int) = synchronized(lock) {
        if (terminallyClosed) return@synchronized
        this.displayRotation = displayRotation
        viewportWidth = width
        viewportHeight = height
        displayGeometryDirty = true
    }

    fun onDisplayRotationChanged(displayRotation: Int) = synchronized(lock) {
        if (terminallyClosed || this.displayRotation == displayRotation) return@synchronized
        this.displayRotation = displayRotation
        displayGeometryDirty = true
    }

    fun onSurfaceDestroyed() = synchronized(lock) {
        invalidateSurfaceUseLocked()
    }

    /** Called exclusively from the GLSurfaceView render thread. */
    fun updateFrame() {
        var runtimeFailure: Pair<SessionOperation, Throwable>? = null
        synchronized(lock) {
            if (!canUpdateLocked()) return
            val ownedSession = session ?: return

            if (configuredTextureId != cameraTextureId) {
                try {
                    ownedSession.setCameraTextureName(cameraTextureId)
                    configuredTextureId = cameraTextureId
                } catch (error: Throwable) {
                    runtimeFailure = stopUpdatesForRuntimeFailureLocked(
                        SessionOperation.CAMERA_TEXTURE,
                        error,
                    )
                    return@synchronized
                }
            }

            if (displayGeometryDirty) {
                try {
                    ownedSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                    displayGeometryDirty = false
                } catch (error: Throwable) {
                    runtimeFailure = stopUpdatesForRuntimeFailureLocked(
                        SessionOperation.DISPLAY_GEOMETRY,
                        error,
                    )
                    return@synchronized
                }
            }

            try {
                val observation = observationConverter.convert(ownedSession.update())
                lastObservation = observation
                publishObservationIfDueLocked(observation)
            } catch (error: Throwable) {
                runtimeFailure = stopUpdatesForRuntimeFailureLocked(SessionOperation.UPDATE, error)
            }
        }
        runtimeFailure?.let { (operation, error) ->
            onRuntimeReleaseRequested(operation, error)
        }
    }

    /**
     * Normal Activity pause. The coordinator has already stopped GLSurfaceView before this call.
     * A successful pause retains Session ownership for foreground reuse.
     */
    fun pause() {
        val ownedSession = synchronized(lock) {
            if (terminallyClosed || !sessionResumed) return
            resumeRequested = false
            sessionResumed = false
            updateEligible = false
            clearCurrentObservationLocked()
            session
        }

        try {
            ownedSession?.pause()
            synchronized(lock) {
                if (session === ownedSession && !terminallyClosed) {
                    setLifecycleLocked(SessionLifecycleState.Paused)
                }
            }
        } catch (error: Throwable) {
            val releasePlan = synchronized(lock) {
                if (session !== ownedSession || ownedSession == null) {
                    null
                } else {
                    detachOwnedSessionLocked(
                        finalState = errorState(SessionOperation.PAUSE, error),
                        primaryError = SessionOperation.PAUSE to error,
                        pauseBeforeClose = false,
                    )
                }
            }
            releasePlan?.let(::executeReleasePlan)
        }
    }

    /**
     * Main-thread continuation for a frame-stage failure. The coordinator stops GLSurfaceView first,
     * then calls this method to pause, detach, and asynchronously close the Session.
     */
    fun releaseAfterRuntimeFailure(operation: SessionOperation, error: Throwable) {
        val releasePlan = synchronized(lock) {
            if (terminallyClosed || session == null) return
            resumeRequested = false
            detachOwnedSessionLocked(
                finalState = errorState(operation, error),
                primaryError = operation to error,
            )
        }
        releasePlan?.let(::executeReleasePlan)
    }

    /** Terminal release; returns before native Session.close() starts or completes. */
    fun close() {
        val releasePlan = synchronized(lock) {
            if (terminallyClosed) return
            terminallyClosed = true
            resumeRequested = false
            val plan = detachOwnedSessionLocked(finalState = SessionLifecycleState.Closed)
            if (plan == null) {
                setLifecycleLocked(SessionLifecycleState.Closed)
            }
            plan
        }
        releasePlan?.let(::executeReleasePlan)
    }

    fun currentLifecycleState(): SessionLifecycleState = synchronized(lock) { lifecycleState }

    fun hasOwnedSession(): Boolean = synchronized(lock) { session != null }

    private fun createSessionOrWaitLocked(): DiagnosticSessionPort? {
        if (!sessionCloseScheduler.tryAcquireSessionSlot()) {
            val waitingState = when (sessionCloseScheduler.currentSlotState()) {
                DiagnosticSessionSlotState.CLOSING -> SessionLifecycleState.Closing
                DiagnosticSessionSlotState.OWNED,
                DiagnosticSessionSlotState.AVAILABLE,
                -> SessionLifecycleState.WaitingForPreviousSession
            }
            setLifecycleLocked(waitingState)
            registerSessionSlotWaiterLocked()
            return null
        }

        setLifecycleLocked(SessionLifecycleState.Creating)
        return try {
            sessionFactory.create().also { created ->
                session = created
                waitingForSessionSlot = false
            }
        } catch (error: Throwable) {
            sessionCloseScheduler.releaseSessionSlot()
            setErrorLocked(SessionOperation.CREATE, error)
            null
        }
    }

    private fun registerSessionSlotWaiterLocked() {
        if (waitingForSessionSlot) return
        waitingForSessionSlot = true
        sessionCloseScheduler.notifyWhenAvailable(::onSessionSlotAvailable)
    }

    private fun onSessionSlotAvailable() {
        val shouldResume = synchronized(lock) {
            waitingForSessionSlot = false
            !terminallyClosed && prerequisitesReady && resumeRequested && session == null
        }
        if (shouldResume) {
            onSessionSlotAvailable?.invoke() ?: resume()
        }
    }

    /**
     * Atomically removes every route by which the Session could be used again. Native pause/close
     * are intentionally deferred to [executeReleasePlan], outside [lock].
     */
    private fun detachOwnedSessionLocked(
        finalState: SessionLifecycleState,
        primaryError: Pair<SessionOperation, Throwable>? = null,
        pauseBeforeClose: Boolean = sessionResumed,
    ): ReleasePlan? {
        val ownedSession = session ?: return null
        val wasResumed = pauseBeforeClose && sessionResumed
        updateEligible = false
        sessionResumed = false
        session = null
        invalidateSurfaceUseLocked()
        clearCurrentObservationLocked()
        setLifecycleLocked(SessionLifecycleState.Closing)
        return ReleasePlan(
            session = ownedSession,
            pauseBeforeClose = wasResumed,
            finalState = finalState,
            primaryError = primaryError,
        )
    }

    private fun executeReleasePlan(plan: ReleasePlan) {
        var pauseError: Throwable? = null
        if (plan.pauseBeforeClose) {
            try {
                plan.session.pause()
            } catch (error: Throwable) {
                pauseError = error
            }
        }

        val effectivePrimary = plan.primaryError ?: pauseError?.let { SessionOperation.PAUSE to it }
        sessionCloseScheduler.scheduleClose(plan.session) { closeError ->
            synchronized(lock) {
                when {
                    effectivePrimary != null -> setErrorLocked(
                        effectivePrimary.first,
                        effectivePrimary.second,
                    )
                    closeError != null -> setErrorLocked(SessionOperation.CLOSE, closeError)
                    else -> setLifecycleLocked(plan.finalState)
                }
            }
        }
    }

    private fun canUpdateLocked(): Boolean =
        !terminallyClosed &&
            updateEligible &&
            sessionResumed &&
            lifecycleState == SessionLifecycleState.Running &&
            surfaceReady &&
            cameraTextureId > 0 &&
            viewportWidth > 0 &&
            viewportHeight > 0

    private fun publishObservationIfDueLocked(observation: DiagnosticObservation) {
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

    private fun stopUpdatesForRuntimeFailureLocked(
        operation: SessionOperation,
        error: Throwable,
    ): Pair<SessionOperation, Throwable> {
        updateEligible = false
        clearCurrentObservationLocked()
        setErrorLocked(operation, error)
        return operation to error
    }

    private fun invalidateSurfaceUseLocked() {
        surfaceReady = false
        cameraTextureId = 0
        configuredTextureId = 0
        viewportWidth = 0
        viewportHeight = 0
        displayGeometryDirty = true
    }

    private fun clearCurrentObservationLocked() {
        lastObservation = null
        lastPublishedObservation = null
        lastPublishedAtNanos = Long.MIN_VALUE
    }

    private fun setLifecycleLocked(state: SessionLifecycleState) {
        if (lifecycleState == state) return
        lifecycleState = state
        publish(state, lastObservation)
    }

    private fun setErrorLocked(operation: SessionOperation, error: Throwable) {
        lifecycleState = errorState(operation, error)
        publish(lifecycleState, null)
    }

    private fun errorState(operation: SessionOperation, error: Throwable): SessionLifecycleState.Error {
        val runtimeError = error as? ArRuntimeException
        return SessionLifecycleState.Error(
            operation = operation,
            failure = runtimeError?.failure ?: SessionFailure.UNEXPECTED_RUNTIME_ERROR,
            detail = error.message?.take(160),
        )
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

    /** Called on main after the render thread has atomically stopped further Session use. */
    @Synchronized
    fun onRuntimeFailure(operation: SessionOperation, error: Throwable) {
        pauseSurfaceIfNeeded()
        sessionController.releaseAfterRuntimeFailure(operation, error)
    }

    @Synchronized
    fun onSessionSlotAvailable() {
        resumeRuntimeIfReady()
    }

    @Synchronized
    fun close() {
        activityResumed = false
        pauseSurfaceIfNeeded()
        sessionController.close()
    }

    private fun resumeRuntimeIfReady() {
        if (!activityResumed || !prerequisitesReady) return
        sessionController.resume()
        if (
            !surfaceResumed &&
            sessionController.currentLifecycleState() == SessionLifecycleState.Running
        ) {
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
