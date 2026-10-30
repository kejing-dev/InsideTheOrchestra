package io.embodit.insideorchestra.domain.usecase

import io.embodit.insideorchestra.domain.model.Orchestra
import io.embodit.insideorchestra.domain.model.Quat4
import io.embodit.insideorchestra.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Where the orchestra stands: [anchor] = the podium's front edge on the floor (design `seats.placeAnchor`), [yaw]
 * turns the orchestra's +Z (towards the audience) about the vertical, [scale] = 1 or 0.5 (1:2).
 */
data class OrchPose(val anchor: Vec3, val yaw: Float, val scale: Float = 1f) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)

    /** Orchestra origin (podium centre on the floor) in Stage space. */
    val origin: Vec3
        get() = anchor - rotation.rotate(Orchestra.ANCHOR * scale)

    /** Footprint centre on the floor (what the wall snap frames hold). */
    val center: Vec3
        get() = toStage(Orchestra.FOOT_CENTER)

    fun toStage(local: Vec3): Vec3 = origin + rotation.rotate(local * scale)

    fun toLocal(stage: Vec3): Vec3 = rotation.conjugate().rotate(stage - origin) / scale

    fun dirToStage(local: Vec3): Vec3 = rotation.rotate(local)

    companion object {
        /** The pose whose footprint centre is [center] (a snap frame). */
        fun fromCenter(center: Vec3, yaw: Float, scale: Float): OrchPose {
            val r = Quat4.axisAngle(Vec3.UP, yaw)
            return OrchPose(center + r.rotate((Orchestra.ANCHOR - Orchestra.FOOT_CENTER) * scale), yaw, scale)
        }
    }
}

/**
 * Placement rules (design `I2`, `I5`, `I6`, `entities3d.ghost`, D14): the pointer meets the floor, the podium's
 * front edge goes there (1.0 - 6 m from the head's floor point) and the orchestra spreads away from the user;
 * a seat or the podium may never come within 0.6 m (level) of the head: the pose is pushed straight away until it
 * is clear; near a snap frame (0.4 m, 20 degrees) it snaps, it lets go at 0.5 m / 25 degrees.
 */
class PlacementUseCase {

    /** The pointer ray against the floor; a ray that does not go down lands 1.5 m ahead. */
    fun floorPoint(origin: Vec3, direction: Vec3, floorY: Float): Vec3 {
        if (direction.y < -0.02f) {
            val t = (floorY - origin.y) / direction.y
            if (t > 0f && t < 30f) return origin + direction * t
        }
        val flat = Vec3(direction.x, 0f, direction.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
        return Vec3(origin.x, floorY, origin.z) + flat * 1.5f
    }

    /** Clamps a floor point into 1.0 - 6 m of the head's floor point (design `I6`). */
    fun clamp(point: Vec3, head: Vec3, floorY: Float): Vec3 {
        val foot = Vec3(head.x, floorY, head.z)
        val d = Vec3(point.x - foot.x, 0f, point.z - foot.z)
        val len = d.length
        val dir = if (len < 1e-3f) Vec3.AHEAD else d / len
        return when {
            len < MIN_M -> foot + dir * MIN_M
            len > FAR_M -> foot + dir * FAR_M
            else -> Vec3(point.x, floorY, point.z)
        }
    }

    /** Level distance from the head to the nearest seat or the podium centre (D14 clearance). */
    fun clearance(pose: OrchPose, head: Vec3): Float {
        val l = pose.toLocal(Vec3(head.x, pose.anchor.y, head.z))
        var best = Orchestra.d(l.x, l.z, 0f, 0f)
        for (s in Orchestra.seats) best = minOf(best, Orchestra.d(l.x, l.z, s.x, s.z))
        return best * pose.scale
    }

    /** The nearest seat's id and its level distance from the head (metres), for logs and near listening. */
    fun nearestSeat(pose: OrchPose, head: Vec3): Pair<String, Float> {
        val l = pose.toLocal(Vec3(head.x, pose.anchor.y, head.z))
        val s = Orchestra.seats.minBy { Orchestra.d(l.x, l.z, it.x, it.z) }
        return s.id to Orchestra.d(l.x, l.z, s.x, s.z) * pose.scale
    }

    /**
     * Pushes the orchestra straight away from the head (level) until every seat is [near] clear of it; returns the
     * pose and whether it had to move.
     */
    fun pushClear(pose: OrchPose, head: Vec3, near: Float = NEAR_M): Pair<OrchPose, Boolean> {
        var p = pose
        var moved = false
        repeat(PUSH_STEPS) {
            val d = clearance(p, head)
            if (d >= near - CLEAR_EPS) return p to moved
            val away = Vec3(p.center.x - head.x, 0f, p.center.z - head.z)
            val dir = if (away.length < 1e-3f) p.rotation.rotate(Vec3(0f, 0f, -1f)) else away.normalized()
            p = p.copy(anchor = p.anchor + dir * (near - d + CLEAR_EPS))
            moved = true
        }
        return p to (moved || clearance(p, head) < near - CLEAR_EPS)
    }

    /** Heading that turns the orchestra's front (+Z, the audience side) from [from] towards the head. */
    fun yawFacing(from: Vec3, head: Vec3, fallback: Float = 0f): Float {
        val dx = head.x - from.x
        val dz = head.z - from.z
        if (dx * dx + dz * dz < 1e-4f) return fallback
        return atan2(dx, dz)
    }

    /** The snap frame the footprint is held by (catch 0.4 m / 20 degrees, release 0.5 m / 25 degrees, design `I5`). */
    fun snap(center: Vec3, yaw: Float, frames: List<SnapFrame>, held: SnapFrame?, scale: Float): SnapFrame? {
        val k = maxOf(scale, 0.5f)
        if (held != null) {
            val same = frames.firstOrNull { it.center.distanceTo(held.center) < 0.05f && abs(angle(it.yaw, held.yaw)) < 0.02f }
            if (same != null && flat(center, same.center) < RELEASE_M * k && abs(angle(yaw, same.yaw)) < RELEASE_RAD) return same
        }
        return frames
            .filter { flat(center, it.center) <= CATCH_M * k && abs(angle(yaw, it.yaw)) <= CATCH_RAD }
            .minByOrNull { flat(center, it.center) }
    }

    private fun flat(a: Vec3, b: Vec3): Float = Vec3(a.x - b.x, 0f, a.z - b.z).length

    companion object {
        /** D14: no musician or podium within 0.6 m (level) of the head. */
        const val NEAR_M = 0.6f
        const val MIN_M = 1.0f
        const val FAR_M = 6f
        private const val PUSH_STEPS = 16
        private const val CLEAR_EPS = 0.01f
        const val CATCH_M = 0.4f
        const val RELEASE_M = 0.5f
        val CATCH_RAD = Math.toRadians(20.0).toFloat()
        val RELEASE_RAD = Math.toRadians(25.0).toFloat()

        /** Signed difference a - b wrapped to -pi..pi. */
        fun angle(a: Float, b: Float): Float {
            var d = a - b
            while (d > PI.toFloat()) d -= 2f * PI.toFloat()
            while (d < -PI.toFloat()) d += 2f * PI.toFloat()
            return d
        }
    }
}
