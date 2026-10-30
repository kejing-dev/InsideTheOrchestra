package io.embodit.insideorchestra.domain.usecase

import io.embodit.insideorchestra.domain.model.Quat4
import io.embodit.insideorchestra.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2

/**
 * Content placed from the head of this moment (taste profile D9, v0.1.1): the head's position and its level
 * heading (yaw only; pitch and roll ignored), never the world origin, the launch pose or an old rack pose.
 *
 * 0.1.0 let the placing ghost start wherever the pointer's floor point was. On the headset an idle controller
 * still reports a pose, so after "Move rack" the ghost (and the rack, on a pinch) could land behind the user
 * ("reset 后他出现在我后面"). Now placing starts straight ahead, the pointer only steers the ghost while its floor
 * point lies in front, and a rack left behind after a recenter is brought back in front.
 */
object HeadFrame {
    /** `place=auto` / first placing: the podium's front edge this far ahead (design `seats.placeAnchor`). */
    const val AHEAD_M = 1.5f

    /** A floor point counts as "in front" within this bearing of the level heading (pointer, taps). */
    val FRONT_RAD = Math.toRadians(70.0).toFloat()

    /** A placed rack whose centre is farther off the heading than this after a recenter is brought back. */
    val BEHIND_RAD = Math.toRadians(100.0).toFloat()

    /**
     * Level heading (unit, y = 0) of a head with [rotation] (-Z ahead, +Y up): the forward vector's level part,
     * which roll does not change. Looking almost straight down or up (beyond about 75 degrees) that part
     * vanishes; the head's up vector then points along (down) or against (up) the heading and is blended in.
     */
    fun heading(rotation: Quat4): Vec3 {
        val f = rotation.rotate(Vec3.AHEAD)
        val flat = Vec3(f.x, 0f, f.z)
        if (flat.length >= STEEP_FLAT) return flat.normalized()
        val u = rotation.rotate(Vec3.UP)
        val h = Vec3(f.x - u.x * f.y, 0f, f.z - u.z * f.y)
        return if (h.length < 1e-4f) Vec3.AHEAD else h.normalized()
    }

    /** Level length of the forward vector below which the gaze counts as steep (about 75 degrees up or down). */
    private const val STEEP_FLAT = 0.25f

    /** Yaw about +Y that turns -Z onto [heading] (the head's level turn). */
    fun yawOf(heading: Vec3): Float = atan2(-heading.x, -heading.z)

    /** The orchestra straight ahead: podium front [distance] along the level heading on [floorY], its audience side to the head. */
    fun orchestraAhead(position: Vec3, rotation: Quat4, floorY: Float, scale: Float, distance: Float = AHEAD_M): OrchPose {
        val h = heading(rotation)
        val anchor = Vec3(position.x, floorY, position.z) + h * distance
        return OrchPose(anchor, facing(anchor, position), scale)
    }

    /** Yaw that turns a front (+Z) at [from] towards [to] about the vertical only. */
    fun facing(from: Vec3, to: Vec3): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return if (dx * dx + dz * dz < 1e-6f) 0f else atan2(dx, dz)
    }

    /** Level angle between the heading and the direction from the head to [point], radians 0..pi. */
    fun bearing(position: Vec3, rotation: Quat4, point: Vec3): Float {
        val d = Vec3(point.x - position.x, 0f, point.z - position.z)
        if (d.length < 1e-3f) return 0f
        return acos(d.normalized().dot(heading(rotation)).coerceIn(-1f, 1f))
    }

    fun inFront(position: Vec3, rotation: Quat4, point: Vec3, within: Float = FRONT_RAD): Boolean = bearing(position, rotation, point) <= within

    /**
     * A real head sample (v0.1.2, QA q2-1): finite, and not the exact Stage origin. Going to the background the HMD
     * provider hands out frames of the origin with the identity rotation (S18 logs `head=(0.0000, 0.0000, 0.0000)`,
     * about 0.5 s before ON_STOP); 0.1.1 fed those to the recenter detector, which read a jump and moved the rack.
     */
    fun plausible(position: Vec3, rotation: Quat4): Boolean = position.isFinite() && rotation.isFinite() && position.length > PLAUSIBLE_M

    const val PLAUSIBLE_M = 1e-3f
}

/**
 * The system recenter ("reset view") has no public callback in SDK 6.1 (pico-dev-knowledge: none found), so it
 * is read from the head pose: a level turn of 45 degrees or a 0.75 m jump between two frames less than 150 ms
 * apart is not a head movement. Slow frames (idle emulator, about 1 fps) never count, nor do placeholder samples
 * ([HeadFrame.plausible]) or anything while the app is in the background (the scene stops feeding).
 */
class RecenterDetector {
    private var lastMs = Long.MIN_VALUE
    private var lastPos = Vec3.ZERO
    private var lastYaw = 0f

    /** Feeds one head sample; true when it jumped from the previous one. A placeholder sample is ignored. */
    fun feed(nowMs: Long, position: Vec3, rotation: Quat4): Boolean {
        if (!HeadFrame.plausible(position, rotation)) return false
        val yaw = HeadFrame.yawOf(HeadFrame.heading(rotation))
        val prevMs = lastMs
        val jumped =
            prevMs != Long.MIN_VALUE &&
                nowMs - prevMs in 0..MAX_GAP_MS &&
                (abs(PlacementUseCase.angle(yaw, lastYaw)) >= TURN_RAD || position.distanceTo(lastPos) >= JUMP_M)
        lastMs = nowMs
        lastPos = position
        lastYaw = yaw
        return jumped
    }

    fun reset() {
        lastMs = Long.MIN_VALUE
    }

    companion object {
        const val MAX_GAP_MS = 150L
        val TURN_RAD = Math.toRadians(45.0).toFloat()
        const val JUMP_M = 0.75f
    }
}
