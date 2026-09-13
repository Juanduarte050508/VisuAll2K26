package com.visuall.app.libras

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Entrada independente do MediaPipe para a deteccao temporal de sobrancelhas.
 * Distancias geometricas ja devem chegar normalizadas pela distancia entre os
 * olhos. Os lados A/B evitam afirmar anatomia antes de validar o espelhamento
 * em aparelho; [BrowRaiseMode] define explicitamente quais lados disparam.
 */
internal data class BrowFrameFeatures(
    val timestampMs: Long,
    val faceCount: Int,
    val landmarksValid: Boolean,
    val blendshapesAvailable: Boolean,
    val gapA: Float = 0f,
    val gapB: Float = 0f,
    val browOuterUpA: Float = 0f,
    val browOuterUpB: Float = 0f,
    val browInnerUp: Float = 0f,
    val browDownA: Float = 0f,
    val browDownB: Float = 0f,
    val eyeBlinkA: Float = 0f,
    val eyeBlinkB: Float = 0f,
    val eyeWideA: Float = 0f,
    val eyeWideB: Float = 0f,
    val pitchDegrees: Float? = null,
    val yawDegrees: Float? = null,
    val rollDegrees: Float? = null
)

internal enum class BrowRaiseMode { SIDE_A, SIDE_B, EITHER, BOTH }

enum class BrowGestureState {
    NO_FACE,
    CALIBRATING,
    NEUTRAL,
    CANDIDATE_RAISE,
    RAISED,
    COOLDOWN
}

enum class BrowDecisionReason {
    NO_FACE,
    MULTIPLE_FACES,
    LANDMARKS_INCOMPLETE,
    POSE_EXCESSIVE,
    CALIBRATING,
    UNSTABLE_CALIBRATION,
    BLINK,
    EYES_WIDE_WITHOUT_BROW,
    BROW_DOWN,
    BELOW_THRESHOLD,
    WAITING_DWELL,
    RAISED,
    WAITING_RELEASE,
    COOLDOWN
}

data class BrowThresholds(
    val gapEnterA: Float,
    val gapEnterB: Float,
    val gapExitA: Float,
    val gapExitB: Float,
    val outerEnterA: Float,
    val outerEnterB: Float,
    val outerExitA: Float,
    val outerExitB: Float,
    val innerEnter: Float,
    val innerExit: Float,
    val browDownDelta: Float
)

data class BrowDetection(
    val state: BrowGestureState,
    val markerVisible: Boolean,
    val triggered: Boolean,
    val reason: BrowDecisionReason,
    val calibrationProgress: Float,
    val faceCount: Int,
    val blendshapesAvailable: Boolean,
    val rawGapA: Float?,
    val rawGapB: Float?,
    val filteredGapA: Float?,
    val filteredGapB: Float?,
    val baselineGapA: Float?,
    val baselineGapB: Float?,
    val gapDeltaA: Float?,
    val gapDeltaB: Float?,
    val browOuterUpA: Float?,
    val browOuterUpB: Float?,
    val browInnerUp: Float?,
    val sideScoreA: Float?,
    val sideScoreB: Float?,
    val thresholds: BrowThresholds?,
    val pitchDegrees: Float?,
    val yawDegrees: Float?,
    val rollDegrees: Float?
)

/**
 * Calibra o neutro da pessoa e transforma evidencias por frame em um evento
 * temporal estavel. Nao depende da taxa de quadros: todos os tempos sao ms.
 */
internal class BrowGestureDetector(
    private val mode: BrowRaiseMode = BrowRaiseMode.BOTH,
    private val config: Config = Config()
) {
    internal data class Config(
        val calibrationDurationMs: Long = 2_000L,
        val calibrationMinSamples: Int = 10,
        val candidateDwellMs: Long = 280L,
        val releaseDwellMs: Long = 240L,
        val minimumVisibleMs: Long = 700L,
        val cooldownMs: Long = 900L,
        val faceLossGraceMs: Long = 350L,
        val baselineResetAfterNoFaceMs: Long = 2_000L,
        val smoothingTauMs: Float = 110f,
        val baselineAdaptTauMs: Float = 15_000f,
        val maxPitchDegrees: Float = 20f,
        val maxYawDegrees: Float = 22f,
        val maxRollDegrees: Float = 20f,
        val maxBlink: Float = 0.65f,
        val maxEyeWide: Float = 0.75f,
        val minGapEnter: Float = 0.025f,
        val minOuterEnter: Float = 0.12f,
        val minInnerEnter: Float = 0.10f,
        val minBrowDownDelta: Float = 0.14f,
        val noiseMultiplierEnter: Float = 4.5f,
        val thresholdExitRatio: Float = 0.55f
    )

    private data class Smoothed(
        var gapA: Float,
        var gapB: Float,
        var outerA: Float,
        var outerB: Float,
        var inner: Float,
        var downA: Float,
        var downB: Float
    )

    private data class Baseline(
        var gapA: Float,
        var gapB: Float,
        var outerA: Float,
        var outerB: Float,
        var inner: Float,
        var downA: Float,
        var downB: Float,
        val thresholds: BrowThresholds
    )

    private data class Evidence(
        val gapDeltaA: Float,
        val gapDeltaB: Float,
        val scoreAEnter: Float,
        val scoreBEnter: Float,
        val scoreAExit: Float,
        val scoreBExit: Float,
        val candidate: Boolean,
        val stillRaised: Boolean,
        val reason: BrowDecisionReason
    )

    private var state = BrowGestureState.NO_FACE
    private var baseline: Baseline? = null
    private var smoothed: Smoothed? = null
    private var lastFilterTimestampMs = 0L
    private var lastBaselineAdaptTimestampMs = 0L
    private var calibrationStartedMs = 0L
    private var candidateStartedMs = 0L
    private var raisedStartedMs = 0L
    private var releaseStartedMs = 0L
    private var cooldownStartedMs = 0L
    private var faceLostStartedMs = 0L
    private var calibrationLastFrame: BrowFrameFeatures? = null

    private val calibrationGapA = mutableListOf<Float>()
    private val calibrationGapB = mutableListOf<Float>()
    private val calibrationOuterA = mutableListOf<Float>()
    private val calibrationOuterB = mutableListOf<Float>()
    private val calibrationInner = mutableListOf<Float>()
    private val calibrationDownA = mutableListOf<Float>()
    private val calibrationDownB = mutableListOf<Float>()

    fun reset() {
        state = BrowGestureState.NO_FACE
        baseline = null
        smoothed = null
        lastFilterTimestampMs = 0L
        lastBaselineAdaptTimestampMs = 0L
        calibrationStartedMs = 0L
        candidateStartedMs = 0L
        raisedStartedMs = 0L
        releaseStartedMs = 0L
        cooldownStartedMs = 0L
        faceLostStartedMs = 0L
        clearCalibrationSamples()
    }

    fun update(frame: BrowFrameFeatures): BrowDetection {
        val availabilityReason = when {
            frame.faceCount == 0 -> BrowDecisionReason.NO_FACE
            frame.faceCount > 1 -> BrowDecisionReason.MULTIPLE_FACES
            !frame.landmarksValid -> BrowDecisionReason.LANDMARKS_INCOMPLETE
            else -> null
        }
        if (availabilityReason != null) return onFaceUnavailable(frame, availabilityReason)

        faceLostStartedMs = 0L
        if (state == BrowGestureState.NO_FACE) {
            if (baseline == null) beginCalibration(frame.timestampMs) else state = BrowGestureState.NEUTRAL
        }

        val poseReason = poseRejection(frame)
        if (state == BrowGestureState.CALIBRATING) {
            return calibrate(frame, poseReason)
        }

        val filtered = filter(frame)
        val currentBaseline = baseline ?: run {
            beginCalibration(frame.timestampMs)
            return calibrate(frame, poseReason)
        }

        if (poseReason != null) {
            if (state == BrowGestureState.CANDIDATE_RAISE) cancelCandidate()
            return detection(frame, filtered, currentBaseline, null, poseReason, triggered = false)
        }

        val evidence = evaluateEvidence(frame, filtered, currentBaseline)
        var triggered = false

        when (state) {
            BrowGestureState.NEUTRAL -> {
                if (evidence.candidate) {
                    state = BrowGestureState.CANDIDATE_RAISE
                    candidateStartedMs = frame.timestampMs
                } else {
                    adaptBaseline(frame.timestampMs, filtered, currentBaseline, evidence)
                }
            }

            BrowGestureState.CANDIDATE_RAISE -> {
                if (!evidence.candidate) {
                    cancelCandidate()
                } else if (frame.timestampMs - candidateStartedMs >= config.candidateDwellMs) {
                    state = BrowGestureState.RAISED
                    raisedStartedMs = frame.timestampMs
                    releaseStartedMs = 0L
                    triggered = true
                }
            }

            BrowGestureState.RAISED -> {
                if (evidence.stillRaised) {
                    releaseStartedMs = 0L
                } else {
                    if (releaseStartedMs == 0L) releaseStartedMs = frame.timestampMs
                    val visibleLongEnough = frame.timestampMs - raisedStartedMs >= config.minimumVisibleMs
                    val releasedLongEnough = frame.timestampMs - releaseStartedMs >= config.releaseDwellMs
                    if (visibleLongEnough && releasedLongEnough) {
                        state = BrowGestureState.COOLDOWN
                        cooldownStartedMs = frame.timestampMs
                        releaseStartedMs = 0L
                    }
                }
            }

            BrowGestureState.COOLDOWN -> {
                if (!evidence.stillRaised && frame.timestampMs - cooldownStartedMs >= config.cooldownMs) {
                    state = BrowGestureState.NEUTRAL
                    adaptBaseline(frame.timestampMs, filtered, currentBaseline, evidence)
                }
            }

            BrowGestureState.NO_FACE, BrowGestureState.CALIBRATING -> Unit
        }

        val reason = when (state) {
            BrowGestureState.CANDIDATE_RAISE -> BrowDecisionReason.WAITING_DWELL
            BrowGestureState.RAISED -> if (evidence.stillRaised) BrowDecisionReason.RAISED else BrowDecisionReason.WAITING_RELEASE
            BrowGestureState.COOLDOWN -> BrowDecisionReason.COOLDOWN
            else -> evidence.reason
        }
        return detection(frame, filtered, currentBaseline, evidence, reason, triggered)
    }

    private fun calibrate(
        frame: BrowFrameFeatures,
        rejection: BrowDecisionReason?
    ): BrowDetection {
        if (rejection != null) {
            return detection(frame, smoothed, null, null, rejection, triggered = false)
        }

        if (!stableForCalibration(frame)) {
            calibrationLastFrame = frame
            return detection(
                frame, smoothed, null, null,
                BrowDecisionReason.UNSTABLE_CALIBRATION, triggered = false
            )
        }

        if (calibrationStartedMs == 0L) calibrationStartedMs = frame.timestampMs
        calibrationLastFrame = frame
        calibrationGapA += frame.gapA
        calibrationGapB += frame.gapB
        calibrationOuterA += frame.browOuterUpA
        calibrationOuterB += frame.browOuterUpB
        calibrationInner += frame.browInnerUp
        calibrationDownA += frame.browDownA
        calibrationDownB += frame.browDownB

        val elapsed = frame.timestampMs - calibrationStartedMs
        if (elapsed >= config.calibrationDurationMs &&
            calibrationGapA.size >= config.calibrationMinSamples
        ) {
            baseline = buildBaseline()
            smoothed = Smoothed(
                frame.gapA, frame.gapB, frame.browOuterUpA, frame.browOuterUpB,
                frame.browInnerUp, frame.browDownA, frame.browDownB
            )
            lastFilterTimestampMs = frame.timestampMs
            lastBaselineAdaptTimestampMs = frame.timestampMs
            state = BrowGestureState.NEUTRAL
            clearCalibrationSamples()
            return detection(
                frame, smoothed, baseline, null,
                BrowDecisionReason.BELOW_THRESHOLD, triggered = false
            )
        }

        return detection(
            frame, smoothed, null, null,
            BrowDecisionReason.CALIBRATING, triggered = false
        )
    }

    private fun onFaceUnavailable(
        frame: BrowFrameFeatures,
        reason: BrowDecisionReason
    ): BrowDetection {
        if (faceLostStartedMs == 0L) faceLostStartedMs = frame.timestampMs
        if (state == BrowGestureState.CANDIDATE_RAISE) cancelCandidate()

        val lostForMs = frame.timestampMs - faceLostStartedMs
        if (lostForMs > config.faceLossGraceMs) state = BrowGestureState.NO_FACE
        if (lostForMs >= config.baselineResetAfterNoFaceMs) {
            baseline = null
            smoothed = null
            clearCalibrationSamples()
        }

        return BrowDetection(
            state = state,
            markerVisible = state == BrowGestureState.RAISED && lostForMs <= config.faceLossGraceMs,
            triggered = false,
            reason = reason,
            calibrationProgress = 0f,
            faceCount = frame.faceCount,
            blendshapesAvailable = frame.blendshapesAvailable,
            rawGapA = null,
            rawGapB = null,
            filteredGapA = smoothed?.gapA,
            filteredGapB = smoothed?.gapB,
            baselineGapA = baseline?.gapA,
            baselineGapB = baseline?.gapB,
            gapDeltaA = null,
            gapDeltaB = null,
            browOuterUpA = null,
            browOuterUpB = null,
            browInnerUp = null,
            sideScoreA = null,
            sideScoreB = null,
            thresholds = baseline?.thresholds,
            pitchDegrees = frame.pitchDegrees,
            yawDegrees = frame.yawDegrees,
            rollDegrees = frame.rollDegrees
        )
    }

    private fun evaluateEvidence(
        frame: BrowFrameFeatures,
        filtered: Smoothed,
        baseline: Baseline
    ): Evidence {
        val t = baseline.thresholds
        val gapDeltaA = filtered.gapA - baseline.gapA
        val gapDeltaB = filtered.gapB - baseline.gapB
        val outerDeltaA = filtered.outerA - baseline.outerA
        val outerDeltaB = filtered.outerB - baseline.outerB
        val innerDelta = filtered.inner - baseline.inner

        val enterA = sideScore(gapDeltaA, outerDeltaA, innerDelta, t.gapEnterA, t.outerEnterA, t.innerEnter)
        val enterB = sideScore(gapDeltaB, outerDeltaB, innerDelta, t.gapEnterB, t.outerEnterB, t.innerEnter)
        val exitA = sideScore(gapDeltaA, outerDeltaA, innerDelta, t.gapExitA, t.outerExitA, t.innerExit)
        val exitB = sideScore(gapDeltaB, outerDeltaB, innerDelta, t.gapExitB, t.outerExitB, t.innerExit)

        val geometryEnterA = positiveRatio(gapDeltaA, t.gapEnterA)
        val geometryEnterB = positiveRatio(gapDeltaB, t.gapEnterB)
        val blendEnterA = max(positiveRatio(outerDeltaA, t.outerEnterA), positiveRatio(innerDelta, t.innerEnter))
        val blendEnterB = max(positiveRatio(outerDeltaB, t.outerEnterB), positiveRatio(innerDelta, t.innerEnter))

        fun sideCandidate(score: Float, geometry: Float, blend: Float): Boolean {
            if (!frame.blendshapesAvailable) return geometry >= 1.45f
            val crossValidated = geometry >= 0.60f && blend >= 0.45f
            val veryStrongGeometry = geometry >= 1.60f
            return score >= 1f && (crossValidated || veryStrongGeometry)
        }

        val sideCandidateA = sideCandidate(enterA, geometryEnterA, blendEnterA)
        val sideCandidateB = sideCandidate(enterB, geometryEnterB, blendEnterB)
        val rawCandidate = selectSides(sideCandidateA, sideCandidateB)
        val stillRaised = selectSides(exitA >= 1f, exitB >= 1f)

        val blink = max(frame.eyeBlinkA, frame.eyeBlinkB) > config.maxBlink
        val browDown = (filtered.downA - baseline.downA) > t.browDownDelta ||
            (filtered.downB - baseline.downB) > t.browDownDelta
        val selectedGeometry = when (mode) {
            BrowRaiseMode.SIDE_A -> geometryEnterA
            BrowRaiseMode.SIDE_B -> geometryEnterB
            BrowRaiseMode.EITHER -> max(geometryEnterA, geometryEnterB)
            BrowRaiseMode.BOTH -> minOf(geometryEnterA, geometryEnterB)
        }
        val eyesWideWithoutBrow = max(frame.eyeWideA, frame.eyeWideB) > config.maxEyeWide &&
            selectedGeometry < 0.80f

        val reason = when {
            blink -> BrowDecisionReason.BLINK
            browDown -> BrowDecisionReason.BROW_DOWN
            eyesWideWithoutBrow -> BrowDecisionReason.EYES_WIDE_WITHOUT_BROW
            else -> BrowDecisionReason.BELOW_THRESHOLD
        }
        val veto = blink || browDown || eyesWideWithoutBrow
        return Evidence(
            gapDeltaA = gapDeltaA,
            gapDeltaB = gapDeltaB,
            scoreAEnter = enterA,
            scoreBEnter = enterB,
            scoreAExit = exitA,
            scoreBExit = exitB,
            candidate = rawCandidate && !veto,
            // Um piscar isolado nao pode desligar um marcador ja confirmado.
            stillRaised = if (veto) state == BrowGestureState.RAISED else stillRaised,
            reason = reason
        )
    }

    private fun sideScore(
        gapDelta: Float,
        outerDelta: Float,
        innerDelta: Float,
        gapThreshold: Float,
        outerThreshold: Float,
        innerThreshold: Float
    ): Float =
        0.55f * positiveRatio(gapDelta, gapThreshold) +
            0.35f * positiveRatio(outerDelta, outerThreshold) +
            0.10f * positiveRatio(innerDelta, innerThreshold)

    private fun positiveRatio(value: Float, threshold: Float): Float =
        (value.coerceAtLeast(0f) / threshold.coerceAtLeast(1e-6f)).coerceAtMost(2f)

    private fun selectSides(sideA: Boolean, sideB: Boolean): Boolean = when (mode) {
        BrowRaiseMode.SIDE_A -> sideA
        BrowRaiseMode.SIDE_B -> sideB
        BrowRaiseMode.EITHER -> sideA || sideB
        BrowRaiseMode.BOTH -> sideA && sideB
    }

    private fun filter(frame: BrowFrameFeatures): Smoothed {
        val previous = smoothed
        if (previous == null) {
            return Smoothed(
                frame.gapA, frame.gapB, frame.browOuterUpA, frame.browOuterUpB,
                frame.browInnerUp, frame.browDownA, frame.browDownB
            ).also {
                smoothed = it
                lastFilterTimestampMs = frame.timestampMs
            }
        }

        val dt = (frame.timestampMs - lastFilterTimestampMs).coerceAtLeast(1L).toFloat()
        val alpha = (1f - exp(-dt / config.smoothingTauMs)).coerceIn(0.05f, 1f)
        previous.gapA += alpha * (frame.gapA - previous.gapA)
        previous.gapB += alpha * (frame.gapB - previous.gapB)
        previous.outerA += alpha * (frame.browOuterUpA - previous.outerA)
        previous.outerB += alpha * (frame.browOuterUpB - previous.outerB)
        previous.inner += alpha * (frame.browInnerUp - previous.inner)
        previous.downA += alpha * (frame.browDownA - previous.downA)
        previous.downB += alpha * (frame.browDownB - previous.downB)
        lastFilterTimestampMs = frame.timestampMs
        return previous
    }

    private fun adaptBaseline(
        timestampMs: Long,
        filtered: Smoothed,
        baseline: Baseline,
        evidence: Evidence
    ) {
        if (evidence.scoreAEnter >= 0.35f || evidence.scoreBEnter >= 0.35f) return
        val dt = (timestampMs - lastBaselineAdaptTimestampMs).coerceAtLeast(1L).toFloat()
        val alpha = (1f - exp(-dt / config.baselineAdaptTauMs)).coerceIn(0f, 0.03f)
        baseline.gapA += alpha * (filtered.gapA - baseline.gapA)
        baseline.gapB += alpha * (filtered.gapB - baseline.gapB)
        baseline.outerA += alpha * (filtered.outerA - baseline.outerA)
        baseline.outerB += alpha * (filtered.outerB - baseline.outerB)
        baseline.inner += alpha * (filtered.inner - baseline.inner)
        baseline.downA += alpha * (filtered.downA - baseline.downA)
        baseline.downB += alpha * (filtered.downB - baseline.downB)
        lastBaselineAdaptTimestampMs = timestampMs
    }

    private fun stableForCalibration(frame: BrowFrameFeatures): Boolean {
        if (max(frame.eyeBlinkA, frame.eyeBlinkB) > config.maxBlink) return false
        val previous = calibrationLastFrame ?: return true
        return abs(frame.gapA - previous.gapA) <= 0.025f &&
            abs(frame.gapB - previous.gapB) <= 0.025f &&
            abs(frame.browOuterUpA - previous.browOuterUpA) <= 0.25f &&
            abs(frame.browOuterUpB - previous.browOuterUpB) <= 0.25f
    }

    private fun poseRejection(frame: BrowFrameFeatures): BrowDecisionReason? {
        if (frame.pitchDegrees != null && abs(frame.pitchDegrees) > config.maxPitchDegrees) {
            return BrowDecisionReason.POSE_EXCESSIVE
        }
        if (frame.yawDegrees != null && abs(frame.yawDegrees) > config.maxYawDegrees) {
            return BrowDecisionReason.POSE_EXCESSIVE
        }
        if (frame.rollDegrees != null && abs(frame.rollDegrees) > config.maxRollDegrees) {
            return BrowDecisionReason.POSE_EXCESSIVE
        }
        return null
    }

    private fun buildBaseline(): Baseline {
        val gapA = median(calibrationGapA)
        val gapB = median(calibrationGapB)
        val outerA = median(calibrationOuterA)
        val outerB = median(calibrationOuterB)
        val inner = median(calibrationInner)
        val downA = median(calibrationDownA)
        val downB = median(calibrationDownB)

        fun enter(values: List<Float>, minimum: Float): Float =
            max(minimum, robustSigma(values) * config.noiseMultiplierEnter)

        val gapEnterA = enter(calibrationGapA, config.minGapEnter)
        val gapEnterB = enter(calibrationGapB, config.minGapEnter)
        val outerEnterA = enter(calibrationOuterA, config.minOuterEnter)
        val outerEnterB = enter(calibrationOuterB, config.minOuterEnter)
        val innerEnter = enter(calibrationInner, config.minInnerEnter)
        val downDelta = max(
            config.minBrowDownDelta,
            max(robustSigma(calibrationDownA), robustSigma(calibrationDownB)) *
                config.noiseMultiplierEnter
        )
        val thresholds = BrowThresholds(
            gapEnterA = gapEnterA,
            gapEnterB = gapEnterB,
            gapExitA = gapEnterA * config.thresholdExitRatio,
            gapExitB = gapEnterB * config.thresholdExitRatio,
            outerEnterA = outerEnterA,
            outerEnterB = outerEnterB,
            outerExitA = outerEnterA * config.thresholdExitRatio,
            outerExitB = outerEnterB * config.thresholdExitRatio,
            innerEnter = innerEnter,
            innerExit = innerEnter * config.thresholdExitRatio,
            browDownDelta = downDelta
        )
        return Baseline(gapA, gapB, outerA, outerB, inner, downA, downB, thresholds)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2f else sorted[middle]
    }

    private fun robustSigma(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val center = median(values)
        val mad = median(values.map { abs(it - center) })
        return mad * 1.4826f
    }

    private fun beginCalibration(timestampMs: Long) {
        state = BrowGestureState.CALIBRATING
        baseline = null
        smoothed = null
        calibrationStartedMs = timestampMs
        clearCalibrationSamples()
    }

    private fun clearCalibrationSamples() {
        calibrationGapA.clear()
        calibrationGapB.clear()
        calibrationOuterA.clear()
        calibrationOuterB.clear()
        calibrationInner.clear()
        calibrationDownA.clear()
        calibrationDownB.clear()
        calibrationLastFrame = null
    }

    private fun cancelCandidate() {
        state = BrowGestureState.NEUTRAL
        candidateStartedMs = 0L
    }

    private fun detection(
        frame: BrowFrameFeatures,
        filtered: Smoothed?,
        baseline: Baseline?,
        evidence: Evidence?,
        reason: BrowDecisionReason,
        triggered: Boolean
    ): BrowDetection {
        val elapsed = if (calibrationStartedMs == 0L) 0L else frame.timestampMs - calibrationStartedMs
        val timeProgress = elapsed.toFloat() / config.calibrationDurationMs.coerceAtLeast(1L)
        val sampleProgress = calibrationGapA.size.toFloat() / config.calibrationMinSamples.coerceAtLeast(1)
        val calibrationProgress = if (state == BrowGestureState.CALIBRATING) {
            minOf(timeProgress, sampleProgress).coerceIn(0f, 1f)
        } else if (baseline != null) 1f else 0f

        return BrowDetection(
            state = state,
            markerVisible = state == BrowGestureState.RAISED,
            triggered = triggered,
            reason = reason,
            calibrationProgress = calibrationProgress,
            faceCount = frame.faceCount,
            blendshapesAvailable = frame.blendshapesAvailable,
            rawGapA = frame.gapA,
            rawGapB = frame.gapB,
            filteredGapA = filtered?.gapA,
            filteredGapB = filtered?.gapB,
            baselineGapA = baseline?.gapA,
            baselineGapB = baseline?.gapB,
            gapDeltaA = evidence?.gapDeltaA,
            gapDeltaB = evidence?.gapDeltaB,
            browOuterUpA = filtered?.outerA ?: frame.browOuterUpA,
            browOuterUpB = filtered?.outerB ?: frame.browOuterUpB,
            browInnerUp = filtered?.inner ?: frame.browInnerUp,
            sideScoreA = evidence?.scoreAEnter,
            sideScoreB = evidence?.scoreBEnter,
            thresholds = baseline?.thresholds,
            pitchDegrees = frame.pitchDegrees,
            yawDegrees = frame.yawDegrees,
            rollDegrees = frame.rollDegrees
        )
    }
}
