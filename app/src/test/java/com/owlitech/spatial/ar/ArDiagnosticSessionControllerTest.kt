package com.owlitech.spatial.ar

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArDiagnosticSessionControllerTest {
    @Test
    fun multipleResumeAndUiEventsCreateExactlyOneSession() {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.setPrerequisitesReady(true)
        controller.resume()
        controller.resume()
        controller.setPrerequisitesReady(true)
        controller.resume()

        assertEquals(1, fixture.factory.created)
        assertEquals(1, fixture.factory.sessions.single().resumeCalls)
        assertTrue(controller.hasOwnedSession())
    }

    @Test
    fun missingPrerequisitesNeverCreateOrResumeSession() {
        val fixture = Fixture()
        val controller = fixture.controller()

        controller.resume()
        controller.onSurfaceCreated(7)
        controller.onSurfaceChanged(0, 100, 200)
        controller.updateFrame()

        assertEquals(0, fixture.factory.created)
    }

    @Test
    fun lifecycleOrderingRetainsSessionAcrossPauseAndQueuesCloseAfterFinalPause() {
        val fixture = Fixture()
        val controller = fixture.controller()
        val coordinator = fixture.coordinator(controller)

        coordinator.onPrerequisitesChanged(true)
        coordinator.onActivityResume()
        coordinator.onActivityPause()
        coordinator.onActivityResume()
        coordinator.close()

        assertEquals(
            listOf(
                "session.create.1",
                "session.resume.1",
                "surface.resume",
                "surface.pause",
                "session.pause.1",
                "session.resume.1",
                "surface.resume",
                "surface.pause",
                "session.pause.1",
            ),
            fixture.events,
        )
        assertEquals(0, fixture.factory.sessions.single().closeCalls)
        assertEquals(SessionLifecycleState.Closing, controller.currentLifecycleState())

        fixture.closeTasks.runNext()

        assertEquals("session.close.1", fixture.events.last())
        assertEquals(1, fixture.factory.created)
        assertEquals(SessionLifecycleState.Closed, controller.currentLifecycleState())
    }

    @Test
    fun prerequisiteLossPausesSurfaceAndSessionBeforeAsyncClose() {
        val fixture = Fixture()
        val controller = fixture.controller()
        val coordinator = fixture.coordinator(controller)
        coordinator.onPrerequisitesChanged(true)
        coordinator.onActivityResume()
        fixture.events.clear()

        coordinator.onPrerequisitesChanged(false)

        assertEquals(listOf("surface.pause", "session.pause.1"), fixture.events)
        assertFalse(controller.hasOwnedSession())
        assertEquals(0, fixture.factory.sessions.single().closeCalls)
        assertEquals(SessionLifecycleState.Closing, controller.currentLifecycleState())

        fixture.closeTasks.runNext()
        assertEquals(listOf("surface.pause", "session.pause.1", "session.close.1"), fixture.events)
        assertEquals(
            SessionLifecycleState.WaitingForPrerequisites,
            controller.currentLifecycleState(),
        )
    }

    @Test
    fun terminalCloseDetachesSynchronouslyAndIsIdempotent() {
        val fixture = Fixture()
        val controller = fixture.controller()
        controller.setPrerequisitesReady(true)
        controller.resume()
        controller.onSurfaceCreated(5)
        controller.onSurfaceChanged(0, 100, 100)
        controller.updateFrame()
        assertEquals(1, fixture.factory.sessions.single().updateCalls)

        controller.close()
        controller.close()
        controller.updateFrame()

        val session = fixture.factory.sessions.single()
        assertFalse(controller.hasOwnedSession())
        assertEquals(1, session.pauseCalls)
        assertEquals(1, session.updateCalls)
        assertEquals(0, session.closeCalls)
        assertEquals(1, fixture.closeTasks.tasks.size)

        fixture.closeTasks.runNext()
        assertEquals(1, session.closeCalls)
        assertEquals(SessionLifecycleState.Closed, controller.currentLifecycleState())
    }

    @Test
    fun previousCloseBlocksNewControllerAndSessionUntilCompletion() {
        val closeTasks = QueueExecutor()
        val scheduler = BoundedDiagnosticSessionCloseScheduler(closeTasks, DirectExecutor)
        val firstFactory = FakeFactory(mutableListOf(), "old")
        val secondFactory = FakeFactory(mutableListOf(), "new")
        val first = controller(firstFactory, scheduler)
        val second = controller(secondFactory, scheduler)

        first.setPrerequisitesReady(true)
        first.resume()
        first.close()

        second.setPrerequisitesReady(true)
        second.resume()

        assertEquals(0, secondFactory.created)
        assertEquals(SessionLifecycleState.Closing, second.currentLifecycleState())
        assertEquals(DiagnosticSessionSlotState.CLOSING, scheduler.currentSlotState())

        closeTasks.runNext()

        assertEquals(1, secondFactory.created)
        assertEquals(1, secondFactory.sessions.single().resumeCalls)
        assertEquals(SessionLifecycleState.Running, second.currentLifecycleState())
    }

    @Test
    fun recreatedCoordinatorResumesSurfaceAfterPreviousCloseCompletes() {
        val closeTasks = QueueExecutor()
        val scheduler = BoundedDiagnosticSessionCloseScheduler(closeTasks, DirectExecutor)
        val oldFactory = FakeFactory(mutableListOf(), "old")
        val oldController = controller(oldFactory, scheduler)
        oldController.setPrerequisitesReady(true)
        oldController.resume()
        oldController.close()

        val events = mutableListOf<String>()
        val newFactory = FakeFactory(events, "new")
        lateinit var coordinator: DiagnosticLifecycleCoordinator
        val newController = ArDiagnosticSessionController(
            sessionFactory = newFactory,
            sessionCloseScheduler = scheduler,
            onSessionSlotAvailable = { coordinator.onSessionSlotAvailable() },
            onStateChanged = { _, _ -> },
        )
        coordinator = DiagnosticLifecycleCoordinator(
            newController,
            object : DiagnosticSurfacePort {
                override fun resumeSurface() {
                    events += "surface.resume"
                }

                override fun pauseSurface() {
                    events += "surface.pause"
                }
            },
        )
        coordinator.onPrerequisitesChanged(true)
        coordinator.onActivityResume()
        assertEquals(0, newFactory.created)

        closeTasks.runNext()

        assertEquals(
            listOf("session.create.new", "session.resume.new", "surface.resume"),
            events,
        )
        assertEquals(SessionLifecycleState.Running, newController.currentLifecycleState())
    }

    @Test
    fun oldActivityOwnershipAlsoBlocksRecreatedActivityBeforeCloseStarts() {
        val closeTasks = QueueExecutor()
        val scheduler = BoundedDiagnosticSessionCloseScheduler(closeTasks, DirectExecutor)
        val firstFactory = FakeFactory(mutableListOf(), "old")
        val secondFactory = FakeFactory(mutableListOf(), "new")
        val first = controller(firstFactory, scheduler)
        val second = controller(secondFactory, scheduler)

        first.setPrerequisitesReady(true)
        first.resume()
        first.pause()

        second.setPrerequisitesReady(true)
        second.resume()
        assertEquals(0, secondFactory.created)
        assertEquals(SessionLifecycleState.WaitingForPreviousSession, second.currentLifecycleState())

        first.close()
        assertEquals(0, secondFactory.created)
        closeTasks.runNext()
        assertEquals(1, secondFactory.created)
    }

    @Test
    fun frameFailureRevokesUpdatesBeforeMainThreadReleaseAndClose() {
        val fixture = Fixture()
        var requestedFailure: Pair<SessionOperation, Throwable>? = null
        val controller = fixture.controller(
            onRuntimeReleaseRequested = { operation, error ->
                requestedFailure = operation to error
            },
        )
        val coordinator = fixture.coordinator(controller)
        coordinator.onPrerequisitesChanged(true)
        coordinator.onActivityResume()
        controller.onSurfaceCreated(5)
        controller.onSurfaceChanged(0, 100, 100)
        fixture.factory.sessions.single().updateError = ArRuntimeException(
            SessionFailure.CAMERA_NOT_AVAILABLE,
            "camera",
        )

        controller.updateFrame()
        controller.updateFrame()

        val session = fixture.factory.sessions.single()
        assertEquals(1, session.updateCalls)
        assertTrue(controller.hasOwnedSession())
        assertEquals(SessionOperation.UPDATE, requestedFailure?.first)
        assertEquals(
            SessionLifecycleState.Error(
                SessionOperation.UPDATE,
                SessionFailure.CAMERA_NOT_AVAILABLE,
                "camera",
            ),
            controller.currentLifecycleState(),
        )

        val failure = requireNotNull(requestedFailure)
        coordinator.onRuntimeFailure(failure.first, failure.second)

        assertFalse(controller.hasOwnedSession())
        assertEquals(1, session.pauseCalls)
        assertEquals(0, session.closeCalls)
        assertEquals("surface.pause", fixture.events[fixture.events.size - 2])
        assertEquals("session.pause.1", fixture.events.last())

        fixture.closeTasks.runNext()
        assertEquals(1, session.closeCalls)
        assertEquals(
            SessionLifecycleState.Error(
                SessionOperation.UPDATE,
                SessionFailure.CAMERA_NOT_AVAILABLE,
                "camera",
            ),
            controller.currentLifecycleState(),
        )
    }

    @Test
    fun pauseFailureDetachesAndSchedulesOneCloseWithoutClosingInline() {
        val fixture = Fixture()
        val controller = fixture.controller()
        controller.setPrerequisitesReady(true)
        controller.resume()
        val session = fixture.factory.sessions.single().also {
            it.pauseError = ArRuntimeException(SessionFailure.SESSION_NOT_PAUSED, "pause")
        }

        controller.pause()
        controller.pause()

        assertEquals(1, session.pauseCalls)
        assertEquals(0, session.closeCalls)
        assertFalse(controller.hasOwnedSession())
        assertEquals(1, fixture.closeTasks.tasks.size)

        fixture.closeTasks.runNext()
        assertEquals(1, session.closeCalls)
        assertEquals(
            SessionLifecycleState.Error(
                SessionOperation.PAUSE,
                SessionFailure.SESSION_NOT_PAUSED,
                "pause",
            ),
            controller.currentLifecycleState(),
        )
    }

    @Test
    fun closeFailureIsReportedAfterBackgroundTask() {
        val fixture = Fixture()
        val controller = fixture.controller()
        controller.setPrerequisitesReady(true)
        controller.resume()
        fixture.factory.sessions.single().closeError = ArRuntimeException(
            SessionFailure.UNEXPECTED_RUNTIME_ERROR,
            "close",
        )

        controller.close()
        assertEquals(SessionLifecycleState.Closing, controller.currentLifecycleState())
        fixture.closeTasks.runNext()

        assertEquals(
            SessionLifecycleState.Error(
                SessionOperation.CLOSE,
                SessionFailure.UNEXPECTED_RUNTIME_ERROR,
                "close",
            ),
            controller.currentLifecycleState(),
        )
    }

    @Test
    fun creationFailureReleasesGlobalSlotForAnotherController() {
        val closeTasks = QueueExecutor()
        val scheduler = BoundedDiagnosticSessionCloseScheduler(closeTasks, DirectExecutor)
        val failingFactory = DiagnosticSessionFactory {
            throw ArRuntimeException(SessionFailure.ARCORE_APK_MISSING, "missing")
        }
        val first = controller(failingFactory, scheduler)
        first.setPrerequisitesReady(true)
        first.resume()

        assertEquals(DiagnosticSessionSlotState.AVAILABLE, scheduler.currentSlotState())

        val secondFactory = FakeFactory(mutableListOf(), "second")
        val second = controller(secondFactory, scheduler)
        second.setPrerequisitesReady(true)
        second.resume()
        assertEquals(1, secondFactory.created)
    }

    @Test
    fun updateRequiresValidSurfaceTextureAndGeometry() {
        val fixture = Fixture()
        val controller = fixture.controller()
        controller.setPrerequisitesReady(true)
        controller.resume()
        val session = fixture.factory.sessions.single()

        controller.updateFrame()
        controller.onSurfaceCreated(0)
        controller.onSurfaceChanged(0, 640, 480)
        controller.updateFrame()
        controller.onSurfaceCreated(5)
        controller.onSurfaceChanged(0, 0, 480)
        controller.updateFrame()
        assertEquals(0, session.updateCalls)

        controller.onSurfaceChanged(2, 640, 480)
        controller.updateFrame()
        assertEquals(1, session.textureCalls)
        assertEquals(1, session.geometryCalls)
        assertEquals(1, session.updateCalls)
        assertEquals(listOf("texture", "geometry", "update"), session.frameEvents)
    }

    @Test
    fun observationPublicationRemainsBoundedAndPauseClearsCurrentObservation() {
        var now = 0L
        val observations = mutableListOf<DiagnosticObservation?>()
        val fixture = Fixture()
        val controller = fixture.controller(
            clockNanos = { now },
            minimumPublishIntervalNanos = 125L,
            onStateChanged = { _, observation -> observations += observation },
        )
        controller.setPrerequisitesReady(true)
        controller.resume()
        controller.onSurfaceCreated(5)
        controller.onSurfaceChanged(0, 100, 100)
        val session = fixture.factory.sessions.single()

        repeat(10) {
            session.nextTimestamp = it.toLong()
            controller.updateFrame()
            now += 10L
        }
        assertEquals(1, observations.filterNotNull().size)

        now = 130L
        controller.updateFrame()
        assertEquals(2, observations.filterNotNull().size)

        controller.pause()
        assertNull(observations.last())
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val closeTasks = QueueExecutor()
        val scheduler = BoundedDiagnosticSessionCloseScheduler(closeTasks, DirectExecutor)
        val factory = FakeFactory(events, "1")

        fun controller(
            clockNanos: () -> Long = System::nanoTime,
            minimumPublishIntervalNanos: Long = 125_000_000L,
            onRuntimeReleaseRequested: (SessionOperation, Throwable) -> Unit = { _, _ -> },
            onStateChanged: (SessionLifecycleState, DiagnosticObservation?) -> Unit = { _, _ -> },
        ) = ArDiagnosticSessionController(
            sessionFactory = factory,
            sessionCloseScheduler = scheduler,
            clockNanos = clockNanos,
            minimumPublishIntervalNanos = minimumPublishIntervalNanos,
            onRuntimeReleaseRequested = onRuntimeReleaseRequested,
            onStateChanged = onStateChanged,
        )

        fun coordinator(controller: ArDiagnosticSessionController) = DiagnosticLifecycleCoordinator(
            controller,
            object : DiagnosticSurfacePort {
                override fun resumeSurface() {
                    events += "surface.resume"
                }

                override fun pauseSurface() {
                    events += "surface.pause"
                }
            },
        )
    }

    private fun controller(
        factory: DiagnosticSessionFactory,
        scheduler: DiagnosticSessionCloseScheduler,
    ) = ArDiagnosticSessionController(
        sessionFactory = factory,
        sessionCloseScheduler = scheduler,
        onStateChanged = { _, _ -> },
    )

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private class QueueExecutor : Executor {
        val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() = tasks.removeAt(0).run()
    }

    private class FakeFactory(
        private val events: MutableList<String>,
        private val label: String,
    ) : DiagnosticSessionFactory {
        var created = 0
        val sessions = mutableListOf<FakeSession>()

        override fun create(): DiagnosticSessionPort {
            created += 1
            events += "session.create.$label"
            return FakeSession(events, label).also(sessions::add)
        }
    }

    private class FakeSession(
        private val events: MutableList<String>,
        private val label: String,
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
            events += "session.resume.$label"
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
            events += "session.pause.$label"
            pauseError?.let { throw it }
        }

        override fun close() {
            closeCalls += 1
            events += "session.close.$label"
            closeError?.let { throw it }
        }
    }
}
