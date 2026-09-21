package com.visuall.app.libras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonAssociationGateTest {

    @Test
    fun `duas amostras faciais novas adquirem a pessoa`() {
        val gate = PersonAssociationGate()
        val pose = poseFor(MAIN_FACE)

        val first = gate.avaliar(1_000L, snapshot(1_000L, MAIN_FACE), pose, emptyList())
        val second = gate.avaliar(1_100L, snapshot(1_100L, jitter(MAIN_FACE)), pose, emptyList())

        assertEquals(PersonAssociationDecision.ACQUIRING, first.decision)
        assertFalse(first.matched)
        assertEquals(0, first.matchedFaceIndex)
        assertEquals(PersonAssociationDecision.MATCHED, second.decision)
        assertTrue(second.matched)
        assertEquals(0, second.matchedFaceIndex)
    }

    @Test
    fun `repetir a mesma amostra ou usar cache nao completa aquisicao`() {
        val gate = PersonAssociationGate()
        val pose = poseFor(MAIN_FACE)
        val firstSample = snapshot(1_000L, MAIN_FACE)

        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(1_000L, firstSample, pose, emptyList()))
        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(1_010L, firstSample, pose, emptyList()))
        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(1_020L, null, pose, emptyList()))

        assertDecision(PersonAssociationDecision.MATCHED,
            gate.avaliar(1_100L, snapshot(1_100L, jitter(MAIN_FACE)), pose, emptyList()))
    }

    @Test
    fun `antes do lock cache de aquisicao respeita ttl`() {
        val gate = PersonAssociationGate()
        val pose = poseFor(MAIN_FACE)
        val sampleTime = 1_000L

        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(sampleTime, snapshot(sampleTime, MAIN_FACE), pose, emptyList()))
        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(sampleTime + PersonAssociationGate.DEFAULT_FACE_TTL_MS,
                null, pose, emptyList()))
        assertDecision(PersonAssociationDecision.STALE,
            gate.avaliar(sampleTime + PersonAssociationGate.DEFAULT_FACE_TTL_MS + 1L,
                null, pose, emptyList()))
    }

    @Test
    fun `apos lock amostra facial vazia nao bloqueia sinal`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE)
        val hand = handAt(pose[15])

        val result = gate.avaliar(
            1_200L,
            FaceSnapshot(1_200L, emptyList()),
            pose,
            listOf(hand)
        )

        assertDecision(PersonAssociationDecision.MATCHED, result)
        assertTrue(result.hasLockedSubject)
        assertEquals(setOf(0), result.acceptedHandIndices)
        assertEquals(null, result.matchedFaceIndex)
    }

    @Test
    fun `antes do lock amostra facial vazia falha fechada`() {
        val result = PersonAssociationGate().avaliar(
            1_000L,
            FaceSnapshot(1_000L, emptyList()),
            poseFor(MAIN_FACE),
            emptyList()
        )

        assertDecision(PersonAssociationDecision.NO_FACE, result)
        assertFalse(result.hasLockedSubject)
    }

    @Test
    fun `salto breve de pose bloqueia quadro mas preserva lock`() {
        val gate = acquiredGate(MAIN_FACE)
        val anotherPose = poseFor(RIGHT_FACE)

        val jumped = gate.avaliar(
            1_200L,
            snapshot(1_200L, RIGHT_FACE),
            anotherPose,
            emptyList()
        )
        val recovered = gate.avaliar(1_300L, null, poseFor(MAIN_FACE), emptyList())

        assertDecision(PersonAssociationDecision.POSE_MISMATCH, jumped)
        assertTrue(jumped.hasLockedSubject)
        assertDecision(PersonAssociationDecision.MATCHED, recovered)
        assertTrue(recovered.hasLockedSubject)
    }

    @Test
    fun `pessoa proxima nao amplia tolerancia ate aceitar corpo vizinho`() {
        val gate = acquiredGate(MAIN_FACE)
        // MAIN_FACE gera ombros largos. Sem teto absoluto, 75% dessa largura
        // aceitaria um salto de um quarto do quadro para outra pessoa.
        val adjacentPose = poseFor(MAIN_FACE).map { point ->
            Point(point.x + 0.25f, point.y)
        }

        val result = gate.avaliar(1_200L, null, adjacentPose, emptyList())

        assertDecision(PersonAssociationDecision.POSE_MISMATCH, result)
        assertTrue(result.hasLockedSubject)
    }

    @Test
    fun `salto persistente de pose destrava depois do grace`() {
        val gate = acquiredGate(MAIN_FACE)
        val jumpedPose = poseFor(RIGHT_FACE)

        val first = gate.avaliar(1_200L, null, jumpedPose, emptyList())
        val stillHeld = gate.avaliar(
            1_200L + PersonAssociationGate.DEFAULT_POSE_GRACE_MS - 1L,
            null,
            jumpedPose,
            emptyList()
        )
        val released = gate.avaliar(
            1_200L + PersonAssociationGate.DEFAULT_POSE_GRACE_MS,
            null,
            jumpedPose,
            emptyList()
        )

        assertDecision(PersonAssociationDecision.POSE_MISMATCH, first)
        assertTrue(first.hasLockedSubject)
        assertDecision(PersonAssociationDecision.POSE_MISMATCH, stillHeld)
        assertTrue(stillHeld.hasLockedSubject)
        assertDecision(PersonAssociationDecision.POSE_MISMATCH, released)
        assertFalse(released.hasLockedSubject)
    }

    @Test
    fun `pose brevemente ausente retoma lock sem aquisicao facial`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE)

        val missing = gate.avaliar(1_200L, null, emptyList(), emptyList())
        val recovered = gate.avaliar(
            2_000L,
            null,
            pose,
            listOf(handAt(pose[15]))
        )

        assertDecision(PersonAssociationDecision.INVALID_POSE, missing)
        assertTrue(missing.hasLockedSubject)
        assertDecision(PersonAssociationDecision.MATCHED, recovered)
        assertTrue(recovered.hasLockedSubject)
        assertEquals(setOf(0), recovered.acceptedHandIndices)
        assertEquals(null, recovered.matchedFaceIndex)
    }

    @Test
    fun `pose ausente alem do grace encerra sessao`() {
        val gate = acquiredGate(MAIN_FACE)

        val first = gate.avaliar(1_200L, null, emptyList(), emptyList())
        val released = gate.avaliar(
            1_200L + PersonAssociationGate.DEFAULT_POSE_GRACE_MS,
            null,
            emptyList(),
            emptyList()
        )

        assertDecision(PersonAssociationDecision.INVALID_POSE, first)
        assertTrue(first.hasLockedSubject)
        assertDecision(PersonAssociationDecision.INVALID_POSE, released)
        assertFalse(released.hasLockedSubject)
    }

    @Test
    fun `intervalo sem avaliar nao expira sessao entre modos`() {
        val gate = acquiredGate(MAIN_FACE)

        val resumed = gate.avaliar(60_000L, null, poseFor(MAIN_FACE), emptyList())

        assertDecision(PersonAssociationDecision.MATCHED, resumed)
        assertTrue(resumed.hasLockedSubject)
        assertEquals(null, resumed.matchedFaceIndex)
    }

    @Test
    fun `retomar apos modo exige uma unica reconfirmacao atual`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE)
        gate.preparePoseReanchor()

        val waiting = gate.avaliar(60_000L, null, pose, emptyList())
        val confirmed = gate.avaliar(
            60_100L,
            snapshot(60_100L, jitter(MAIN_FACE)),
            pose,
            emptyList()
        )

        assertDecision(PersonAssociationDecision.ACQUIRING, waiting)
        assertTrue(waiting.hasLockedSubject)
        assertDecision(PersonAssociationDecision.MATCHED, confirmed)
        assertTrue(confirmed.hasLockedSubject)
        assertEquals(0, confirmed.matchedFaceIndex)
    }

    @Test
    fun `reconfirmacao permite mesma pessoa mudar de lugar entre modos`() {
        val gate = acquiredGate(MAIN_FACE)
        val movedFace = translate(MAIN_FACE, 0.25f)
        gate.preparePoseReanchor()

        val result = gate.avaliar(
            60_000L,
            snapshot(60_000L, movedFace),
            poseFor(movedFace),
            emptyList()
        )

        assertDecision(PersonAssociationDecision.MATCHED, result)
        assertTrue(result.hasLockedSubject)
        assertEquals(0, result.matchedFaceIndex)
    }

    @Test
    fun `face volta a ser rastreada apos movimento rapido sem bloquear corpo`() {
        val gate = acquiredGate(RIGHT_FACE)
        val movedFace = translate(RIGHT_FACE, -0.15f)

        val result = gate.avaliar(
            1_200L,
            snapshot(1_200L, movedFace),
            poseFor(movedFace),
            emptyList()
        )

        assertDecision(PersonAssociationDecision.MATCHED, result)
        assertEquals(0, result.matchedFaceIndex)
    }

    @Test
    fun `rosto valido do fundo nao rouba sessao nem confirma interrogacao`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE)

        val background = gate.avaliar(
            1_200L,
            FaceSnapshot(1_200L, listOf(candidate(LEFT_SIMILAR_FACE))),
            pose,
            emptyList()
        )
        val signerAgain = gate.avaliar(
            1_300L,
            snapshot(1_300L, jitter(MAIN_FACE)),
            pose,
            emptyList()
        )

        assertDecision(PersonAssociationDecision.MATCHED, background)
        assertTrue(background.hasLockedSubject)
        assertEquals(null, background.matchedFaceIndex)
        assertDecision(PersonAssociationDecision.MATCHED, signerAgain)
        assertEquals(0, signerAgain.matchedFaceIndex)
    }

    @Test
    fun `ausencia facial persistente nao bloqueia enquanto pose continua`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE)

        val first = gate.avaliar(1_200L, FaceSnapshot(1_200L, emptyList()),
            pose, emptyList())
        val second = gate.avaliar(1_700L, FaceSnapshot(1_700L, emptyList()),
            pose, emptyList())
        val third = gate.avaliar(2_100L, FaceSnapshot(2_100L, emptyList()),
            pose, emptyList())

        assertDecision(PersonAssociationDecision.MATCHED, first)
        assertDecision(PersonAssociationDecision.MATCHED, second)
        assertTrue(first.hasLockedSubject)
        assertTrue(second.hasLockedSubject)
        assertDecision(PersonAssociationDecision.MATCHED, third)
        assertTrue(third.hasLockedSubject)
        assertEquals(null, third.matchedFaceIndex)
    }

    @Test
    fun `dois rostos quase empatados sao ambiguos`() {
        val gate = PersonAssociationGate()
        val almostSameArea = Rect(0.36f, 0.16f, 0.64f, 0.44f)

        val result = gate.avaliar(
            1_000L,
            FaceSnapshot(1_000L, listOf(candidate(MAIN_FACE), candidate(almostSameArea))),
            poseFor(MAIN_FACE),
            emptyList()
        )

        assertDecision(PersonAssociationDecision.AMBIGUOUS, result)
    }

    @Test
    fun `dois rostos semelhantes permitem o unico que casa com a pose`() {
        val gate = PersonAssociationGate()
        val pose = poseFor(RIGHT_FACE)

        val first = gate.avaliar(
            1_000L,
            FaceSnapshot(1_000L,
                listOf(candidate(LEFT_SIMILAR_FACE), candidate(RIGHT_FACE))),
            pose,
            emptyList()
        )
        val second = gate.avaliar(
            1_100L,
            FaceSnapshot(1_100L,
                listOf(candidate(jitter(LEFT_SIMILAR_FACE)), candidate(jitter(RIGHT_FACE)))),
            pose,
            emptyList()
        )

        assertDecision(PersonAssociationDecision.ACQUIRING, first)
        assertEquals(1, first.matchedFaceIndex)
        assertDecision(PersonAssociationDecision.MATCHED, second)
        assertEquals(1, second.matchedFaceIndex)
    }

    @Test
    fun `unico match muito menor que rosto dominante e rejeitado`() {
        val gate = PersonAssociationGate()

        val result = gate.avaliar(
            1_000L,
            FaceSnapshot(1_000L,
                listOf(candidate(LARGE_LEFT_FACE), candidate(RIGHT_FACE))),
            poseFor(RIGHT_FACE),
            emptyList()
        )

        assertDecision(PersonAssociationDecision.POSE_MISMATCH, result)
    }

    @Test
    fun `bbox alinhado mas ancora do nariz divergente e rejeitado`() {
        val gate = PersonAssociationGate()
        val divergentNose = Point(MAIN_FACE.right, MAIN_FACE.center.y)

        val result = gate.avaliar(
            1_000L,
            FaceSnapshot(1_000L, listOf(candidate(MAIN_FACE, divergentNose))),
            poseFor(MAIN_FACE),
            emptyList()
        )

        assertDecision(PersonAssociationDecision.POSE_MISMATCH, result)
    }

    @Test
    fun `maior rosto vence um rosto pequeno do fundo independente da ordem`() {
        val gate = PersonAssociationGate()
        val pose = poseFor(MAIN_FACE)

        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(1_000L,
                FaceSnapshot(1_000L,
                    listOf(candidate(BACKGROUND_FACE), candidate(MAIN_FACE))),
                pose, emptyList()))
        val matched = gate.avaliar(1_100L,
            FaceSnapshot(1_100L,
                listOf(candidate(BACKGROUND_FACE), candidate(jitter(MAIN_FACE)))),
            pose, emptyList())
        assertDecision(PersonAssociationDecision.MATCHED, matched)
        assertEquals(1, matched.matchedFaceIndex)
    }

    @Test
    fun `um unico rosto distante demais nao pode ser adquirido`() {
        val gate = PersonAssociationGate()
        val distantFace = Rect(0.47f, 0.20f, 0.53f, 0.27f)

        assertDecision(PersonAssociationDecision.FACE_TOO_SMALL,
            gate.avaliar(1_000L, snapshot(1_000L, distantFace),
                poseFor(distantFace), emptyList()))
        assertDecision(PersonAssociationDecision.NO_FACE,
            gate.avaliar(1_001L, null, poseFor(distantFace), emptyList()))
    }

    @Test
    fun `tamanho facial minimo pode ser calibrado para outro enquadramento`() {
        val distantFace = Rect(0.47f, 0.20f, 0.53f, 0.27f)
        val pose = poseFor(distantFace)
        val gate = PersonAssociationGate(minFaceWidth = 0.05f, minFaceHeight = 0.06f)

        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(1_000L, snapshot(1_000L, distantFace), pose, emptyList()))
        assertDecision(PersonAssociationDecision.MATCHED,
            gate.avaliar(1_100L, snapshot(1_100L, jitter(distantFace)),
                pose, emptyList()))
    }

    @Test
    fun `troca persistente de pessoa exige nova aquisicao facial`() {
        val gate = acquiredGate(MAIN_FACE)
        val rightPose = poseFor(RIGHT_FACE)

        assertDecision(PersonAssociationDecision.POSE_MISMATCH,
            gate.avaliar(1_200L, null, rightPose, emptyList()))
        val released = gate.avaliar(
            1_200L + PersonAssociationGate.DEFAULT_POSE_GRACE_MS,
            null,
            rightPose,
            emptyList()
        )
        assertDecision(PersonAssociationDecision.POSE_MISMATCH, released)
        assertFalse(released.hasLockedSubject)

        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(3_300L, snapshot(3_300L, RIGHT_FACE), rightPose, emptyList()))
        assertDecision(PersonAssociationDecision.MATCHED,
            gate.avaliar(3_400L, snapshot(3_400L, jitter(RIGHT_FACE)),
                rightPose, emptyList()))
    }

    @Test
    fun `mao alheia longe dos pulsos da pose e filtrada`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE)
        val ownHand = handAt(Point(pose[15].x + 0.01f, pose[15].y - 0.01f))
        val strangerHand = handAt(Point(0.02f, 0.02f))

        val result = gate.avaliar(1_150L, null, pose, listOf(ownHand, strangerHand))

        assertDecision(PersonAssociationDecision.MATCHED, result)
        assertEquals(setOf(0), result.acceptedHandIndices)
        assertEquals(mapOf(0 to 0), result.handSlotAssignments)
    }

    @Test
    fun `duas maos junto do mesmo pulso aceitam apenas a mais proxima`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE)
        val farther = handAt(Point(pose[15].x + 0.025f, pose[15].y))
        val closer = handAt(Point(pose[15].x + 0.004f, pose[15].y))

        val result = gate.avaliar(1_150L, null, pose, listOf(farther, closer))

        assertDecision(PersonAssociationDecision.MATCHED, result)
        assertEquals(setOf(1), result.acceptedHandIndices)
        assertEquals(mapOf(1 to 0), result.handSlotAssignments)
    }

    @Test
    fun `matching de maos prioriza duas associacoes antes da menor distancia local`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE).toMutableList().also {
            it[15] = Point(0.40f, 0.75f)
            it[16] = Point(0.60f, 0.75f)
        }
        // A mao 0 prefere levemente o pulso esquerdo, mas tambem alcanca o
        // direito. A mao 1 so alcanca o esquerdo. Greedy aceitaria apenas uma.
        val flexibleHand = handAt(Point(0.49f, 0.75f))
        val leftOnlyHand = handAt(Point(0.30f, 0.75f))

        val result = gate.avaliar(
            1_150L,
            null,
            pose,
            listOf(flexibleHand, leftOnlyHand)
        )

        assertDecision(PersonAssociationDecision.MATCHED, result)
        assertEquals(setOf(0, 1), result.acceptedHandIndices)
        assertEquals(mapOf(0 to 1, 1 to 0), result.handSlotAssignments)
    }

    @Test
    fun `associacao e filtro de maos sao invariantes a espelhamento`() {
        val originalPose = poseFor(MAIN_FACE)
        val originalHand = handAt(originalPose[16])
        val original = matchFromTwoSamples(MAIN_FACE, originalPose, listOf(originalHand))

        val mirroredFace = mirror(MAIN_FACE)
        val mirroredPose = originalPose.map(::mirror)
        val mirroredHand = originalHand.map(::mirror)
        val mirrored = matchFromTwoSamples(mirroredFace, mirroredPose, listOf(mirroredHand))

        assertDecision(PersonAssociationDecision.MATCHED, original)
        assertDecision(PersonAssociationDecision.MATCHED, mirrored)
        assertEquals(original.acceptedHandIndices, mirrored.acceptedHandIndices)
        assertEquals(setOf(0), mirrored.acceptedHandIndices)
    }

    @Test
    fun `pose incompleta ou nao finita falha fechada`() {
        val validPose = poseFor(MAIN_FACE)

        val incompleteGate = PersonAssociationGate()
        assertDecision(PersonAssociationDecision.INVALID_POSE,
            incompleteGate.avaliar(1_000L, snapshot(1_000L, MAIN_FACE),
                validPose.dropLast(1), emptyList()))

        val nanPose = validPose.toMutableList().also { it[20] = Point(Float.NaN, 0.5f) }
        val nanGate = PersonAssociationGate()
        assertDecision(PersonAssociationDecision.INVALID_POSE,
            nanGate.avaliar(1_000L, snapshot(1_000L, MAIN_FACE), nanPose, emptyList()))
    }

    @Test
    fun `mao incompleta ou nao finita nunca e aceita`() {
        val pose = poseFor(MAIN_FACE)
        val gate = acquiredGate(MAIN_FACE)
        val incomplete = handAt(pose[15]).dropLast(1)
        val nanHand = handAt(pose[16]).toMutableList().also {
            it[10] = Point(Float.NaN, 0f)
        }

        val result = gate.avaliar(1_150L, null, pose, listOf(incomplete, nanHand))

        assertDecision(PersonAssociationDecision.MATCHED, result)
        assertTrue(result.acceptedHandIndices.isEmpty())
    }

    @Test
    fun `retangulo facial invalido falha fechado antes do lock`() {
        val gate = PersonAssociationGate()
        val invalid = Rect(Float.NaN, 0.1f, 0.4f, 0.4f)

        assertDecision(PersonAssociationDecision.INVALID_FACE,
            gate.avaliar(1_200L, snapshot(1_200L, invalid),
                poseFor(MAIN_FACE), emptyList()))
    }

    @Test
    fun `flag de face invalida apos lock nao bloqueia e nao confirma rosto`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE)

        val result = gate.avaliar(
            1_200L,
            FaceSnapshot(
                timestampMs = 1_200L,
                faces = listOf(candidate(MAIN_FACE)),
                invalidFaceDetected = true
            ),
            pose,
            listOf(handAt(pose[16]))
        )

        assertDecision(PersonAssociationDecision.MATCHED, result)
        assertTrue(result.hasLockedSubject)
        assertEquals(null, result.matchedFaceIndex)
        assertEquals(setOf(0), result.acceptedHandIndices)
    }

    @Test
    fun `flag de face invalida antes do lock falha fechada`() {
        val result = PersonAssociationGate().avaliar(
            1_000L,
            FaceSnapshot(
                timestampMs = 1_000L,
                faces = listOf(candidate(MAIN_FACE)),
                invalidFaceDetected = true
            ),
            poseFor(MAIN_FACE),
            emptyList()
        )

        assertDecision(PersonAssociationDecision.INVALID_FACE, result)
        assertFalse(result.hasLockedSubject)
    }

    @Test
    fun `aquisicao a aproximadamente sete fps cabe no ttl`() {
        val gate = PersonAssociationGate()
        val pose = poseFor(MAIN_FACE)

        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(1_000L, snapshot(1_000L, MAIN_FACE), pose, emptyList()))
        // FACE_DETECT_STRIDE=5 a ~7 fps deixa cerca de 715 ms entre amostras.
        assertDecision(PersonAssociationDecision.MATCHED,
            gate.avaliar(1_715L, snapshot(1_715L, jitter(MAIN_FACE)), pose, emptyList()))
    }

    @Test
    fun `reset exige aquisicao novamente`() {
        val gate = acquiredGate(MAIN_FACE)
        val pose = poseFor(MAIN_FACE)

        gate.reset()

        assertDecision(PersonAssociationDecision.NO_FACE,
            gate.avaliar(1_150L, null, pose, emptyList()))
        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(1_200L, snapshot(1_200L, MAIN_FACE), pose, emptyList()))
    }

    private fun acquiredGate(face: Rect, startMs: Long = 1_000L): PersonAssociationGate {
        val gate = PersonAssociationGate()
        val pose = poseFor(face)
        assertDecision(PersonAssociationDecision.ACQUIRING,
            gate.avaliar(startMs, snapshot(startMs, face), pose, emptyList()))
        assertDecision(PersonAssociationDecision.MATCHED,
            gate.avaliar(startMs + 100L, snapshot(startMs + 100L, jitter(face)),
                pose, emptyList()))
        return gate
    }

    private fun matchFromTwoSamples(
        face: Rect,
        pose: List<Point>,
        hands: List<List<Point>>
    ): PersonAssociationResult {
        val gate = PersonAssociationGate()
        gate.avaliar(1_000L, snapshot(1_000L, face), pose, hands)
        return gate.avaliar(1_100L, snapshot(1_100L, jitter(face)), pose, hands)
    }

    private fun snapshot(timestampMs: Long, face: Rect) =
        FaceSnapshot(timestampMs, listOf(candidate(face)))

    private fun candidate(face: Rect, nose: Point = face.center) =
        FaceCandidate(face, nose)

    private fun jitter(face: Rect) = Rect(
        face.left + 0.005f,
        face.top + 0.004f,
        face.right + 0.005f,
        face.bottom + 0.004f
    )

    private fun translate(face: Rect, dx: Float) = Rect(
        face.left + dx,
        face.top,
        face.right + dx,
        face.bottom
    )

    private fun poseFor(face: Rect): List<Point> {
        val center = face.center
        val points = MutableList(33) { Point(center.x, face.bottom + face.height) }

        // Pose face landmarks 0..10 stay around the detected face.
        for (index in 0..10) {
            val column = (index % 3) - 1
            val row = (index / 3) - 1
            points[index] = Point(
                center.x + column * face.width * 0.08f,
                center.y + row * face.height * 0.06f
            )
        }
        points[0] = center // nose

        val shoulderY = face.bottom + face.height * 0.35f
        val shoulderHalfWidth = face.width * 0.80f
        points[11] = Point(center.x - shoulderHalfWidth, shoulderY)
        points[12] = Point(center.x + shoulderHalfWidth, shoulderY)
        points[15] = Point(center.x - shoulderHalfWidth - face.width * 0.15f,
            shoulderY + face.height * 0.65f)
        points[16] = Point(center.x + shoulderHalfWidth + face.width * 0.15f,
            shoulderY + face.height * 0.65f)
        return points
    }

    private fun handAt(wrist: Point): List<Point> =
        List(21) { index -> Point(wrist.x + index * 0.0002f, wrist.y) }

    private fun mirror(point: Point) = Point(1f - point.x, point.y)

    private fun mirror(rect: Rect) = Rect(1f - rect.right, rect.top, 1f - rect.left, rect.bottom)

    private fun assertDecision(
        expected: PersonAssociationDecision,
        actual: PersonAssociationResult
    ) {
        assertEquals(expected, actual.decision)
        if (expected != PersonAssociationDecision.MATCHED) {
            assertTrue("rejected result exposed hands", actual.acceptedHandIndices.isEmpty())
        } else {
            assertTrue("matched result lost its session lock", actual.hasLockedSubject)
        }
    }

    companion object {
        private val MAIN_FACE = Rect(0.35f, 0.15f, 0.65f, 0.45f)
        private val RIGHT_FACE = Rect(0.78f, 0.18f, 0.98f, 0.42f)
        private val LEFT_SIMILAR_FACE = Rect(0.08f, 0.17f, 0.30f, 0.43f)
        private val LARGE_LEFT_FACE = Rect(0.04f, 0.08f, 0.36f, 0.44f)
        private val BACKGROUND_FACE = Rect(0.04f, 0.05f, 0.10f, 0.12f)
    }
}
