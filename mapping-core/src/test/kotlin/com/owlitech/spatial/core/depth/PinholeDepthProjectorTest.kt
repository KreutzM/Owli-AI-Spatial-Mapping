package com.owlitech.spatial.core.depth

import com.owlitech.spatial.core.geometry.Quaterniond
import com.owlitech.spatial.core.geometry.RigidTransform
import com.owlitech.spatial.core.geometry.Vec3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PinholeDepthProjectorTest {
    @Test
    fun principalPointProjectsStraightForward() {
        val frame = DepthFrame(3, 3, intArrayOf(0,0,0, 0,2000,0, 0,0,0))
        val intrinsics = CameraIntrinsics(3, 3, fx = 100.0, fy = 100.0, cx = 1.0, cy = 1.0)

        val point = PinholeDepthProjector.projectOptical(frame, intrinsics).single().point

        assertEquals(Vec3d(0.0, 0.0, 2.0), point)
    }

    @Test
    fun confidenceAndUnknownDepthAreFiltered() {
        val frame = DepthFrame(
            width = 2,
            height = 1,
            depthMillimetres = intArrayOf(0, 1000),
            confidence = byteArrayOf(255.toByte(), 20),
        )
        val intrinsics = CameraIntrinsics(2, 1, 100.0, 100.0, 0.0, 0.0)

        val points = PinholeDepthProjector.projectOptical(frame, intrinsics, minimumConfidence = 21)

        assertEquals(0, points.size)
    }

    @Test
    fun positiveConfidenceThresholdRejectsFramesWithoutConfidencePlane() {
        val frame = DepthFrame(1, 1, intArrayOf(1000))
        val intrinsics = CameraIntrinsics(1, 1, 100.0, 100.0, 0.0, 0.0)

        val points = PinholeDepthProjector.projectOptical(frame, intrinsics, minimumConfidence = 1)

        assertEquals(0, points.size)
    }

    @Test
    fun opticalCoordinatesAreConvertedBeforeWorldTransform() {
        val frame = DepthFrame(1, 1, intArrayOf(1000))
        val intrinsics = CameraIntrinsics(1, 1, 100.0, 100.0, 0.0, 0.0)
        val transform = RigidTransform(Vec3d(1.0, 2.0, 3.0), Quaterniond.IDENTITY)

        val point = PinholeDepthProjector.projectWorld(frame, intrinsics, transform).single().point

        assertEquals(Vec3d(1.0, 2.0, 2.0), point)
    }
}
