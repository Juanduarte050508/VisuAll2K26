package com.visuall.app.libras

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Extrai sinais do FaceLandmarker e entrega a decisao temporal ao
 * [BrowGestureDetector]. O evento de pergunta atende Corpo e Alfabeto; os
 * pontos e o diagnostico continuam sendo uma escolha da interface.
 */
internal class FaceMarkerEngine(private val context: Context) {

    private var faceLandmarker: FaceLandmarker? = null
    private var faceModelIndisponivel = false
    private val detector = BrowGestureDetector(mode = BrowRaiseMode.BOTH)
    private var faceFrameCounter = 0
    private var lastFaceSampleTimestamp: Long? = null

    /**
     * Chamado uma vez por frame nos dois modos. A inferencia facial e reduzida
     * por [LibrasAnalyzer.FACE_DETECT_STRIDE], mas a maquina temporal usa o
     * timestamp real em milissegundos, nao uma contagem de frames.
     *
     * Devolve um [FaceSnapshot] somente quando este frame foi realmente
     * amostrado. `null` significa "stride pulou o frame", e nao "sem rosto";
     * [forceSample] permite adquirir o sinalizante logo ao entrar no modo
     * Corpo. Depois do lock, a cadencia reduzida volta a priorizar os gestos.
     */
    @Synchronized
    fun step(
        mpImage: MPImage,
        timestamp: Long,
        forceSample: Boolean = false,
        onDetected: () -> Unit,
        onLandmarks: (pontos: FloatArray?) -> Unit,
        onDiagnostic: (BrowDetection) -> Unit
    ): FaceSnapshot? {
        faceFrameCounter++
        val lastSample = lastFaceSampleTimestamp
        val acquisitionDue = forceSample && (lastSample == null ||
            timestamp < lastSample ||
            timestamp - lastSample >= ACQUISITION_FACE_INTERVAL_MS)
        val regularDue = !forceSample &&
            faceFrameCounter % LibrasAnalyzer.FACE_DETECT_STRIDE == 0
        if (!acquisitionDue && !regularDue) return null
        lastFaceSampleTimestamp = timestamp

        val faceResult = ensureLoaded()?.detectForVideo(mpImage, timestamp)
        onLandmarks(extractOverlayPoints(faceResult))

        val detection = detector.update(extractFeatures(faceResult, timestamp))
        onDiagnostic(detection)
        // O gesto e um EVENTO: depois de confirmado, a interrogacao pertence
        // a frase e nao some quando a pessoa abaixa a sobrancelha.
        if (detection.triggered) onDetected()
        return extractFaceSnapshot(faceResult, timestamp)
    }

    @Synchronized
    fun reset() {
        detector.reset()
        faceFrameCounter = 0
        lastFaceSampleTimestamp = null
    }

    /** Reinicia apenas o baseline facial quando a sessao troca de pessoa. */
    @Synchronized
    fun onSubjectLost() {
        detector.reset()
        lastFaceSampleTimestamp = null
    }

    private fun faceOptions(delegate: Delegate) = FaceLandmarkerOptions.builder()
        .setBaseOptions(
            BaseOptions.builder()
                .setModelAssetPath(FACE_MODEL_ASSET)
                .setDelegate(delegate)
                .build()
        )
        .setRunningMode(RunningMode.VIDEO)
        // Dois permite enxergar o sinalizante e um rosto ao fundo: o filtro de
        // pessoa escolhe o dominante ou rejeita a ambiguidade. A sobrancelha
        // continua exigindo um unico rosto, e tem suavizacao temporal propria.
        .setNumFaces(2)
        .setMinFaceDetectionConfidence(MIN_FACE_CONFIDENCE)
        .setMinFacePresenceConfidence(MIN_FACE_CONFIDENCE)
        .setMinTrackingConfidence(MIN_FACE_CONFIDENCE)
        .setOutputFaceBlendshapes(true)
        .setOutputFacialTransformationMatrixes(true)
        .build()

    private fun ensureLoaded(): FaceLandmarker? {
        faceLandmarker?.let { return it }
        if (faceModelIndisponivel) return null

        // O delegate GPU precisa ser criado e usado no mesmo executor. O
        // LibrasAnalyzer faz ambos no executor unico da camera.
        return try {
            FaceLandmarker.createFromOptions(context, faceOptions(Delegate.GPU))
                .also { faceLandmarker = it }
        } catch (error: Throwable) {
            try {
                Log.w("FaceMarkerEngine", "GPU indisponivel pro FaceLandmarker, usando CPU", error)
                FaceLandmarker.createFromOptions(context, faceOptions(Delegate.CPU))
                    .also { faceLandmarker = it }
            } catch (error2: Throwable) {
                Log.e("FaceMarkerEngine", "FaceLandmarker indisponivel", error2)
                faceLandmarker = null
                faceModelIndisponivel = true
                null
            }
        }
    }

    private fun extractFeatures(
        result: FaceLandmarkerResult?,
        timestamp: Long
    ): BrowFrameFeatures {
        val faces = result?.faceLandmarks().orEmpty()
        if (faces.size != 1) {
            return BrowFrameFeatures(
                timestampMs = timestamp,
                faceCount = faces.size,
                landmarksValid = false,
                blendshapesAvailable = false
            )
        }

        val face = faces[0]
        if (REQUIRED_LANDMARKS.any { it !in face.indices }) {
            return BrowFrameFeatures(
                timestampMs = timestamp,
                faceCount = 1,
                landmarksValid = false,
                blendshapesAvailable = false
            )
        }

        val browA = center(face, BROW_A)
        val browB = center(face, BROW_B)
        val eyeA = midpoint(face[EYE_A_CORNERS[0]], face[EYE_A_CORNERS[1]])
        val eyeB = midpoint(face[EYE_B_CORNERS[0]], face[EYE_B_CORNERS[1]])
        val geometry = normalizedBrowGeometry(browA, browB, eyeA, eyeB)
            ?: return BrowFrameFeatures(
                timestampMs = timestamp,
                faceCount = 1,
                landmarksValid = false,
                blendshapesAvailable = false
            )

        val blendshapes = result?.faceBlendshapes()?.orElse(emptyList())?.singleOrNull()
        val scores = blendshapes?.associateBy(Category::categoryName).orEmpty()
        val pose = result?.facialTransformationMatrixes()
            ?.orElse(emptyList())
            ?.singleOrNull()
            ?.let(FacePoseEstimator::fromColumnMajorMatrix)

        return BrowFrameFeatures(
            timestampMs = timestamp,
            faceCount = 1,
            landmarksValid = true,
            blendshapesAvailable = blendshapes != null,
            gapA = geometry.gapA,
            gapB = geometry.gapB,
            // Grupo A (landmarks 33/133) corresponde ao lado "Right" da
            // topologia do modelo; B (362/263), ao "Left". A interface usa
            // A/B ate o espelhamento ser validado manualmente no aparelho.
            browOuterUpA = scores.score(BLEND_BROW_OUTER_UP_A),
            browOuterUpB = scores.score(BLEND_BROW_OUTER_UP_B),
            browInnerUp = scores.score(BLEND_BROW_INNER_UP),
            browDownA = scores.score(BLEND_BROW_DOWN_A),
            browDownB = scores.score(BLEND_BROW_DOWN_B),
            eyeBlinkA = scores.score(BLEND_EYE_BLINK_A),
            eyeBlinkB = scores.score(BLEND_EYE_BLINK_B),
            eyeWideA = scores.score(BLEND_EYE_WIDE_A),
            eyeWideB = scores.score(BLEND_EYE_WIDE_B),
            pitchDegrees = pose?.pitchDegrees,
            yawDegrees = pose?.yawDegrees,
            rollDegrees = pose?.rollDegrees ?: geometry.rollDegrees
        )
    }

    /**
     * Projeta a distancia sobrancelha-olho no eixo perpendicular a linha dos
     * olhos. Isso remove inclinacao 2D da cabeca e normaliza por escala facial.
     */
    private fun normalizedBrowGeometry(
        browA: BrowPoint,
        browB: BrowPoint,
        eyeA: BrowPoint,
        eyeB: BrowPoint
    ): BrowGeometry? {
        var dx = eyeB.x - eyeA.x
        var dy = eyeB.y - eyeA.y
        // Mantem o eixo apontando para a direita da imagem. Assim a normal
        // (-dy, dx) aponta aproximadamente para baixo, mesmo com espelhamento.
        if (dx < 0f) {
            dx = -dx
            dy = -dy
        }
        val scale = sqrt(dx * dx + dy * dy)
        if (scale < 1e-6f) return null
        val axisX = dx / scale
        val axisY = dy / scale
        val downX = -axisY
        val downY = axisX

        fun gap(eye: BrowPoint, brow: BrowPoint): Float {
            val eyeToBrowX = eye.x - brow.x
            val eyeToBrowY = eye.y - brow.y
            return (eyeToBrowX * downX + eyeToBrowY * downY) / scale
        }

        return BrowGeometry(
            gapA = gap(eyeA, browA),
            gapB = gap(eyeB, browB),
            rollDegrees = (atan2(dy, dx) * 180.0 / PI).toFloat()
        )
    }

    /**
     * Pontos faciais: 3 por sobrancelha, 3 por olho, nariz e boca. O ponto
     * central de cada olho e virtual (media dos cantos), portanto nao desce ao
     * piscar como acontecia com os antigos indices 159/386 da palpebra.
     */
    private fun extractOverlayPoints(result: FaceLandmarkerResult?): FloatArray? {
        val face = result?.faceLandmarks()?.singleOrNull() ?: return null
        if (OVERLAY_LANDMARKS.any { it !in face.indices }) return null

        val points = ArrayList<BrowPoint>(OVERLAY_POINT_COUNT)
        BROW_A.forEach { points += face[it].toPoint() }
        BROW_B.forEach { points += face[it].toPoint() }
        addEyeOverlay(points, face, EYE_A_CORNERS)
        addEyeOverlay(points, face, EYE_B_CORNERS)
        NOSE.forEach { points += face[it].toPoint() }
        MOUTH.forEach { points += face[it].toPoint() }

        return FloatArray(points.size * 2).also { output ->
            points.forEachIndexed { index, point ->
                output[index * 2] = point.x
                output[index * 2 + 1] = point.y
            }
        }
    }

    /**
     * Observacao facial completa, no mesmo espaco normalizado de pose/maos.
     * Um rosto bruto malformado nao pode desaparecer silenciosamente da lista:
     * o gate precisa dessa evidencia para falhar fechado em vez de promover um
     * segundo rosto valido do fundo.
     */
    private fun extractFaceSnapshot(
        result: FaceLandmarkerResult?,
        timestamp: Long
    ): FaceSnapshot {
        val candidates = mutableListOf<FaceCandidate>()
        var invalidFaceDetected = false

        result?.faceLandmarks().orEmpty().forEach { face ->
            val candidate = faceCandidate(face)
            if (candidate == null) {
                invalidFaceDetected = true
            } else {
                candidates += candidate
            }
        }

        return FaceSnapshot(
            timestampMs = timestamp,
            faces = candidates,
            invalidFaceDetected = invalidFaceDetected
        )
    }

    private fun faceCandidate(face: List<NormalizedLandmark>): FaceCandidate? {
        val bounds = faceBounds(face) ?: return null
        val nose = face.getOrNull(NOSE_LANDMARK_INDEX) ?: return null
        val noseX = nose.x()
        val noseY = nose.y()
        if (!noseX.isFinite() || !noseY.isFinite()) return null

        return FaceCandidate(
            bounds = bounds,
            nose = Point(noseX, noseY)
        )
    }

    private fun faceBounds(face: List<NormalizedLandmark>): Rect? {
        if (face.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        face.forEach { landmark ->
            val x = landmark.x()
            val y = landmark.y()
            if (!x.isFinite() || !y.isFinite()) return null
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        val bounds = Rect(
            left = minX.coerceIn(0f, 1f),
            top = minY.coerceIn(0f, 1f),
            right = maxX.coerceIn(0f, 1f),
            bottom = maxY.coerceIn(0f, 1f)
        )
        return bounds.takeIf { it.width > 0f && it.height > 0f }
    }

    private fun addEyeOverlay(
        destination: MutableList<BrowPoint>,
        face: List<NormalizedLandmark>,
        corners: IntArray
    ) {
        val first = face[corners[0]].toPoint()
        val second = face[corners[1]].toPoint()
        destination += first
        destination += midpoint(first, second)
        destination += second
    }

    private fun center(face: List<NormalizedLandmark>, indices: IntArray): BrowPoint {
        var x = 0f
        var y = 0f
        indices.forEach { index ->
            x += face[index].x()
            y += face[index].y()
        }
        return BrowPoint(x / indices.size, y / indices.size)
    }

    private fun midpoint(a: NormalizedLandmark, b: NormalizedLandmark): BrowPoint =
        midpoint(a.toPoint(), b.toPoint())

    private fun midpoint(a: BrowPoint, b: BrowPoint): BrowPoint =
        BrowPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    private fun NormalizedLandmark.toPoint() = BrowPoint(x(), y())

    private fun Map<String, Category>.score(name: String): Float = this[name]?.score() ?: 0f

    @Synchronized
    fun close() {
        runCatching { faceLandmarker?.close() }
            .onFailure { Log.w("FaceMarkerEngine", "Falha ao fechar FaceLandmarker", it) }
        faceLandmarker = null
    }

    private data class BrowPoint(val x: Float, val y: Float)
    private data class BrowGeometry(val gapA: Float, val gapB: Float, val rollDegrees: Float)

    private companion object {
        const val FACE_MODEL_ASSET = "face_landmarker.task"
        const val MIN_FACE_CONFIDENCE = 0.5f
        const val ACQUISITION_FACE_INTERVAL_MS = 120L
        const val NOSE_LANDMARK_INDEX = 1

        val BROW_A = intArrayOf(70, 105, 107)
        val BROW_B = intArrayOf(300, 334, 336)
        val EYE_A_CORNERS = intArrayOf(33, 133)
        val EYE_B_CORNERS = intArrayOf(362, 263)
        val NOSE = intArrayOf(1)
        val MOUTH = intArrayOf(61, 13, 291, 14)
        val REQUIRED_LANDMARKS = BROW_A + BROW_B + EYE_A_CORNERS + EYE_B_CORNERS
        val OVERLAY_LANDMARKS = REQUIRED_LANDMARKS + NOSE + MOUTH
        const val OVERLAY_POINT_COUNT = 17

        const val BLEND_BROW_OUTER_UP_A = "browOuterUpRight"
        const val BLEND_BROW_OUTER_UP_B = "browOuterUpLeft"
        const val BLEND_BROW_INNER_UP = "browInnerUp"
        const val BLEND_BROW_DOWN_A = "browDownRight"
        const val BLEND_BROW_DOWN_B = "browDownLeft"
        const val BLEND_EYE_BLINK_A = "eyeBlinkRight"
        const val BLEND_EYE_BLINK_B = "eyeBlinkLeft"
        const val BLEND_EYE_WIDE_A = "eyeWideRight"
        const val BLEND_EYE_WIDE_B = "eyeWideLeft"
    }
}
