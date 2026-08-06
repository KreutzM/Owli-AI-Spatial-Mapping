package com.owlitech.spatial.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticObservationConverterTest {
    private val converter = DiagnosticObservationConverter()

    @Test
    fun trackingFrameCopiesMetresAndReordersQuaternionExactly() {
        val observation = converter.convert(validTrackingFrame())
        val pose = requireNotNull(observation.worldFromArCoreCamera)

        assertEquals(1.25, pose.translation.x, 0.0)
        assertEquals(-2.5, pose.translation.y, 0.0)
        assertEquals(3.75, pose.translation.z, 0.0)
        assertEquals(0.4, pose.rotation.w, 0.0)
        assertEquals(0.1, pose.rotation.x, 0.0)
        assertEquals(0.2, pose.rotation.y, 0.0)
        assertEquals(0.3, pose.rotation.z, 0.0)

        val intrinsics = requireNotNull(observation.nativeImageIntrinsics)
        assertEquals(600.5, intrinsics.fx, 0.0)
        assertEquals(601.5, intrinsics.fy, 0.0)
        assertEquals(319.25, intrinsics.cx, 0.0)
        assertEquals(239.75, intrinsics.cy, 0.0)
        assertEquals(640, intrinsics.width)
        assertEquals(480, intrinsics.height)
        assertEquals(9_876_543_210L, observation.frameTimestampNanos)
    }

    @Test
    fun pausedAndStoppedFramesRemoveCurrentPoseAndIntrinsics() {
        for (trackingState in listOf(DiagnosticTrackingState.PAUSED, DiagnosticTrackingState.STOPPED)) {
            val observation = converter.convert(validTrackingFrame().copy(trackingState = trackingState))
            assertNull(observation.worldFromArCoreCamera)
            assertNull(observation.nativeImageIntrinsics)
        }
    }

    @Test
    fun invalidIntrinsicsAreDiscardedWithoutChangingValidPose() {
        val invalidFrames = listOf(
            validTrackingFrame().copy(imageFx = Double.NaN),
            validTrackingFrame().copy(imageFx = Double.POSITIVE_INFINITY),
            validTrackingFrame().copy(imageFx = 0.0),
            validTrackingFrame().copy(imageFy = -1.0),
            validTrackingFrame().copy(imageCx = Double.NEGATIVE_INFINITY),
            validTrackingFrame().copy(imageWidth = 0),
            validTrackingFrame().copy(imageHeight = -1),
        )

        invalidFrames.forEach { frame ->
            val observation = converter.convert(frame)
            assertTrue(observation.worldFromArCoreCamera != null)
            assertNull(observation.nativeImageIntrinsics)
        }
    }

    @Test
    fun nonFinitePoseScalarsDiscardOnlyCurrentPose() {
        val observation = converter.convert(
            validTrackingFrame().copy(translationMetresX = Double.NaN),
        )

        assertNull(observation.worldFromArCoreCamera)
        assertTrue(observation.nativeImageIntrinsics != null)
    }

    private fun validTrackingFrame() = DiagnosticFrameScalars(
        frameTimestampNanos = 9_876_543_210L,
        trackingState = DiagnosticTrackingState.TRACKING,
        trackingFailureReason = DiagnosticTrackingFailureReason.NONE,
        translationMetresX = 1.25,
        translationMetresY = -2.5,
        translationMetresZ = 3.75,
        arCoreQuaternionX = 0.1,
        arCoreQuaternionY = 0.2,
        arCoreQuaternionZ = 0.3,
        arCoreQuaternionW = 0.4,
        imageFx = 600.5,
        imageFy = 601.5,
        imageCx = 319.25,
        imageCy = 239.75,
        imageWidth = 640,
        imageHeight = 480,
    )
}
