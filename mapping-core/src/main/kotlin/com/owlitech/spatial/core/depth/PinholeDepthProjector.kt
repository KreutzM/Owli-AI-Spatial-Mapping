package com.owlitech.spatial.core.depth

import com.owlitech.spatial.core.geometry.RigidTransform
import com.owlitech.spatial.core.geometry.Vec3d

/**
 * Projects depth pixels using optical camera coordinates: +X right, +Y down, +Z forward.
 * ARCore camera coordinates are +X right, +Y up, -Z forward, so conversion is explicit.
 */
object PinholeDepthProjector {
    fun projectOptical(
        frame: DepthFrame,
        intrinsics: CameraIntrinsics,
        stride: Int = 1,
        minimumConfidence: Int = 0,
    ): List<ProjectedPoint> {
        require(frame.width == intrinsics.width && frame.height == intrinsics.height) {
            "Depth frame and intrinsics dimensions must match."
        }
        require(stride > 0) { "Stride must be positive." }
        require(minimumConfidence in 0..255) { "Confidence threshold must be 0..255." }

        val result = ArrayList<ProjectedPoint>((frame.width / stride) * (frame.height / stride))
        for (y in 0 until frame.height step stride) {
            for (x in 0 until frame.width step stride) {
                val depthMm = frame.depthMm(x, y)
                val confidence = frame.confidenceOrNull(x, y)
                if (depthMm == 0) continue
                // A positive threshold requires actual confidence evidence. Missing data is not promoted to certainty.
                if (minimumConfidence > 0 && (confidence == null || confidence < minimumConfidence)) continue
                val z = depthMm / 1000.0
                val point = Vec3d(
                    x = (x - intrinsics.cx) * z / intrinsics.fx,
                    y = (y - intrinsics.cy) * z / intrinsics.fy,
                    z = z,
                )
                result += ProjectedPoint(x, y, confidence, point)
            }
        }
        return result
    }

    fun opticalToArCoreCamera(point: Vec3d): Vec3d = Vec3d(point.x, -point.y, -point.z)

    fun projectWorld(
        frame: DepthFrame,
        intrinsics: CameraIntrinsics,
        worldFromArCoreCamera: RigidTransform,
        stride: Int = 1,
        minimumConfidence: Int = 0,
    ): List<ProjectedPoint> = projectOptical(frame, intrinsics, stride, minimumConfidence).map { projected ->
        projected.copy(
            point = worldFromArCoreCamera.transform(opticalToArCoreCamera(projected.point)),
        )
    }
}
