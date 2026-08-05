package com.owlitech.spatial.core.depth

import com.owlitech.spatial.core.geometry.Vec3d

data class ProjectedPoint(
    val pixelX: Int,
    val pixelY: Int,
    /** Null means the source frame did not provide a confidence plane. */
    val confidence: Int?,
    val point: Vec3d,
)
