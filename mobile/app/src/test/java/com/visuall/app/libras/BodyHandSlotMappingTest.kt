package com.visuall.app.libras

import org.junit.Assert.assertEquals
import org.junit.Test

class BodyHandSlotMappingTest {

    @Test
    fun `pulso esquerdo da pose entra no segundo slot do modelo`() {
        assertEquals(1, bodyModelHandSlot(0, "Left", 0.2))
    }

    @Test
    fun `pulso direito da pose entra no primeiro slot do modelo`() {
        assertEquals(0, bodyModelHandSlot(1, "Right", 0.8))
    }

    @Test
    fun `associacao da pose tem prioridade sobre handedness instavel`() {
        assertEquals(1, bodyModelHandSlot(0, "Right", 0.8))
        assertEquals(0, bodyModelHandSlot(1, "Left", 0.2))
    }

    @Test
    fun `sem pose preserva inversao do MediaPipe Tasks validada no aparelho`() {
        assertEquals(0, bodyModelHandSlot(null, "Right", 0.8))
        assertEquals(1, bodyModelHandSlot(null, "Left", 0.2))
    }

    @Test
    fun `sem rotulo usa lado da imagem como desempate`() {
        assertEquals(0, bodyModelHandSlot(null, "", 0.49))
        assertEquals(1, bodyModelHandSlot(null, "", 0.50))
    }
}
