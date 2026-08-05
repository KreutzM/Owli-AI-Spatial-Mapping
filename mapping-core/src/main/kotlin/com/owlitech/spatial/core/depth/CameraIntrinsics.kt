package com.owlitech.spatial.core.depth

data class CameraIntrinsics(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive." }
        require(fx > 0.0 && fy > 0.0) { "Focal lengths must be positive." }
    }
}
