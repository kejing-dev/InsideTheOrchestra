package io.embodit.insideorchestra.domain.usecase

import io.embodit.insideorchestra.domain.model.Vec3

/**
 * The placing state machine (v0.1.2, profile D1 "放好就锁住，点 UI「移动」才能再动" and "放置模式必须有终点"):
 *
 *   PLACING (no marker) --floor tap / trigger--> PLACING (marker, ghost there) --[Confirm]--> LOCKED
 *        ^                                            |  floor tap again = new marker; [Re-place] = no marker
 *        |                                            |  stick = turn the ghost
 *   LOCKED --[Move orchestra]--> PLACING (marker at the current place, [Cancel] back to LOCKED unchanged)
 *
 * LOCKED ignores every spatial input that could move the orchestra (stick, drag, trigger, ray, floor taps); only
 * the panel's [Move orchestra] opens placing again. Pure: the ViewModel keeps one [PlaceState] and the scene asks
 * [canMove] before it lets an input touch the pose.
 */
enum class PlaceMode { PLACING, LOCKED }

data class PlaceState(
    val mode: PlaceMode = PlaceMode.PLACING,
    /** The floor marker (Stage metres) while placing; the ghost stands there. */
    val marker: Vec3? = null,
    /** The confirmed pose (LOCKED), or the pose being moved away from (PLACING after [Move orchestra]). */
    val pose: OrchPose? = null,
    /** Placing was opened from a locked orchestra: [Cancel] goes back to [pose] unchanged. */
    val moving: Boolean = false,
) {
    val placing: Boolean
        get() = mode == PlaceMode.PLACING

    val locked: Boolean
        get() = mode == PlaceMode.LOCKED

    /** [Confirm] is possible: placing with a marker. */
    val canConfirm: Boolean
        get() = placing && marker != null
}

sealed interface PlaceInput {
    /** A pinch / trigger / click that met the floor at [point]. */
    data class FloorTap(val point: Vec3) : PlaceInput

    /** Thumbstick deflection (turns the ghost while placing). */
    data class Stick(val x: Float, val y: Float) : PlaceInput

    /** A drag of the orchestra (podium / floor zone), metres. */
    data class Drag(val dx: Float, val dz: Float) : PlaceInput

    /** [Confirm] with the ghost's pose of this moment (null = not placeable: too close). */
    data class Confirm(val pose: OrchPose?) : PlaceInput

    /** [Re-place]: drop the marker, stay placing. */
    data object Replace : PlaceInput

    /** [Move orchestra] on the panel (the only way out of LOCKED). */
    data object Move : PlaceInput

    /** [Cancel] while moving: back to the locked pose. */
    data object Cancel : PlaceInput

    /** Debug / restart: back to placing from scratch. */
    data object Reset : PlaceInput
}

object PlacementFlow {
    fun reduce(s: PlaceState, input: PlaceInput): PlaceState =
        when (input) {
            is PlaceInput.FloorTap -> if (s.placing) s.copy(marker = input.point) else s
            is PlaceInput.Stick -> s
            is PlaceInput.Drag -> s
            is PlaceInput.Confirm -> if (s.canConfirm && input.pose != null) PlaceState(PlaceMode.LOCKED, null, input.pose, moving = false) else s
            PlaceInput.Replace -> if (s.placing) s.copy(marker = null) else s
            PlaceInput.Move -> if (s.locked && s.pose != null) PlaceState(PlaceMode.PLACING, s.pose.anchor, s.pose, moving = true) else s
            PlaceInput.Cancel -> if (s.placing && s.moving && s.pose != null) PlaceState(PlaceMode.LOCKED, null, s.pose, moving = false) else s
            PlaceInput.Reset -> PlaceState()
        }

    /** May a spatial input (stick, drag, trigger, ray, floor tap) change where the orchestra or its ghost is? */
    fun canMove(s: PlaceState): Boolean = s.placing
}
