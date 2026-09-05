package com.visuall.app.libras

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.sqrt

// Marcador de sobrancelha (frase vira pergunta) — extraído do LibrasAnalyzer.
// Roda apenas no modo CORPO, onde o marcador facial sera validado primeiro,
// mas só a cada FACE_DETECT_STRIDE frames, e se a inicialização falhar
// (aparelho fraco/sem o asset) o app segue sem o marcador em vez de quebrar
// — mesma filosofia defensiva do BodyGestureEngine.
internal class FaceMarkerEngine(private val context: Context) {

    private var faceLandmarker: FaceLandmarker? = null
    private var faceModelIndisponivel = false
    private val bufferSobrancelha = ArrayDeque<Float>()
    private var interrogativoAtivo = false
    private var faceFrameCounter = 0

    // Chamado uma vez por frame no modo corpo. `onChange` so e disparado
    // quando o estado da sobrancelha muda. `onLandmarks` entrega pontos reais
    // do FaceLandmarker para sobrancelhas, olhos, nariz e boca, sem mistura-los
    // com os pontos faciais aproximados do PoseLandmarker.
    fun step(
        mpImage: MPImage,
        timestamp: Long,
        onChange: (ativo: Boolean) -> Unit,
        onLandmarks: (pontos: FloatArray?) -> Unit
    ) {
        faceFrameCounter++
        if (faceFrameCounter % LibrasAnalyzer.FACE_DETECT_STRIDE != 0) return

        val faceResult = ensureLoaded()?.detectForVideo(mpImage, timestamp)
        onLandmarks(extrairPontosDoMarcador(faceResult))
        val sobrancelhaAtiva = lerMarcador(faceResult)
        if (sobrancelhaAtiva != interrogativoAtivo) {
            interrogativoAtivo = sobrancelhaAtiva
            onChange(sobrancelhaAtiva)
        }
    }

    private fun extrairPontosDoMarcador(faceResult: FaceLandmarkerResult?): FloatArray? {
        val rosto = faceResult?.faceLandmarks()?.firstOrNull() ?: return null
        if (PONTOS_OVERLAY.any { it !in rosto.indices }) return null

        return FloatArray(PONTOS_OVERLAY.size * 2).also { pontos ->
            PONTOS_OVERLAY.forEachIndexed { destino, origem ->
                pontos[destino * 2] = rosto[origem].x()
                pontos[destino * 2 + 1] = rosto[origem].y()
            }
        }
    }

    fun reset() {
        bufferSobrancelha.clear()
        interrogativoAtivo = false
        faceFrameCounter = 0
    }

    private fun faceOptions(delegate: Delegate) = FaceLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(delegate)
            .build())
        .setRunningMode(RunningMode.VIDEO)
        .setNumFaces(1)
        .setMinFaceDetectionConfidence(0.5f)
        .setMinFacePresenceConfidence(0.5f)
        .setMinTrackingConfidence(0.5f)
        .build()

    private fun ensureLoaded(): FaceLandmarker? {
        faceLandmarker?.let { return it }
        if (faceModelIndisponivel) return null

        // GPU primeiro (mais rápido pro 3º modelo rodando por frame); se o
        // aparelho não aceitar, cai pra CPU antes de desistir de vez — mesma
        // ideia do HandLandmarker no LibrasAnalyzer.
        return try {
            FaceLandmarker.createFromOptions(context, faceOptions(Delegate.GPU))
                .also { faceLandmarker = it }
        } catch (error: Throwable) {
            try {
                Log.w("FaceMarkerEngine", "GPU indisponivel pro FaceLandmarker, usando CPU", error)
                FaceLandmarker.createFromOptions(context, faceOptions(Delegate.CPU))
                    .also { faceLandmarker = it }
            } catch (error2: Throwable) {
                faceLandmarker = null
                faceModelIndisponivel = true
                null
            }
        }
    }

    // Mede a distancia vertical entre o centro de tres pontos de cada
    // sobrancelha e o centro de tres pontos do olho correspondente. O valor e
    // normalizado pela distancia entre os centros dos olhos para nao depender
    // do tamanho do rosto na imagem e suavizado em JANELA_SOBR amostras.
    private fun lerMarcador(faceResult: FaceLandmarkerResult?): Boolean {
        val rosto = faceResult?.faceLandmarks()?.firstOrNull() ?: return false
        val maxIdx = PONTOS_MEDICAO.maxOrNull() ?: return false
        if (rosto.size <= maxIdx) return false

        fun centro(indices: IntArray): Centro {
            var x = 0f
            var y = 0f
            indices.forEach { indice ->
                x += rosto[indice].x()
                y += rosto[indice].y()
            }
            return Centro(x / indices.size, y / indices.size)
        }

        val centroSobrancelhaA = centro(SOBRANCELHA_A)
        val centroSobrancelhaB = centro(SOBRANCELHA_B)
        val centroOlhoA = centro(OLHO_A)
        val centroOlhoB = centro(OLHO_B)

        val dx = centroOlhoA.x - centroOlhoB.x
        val dy = centroOlhoA.y - centroOlhoB.y
        val escala = sqrt(dx * dx + dy * dy)
        if (escala < 1e-6f) return false

        val distanciaA = centroOlhoA.y - centroSobrancelhaA.y
        val distanciaB = centroOlhoB.y - centroSobrancelhaB.y
        bufferSobrancelha.addLast(((distanciaA + distanciaB) / 2f) / escala)
        while (bufferSobrancelha.size > LibrasAnalyzer.JANELA_SOBR) bufferSobrancelha.removeFirst()

        val suavizado = bufferSobrancelha.average().toFloat()
        return suavizado >= LibrasAnalyzer.LIMIAR_SOBRANCELHA
    }

    // NOTA: antes desta refatoração, o FaceLandmarker nunca era fechado em
    // close() (só handLandmarker/poseLandmarker/bodyInterpreter eram) — um
    // vazamento real do detector nativo a cada troca de câmera (o Fragment
    // cria um LibrasAnalyzer novo por bind). Corrigido aqui.
    fun close() {
        runCatching { faceLandmarker?.close() }
            .onFailure { Log.w("FaceMarkerEngine", "Falha ao fechar FaceLandmarker", it) }
        faceLandmarker = null
    }

    private companion object {
        // Tres amostras distribuidas ao longo de cada sobrancelha e olho.
        // Os olhos usam os cantos e a palpebra superior; nao o centro da pupila.
        val SOBRANCELHA_A = intArrayOf(70, 105, 107)
        val SOBRANCELHA_B = intArrayOf(300, 334, 336)
        val OLHO_A = intArrayOf(33, 159, 133)
        val OLHO_B = intArrayOf(362, 386, 263)
        val NARIZ = intArrayOf(1)
        val BOCA = intArrayOf(61, 13, 291, 14)
        val PONTOS_MEDICAO = SOBRANCELHA_A + SOBRANCELHA_B + OLHO_A + OLHO_B
        val PONTOS_OVERLAY =
            PONTOS_MEDICAO + NARIZ + BOCA
    }

    private data class Centro(val x: Float, val y: Float)
}
