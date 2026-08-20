package com.owlitech.spatial.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ArDiagnosticSurfaceLifecycleTest {
    @Test
    fun initialContextSurfaceAndResumeEnableUpdatesInOrder() {
        val fixture = Fixture()

        fixture.coordinator.onPrerequisitesChanged(true)
        fixture.coordinator.onActivityResume()
        fixture.controller.onSurfaceCreated(TEXTURE_1)
        fixture.controller.updateFrame()
        assertEquals(0, fixture.session.updateCalls)

        fixture.controller.onSurfaceChanged(ROTATION_0, WIDTH, HEIGHT)
        fixture.controller.updateFrame()

        assertEquals(1, fixture.factory.created)
        assertEquals(listOf(TEXTURE_1), fixture.session.textureIds)
        assertEquals(listOf(Geometry(ROTATION_0, WIDTH, HEIGHT)), fixture.session.geometries)
        assertEquals(1, fixture.session.updateCalls)
        assertEquals(listOf("texture:$TEXTURE_1", "geometry:$ROTATION_0:$WIDTH:$HEIGHT", "update"), fixture.session.frameEvents)
    }

    @Test
    fun pauseStopsRenderEligibilityBeforeSessionPauseAndClearsCurrentState() {
        val fixture = Fixture()
        fixture.startAndPublishDepth()
        val updatesBeforePause = fixture.session.updateCalls
        fixture.events.clear()

        fixture.coordinator.onActivityPause()

        assertEquals(listOf("surface.pause", "surface.pause.update-blocked", "session.pause"), fixture.events)
        assertEquals(updatesBeforePause, fixture.session.updateCalls)
        assertEquals(1, fixture.session.pauseCalls)
        assertNull(fixture.observations.last())
        assertNull(fixture.depthStates.last().currentObservation)
        assertNull(fixture.depthStates.last().lastNewDataStatistics)

        fixture.controller.updateFrame()
        assertEquals(updatesBeforePause, fixture.session.updateCalls)
    }

    @Test
    fun preservedContextForegroundRecoversWithoutSecondSurfaceCreatedActivityOrSession() {
        val fixture = Fixture()
        fixture.startAndPublishDepth()
        val originalCoordinator = fixture.coordinator
        val originalSession = fixture.session
        assertEquals(1, fixture.surfaceCreatedEvents)
        assertEquals(1, originalSession.textureIds.size)

        fixture.coordinator.onActivityPause()
        fixture.coordinator.onActivityResume()

        // A preserved EGL context recreates the window/EGL surface without a second
        // Renderer.onSurfaceCreated callback. Until onSurfaceChanged reports that new surface,
        // update remains blocked even though Session.resume() has succeeded.
        fixture.controller.updateFrame()
        assertEquals(1, originalSession.updateCalls)
        fixture.surfaceChanged(ROTATION_90, HEIGHT, WIDTH)
        fixture.controller.updateFrame()

        assertSame(originalCoordinator, fixture.coordinator)
        assertSame(originalSession, fixture.session)
        assertEquals(1, fixture.surfaceCreatedEvents)
        assertEquals(1, fixture.factory.created)
        assertEquals(2, originalSession.resumeCalls)
        assertEquals(1, originalSession.pauseCalls)
        assertEquals(listOf(TEXTURE_1), originalSession.textureIds)
        assertEquals(
            listOf(
                Geometry(ROTATION_0, WIDTH, HEIGHT),
                Geometry(ROTATION_90, HEIGHT, WIDTH),
            ),
            originalSession.geometries,
        )
        assertEquals(2, originalSession.updateCalls)
    }

    @Test
    fun recreatedContextReplacesTextureAndRebindsBeforeUpdate() {
        val fixture = Fixture()
        fixture.startAndPublishDepth()

        fixture.coordinator.onActivityPause()
        fixture.coordinator.onActivityResume()
        fixture.surfaceCreated(TEXTURE_2)
        fixture.surfaceChanged(ROTATION_0, WIDTH, HEIGHT)
        fixture.controller.updateFrame()

        assertEquals(2, fixture.surfaceCreatedEvents)
        assertEquals(1, fixture.factory.created)
        assertEquals(listOf(TEXTURE_1, TEXTURE_2), fixture.session.textureIds)
        assertEquals(
            listOf(
                "texture:$TEXTURE_1",
                "geometry:$ROTATION_0:$WIDTH:$HEIGHT",
                "update",
                "texture:$TEXTURE_2",
                "geometry:$ROTATION_0:$WIDTH:$HEIGHT",
                "update",
            ),
            fixture.session.frameEvents,
        )
    }

    @Test
    fun repeatedPreservedContextPauseResumeCyclesRemainIdempotent() {
        val fixture = Fixture()
        fixture.startAndPublishDepth()

        repeat(3) {
            fixture.coordinator.onActivityPause()
            fixture.coordinator.onActivityPause()
            fixture.coordinator.onActivityResume()
            fixture.coordinator.onActivityResume()
            fixture.surfaceChanged(ROTATION_0, WIDTH, HEIGHT)
            fixture.controller.updateFrame()
        }

        assertEquals(1, fixture.factory.created)
        assertEquals(1, fixture.surfaceCreatedEvents)
        assertEquals(4, fixture.session.resumeCalls)
        assertEquals(3, fixture.session.pauseCalls)
        assertEquals(listOf(TEXTURE_1), fixture.session.textureIds)
        assertEquals(4, fixture.session.geometryCalls)
        assertEquals(4, fixture.session.updateCalls)
    }

    @Test
    fun updateWaitsForSessionContextTextureAndRenderSurfacePrerequisites() {
        val fixture = Fixture()

        fixture.controller.updateFrame()
        fixture.surfaceCreated(TEXTURE_1)
        fixture.surfaceChanged(ROTATION_0, WIDTH, HEIGHT)
        fixture.controller.updateFrame()
        assertEquals(0, fixture.factory.created)

        fixture.coordinator.onPrerequisitesChanged(true)
        fixture.coordinator.onActivityResume()
        fixture.controller.onRenderSurfaceUnavailable()
        fixture.controller.updateFrame()
        assertEquals(0, fixture.session.updateCalls)

        fixture.surfaceChanged(ROTATION_0, WIDTH, HEIGHT)
        fixture.controller.updateFrame()
        assertEquals(1, fixture.session.updateCalls)
    }

    @Test
    fun newSessionOnRealOwnershipReplacementRebindsRetainedTexture() {
        val scheduler = FakeScheduler()
        val first = Fixture(scheduler)
        first.startAndPublishDepth()
        first.controller.close()

        val second = Fixture(scheduler)
        second.surfaceCreated(TEXTURE_2)
        second.surfaceChanged(ROTATION_0, WIDTH, HEIGHT)
        second.coordinator.onPrerequisitesChanged(true)
        second.coordinator.onActivityResume()
        second.controller.updateFrame()

        assertEquals(1, first.factory.created)
        assertEquals(1, first.session.closeCalls)
        assertEquals(1, second.factory.created)
        assertEquals(listOf(TEXTURE_2), second.session.textureIds)
        assertEquals(1, second.session.updateCalls)
    }

    private class Fixture(
        scheduler: DiagnosticSessionCloseScheduler = FakeScheduler(),
    ) {
        val events = mutableListOf<String>()
        val observations = mutableListOf<DiagnosticObservation?>()
        val depthStates = mutableListOf<DepthDiagnosticState>()
        val factory = FakeFactory(events)
        val controller = ArDiagnosticSessionController(
            sessionFactory = factory,
            sessionCloseScheduler = scheduler,
            minimumPublishIntervalNanos = 0L,
            onStateChanged = { _, observation, depth ->
                observations += observation
                depthStates += depth
            },
        )
        var surfaceCreatedEvents = 0
            private set

        private val surface = object : DiagnosticSurfacePort {
            override fun resumeSurface() {
                events += "surface.resume"
            }

            override fun pauseSurface() {
                events += "surface.pause"
                controller.onRenderSurfaceUnavailable()
                val before = session.updateCalls
                controller.updateFrame()
                check(session.updateCalls == before) {
                    "Controller update raced the render-surface pause boundary."
                }
                events += "surface.pause.update-blocked"
            }
        }

        val coordinator = DiagnosticLifecycleCoordinator(controller, surface)
        val session: FakeSession
            get() = factory.sessions.single()

        fun startAndPublishDepth() {
            coordinator.onPrerequisitesChanged(true)
            coordinator.onActivityResume()
            surfaceCreated(TEXTURE_1)
            surfaceChanged(ROTATION_0, WIDTH, HEIGHT)
            session.nextDepthDiagnostic = fakeDepthState(10L)
            controller.updateFrame()
        }

        fun surfaceCreated(textureId: Int) {
            surfaceCreatedEvents += 1
            controller.onSurfaceCreated(textureId)
        }

        fun surfaceChanged(rotation: Int, width: Int, height: Int) {
            controller.onSurfaceChanged(rotation, width, height)
        }
    }

    private class FakeFactory(
        private val events: MutableList<String>,
    ) : DiagnosticSessionFactory {
        var created = 0
        val sessions = mutableListOf<FakeSession>()

        override fun create(): DiagnosticSessionPort {
            created += 1
            events += "session.create"
            return FakeSession(events).also(sessions::add)
        }
    }

    private class FakeSession(
        private val events: MutableList<String>,
    ) : DiagnosticSessionPort {
        var resumeCalls = 0
        var pauseCalls = 0
        var closeCalls = 0
        var updateCalls = 0
        val textureIds = mutableListOf<Int>()
        val geometries = mutableListOf<Geometry>()
        val frameEvents = mutableListOf<String>()
        var nextTimestamp = 1L
        var nextDepthDiagnostic: DepthDiagnosticState? = null

        val geometryCalls: Int
            get() = geometries.size

        override fun resume() {
            resumeCalls += 1
            events += "session.resume"
        }

        override fun setCameraTextureName(textureId: Int) {
            textureIds += textureId
            frameEvents += "texture:$textureId"
        }

        override fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int) {
            geometries += Geometry(displayRotation, width, height)
            frameEvents += "geometry:$displayRotation:$width:$height"
        }

        override fun update(): DiagnosticFrameScalars {
            updateCalls += 1
            frameEvents += "update"
            return DiagnosticFrameScalars(
                frameTimestampNanos = nextTimestamp++,
                trackingState = DiagnosticTrackingState.TRACKING,
                trackingFailureReason = DiagnosticTrackingFailureReason.NONE,
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
                depthDiagnostic = nextDepthDiagnostic,
            )
        }

        override fun pause() {
            pauseCalls += 1
            events += "session.pause"
        }

        override fun close() {
            closeCalls += 1
            events += "session.close"
        }
    }

    private class FakeScheduler : DiagnosticSessionCloseScheduler {
        private var state = DiagnosticSessionSlotState.AVAILABLE
        private var waiter: (() -> Unit)? = null

        override fun tryAcquireSessionSlot(): Boolean {
            if (state != DiagnosticSessionSlotState.AVAILABLE) return false
            state = DiagnosticSessionSlotState.OWNED
            return true
        }

        override fun releaseSessionSlot() {
            state = DiagnosticSessionSlotState.AVAILABLE
            notifyWaiter()
        }

        override fun scheduleClose(
            session: DiagnosticSessionPort,
            onComplete: (Throwable?) -> Unit,
        ) {
            state = DiagnosticSessionSlotState.CLOSING
            val error = try {
                session.close()
                null
            } catch (failure: Throwable) {
                failure
            }
            state = DiagnosticSessionSlotState.AVAILABLE
            onComplete(error)
            notifyWaiter()
        }

        override fun notifyWhenAvailable(callback: () -> Unit) {
            if (state == DiagnosticSessionSlotState.AVAILABLE) {
                callback()
            } else {
                waiter = callback
            }
        }

        override fun currentSlotState(): DiagnosticSessionSlotState = state

        private fun notifyWaiter() {
            val callback = waiter ?: return
            waiter = null
            callback()
        }
    }

    private data class Geometry(
        val rotation: Int,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val TEXTURE_1 = 11
        const val TEXTURE_2 = 22
        const val ROTATION_0 = 0
        const val ROTATION_90 = 1
        const val WIDTH = 640
        const val HEIGHT = 480

        fun fakeDepthState(timestamp: Long): DepthDiagnosticState {
            val stats = DepthPixelStatistics(1, 1, 1000, 1000, 1, 255, 255, 1, 0, 0)
            val configuration = DepthConfigurationDiagnostic(
                rawDepthOnlySupported = true,
                automaticSupported = true,
                selectedMode = DiagnosticDepthMode.RAW_DEPTH_ONLY,
                configuredMode = DiagnosticDepthMode.RAW_DEPTH_ONLY,
                status = DepthConfigurationStatus.CONFIGURED,
            )
            return DepthDiagnosticState(
                configuration = configuration,
                acquisitionStatus = DepthAcquisitionStatus.NEW_DEPTH_DATA,
                currentObservation = DepthDiagnosticObservation(
                    frameTimestampNanos = timestamp,
                    rawDepthTimestampNanos = timestamp,
                    confidenceTimestampNanos = timestamp,
                    dataKind = DepthDataKind.NEW,
                    depthWidth = 1,
                    depthHeight = 1,
                    confidenceWidth = 1,
                    confidenceHeight = 1,
                    depthRowStride = 2,
                    depthPixelStride = 2,
                    confidenceRowStride = 1,
                    confidencePixelStride = 1,
                    depthFormat = DepthFormatClassification.D_16,
                    confidenceFormat = DepthFormatClassification.Y8,
                    statistics = stats,
                    cpuImageIntrinsics = null,
                    gpuTextureIntrinsics = null,
                    displayRotation = ROTATION_0,
                    viewportWidth = WIDTH,
                    viewportHeight = HEIGHT,
                ),
                lastNewDataStatistics = stats,
                lastNewDataTimestampNanos = timestamp,
            )
        }
    }
}
