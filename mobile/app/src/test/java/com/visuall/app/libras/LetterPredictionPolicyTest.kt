package com.visuall.app.libras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterPredictionPolicyTest {
    @Test
    fun `modelo geral aceito nao pode ser trocado por especialista`() {
        var specialistRan = false
        val result = LetterPredictionPolicy.generalFirst(
            Prediction("M", 0.96f, "estatico")
        ) {
            specialistRan = true
            Prediction("A", 0.99f, "estatico_individual")
        }

        assertEquals("M", result.letra)
        assertFalse(specialistRan)
    }

    @Test
    fun `especialista recupera somente rejeicao do modelo geral`() {
        var specialistRan = false
        val result = LetterPredictionPolicy.generalFirst(
            Prediction("-", 0.80f, "estatico")
        ) {
            specialistRan = true
            Prediction("A", 0.99f, "estatico_individual")
        }

        assertEquals("A", result.letra)
        assertTrue(specialistRan)
    }

    @Test
    fun `rejeicao do especialista preserva diagnostico do geral`() {
        val general = Prediction("-", 0.77f, "estatico")
        val result = LetterPredictionPolicy.generalFirst(general) {
            Prediction("-", 0.20f, "estatico_individual")
        }

        assertEquals(general, result)
    }
}
