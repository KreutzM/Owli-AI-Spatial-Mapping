package com.owlitech.spatial.core.geometry

/**
 * A transform from a local coordinate frame into its parent frame.
 * `transform(point)` first rotates, then translates the point.
 */
data class RigidTransform(
    val translation: Vec3d,
    val rotation: Quaterniond,
) {
    fun transform(point: Vec3d): Vec3d = rotation.rotate(point) + translation

    companion object {
        val IDENTITY = RigidTransform(Vec3d.ZERO, Quaterniond.IDENTITY)
    }
}
