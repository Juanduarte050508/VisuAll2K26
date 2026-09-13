package com.visuall.app.libras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowGestureDetectorTest {

    private val fastConfig = BrowGestureDetector.Config(
        calibrationDurationMs = 900L,
        calibrationMinSamples = 8,
        candidateDwellMs = 250L,
        releaseDwellMs = 200L,
        minimumVisibleMs = 500L,
        cooldownMs = 600L,
        faceLossGraceMs = 250L,
        baselineResetAfterNoFaceMs = 1_500L,
        smoothingTauMs = 35f
    )

    @Test
    fun `calibra neutro individual antes de aceitar gesto`() {
        val detector = BrowGestureDetector(config = fastConfig)
        val afterCalibration = calibrate(detector, neutralGap = 0.31f)

        assertEquals(BrowGestureState.NEUTRAL, afterCalibration.state)
        assertEquals(0.31f, afterCalibration.baselineGapA!!, 0.002f)
        assertFalse(afterCalibration.markerVisible)
    }

    @Test
    fun `um quadro alto nao ativa sem tempo de sustentacao`() {
        val detector = BrowGestureDetector(config = fastConfig)
        var time = calibrate(detector).let { 1_100L }

        val spike = detector.update(raised(time))
        val neutralAgain = detector.update(neutral(time + 100L))

        assertEquals(BrowGestureState.CANDIDATE_RAISE, spike.state)
        assertFalse(spike.markerVisible)
        assertEquals(BrowGestureState.NEUTRAL, neutralAgain.state)
    }

    @Test
    fun `levantar os dois lados ativa depois do dwell`() {
        val detector = BrowGestureDetector(config = fastConfig)
        calibrate(detector)

        detector.update(raised(1_100L))
        detector.update(raised(1_250L))
        val result = detector.update(raised(1_400L))

        assertTrue(result.triggered)
        assertTrue(result.markerVisible)
        assertEquals(BrowGestureState.RAISED, result.state)
    }

    @Test
    fun `piscar nao inicia pergunta`() {
        val detector = BrowGestureDetector(config = fastConfig)
        calibrate(detector)

        val blink = raised(1_100L).copy(eyeBlinkA = 0.95f, eyeBlinkB = 0.93f)
        val result = detector.update(blink)

        assertFalse(result.markerVisible)
        assertEquals(BrowGestureState.NEUTRAL, result.state)
        assertEquals(BrowDecisionReason.BLINK, result.reason)
    }

    @Test
    fun `pose excessiva rejeita mesmo com sobrancelha alta`() {
        val detector = BrowGestureDetector(config = fastConfig)
        calibrate(detector)

        val result = detector.update(raised(1_100L).copy(yawDegrees = 35f))

        assertFalse(result.markerVisible)
        assertEquals(BrowDecisionReason.POSE_EXCESSIVE, result.reason)
    }

    @Test
    fun `modo ambos rejeita elevacao unilateral`() {
        val detector = BrowGestureDetector(mode = BrowRaiseMode.BOTH, config = fastConfig)
        calibrate(detector)

        detector.update(raisedOnlyA(1_100L))
        val result = detector.update(raisedOnlyA(1_400L))

        assertFalse(result.markerVisible)
        assertEquals(BrowGestureState.NEUTRAL, result.state)
    }

    @Test
    fun `modo qualquer lado aceita elevacao unilateral`() {
        val detector = BrowGestureDetector(mode = BrowRaiseMode.EITHER, config = fastConfig)
        calibrate(detector)

        detector.update(raisedOnlyA(1_100L))
        val result = detector.update(raisedOnlyA(1_400L))

        assertTrue(result.markerVisible)
    }

    @Test
    fun `histerese segura marcador durante oscilacao e cooldown impede repeticao`() {
        val detector = BrowGestureDetector(config = fastConfig)
        calibrate(detector)
        detector.update(raised(1_100L))
        detector.update(raised(1_400L))

        // Cai abaixo da entrada, mas ainda acima da saida: nao pisca.
        val nearExit = raised(1_650L).copy(gapA = 0.322f, gapB = 0.322f)
        assertTrue(detector.update(nearExit).markerVisible)

        detector.update(neutral(1_900L))
        val cooldown = detector.update(neutral(2_150L))
        assertEquals(BrowGestureState.COOLDOWN, cooldown.state)
        assertFalse(cooldown.markerVisible)

        val attemptedAgain = detector.update(raised(2_350L))
        assertEquals(BrowGestureState.COOLDOWN, attemptedAgain.state)
        assertFalse(attemptedAgain.triggered)
    }

    @Test
    fun `perda curta do rosto nao causa flicker`() {
        val detector = BrowGestureDetector(config = fastConfig)
        calibrate(detector)
        detector.update(raised(1_100L))
        detector.update(raised(1_400L))

        val shortLoss = detector.update(noFace(1_500L))
        val recovered = detector.update(raised(1_650L))

        assertTrue(shortLoss.markerVisible)
        assertTrue(recovered.markerVisible)
    }

    @Test
    fun `evento facial preserva pergunta na frase ao baixar e alternar trechos`() {
        val detector = BrowGestureDetector(mode = BrowRaiseMode.BOTH, config = fastConfig)
        val sentence = SentenceBuilder().apply {
            iniciarTrechoCorpo()
            definirTrechoCorpo("PESSOA")
        }
        calibrate(detector)
        var events = 0
        fun deliver(frame: BrowFrameFeatures): BrowDetection {
            val result = detector.update(frame)
            if (result.triggered && sentence.marcarInterrogativa()) events++
            return result
        }

        deliver(raised(1_100L))
        deliver(raised(1_400L))
        deliver(raised(1_650L))
        deliver(neutral(1_900L))
        val released = deliver(neutral(2_150L))
        assertEquals(1, events)
        assertFalse(released.markerVisible)
        assertEquals("PESSOA?", PhraseOutput.exibicao(sentence.frase, sentence.interrogativa))

        // Mesmas operacoes da troca de modo, sem depender da camera Android.
        detector.reset()
        sentence.encerrarTrechoCorpo()
        sentence.adicionarEspaco()
        sentence.aceitarLetra("A")
        sentence.iniciarTrechoCorpo()
        sentence.definirTrechoCorpo("AJUDAR")
        val displayed = PhraseOutput.exibicao(sentence.frase, sentence.interrogativa)
        assertEquals("PESSOA A AJUDAR?", displayed)
        // A leitura da frase inteira adicionada na main recebe a pontuacao.
        assertEquals("pessoa a ajudar?", PhraseOutput.textoParaVoz(displayed))
        assertTrue(sentence.removerInterrogacao())
        assertEquals("PESSOA A AJUDAR", sentence.frase)
        assertFalse(sentence.interrogativa)
    }

    private fun calibrate(
        detector: BrowGestureDetector,
        neutralGap: Float = 0.30f
    ): BrowDetection {
        var result = detector.update(neutral(0L, neutralGap))
        for (time in 100L..1_000L step 100) {
            val smallNoise = if ((time / 100L) % 2L == 0L) 0.001f else -0.001f
            result = detector.update(neutral(time, neutralGap + smallNoise))
        }
        return result
    }

    private fun neutral(time: Long, gap: Float = 0.30f) = BrowFrameFeatures(
        timestampMs = time,
        faceCount = 1,
        landmarksValid = true,
        blendshapesAvailable = true,
        gapA = gap,
        gapB = gap,
        browOuterUpA = 0.08f,
        browOuterUpB = 0.08f,
        browInnerUp = 0.06f,
        browDownA = 0.05f,
        browDownB = 0.05f,
        eyeBlinkA = 0.02f,
        eyeBlinkB = 0.02f,
        eyeWideA = 0.05f,
        eyeWideB = 0.05f,
        pitchDegrees = 0f,
        yawDegrees = 0f,
        rollDegrees = 0f
    )

    private fun raised(time: Long) = neutral(time).copy(
        gapA = 0.39f,
        gapB = 0.39f,
        browOuterUpA = 0.48f,
        browOuterUpB = 0.48f,
        browInnerUp = 0.45f
    )

    private fun raisedOnlyA(time: Long) = neutral(time).copy(
        gapA = 0.39f,
        browOuterUpA = 0.48f,
        browInnerUp = 0.25f
    )

    private fun noFace(time: Long) = BrowFrameFeatures(
        timestampMs = time,
        faceCount = 0,
        landmarksValid = false,
        blendshapesAvailable = false
    )
}
