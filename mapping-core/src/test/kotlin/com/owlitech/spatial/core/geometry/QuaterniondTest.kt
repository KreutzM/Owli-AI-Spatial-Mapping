package com.owlitech.spatial.core.geometry

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuaterniondTest {
    @Test
    fun rotatesAroundZAxis() {
        val halfAngle = PI / 4.0
        val rotation = Quaterniond(cos(halfAngle), 0.0, 0.0, sin(halfAngle))

        val result = rotation.rotate(Vec3d(1.0, 0.0, 0.0))

        assertEquals(0.0, result.x, 1e-12)
        assertEquals(1.0, result.y, 1e-12)
        assertEquals(0.0, result.z, 1e-12)
    }
}
