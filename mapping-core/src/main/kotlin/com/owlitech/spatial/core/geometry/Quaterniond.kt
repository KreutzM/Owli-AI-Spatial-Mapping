package com.owlitech.spatial.core.geometry

import kotlin.math.sqrt

data class Quaterniond(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun normalized(): Quaterniond {
        val magnitude = sqrt(w * w + x * x + y * y + z * z)
        require(magnitude > 0.0) { "Quaternion magnitude must be positive." }
        return Quaterniond(w / magnitude, x / magnitude, y / magnitude, z / magnitude)
    }

    fun conjugate() = Quaterniond(w, -x, -y, -z)

    operator fun times(other: Quaterniond) = Quaterniond(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun rotate(vector: Vec3d): Vec3d {
        val q = normalized()
        val result = q * Quaterniond(0.0, vector.x, vector.y, vector.z) * q.conjugate()
        return Vec3d(result.x, result.y, result.z)
    }

    companion object {
        val IDENTITY = Quaterniond(1.0, 0.0, 0.0, 0.0)
    }
}
