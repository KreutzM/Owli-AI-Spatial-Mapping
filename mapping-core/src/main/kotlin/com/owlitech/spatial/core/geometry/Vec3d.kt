package com.owlitech.spatial.core.geometry

import kotlin.math.sqrt

data class Vec3d(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(other: Vec3d) = Vec3d(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3d) = Vec3d(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vec3d(x * scale, y * scale, z * scale)

    fun norm(): Double = sqrt(x * x + y * y + z * z)

    companion object {
        val ZERO = Vec3d(0.0, 0.0, 0.0)
    }
}
