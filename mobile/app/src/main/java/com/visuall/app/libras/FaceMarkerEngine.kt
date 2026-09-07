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
 * [BrowGestureDetector]. Roda apenas no modo CORPO, por decisao do produto.
 */
internal class FaceMarkerEngine(private val context: Context) {

    private var faceLandmarker: FaceLandmarker? = null
    private var faceModelIndisponivel = false
    private val detector = BrowGestureDetector(mode = BrowRaiseMode.BOTH)
    private var markerVisible = false
    private var faceFrameCounter = 0

    /**
     * Chamado uma vez por frame do modo corpo. A inferencia facial e reduzida
     * por [LibrasAnalyzer.FACE_DETECT_STRIDE], mas a maquina temporal usa o
     * timestamp real em milissegundos, nao uma contagem de frames.
     */
    @Synchronized
    fun step(
        mpImage: MPImage,
        timestamp: Long,
        onChange: (ativo: Boolean) -> Unit,
        onLandmarks: (pontos: FloatArray?) -> Unit,
        onDiagnostic: (BrowDetection) -> Unit
    ) {
        faceFrameCounter++
        if (faceFrameCounter % LibrasAnalyzer.FACE_DETECT_STRIDE != 0) return

        val faceResult = ensureLoaded()?.detectForVideo(mpImage, timestamp)
        onLandmarks(extractOverlayPoints(faceResult))

        val detection = detector.update(extractFeatures(faceResult, timestamp))
        onDiagnostic(detection)
        if (detection.markerVisible != markerVisible) {
            markerVisible = detection.markerVisible
            onChange(markerVisible)
        }
    }

    @Synchronized
    fun reset() {
        detector.reset()
        markerVisible = false
        faceFrameCounter = 0
    }

    private fun faceOptions(delegate: Delegate) = FaceLandmarkerOptions.builder()
        .setBaseOptions(
            BaseOptions.builder()
                .setModelAssetPath(FACE_MODEL_ASSET)
                .setDelegate(delegate)
                .build()
        )
        .setRunningMode(RunningMode.VIDEO)
        // Dois permite detectar e rejeitar explicitamente mais de uma pessoa.
        // A suavizacao interna do MediaPipe so existe com um rosto, mas esta
        // classe ja aplica filtro temporal proprio, independente do FPS.
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
        browA: Point,
        browB: Point,
        eyeA: Point,
        eyeB: Point
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

        fun gap(eye: Point, brow: Point): Float {
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
     * Pontos vermelhos: 3 por sobrancelha, 3 por olho, nariz e boca. O ponto
     * central de cada olho e virtual (media dos cantos), portanto nao desce ao
     * piscar como acontecia com os antigos indices 159/386 da palpebra.
     */
    private fun extractOverlayPoints(result: FaceLandmarkerResult?): FloatArray? {
        val face = result?.faceLandmarks()?.singleOrNull() ?: return null
        if (OVERLAY_LANDMARKS.any { it !in face.indices }) return null

        val points = ArrayList<Point>(OVERLAY_POINT_COUNT)
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

    private fun addEyeOverlay(
        destination: MutableList<Point>,
        face: List<NormalizedLandmark>,
        corners: IntArray
    ) {
        val first = face[corners[0]].toPoint()
        val second = face[corners[1]].toPoint()
        destination += first
        destination += midpoint(first, second)
        destination += second
    }

    private fun center(face: List<NormalizedLandmark>, indices: IntArray): Point {
        var x = 0f
        var y = 0f
        indices.forEach { index ->
            x += face[index].x()
            y += face[index].y()
        }
        return Point(x / indices.size, y / indices.size)
    }

    private fun midpoint(a: NormalizedLandmark, b: NormalizedLandmark): Point =
        midpoint(a.toPoint(), b.toPoint())

    private fun midpoint(a: Point, b: Point): Point = Point((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    private fun NormalizedLandmark.toPoint() = Point(x(), y())

    private fun Map<String, Category>.score(name: String): Float = this[name]?.score() ?: 0f

    @Synchronized
    fun close() {
        runCatching { faceLandmarker?.close() }
            .onFailure { Log.w("FaceMarkerEngine", "Falha ao fechar FaceLandmarker", it) }
        faceLandmarker = null
    }

    private data class Point(val x: Float, val y: Float)
    private data class BrowGeometry(val gapA: Float, val gapB: Float, val rollDegrees: Float)

    private companion object {
        const val FACE_MODEL_ASSET = "face_landmarker.task"
        const val MIN_FACE_CONFIDENCE = 0.5f

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
