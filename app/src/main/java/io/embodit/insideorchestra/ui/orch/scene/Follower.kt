package io.embodit.insideorchestra.ui.orch.scene

import io.embodit.insideorchestra.domain.model.PlaneSurface
import io.embodit.insideorchestra.domain.model.Quat4
import io.embodit.insideorchestra.domain.model.Vec3
import io.embodit.insideorchestra.domain.usecase.FacePanelUseCase
import io.embodit.insideorchestra.domain.usecase.RestBodyUseCase
import io.embodit.insideorchestra.platform.OrchLog
import io.embodit.insideorchestra.platform.tracking.HeadPose
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos

/** A pose in Stage space: where a followed thing sits and its level turn (yaw about the vertical). */
data class FollowPose(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

/**
 * Lazy follow (Spatial Ruler rule T11, taken over for the main panel and the extinguisher prop): the
 * thing rests where it settled; once its centre has been more than 45 degrees off the view direction,
 * or nearer than [nearM] or farther than [farM], for 0.5 s without a break, it slides to the designed
 * pose of that moment in 0.35 s (ease-in-out, time based). It holds still while [hold]. The designed
 * pose is [headOffset] in the head frame (x right, y up, -z ahead), level, turned to the head's heading.
 * The surface rule keeps it out of real surfaces ([halfExtents] along its own axes).
 */
internal class Follower(
    private val name: String,
    private val headOffset: Vec3,
    private val halfExtents: List<Float>,
    private val lean: Quat4 = Quat4.IDENTITY,
    private val nearM: Float = 0.5f,
    private val farM: Float = 1.3f,
) {
    private val restRule = RestBodyUseCase()
    private var started = false
    private var restYaw = 0f
    private var anchorPos = Vec3.ZERO
    private var applied = Vec3.ZERO
    private var outSinceMs = NEVER
    private var slideStartMs = NEVER
    private var slideFromPos = Vec3.ZERO
    private var slideFromYaw = 0f
    private var slideFromApplied = Vec3.ZERO
    private var slideToPos = Vec3.ZERO
    private var slideToYaw = 0f
    private var slideToApplied = Vec3.ZERO
    private var lastPlaneCount = -1

    /** The current pose (centre and level turn). */
    var pose: FollowPose = FollowPose(Vec3.ZERO, 0f)
        private set

    /** Turned to the head's heading and tilted to face the head from its elevation (plus the designed lean). */
    val rotation: Quat4
        get() = pose.rotation * Quat4.axisAngle(Vec3.RIGHT, pitch * FACE_SHARE) * lean

    /** Elevation of the designed place below the horizontal, radians (negative = below). */
    private var pitch = 0f

    /**
     * Highest elevation the designed place may take, radians (set every frame by the scene from the rack:
     * `PanelClearance`, v0.1.1); never below the 40-degree floor.
     */
    var pitchCap = Float.POSITIVE_INFINITY

    val sliding: Boolean
        get() = slideStartMs != NEVER

    fun reset() {
        started = false
        outSinceMs = NEVER
        slideStartMs = NEVER
    }

    /**
     * The designed place: [headOffset]'s distance, its own bearing beside the gaze heading, and its own
     * elevation added to the gaze elevation, clamped to 24 - 40 degrees below the horizontal (design
     * `panels.control.placement`: the panel stays low, under the lower tier's drums, and does not climb
     * when the user looks up at the small bells nor dive when they look down at the big ones).
     */
    private fun designedFor(head: HeadPose): Pair<Vec3, Float> {
        val yaw = FacePanelUseCase.yawOf(head.forward, restYaw)
        val gaze = kotlin.math.asin(head.forward.y.coerceIn(-1f, 1f))
        val flat = kotlin.math.sqrt(headOffset.x * headOffset.x + headOffset.z * headOffset.z)
        val own = kotlin.math.atan2(headOffset.y, flat)
        pitch = minOf((gaze + own).coerceIn(MIN_PITCH_RAD, MAX_PITCH_RAD), pitchCap).coerceAtLeast(MIN_PITCH_RAD)
        val bearing = kotlin.math.atan2(-headOffset.x, -headOffset.z)
        val range = headOffset.length
        val dir = Quat4.axisAngle(Vec3.UP, yaw + bearing).rotate(Vec3(0f, kotlin.math.sin(pitch), -kotlin.math.cos(pitch)))
        return (head.position + dir * range) to yaw
    }

    private fun correction(pos: Vec3, yaw: Float, headPos: Vec3, planes: Collection<PlaneSurface>): Vec3 {
        val turn = Quat4.axisAngle(Vec3.UP, yaw) * lean
        val axes = listOf(turn.rotate(Vec3.RIGHT), turn.rotate(Vec3.UP), turn.rotate(Vec3(0f, 0f, 1f)))
        return restRule.rest(pos, axes, halfExtents, headPos, planes) - pos
    }

    /** Once per frame. Returns true when the pose changed this frame. */
    fun follow(head: HeadPose, planes: Collection<PlaneSurface>, planeCount: Int, nowMs: Long, hold: Boolean): Boolean {
        if (!started) {
            val (pos, yaw) = designedFor(head)
            started = true
            restYaw = yaw
            anchorPos = pos
            applied = correction(pos, yaw, head.position, planes)
            lastPlaneCount = planeCount
            write()
            OrchLog.i("FOLLOW $name park reason=start pos=${OrchLog.pos(pose.position)} yaw=${String.format(Locale.US, "%.1f", Math.toDegrees(yaw.toDouble()))}")
            return true
        }
        if (sliding) {
            slide(nowMs)
            return true
        }
        val to = pose.position - head.position
        val dist = to.length
        val away = if (dist < 1e-3f) 0f else Math.toDegrees(acos((to / dist).dot(head.forward).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val reason =
            when {
                away > WINDOW_DEG -> "angle"
                dist < nearM -> "near"
                dist > farM -> "far"
                else -> null
            }
        if (reason == null || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            startSlide(head, planes, nowMs, reason)
            return true
        }
        if (planeCount != lastPlaneCount) {
            lastPlaneCount = planeCount
            val wanted = correction(anchorPos, restYaw, head.position, planes)
            if ((wanted - applied).length > DEAD_BAND_M) {
                applied = wanted
                write()
                return true
            }
        }
        return false
    }

    /** Slides to the designed pose now (debug `park`, and after a hidden panel comes back). */
    fun park(head: HeadPose, planes: Collection<PlaneSurface>, nowMs: Long) {
        if (!started) return
        startSlide(head, planes, nowMs, "park")
    }

    private fun startSlide(head: HeadPose, planes: Collection<PlaneSurface>, nowMs: Long, reason: String) {
        val (pos, yaw) = designedFor(head)
        slideFromPos = anchorPos
        slideFromYaw = restYaw
        slideFromApplied = applied
        slideToPos = pos
        slideToYaw = yaw
        slideToApplied = correction(pos, yaw, head.position, planes)
        slideStartMs = nowMs
        outSinceMs = NEVER
        OrchLog.i("FOLLOW $name slide reason=$reason to=${OrchLog.pos(pos + slideToApplied)}")
        slide(nowMs)
    }

    private fun slide(nowMs: Long) {
        val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
        val t = Ease.inOutCubic(q)
        var dYaw = slideToYaw - slideFromYaw
        while (dYaw > PI.toFloat()) dYaw -= 2f * PI.toFloat()
        while (dYaw < -PI.toFloat()) dYaw += 2f * PI.toFloat()
        anchorPos = slideFromPos.lerp(slideToPos, t)
        restYaw = slideFromYaw + dYaw * t
        applied = slideFromApplied.lerp(slideToApplied, t)
        write()
        if (q >= 1f) slideStartMs = NEVER
    }

    private fun write() {
        pose = FollowPose(anchorPos + applied, restYaw)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        /** The side panel rests 40 degrees left of the gaze (v0.1.1), so it re-parks only beyond 65 degrees. */
        private const val WINDOW_DEG = 65f
        private const val OUT_MS = 500L
        private const val SLIDE_MS = 350f
        private const val DEAD_BAND_M = 0.01f
        /** The panel stays 12 - 35 degrees below the horizontal (design: 15 degrees down, D10). */
        private const val MIN_PITCH_RAD = -0.6109f
        private const val MAX_PITCH_RAD = -0.2094f
        private const val FACE_SHARE = 0.8f

        /**
         * v0.1.1 side panel: 40 degrees left and 20 degrees down, 0.7 m from the head, outside the view onto the
         * orchestra (D20: the shared controls go to the side; the section controls float above the sections).
         */
        val MAIN_OFFSET = Vec3(-0.4228f, -0.2394f, -0.5039f)

        /** The control panel leans back 10 degrees. */
        val MAIN_LEAN: Quat4 = Quat4.axisAngle(Vec3.RIGHT, -0.17453f)
    }
}
