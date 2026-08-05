package com.owlitech.spatial.core.depth

/** Depth values are unsigned millimetres. Zero means unknown. */
class DepthFrame(
    val width: Int,
    val height: Int,
    private val depthMillimetres: IntArray,
    private val confidence: ByteArray? = null,
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive." }
        require(depthMillimetres.size == width * height) { "Depth buffer size does not match dimensions." }
        require(confidence == null || confidence.size == width * height) {
            "Confidence buffer size does not match dimensions."
        }
        require(depthMillimetres.all { it >= 0 }) { "Depth values must not be negative." }
    }

    fun depthMm(x: Int, y: Int): Int = depthMillimetres[index(x, y)]

    /** Returns 0..255 when a confidence plane exists, otherwise null. */
    fun confidenceOrNull(x: Int, y: Int): Int? =
        confidence?.get(index(x, y))?.toInt()?.and(0xFF)

    private fun index(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "Pixel outside frame." }
        return y * width + x
    }
}
