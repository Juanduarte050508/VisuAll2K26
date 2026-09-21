package com.visuall.app.libras

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A normalized image point. This type deliberately has no Android dependency. */
internal data class Point(val x: Float, val y: Float)

/** A face bounding box in the same coordinate system as [Point]. */
internal data class Rect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
    val center: Point get() = Point((left + right) / 2f, (top + bottom) / 2f)

    internal fun contains(point: Point): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

    internal fun expanded(horizontal: Float, vertical: Float): Rect = Rect(
        left = left - width * horizontal,
        top = top - height * vertical,
        right = right + width * horizontal,
        bottom = bottom + height * vertical
    )
}

/**
 * One result produced by the face detector.
 *
 * A non-null snapshot means that the detector really sampled that frame. In
 * particular, an empty [faces] list is evidence that the face disappeared; it
 * is different from passing `null` to [PersonAssociationGate.avaliar], which
 * merely means that this video frame did not run the face detector.
 */
internal data class FaceCandidate(
    val bounds: Rect,
    val nose: Point
)

internal data class FaceSnapshot(
    val timestampMs: Long,
    val faces: List<FaceCandidate>,
    val invalidFaceDetected: Boolean = false
)

internal enum class PersonAssociationDecision {
    MATCHED,
    ACQUIRING,
    NO_FACE,
    STALE,
    AMBIGUOUS,
    FACE_TOO_SMALL,
    POSE_MISMATCH,
    INVALID_POSE,
    INVALID_FACE
}

internal data class PersonAssociationResult(
    val decision: PersonAssociationDecision,
    val acceptedHandIndices: Set<Int> = emptySet(),
    val matchedFaceIndex: Int? = null,
    val hasLockedSubject: Boolean = false,
    /** Detector hand index -> pose wrist slot (0=left, 1=right). */
    val handSlotAssignments: Map<Int, Int> = emptyMap()
) {
    val matched: Boolean get() = decision == PersonAssociationDecision.MATCHED
}

/**
 * Associates a pose and its hands with the dominant face in the image.
 *
 * Face inference is normally slower than pose/hand inference. [faceSnapshot]
 * can therefore be `null` on frames between face samples; a previous
 * acquisition candidate remains valid for [faceTtlMs]. Once acquired, the
 * identity is locked for the screen session: an isolated empty, ambiguous,
 * invalid or incompatible face sample cannot interrupt body recognition.
 * Face samples only confirm which current face may produce face-only events.
 * Loss or a spatial jump of the pose blocks that frame and releases the lock
 * only after [poseGraceMs] of observed inconsistency.
 *
 * Acquisition needs two *new* face samples. This prevents one detector glitch
 * from allowing a background person's hands into the body recognizer.
 */
internal class PersonAssociationGate(
    private val faceTtlMs: Long = DEFAULT_FACE_TTL_MS,
    private val poseGraceMs: Long = DEFAULT_POSE_GRACE_MS,
    private val minFaceWidth: Float = DEFAULT_MIN_FACE_WIDTH,
    private val minFaceHeight: Float = DEFAULT_MIN_FACE_HEIGHT
) {
    private data class CachedFace(
        val candidate: FaceCandidate,
        val timestampMs: Long
    )

    private data class PoseAnchor(
        val headCenter: Point,
        val shoulderCenter: Point,
        val shoulderWidth: Float
    )

    private var cachedFace: CachedFace? = null
    private var candidateFace: FaceCandidate? = null
    private var candidateSamples = 0
    private var lastSampleTimestampMs: Long? = null
    private var acquired = false
    private var poseAnchor: PoseAnchor? = null
    private var poseInconsistentSinceMs: Long? = null
    private var poseReanchorPending = false

    init {
        require(faceTtlMs > 0L) { "faceTtlMs must be positive" }
        require(poseGraceMs > 0L) { "poseGraceMs must be positive" }
        require(minFaceWidth.isFinite() && minFaceWidth > 0f) {
            "minFaceWidth must be finite and positive"
        }
        require(minFaceHeight.isFinite() && minFaceHeight > 0f) {
            "minFaceHeight must be finite and positive"
        }
    }

    /**
     * @param agoraMs clock value from the same monotonic clock as
     * [FaceSnapshot.timestampMs].
     * @param faceSnapshot a newly sampled detector result, or `null` when face
     * detection did not run on this frame.
     * @param pose exactly the 33 MediaPipe pose points, represented only by
     * this module's dependency-free [Point].
     * @param hands one 21-point hand list per detector hand; list positions are
     * returned in [PersonAssociationResult.acceptedHandIndices].
     */
    fun avaliar(
        agoraMs: Long,
        faceSnapshot: FaceSnapshot?,
        pose: List<Point>,
        hands: List<List<Point>>
    ): PersonAssociationResult {
        if (acquired) {
            return avaliarSessaoTravada(agoraMs, faceSnapshot, pose, hands)
        }
        if (faceSnapshot != null) {
            return avaliarAmostraNova(agoraMs, faceSnapshot, pose, hands)
        }

        val cached = cachedFace ?: return result(PersonAssociationDecision.NO_FACE)
        if (isExpired(cached.timestampMs, agoraMs)) {
            reset()
            return result(PersonAssociationDecision.STALE)
        }
        if (!isValidPose(pose)) {
            reset()
            return result(PersonAssociationDecision.INVALID_POSE)
        }
        if (!poseBelongsToFace(cached.candidate, pose)) {
            reset()
            return result(PersonAssociationDecision.POSE_MISMATCH)
        }
        return result(PersonAssociationDecision.ACQUIRING)
    }

    /**
     * Once acquired, the subject is a session rather than a per-gesture face
     * check. Pose continuity keeps body transcription flowing; face samples
     * only revalidate which current face may emit face-only events such as `?`.
     */
    private fun avaliarSessaoTravada(
        agoraMs: Long,
        snapshot: FaceSnapshot?,
        pose: List<Point>,
        hands: List<List<Point>>
    ): PersonAssociationResult {
        if (!isValidPose(pose)) {
            return rejectLockedPose(PersonAssociationDecision.INVALID_POSE, agoraMs)
        }

        val currentAnchor = buildPoseAnchor(pose)
            ?: return rejectLockedPose(PersonAssociationDecision.INVALID_POSE, agoraMs)

        // Apos troca de modo ou pausa, uma unica amostra atual volta a ligar
        // rosto e pose. Nao ha espera se e a mesma pessoa, mas outra pessoa nao
        // herda o lock apenas por ocupar uma posicao parecida.
        if (poseReanchorPending) {
            val selected = snapshot?.let { selectFaceForPose(agoraMs, it, pose) }
                ?: return PersonAssociationResult(
                    decision = PersonAssociationDecision.ACQUIRING,
                    hasLockedSubject = true
                )
            cacheFace(selected, snapshot.timestampMs)
            poseAnchor = currentAnchor
            poseInconsistentSinceMs = null
            poseReanchorPending = false
            return matchedResult(pose, hands, selected.index)
        }

        val previousAnchor = poseAnchor
        if (previousAnchor != null && !isPoseContinuous(previousAnchor, currentAnchor)) {
            return rejectLockedPose(PersonAssociationDecision.POSE_MISMATCH, agoraMs)
        }

        // A valid continuation cancels an observed absence/jump. Time spent in
        // another mode does not count because no absence was observed there.
        poseAnchor = currentAnchor
        poseInconsistentSinceMs = null

        val currentFaceIndex = snapshot?.let {
            checkLockedFaceSample(agoraMs, it, pose)
        }
        return matchedResult(pose, hands, currentFaceIndex)
    }

    private fun rejectLockedPose(
        decision: PersonAssociationDecision,
        agoraMs: Long
    ): PersonAssociationResult {
        val since = poseInconsistentSinceMs
            ?: agoraMs.also { poseInconsistentSinceMs = it }
        if (agoraMs < since || agoraMs - since >= poseGraceMs) {
            reset()
            return result(decision)
        }
        // The current frame is blocked, but the identity lock survives long
        // enough for a brief occlusion to recover without two new face reads.
        return PersonAssociationResult(
            decision = decision,
            hasLockedSubject = true
        )
    }

    /**
     * Returns an index only when this exact face sample confirms the locked
     * pose. Every kind of isolated facial failure is intentionally ignored:
     * it cannot block a continuous body session and cannot emit a face event.
     */
    private fun checkLockedFaceSample(
        agoraMs: Long,
        snapshot: FaceSnapshot,
        pose: List<Point>
    ): Int? {
        // Old/duplicated asynchronous results are not new evidence of either
        // presence or absence.
        if (isExpired(snapshot.timestampMs, agoraMs)) return null
        val cachedTimestamp = cachedFace?.timestampMs
        if (cachedTimestamp != null && snapshot.timestampMs <= cachedTimestamp) {
            return null
        }
        if (snapshot.invalidFaceDetected || snapshot.faces.isEmpty()) return null
        if (snapshot.faces.any { !isValidFaceCandidate(it) }) return null

        val eligible = snapshot.faces.withIndex().filter { (_, candidate) ->
            candidate.bounds.width >= minFaceWidth &&
                candidate.bounds.height >= minFaceHeight
        }
        if (eligible.isEmpty()) return null

        val lockedFace = cachedFace?.candidate ?: return null
        val continuousFaces = eligible.filter { (_, candidate) ->
            spatiallyContinuous(lockedFace, candidate) &&
                poseBelongsToFace(candidate, pose)
        }
        val selected = when (continuousFaces.size) {
            1 -> continuousFaces.single()
            // Uma virada/mudanca rapida pode perder a sobreposicao entre duas
            // amostras stride-5. Recuperamos pelo vinculo rosto-pose, apenas
            // para eventos faciais; o corpo nunca e bloqueado por esse fallback.
            0 -> eligible.filter { (_, candidate) ->
                poseBelongsToFace(candidate, pose)
            }.singleOrNull() ?: return null
            else -> return null
        }
        val largestEligibleArea = eligible.maxOf { it.value.bounds.area }
        if (selected.value.bounds.area <
            largestEligibleArea * MIN_MATCHED_DOMINANT_AREA_RATIO
        ) return null

        // The pose and face tracks must both remain continuous. A face failure
        // suppresses face-only events but never interrupts the body session.
        cacheFace(selected, snapshot.timestampMs)
        return selected.index
    }

    private fun selectFaceForPose(
        agoraMs: Long,
        snapshot: FaceSnapshot,
        pose: List<Point>
    ): IndexedValue<FaceCandidate>? {
        if (snapshot.invalidFaceDetected || snapshot.faces.isEmpty()) return null
        if (snapshot.faces.any { !isValidFaceCandidate(it) }) return null
        if (isExpired(snapshot.timestampMs, agoraMs)) return null
        val cachedTimestamp = cachedFace?.timestampMs
        if (cachedTimestamp != null && snapshot.timestampMs <= cachedTimestamp) return null

        val eligible = snapshot.faces.withIndex().filter { (_, candidate) ->
            candidate.bounds.width >= minFaceWidth &&
                candidate.bounds.height >= minFaceHeight
        }
        val selected = eligible.filter { (_, candidate) ->
            poseBelongsToFace(candidate, pose)
        }.singleOrNull() ?: return null
        val largestEligibleArea = eligible.maxOfOrNull { it.value.bounds.area } ?: return null
        return selected.takeIf {
            it.value.bounds.area >= largestEligibleArea * MIN_MATCHED_DOMINANT_AREA_RATIO
        }
    }

    private fun cacheFace(
        selected: IndexedValue<FaceCandidate>,
        timestampMs: Long
    ) {
        cachedFace = CachedFace(selected.value, timestampMs)
        candidateFace = selected.value
        lastSampleTimestampMs = timestampMs
    }

    private fun avaliarAmostraNova(
        agoraMs: Long,
        snapshot: FaceSnapshot,
        pose: List<Point>,
        hands: List<List<Point>>
    ): PersonAssociationResult {
        // The adapter sets this flag when even one raw detector face could not
        // be represented safely. Never hide that evidence by selecting a
        // different, valid-looking candidate from the same sample.
        if (snapshot.invalidFaceDetected) {
            reset()
            return result(PersonAssociationDecision.INVALID_FACE)
        }
        // An explicit empty observation overrides a still-young cached face.
        if (snapshot.faces.isEmpty()) {
            reset()
            return result(PersonAssociationDecision.NO_FACE)
        }
        if (snapshot.faces.any { !isValidFaceCandidate(it) }) {
            reset()
            return result(PersonAssociationDecision.INVALID_FACE)
        }
        if (isExpired(snapshot.timestampMs, agoraMs)) {
            reset()
            return result(PersonAssociationDecision.STALE)
        }

        // Small audience faces are not candidates at all. They also must not
        // make two valid foreground candidates look ambiguous.
        val eligible = snapshot.faces.withIndex().filter { (_, candidate) ->
            candidate.bounds.width >= minFaceWidth &&
                candidate.bounds.height >= minFaceHeight
        }
        if (eligible.isEmpty()) {
            reset()
            return result(PersonAssociationDecision.FACE_TOO_SMALL)
        }

        if (!isValidPose(pose)) {
            reset()
            return result(PersonAssociationDecision.INVALID_POSE)
        }

        // Geometry, including the independent FaceMesh nose anchor, decides
        // which face belongs to this pose. Similar-sized faces are fine when
        // exactly one of them matches; two matching bodies are not.
        val matchingFaces = eligible.filter { (_, candidate) ->
            poseBelongsToFace(candidate, pose)
        }
        if (matchingFaces.isEmpty()) {
            reset()
            return result(PersonAssociationDecision.POSE_MISMATCH)
        }
        if (matchingFaces.size > 1) {
            reset()
            return result(PersonAssociationDecision.AMBIGUOUS)
        }

        val selected = matchingFaces.single()
        val selectedFace = selected.value
        val largestEligibleArea = eligible.maxOf { it.value.bounds.area }
        if (selectedFace.bounds.area <
            largestEligibleArea * MIN_MATCHED_DOMINANT_AREA_RATIO
        ) {
            // A tiny matching body behind a much larger foreground face is the
            // exact "ghost in the audience" case this gate must suppress.
            reset()
            return result(PersonAssociationDecision.POSE_MISMATCH)
        }

        val previousTimestamp = lastSampleTimestampMs
        if (previousTimestamp != null && snapshot.timestampMs < previousTimestamp) {
            reset()
            return result(PersonAssociationDecision.STALE)
        }

        if (previousTimestamp == snapshot.timestampMs) {
            // Reusing the same detector result must not count as a second
            // acquisition sample. A changed box under the same timestamp is
            // internally inconsistent, so fail closed.
            val previous = candidateFace
            if (previous == null || !spatiallyContinuous(previous, selectedFace)) {
                reset()
                return result(PersonAssociationDecision.INVALID_FACE)
            }
            cachedFace = CachedFace(selectedFace, snapshot.timestampMs)
            return if (acquired) matchedResult(pose, hands, selected.index)
            else result(PersonAssociationDecision.ACQUIRING, selected.index)
        }

        val continuesCandidate = candidateFace?.let { previous ->
            val gapIsFresh = previousTimestamp != null &&
                snapshot.timestampMs - previousTimestamp <= faceTtlMs
            gapIsFresh && spatiallyContinuous(previous, selectedFace)
        } == true

        candidateSamples = if (continuesCandidate) candidateSamples + 1 else 1
        candidateFace = selectedFace
        lastSampleTimestampMs = snapshot.timestampMs
        cachedFace = CachedFace(selectedFace, snapshot.timestampMs)
        acquired = candidateSamples >= REQUIRED_FACE_SAMPLES

        return if (acquired) matchedResult(pose, hands, selected.index)
        else result(PersonAssociationDecision.ACQUIRING, selected.index)
    }

    private fun matchedResult(
        pose: List<Point>,
        hands: List<List<Point>>,
        matchedFaceIndex: Int?
    ): PersonAssociationResult {
        if (poseAnchor == null) poseAnchor = buildPoseAnchor(pose)
        poseInconsistentSinceMs = null
        val shoulderScale = distance(pose[LEFT_SHOULDER], pose[RIGHT_SHOULDER])
        val wristThreshold = (shoulderScale * WRIST_TO_SHOULDER_RATIO)
            .coerceIn(MIN_WRIST_DISTANCE, MAX_WRIST_DISTANCE)
        val poseWrists = listOf(pose[LEFT_WRIST], pose[RIGHT_WRIST])

        val validHands = hands.indices.filter { isValidHand(hands[it]) }
        var bestAssignments: Map<Int, Int> = emptyMap()
        var bestDistance = Float.POSITIVE_INFINITY

        fun search(
            position: Int,
            usedWrists: Set<Int>,
            assignments: Map<Int, Int>,
            totalDistance: Float
        ) {
            if (position == validHands.size) {
                if (assignments.size > bestAssignments.size ||
                    (assignments.size == bestAssignments.size &&
                        totalDistance < bestDistance)
                ) {
                    bestAssignments = assignments
                    bestDistance = totalDistance
                }
                return
            }

            val handIndex = validHands[position]
            search(position + 1, usedWrists, assignments, totalDistance)
            poseWrists.forEachIndexed { wristIndex, poseWrist ->
                if (wristIndex in usedWrists) return@forEachIndexed
                val wristDistance = distance(hands[handIndex][HAND_WRIST], poseWrist)
                if (wristDistance <= wristThreshold) {
                    search(
                        position + 1,
                        usedWrists + wristIndex,
                        assignments + (handIndex to wristIndex),
                        totalDistance + wristDistance
                    )
                }
            }
        }
        search(0, emptySet(), emptyMap(), 0f)

        return PersonAssociationResult(
            decision = PersonAssociationDecision.MATCHED,
            acceptedHandIndices = bestAssignments.keys,
            matchedFaceIndex = matchedFaceIndex,
            hasLockedSubject = true,
            handSlotAssignments = bestAssignments
        )
    }

    private fun buildPoseAnchor(pose: List<Point>): PoseAnchor? {
        val leftShoulder = pose[LEFT_SHOULDER]
        val rightShoulder = pose[RIGHT_SHOULDER]
        val shoulderWidth = distance(leftShoulder, rightShoulder)
        if (!shoulderWidth.isFinite() || shoulderWidth <= MIN_SHOULDER_WIDTH) return null
        return PoseAnchor(
            headCenter = average(pose.subList(FIRST_HEAD_POINT, LAST_HEAD_POINT + 1)),
            shoulderCenter = midpoint(leftShoulder, rightShoulder),
            shoulderWidth = shoulderWidth
        )
    }

    private fun isPoseContinuous(previous: PoseAnchor, current: PoseAnchor): Boolean {
        val scale = max(previous.shoulderWidth, current.shoulderWidth)
        val sizeRatio = min(previous.shoulderWidth, current.shoulderWidth) / scale
        if (!sizeRatio.isFinite() || sizeRatio < MIN_POSE_SHOULDER_SIZE_RATIO) return false
        val shiftTolerance = min(
            MAX_POSE_SHIFT,
            max(MIN_POSE_SHIFT, scale * MAX_POSE_SHIFT_TO_SHOULDERS)
        )
        if (distance(previous.headCenter, current.headCenter) >
            shiftTolerance
        ) return false
        return distance(previous.shoulderCenter, current.shoulderCenter) <=
            shiftTolerance
    }

    private fun poseBelongsToFace(faceCandidate: FaceCandidate, pose: List<Point>): Boolean {
        val face = faceCandidate.bounds
        val nose = pose[NOSE]
        val headCenter = average(pose.subList(FIRST_HEAD_POINT, LAST_HEAD_POINT + 1))
        val expandedFace = face.expanded(FACE_EXPANSION_X, FACE_EXPANSION_Y)
        if (!expandedFace.contains(nose) || !expandedFace.contains(headCenter)) return false

        val normalizedNoseDx = (nose.x - faceCandidate.nose.x) / face.width
        val normalizedNoseDy = (nose.y - faceCandidate.nose.y) / face.height
        val normalizedNoseDistance = sqrt(
            normalizedNoseDx * normalizedNoseDx +
                normalizedNoseDy * normalizedNoseDy
        )
        if (!normalizedNoseDistance.isFinite() ||
            normalizedNoseDistance > MAX_NORMALIZED_NOSE_DISTANCE
        ) return false

        val leftShoulder = pose[LEFT_SHOULDER]
        val rightShoulder = pose[RIGHT_SHOULDER]
        val shoulderWidth = distance(leftShoulder, rightShoulder)
        if (!shoulderWidth.isFinite() || shoulderWidth <= MIN_SHOULDER_WIDTH) return false

        // The face must be centered over a plausible shoulder segment. All
        // comparisons are symmetric in left/right, so horizontal mirroring
        // cannot change the decision.
        val shoulderMid = midpoint(leftShoulder, rightShoulder)
        val faceCenter = face.center
        val horizontalOffset = abs(shoulderMid.x - faceCenter.x)
        val horizontalTolerance = max(face.width * FACE_TO_SHOULDER_X_TOLERANCE,
            shoulderWidth * SHOULDER_CENTER_TOLERANCE)
        if (horizontalOffset > horizontalTolerance) return false

        val shoulderMinX = min(leftShoulder.x, rightShoulder.x)
        val shoulderMaxX = max(leftShoulder.x, rightShoulder.x)
        val straddleTolerance = face.width * SHOULDER_STRADDLE_TOLERANCE
        if (faceCenter.x < shoulderMinX - straddleTolerance ||
            faceCenter.x > shoulderMaxX + straddleTolerance
        ) return false

        val shoulderToFaceRatio = shoulderWidth / face.width
        if (shoulderToFaceRatio !in MIN_SHOULDER_FACE_RATIO..MAX_SHOULDER_FACE_RATIO) {
            return false
        }

        val verticalGap = shoulderMid.y - faceCenter.y
        if (verticalGap < face.height * MIN_SHOULDER_VERTICAL_GAP) return false
        if (verticalGap > min(face.height * MAX_SHOULDER_VERTICAL_GAP,
                shoulderWidth * MAX_VERTICAL_GAP_TO_SHOULDER)
        ) return false

        val shoulderTilt = abs(leftShoulder.y - rightShoulder.y)
        val tiltTolerance = max(face.height * SHOULDER_TILT_FACE_TOLERANCE,
            shoulderWidth * SHOULDER_TILT_WIDTH_TOLERANCE)
        return shoulderTilt <= tiltTolerance
    }

    private fun spatiallyContinuous(
        previousCandidate: FaceCandidate,
        currentCandidate: FaceCandidate
    ): Boolean {
        val previous = previousCandidate.bounds
        val current = currentCandidate.bounds
        val sizeRatio = min(previous.area, current.area) / max(previous.area, current.area)
        if (!sizeRatio.isFinite() || sizeRatio < MIN_CONTINUITY_SIZE_RATIO) return false

        val intersectionWidth = max(0f, min(previous.right, current.right) -
            max(previous.left, current.left))
        val intersectionHeight = max(0f, min(previous.bottom, current.bottom) -
            max(previous.top, current.top))
        val intersection = intersectionWidth * intersectionHeight
        val overlapOfSmaller = intersection / min(previous.area, current.area)
        return overlapOfSmaller.isFinite() && overlapOfSmaller >= MIN_CONTINUITY_OVERLAP
    }

    private fun isExpired(timestampMs: Long, agoraMs: Long): Boolean =
        timestampMs < 0L || agoraMs < timestampMs || agoraMs - timestampMs > faceTtlMs

    private fun isValidFaceCandidate(candidate: FaceCandidate): Boolean =
        isValidRect(candidate.bounds) && isFinite(candidate.nose)

    private fun isValidRect(rect: Rect): Boolean =
        rect.left.isFinite() && rect.top.isFinite() &&
            rect.right.isFinite() && rect.bottom.isFinite() &&
            rect.width > 0f && rect.height > 0f && rect.area.isFinite()

    private fun isValidPose(pose: List<Point>): Boolean =
        pose.size == POSE_POINT_COUNT && pose.all(::isFinite)

    private fun isValidHand(hand: List<Point>): Boolean =
        hand.size == HAND_POINT_COUNT && hand.all(::isFinite)

    private fun isFinite(point: Point): Boolean = point.x.isFinite() && point.y.isFinite()

    private fun average(points: List<Point>): Point {
        var x = 0f
        var y = 0f
        points.forEach {
            x += it.x
            y += it.y
        }
        return Point(x / points.size, y / points.size)
    }

    private fun midpoint(a: Point, b: Point): Point =
        Point((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    private fun distance(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun result(
        decision: PersonAssociationDecision,
        matchedFaceIndex: Int? = null
    ) = PersonAssociationResult(decision, emptySet(), matchedFaceIndex, false)

    fun reset() {
        cachedFace = null
        candidateFace = null
        candidateSamples = 0
        lastSampleTimestampMs = null
        acquired = false
        poseAnchor = null
        poseInconsistentSinceMs = null
        poseReanchorPending = false
    }

    /** Exige uma unica reconfirmacao rosto-pose apos um intervalo sem corpo. */
    fun preparePoseReanchor() {
        if (!acquired) return
        poseReanchorPending = true
        poseInconsistentSinceMs = null
    }

    companion object {
        const val DEFAULT_FACE_TTL_MS = 1_000L
        const val DEFAULT_POSE_GRACE_MS = 2_000L
        const val DEFAULT_MIN_FACE_WIDTH = 0.07f
        const val DEFAULT_MIN_FACE_HEIGHT = 0.08f

        private const val REQUIRED_FACE_SAMPLES = 2
        private const val POSE_POINT_COUNT = 33
        private const val HAND_POINT_COUNT = 21

        private const val NOSE = 0
        private const val FIRST_HEAD_POINT = 0
        private const val LAST_HEAD_POINT = 10
        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12
        private const val LEFT_WRIST = 15
        private const val RIGHT_WRIST = 16
        private const val HAND_WRIST = 0

        private const val MIN_MATCHED_DOMINANT_AREA_RATIO = 0.75f

        private const val FACE_EXPANSION_X = 0.35f
        private const val FACE_EXPANSION_Y = 0.45f
        private const val MAX_NORMALIZED_NOSE_DISTANCE = 0.45f
        private const val MIN_SHOULDER_WIDTH = 0.01f
        private const val MIN_POSE_SHOULDER_SIZE_RATIO = 0.45f
        private const val MIN_POSE_SHIFT = 0.10f
        private const val MAX_POSE_SHIFT = 0.22f
        private const val MAX_POSE_SHIFT_TO_SHOULDERS = 0.75f
        private const val FACE_TO_SHOULDER_X_TOLERANCE = 0.70f
        private const val SHOULDER_CENTER_TOLERANCE = 0.30f
        private const val SHOULDER_STRADDLE_TOLERANCE = 0.35f
        private const val MIN_SHOULDER_FACE_RATIO = 0.75f
        private const val MAX_SHOULDER_FACE_RATIO = 6.0f
        private const val MIN_SHOULDER_VERTICAL_GAP = 0.20f
        private const val MAX_SHOULDER_VERTICAL_GAP = 3.5f
        private const val MAX_VERTICAL_GAP_TO_SHOULDER = 1.75f
        private const val SHOULDER_TILT_FACE_TOLERANCE = 0.65f
        private const val SHOULDER_TILT_WIDTH_TOLERANCE = 0.45f

        private const val MIN_CONTINUITY_SIZE_RATIO = 0.40f
        private const val MIN_CONTINUITY_OVERLAP = 0.30f

        private const val WRIST_TO_SHOULDER_RATIO = 0.30f
        private const val MIN_WRIST_DISTANCE = 0.03f
        private const val MAX_WRIST_DISTANCE = 0.12f
    }
}
