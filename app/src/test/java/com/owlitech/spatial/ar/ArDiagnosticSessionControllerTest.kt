package com.owlitech.spatial.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArDiagnosticSessionControllerTest {
    @Test
    fun multipleResumeAndUiEventsCreateExactlyOneSession() {
        val events = mutableListOf<String>()
        val factory = FakeFactory(events)
        val controller = controller(factory)

        controller.setPrerequisitesReady(true)
        controller.resume()
        controller.resume()
        controller.setPrerequisitesReady(true)
        controller.resume()

        assertEquals(1, factory.created)
        assertEquals(1, factory.session.resumeCalls)
        assertTrue(controller.hasOwnedSession())
    }

    @Test
    fun missingPrerequisitesNeverCreateOrResumeSession() {
        val factory = FakeFactory(mutableListOf())
        val controller = controller(factory)

        controller.resume()
        controller.onSurfaceCreated(7)
        controller.onSurfaceChanged(0, 100, 200)
        controller.updateFrame()

        assertEquals(0, factory.created)
        assertEquals(0, factory.session.resumeCalls)
        assertEquals(0, factory.session.updateCalls)
    }

    @Test
    fun lifecycleOrderingIsDeterministicAndResumeReusesSession() {
        val events = mutableListOf<String>()
        val factory = FakeFactory(events)
        val controller = controller(factory)
        val coordinator = DiagnosticLifecycleCoordinator(
            controller,
            object : DiagnosticSurfacePort {
                override fun resumeSurface() { events += "surface.resume" }
                override fun pauseSurface() { events += "surface.pause" }
            },
        )

        coordinator.onPrerequisitesChanged(true)
        coordinator.onActivityResume()
        coordinator.onActivityPause()
        coordinator.onActivityResume()
        coordinator.close()

        assertEquals(
            listOf(
                "session.create",
                "session.resume",
                "surface.resume",
                "surface.pause",
                "session.pause",
                "session.resume",
                "surface.resume",
                "surface.pause",
                "session.pause",
                "session.close",
            ),
            events,
        )
        assertEquals(1, factory.created)
    }

    @Test
    fun prerequisiteLossPausesSurfaceBeforePausingAndClosingSession() {
        val events = mutableListOf<String>()
        val factory = FakeFactory(events)
        val controller = controller(factory)
        val coordinator = DiagnosticLifecycleCoordinator(
            controller,
            object : DiagnosticSurfacePort {
                override fun resumeSurface() { events += "surface.resume" }
                override fun pauseSurface() { events += "surface.pause" }
            },
        )
        coordinator.onPrerequisitesChanged(true)
        coordinator.onActivityResume()
        events.clear()

        coordinator.onPrerequisitesChanged(false)

        assertEquals(
            listOf("surface.pause", "session.pause", "session.close"),
            events,
        )
        assertFalse(controller.hasOwnedSession())
    }

    @Test
    fun failedSessionResumeDoesNotStartSurfaceLoop() {
        val events = mutableListOf<String>()
        val factory = FakeFactory(events).also {
            it.session.resumeError = ArRuntimeException(
                SessionFailure.CAMERA_NOT_AVAILABLE,
                "resume",
            )
        }
        val controller = controller(factory)
        val coordinator = DiagnosticLifecycleCoordinator(
            controller,
            object : DiagnosticSurfacePort {
                override fun resumeSurface() { events += "surface.resume" }
                override fun pauseSurface() { events += "surface.pause" }
            },
        )

        coordinator.onPrerequisitesChanged(true)
        coordinator.onActivityResume()

        assertEquals(listOf("session.create", "session.resume"), events)
        assertEquals(
            SessionLifecycleState.Error(
                SessionOperation.RESUME,
                SessionFailure.CAMERA_NOT_AVAILABLE,
                "resume",
            ),
            controller.currentLifecycleState(),
        )
    }

    @Test
    fun pauseAndCloseAreIdempotentAndFramesStopWhilePaused() {
        val factory = FakeFactory(mutableListOf())
        val controller = controller(factory)
        controller.setPrerequisitesReady(true)
        controller.resume()
        controller.onSurfaceCreated(11)
        controller.onSurfaceChanged(1, 640, 480)
        controller.updateFrame()
        assertEquals(1, factory.session.updateCalls)

        controller.pause()
        controller.pause()
        controller.updateFrame()
        assertEquals(1, factory.session.pauseCalls)
        assertEquals(1, factory.session.updateCalls)

        controller.close()
        controller.close()
        assertEquals(1, factory.session.closeCalls)
    }

    @Test
    fun updateRequiresValidSurfaceTextureAndGeometry() {
        val factory = FakeFactory(mutableListOf())
        val controller = controller(factory)
        controller.setPrerequisitesReady(true)
        controller.resume()

        controller.updateFrame()
        controller.onSurfaceCreated(0)
        controller.onSurfaceChanged(0, 640, 480)
        controller.updateFrame()
        controller.onSurfaceCreated(5)
        controller.onSurfaceChanged(0, 0, 480)
        controller.updateFrame()
        assertEquals(0, factory.session.updateCalls)

        controller.onSurfaceChanged(2, 640, 480)
        controller.updateFrame()
        assertEquals(1, factory.session.textureCalls)
        assertEquals(1, factory.session.geometryCalls)
        assertEquals(1, factory.session.updateCalls)
        assertEquals(listOf("texture", "geometry", "update"), factory.session.frameEvents)
    }

    @Test
    fun updateFailureBecomesVisibleAndStopsFurtherUpdates() {
        val published = mutableListOf<SessionLifecycleState>()
        val factory = FakeFactory(mutableListOf()).also {
            it.session.updateError = ArRuntimeException(SessionFailure.CAMERA_NOT_AVAILABLE, "camera")
        }
        val controller = ArDiagnosticSessionController(
            sessionFactory = factory,
            onStateChanged = { state, _ -> published += state },
        )
        controller.setPrerequisitesReady(true)
        controller.resume()
        controller.onSurfaceCreated(5)
        controller.onSurfaceChanged(0, 100, 100)

        controller.updateFrame()
        controller.updateFrame()

        assertEquals(1, factory.session.updateCalls)
        assertEquals(
            SessionLifecycleState.Error(
                SessionOperation.UPDATE,
                SessionFailure.CAMERA_NOT_AVAILABLE,
                "camera",
            ),
            published.last(),
        )
    }


    @Test
    fun pauseFailureAttemptsTerminalReleaseAndDropsSessionOwnership() {
        val published = mutableListOf<SessionLifecycleState>()
        val factory = FakeFactory(mutableListOf()).also {
            it.session.pauseError = ArRuntimeException(SessionFailure.SESSION_NOT_PAUSED, "pause")
        }
        val controller = ArDiagnosticSessionController(
            sessionFactory = factory,
            onStateChanged = { state, _ -> published += state },
        )
        controller.setPrerequisitesReady(true)
        controller.resume()

        controller.pause()
        controller.pause()

        assertEquals(1, factory.session.pauseCalls)
        assertEquals(1, factory.session.closeCalls)
        assertFalse(controller.hasOwnedSession())
        assertEquals(
            SessionLifecycleState.Error(
                SessionOperation.PAUSE,
                SessionFailure.SESSION_NOT_PAUSED,
                "pause",
            ),
            published.last(),
        )
    }

    @Test
    fun textureAndDisplayGeometryFailuresUseTheirExactOperation() {
        listOf(
            SessionOperation.CAMERA_TEXTURE to { session: FakeSession ->
                session.textureError = ArRuntimeException(
                    SessionFailure.CAMERA_TEXTURE_NOT_SET,
                    "texture",
                )
            },
            SessionOperation.DISPLAY_GEOMETRY to { session: FakeSession ->
                session.geometryError = ArRuntimeException(
                    SessionFailure.MISSING_GL_CONTEXT,
                    "geometry",
                )
            },
        ).forEach { (operation, configure) ->
            val published = mutableListOf<SessionLifecycleState>()
            val factory = FakeFactory(mutableListOf())
            configure(factory.session)
            val controller = ArDiagnosticSessionController(
                sessionFactory = factory,
                onStateChanged = { state, _ -> published += state },
            )
            controller.setPrerequisitesReady(true)
            controller.resume()
            controller.onSurfaceCreated(5)
            controller.onSurfaceChanged(0, 100, 100)

            controller.updateFrame()

            val error = published.last() as SessionLifecycleState.Error
            assertEquals(operation, error.operation)
            assertEquals(0, factory.session.updateCalls)
        }
    }

    @Test
    fun createResumePauseAndCloseFailuresUseTheirExactOperation() {
        fun lastStateFor(
            factory: DiagnosticSessionFactory,
            exercise: (ArDiagnosticSessionController) -> Unit,
        ): SessionLifecycleState {
            val states = mutableListOf<SessionLifecycleState>()
            val controller = ArDiagnosticSessionController(
                sessionFactory = factory,
                onStateChanged = { state, _ -> states += state },
            )
            controller.setPrerequisitesReady(true)
            exercise(controller)
            return states.last()
        }

        val createFailure = lastStateFor(
            factory = DiagnosticSessionFactory {
                throw ArRuntimeException(SessionFailure.ARCORE_APK_MISSING, "missing")
            },
            exercise = { it.resume() },
        )
        assertEquals(
            SessionLifecycleState.Error(
                SessionOperation.CREATE,
                SessionFailure.ARCORE_APK_MISSING,
                "missing",
            ),
            createFailure,
        )

        listOf(
            SessionOperation.RESUME to { session: FakeSession ->
                session.resumeError = ArRuntimeException(SessionFailure.CAMERA_NOT_AVAILABLE, "resume")
            },
            SessionOperation.PAUSE to { session: FakeSession ->
                session.pauseError = ArRuntimeException(SessionFailure.SESSION_NOT_PAUSED, "pause")
            },
            SessionOperation.CLOSE to { session: FakeSession ->
                session.closeError = ArRuntimeException(SessionFailure.UNEXPECTED_RUNTIME_ERROR, "close")
            },
        ).forEach { (operation, configure) ->
            val factory = FakeFactory(mutableListOf())
            configure(factory.session)
            val state = lastStateFor(factory) { controller ->
                controller.resume()
                when (operation) {
                    SessionOperation.RESUME -> Unit
                    SessionOperation.PAUSE -> controller.pause()
                    SessionOperation.CLOSE -> controller.close()
                    else -> error("Unexpected operation")
                }
            }
            val error = state as SessionLifecycleState.Error
            assertEquals(operation, error.operation)
        }
    }

    @Test
    fun observationPublicationIsBoundedButStateChangesPublishImmediately() {
        var now = 0L
        val observations = mutableListOf<DiagnosticObservation>()
        val factory = FakeFactory(mutableListOf())
        val controller = ArDiagnosticSessionController(
            sessionFactory = factory,
            clockNanos = { now },
            minimumPublishIntervalNanos = 125L,
            onStateChanged = { _, observation -> observation?.let(observations::add) },
        )
        controller.setPrerequisitesReady(true)
        controller.resume()
        controller.onSurfaceCreated(5)
        controller.onSurfaceChanged(0, 100, 100)

        repeat(10) {
            factory.session.nextTimestamp = it.toLong()
            controller.updateFrame()
            now += 10L
        }
        assertEquals(1, observations.size)

        now = 130L
        controller.updateFrame()
        assertEquals(2, observations.size)

        factory.session.trackingState = DiagnosticTrackingState.PAUSED
        now = 131L
        controller.updateFrame()
        assertEquals(3, observations.size)
        assertFalse(observations.last().worldFromArCoreCamera != null)
    }

    private fun controller(factory: DiagnosticSessionFactory) = ArDiagnosticSessionController(
        sessionFactory = factory,
        onStateChanged = { _, _ -> },
    )

    private class FakeFactory(
        private val events: MutableList<String>,
    ) : DiagnosticSessionFactory {
        var created = 0
        val session = FakeSession(events)

        override fun create(): DiagnosticSessionPort {
            created += 1
            events += "session.create"
            return session
        }
    }

    private class FakeSession(
        private val events: MutableList<String>,
    ) : DiagnosticSessionPort {
        var resumeCalls = 0
        var pauseCalls = 0
        var closeCalls = 0
        var textureCalls = 0
        var geometryCalls = 0
        var updateCalls = 0
        var nextTimestamp = 1L
        var trackingState = DiagnosticTrackingState.TRACKING
        var resumeError: Throwable? = null
        var pauseError: Throwable? = null
        var closeError: Throwable? = null
        var textureError: Throwable? = null
        var geometryError: Throwable? = null
        var updateError: Throwable? = null
        val frameEvents = mutableListOf<String>()

        override fun resume() {
            resumeCalls += 1
            events += "session.resume"
            resumeError?.let { throw it }
        }

        override fun setCameraTextureName(textureId: Int) {
            textureCalls += 1
            frameEvents += "texture"
            textureError?.let { throw it }
        }

        override fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int) {
            geometryCalls += 1
            frameEvents += "geometry"
            geometryError?.let { throw it }
        }

        override fun update(): DiagnosticFrameScalars {
            updateCalls += 1
            frameEvents += "update"
            updateError?.let { throw it }
            return DiagnosticFrameScalars(
                frameTimestampNanos = nextTimestamp,
                trackingState = trackingState,
                trackingFailureReason = if (trackingState == DiagnosticTrackingState.TRACKING) {
                    DiagnosticTrackingFailureReason.NONE
                } else {
                    DiagnosticTrackingFailureReason.INSUFFICIENT_LIGHT
                },
                translationMetresX = 1.0,
                translationMetresY = 2.0,
                translationMetresZ = 3.0,
                arCoreQuaternionX = 0.1,
                arCoreQuaternionY = 0.2,
                arCoreQuaternionZ = 0.3,
                arCoreQuaternionW = 0.9,
                imageFx = 500.0,
                imageFy = 501.0,
                imageCx = 250.0,
                imageCy = 251.0,
                imageWidth = 640,
                imageHeight = 480,
            )
        }

        override fun pause() {
            pauseCalls += 1
            events += "session.pause"
            pauseError?.let { throw it }
        }

        override fun close() {
            closeCalls += 1
            events += "session.close"
            closeError?.let { throw it }
        }
    }
}
