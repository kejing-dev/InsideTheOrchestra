package io.embodit.insideorchestra.ui.orch

import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.domain.usecase.OrchPose

/** Everything that changes [OrchUiState]: panel controls, scene callbacks, lifecycle and debug hooks. */
sealed interface OrchEvent {
    // ---- scene
    data object SceneReady : OrchEvent

    /** [floor]: a floor is known (real or virtual); [frames]: snap frames available; [virtual]: the floor is virtual. */
    data class PlanesChanged(val floor: Boolean, val frames: Int, val virtual: Boolean) : OrchEvent

    data object PlaneTrackingFailed : OrchEvent

    data class GhostChanged(val snapped: Boolean, val tooClose: Boolean) : OrchEvent

    /** Put the orchestra down at [pose]; null = not placeable now (red ghost). */
    data class PlaceRequested(val pose: OrchPose?) : OrchEvent

    data class MusicianTapped(val seatId: String) : OrchEvent

    data object NothingTapped : OrchEvent

    data class FocusChanged(val seatId: String?) : OrchEvent

    data class NearChanged(val near: Near?) : OrchEvent

    /** The orchestra was moved while listening (stick orbit / push, drag, pull, home): the new pose. */
    data class Moved(val pose: OrchPose) : OrchEvent

    data class MovingChanged(val moving: Boolean) : OrchEvent

    data class AudioLoaded(val ok: Boolean) : OrchEvent

    // ---- control panel
    data object PlayClicked : OrchEvent

    data object PauseClicked : OrchEvent

    data object RestartClicked : OrchEvent

    data object AgainClicked : OrchEvent

    data class SeekReleased(val s: Float) : OrchEvent

    data class FamilyChipClicked(val family: Family) : OrchEvent

    /** v0.1.1 section bar: mute / unmute this section. */
    data class FamilyMuteClicked(val family: Family) : OrchEvent

    /** v0.1.1 section bar: open / close this section's card. */
    data class FamilyInfoClicked(val family: Family) : OrchEvent

    /** v0.1.1 placing: a floor marker was set (true) or cleared. */
    data class MarkerChanged(val set: Boolean) : OrchEvent

    /** Placing: [Confirm] (v0.1.2: the end of placing; the orchestra is locked afterwards). */
    data object PlaceAtMarkerClicked : OrchEvent

    /** Placing: [Re-place] (drop the marker, stay placing). */
    data object ReplaceMarkerClicked : OrchEvent

    /** Side panel: [Move orchestra] (the only way to unlock it). */
    data object MoveOrchestraClicked : OrchEvent

    /** Placing after [Move orchestra]: [Cancel] (back where it was, locked). */
    data object MoveCancelClicked : OrchEvent

    data class PointerChanged(val head: Boolean) : OrchEvent

    data object CardCloseClicked : OrchEvent

    data class MasterVolume(val v: Float, val done: Boolean) : OrchEvent

    data class FamilyVolume(val family: Family, val v: Float, val done: Boolean) : OrchEvent

    /** More menu: MR (passthrough) / VR (virtual concert hall). */
    data class VrSelected(val vr: Boolean) : OrchEvent

    /** D21: the window's [Bring here]. */
    data object BringHereClicked : OrchEvent

    /** v0.1.5: [Back to podium]. */
    data object PodiumClicked : OrchEvent

    /** v0.1.5: a piece picked on the Pieces page. */
    data class PieceSelected(val id: String) : OrchEvent

    /** D44: the answer to "switch to …? this one stops" ([PieceSelected] while a piece is in progress). */
    data class PieceSwitchAnswer(val switch: Boolean) : OrchEvent

    /** v0.1.5: a TabBar page. */
    data class PageSelected(val page: String) : OrchEvent

    /** v0.1.5: the scene's pose that puts the user on the podium (start: placed there, no placing step). */
    data class AutoPlace(val pose: OrchPose) : OrchEvent

    /** D26: [Exit app] (show = true) / [Cancel] (false); [ExitClicked] is the second, confirming press. */
    data class ExitAsked(val show: Boolean) : OrchEvent

    data object Turn45Clicked : OrchEvent

    data object MuteClicked : OrchEvent

    data object RetryAudioClicked : OrchEvent

    data object HomeClicked : OrchEvent

    // ---- card
    data object RestoreClicked : OrchEvent

    data object PullClicked : OrchEvent

    // ---- menu
    data class CaptionsSelected(val on: Boolean) : OrchEvent

    data class LanguageSelected(val language: LanguagePref) : OrchEvent

    data class ScaleSelected(val scale: Float) : OrchEvent

    data object ReplaceClicked : OrchEvent

    data object ExitClicked : OrchEvent

    data class RoomResize(val dLength: Float, val dWidth: Float, val dHeight: Float) : OrchEvent

    // ---- lifecycle
    data object WentBackground : OrchEvent

    data object CameForeground : OrchEvent

    data class Debug(val key: String, val value: String) : OrchEvent
}
