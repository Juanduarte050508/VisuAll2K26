package com.visuall.app.libras

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

internal data class FacePoseAngles(
    val pitchDegrees: Float,
    val yawDegrees: Float,
    val rollDegrees: Float
)

/** Extrai Euler XYZ da matriz 4x4 column-major devolvida pelo MediaPipe. */
internal object FacePoseEstimator {
    private const val RAD_TO_DEGREES = 57.29577951308232

    fun fromColumnMajorMatrix(matrix: FloatArray): FacePoseAngles? {
        if (matrix.size != 16 || matrix.any { !it.isFinite() }) return null

        // O transform inclui escala uniforme. Normalizar as colunas conserva
        // somente a rotacao antes da conversao para Euler.
        val scaleX = length(matrix[0], matrix[1], matrix[2])
        val scaleY = length(matrix[4], matrix[5], matrix[6])
        val scaleZ = length(matrix[8], matrix[9], matrix[10])
        if (scaleX < 1e-6f || scaleY < 1e-6f || scaleZ < 1e-6f) return null

        val r00 = matrix[0] / scaleX
        val r10 = matrix[1] / scaleX
        val r20 = matrix[2] / scaleX
        val r21 = matrix[6] / scaleY
        val r22 = matrix[10] / scaleZ

        // Convencao R = Rz(roll) * Ry(yaw) * Rx(pitch).
        val yaw = asin((-r20).coerceIn(-1f, 1f))
        val pitch = atan2(r21, r22)
        val roll = atan2(r10, r00)
        return FacePoseAngles(
            pitchDegrees = (pitch * RAD_TO_DEGREES).toFloat(),
            yawDegrees = (yaw * RAD_TO_DEGREES).toFloat(),
            rollDegrees = (roll * RAD_TO_DEGREES).toFloat()
        )
    }

    private fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
}
