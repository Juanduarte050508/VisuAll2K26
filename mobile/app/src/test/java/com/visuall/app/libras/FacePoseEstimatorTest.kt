package com.visuall.app.libras

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacePoseEstimatorTest {

    @Test
    fun `identidade representa rosto frontal`() {
        val pose = FacePoseEstimator.fromColumnMajorMatrix(identity())!!

        assertEquals(0f, pose.pitchDegrees, 0.001f)
        assertEquals(0f, pose.yawDegrees, 0.001f)
        assertEquals(0f, pose.rollDegrees, 0.001f)
    }

    @Test
    fun `extrai yaw mesmo quando matriz contem escala`() {
        val angle = 30.0 * PI / 180.0
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val scale = 2f
        val matrix = floatArrayOf(
            c * scale, 0f, -s * scale, 0f,
            0f, scale, 0f, 0f,
            s * scale, 0f, c * scale, 0f,
            0f, 0f, 0f, 1f
        )

        val pose = FacePoseEstimator.fromColumnMajorMatrix(matrix)!!

        assertEquals(30f, pose.yawDegrees, 0.01f)
        assertEquals(0f, pose.pitchDegrees, 0.01f)
        assertEquals(0f, pose.rollDegrees, 0.01f)
    }

    @Test
    fun `rejeita matriz incompleta`() {
        assertNull(FacePoseEstimator.fromColumnMajorMatrix(FloatArray(9)))
    }

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )
}
