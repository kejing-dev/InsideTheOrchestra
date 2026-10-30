package io.embodit.insideorchestra.ui.orch

import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.domain.model.Stem
import io.embodit.insideorchestra.domain.usecase.OrchPose

/** The room states (design `stateModel.rule`: S0, S1, placing S2 / S2a / S17 / S13 / S14, placed). */
enum class Room(val placed: Boolean) {
    STARTING(false),
    FINDING(false),
    PLACING(false),
    PLACED(true),
}

/** Where the music is (placed only): S3 seated, S4 playing, S5 paused, S9 finished. */
enum class Transport { SEATED, PLAYING, PAUSED, DONE }

enum class LanguagePref { SYSTEM, ZH, EN }

enum class AudioState { LOADING, READY, FAILED }

/**
 * Solo (S7): [stems] play, the others fall to 8 %. [seatId] = the musician that was pinched (null when a capsule
 * started a whole-family solo); [item] = the card line highlighted first (null = default order).
 */
data class Solo(val family: Family, val stems: Set<Stem>, val seatId: String?, val item: String?, val wholeFamily: Boolean)

/** Near listening (S8): the nearest stem under 0.9 m, or the podium. */
data class Near(val stem: Stem?, val podium: Boolean, val seatId: String?)

enum class SnackKind { CAPTION, COACH, SNAPPED, NO_WALL, VIRTUAL, BLOCKED }

data class SnackUi(val id: Int, val kind: SnackKind, val pass: Int = -1, val family: Family? = null)

/** Virtual room (D7): length (along the view), width, height in metres. */
data class VirtualRoom(val length: Float = 6.0f, val width: Float = 5.0f, val height: Float = 2.6f)

/**
 * Everything the panels and the scene draw. Time-critical things (the piece clock, envelopes, pointing) live in
 * the scene and the [OrchViewModel.clock]; this state changes on events and at most a few times per second.
 */
data class OrchUiState(
    val room: Room = Room.STARTING,
    val transport: Transport = Transport.SEATED,
    /** Piece position shown by the panel (refreshed ~4 times per second while playing). */
    val posS: Float = 0f,
    val durationS: Float = 171f,
    val floorKnown: Boolean = false,
    /** The floor is the virtual one (S13 / S14). */
    val virtualRoom: Boolean = false,
    val planesBlocked: Boolean = false,
    val room3: VirtualRoom = VirtualRoom(),
    val noWall: Boolean = false,
    val snapped: Boolean = false,
    val tooClose: Boolean = false,
    val turnSerial: Int = 0,
    val pose: OrchPose? = null,
    /** The pose at placing time ([Home] goes back there). */
    val home: OrchPose? = null,
    val moving: Boolean = false,
    val solo: Solo? = null,
    val focusSeat: String? = null,
    val near: Near? = null,
    val menuOpen: Boolean = false,
    val captionsOn: Boolean = true,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val english: Boolean = false,
    val scale: Float = 1f,
    val muted: Boolean = false,
    val coachSeen: Boolean = false,
    val snack: SnackUi? = null,
    val audio: AudioState = AudioState.LOADING,
    val background: Boolean = false,
    /** Plane detection wanted (stops 15 s after placing, design `container.lifecycle`). */
    val planesOn: Boolean = true,
    /** The current pass (for caption focus breathing). */
    val pass: Int = 0,
    /** Bumped when a caption starts: its focus zone breathes once. */
    val captionSerial: Int = 0,
    /** v0.1.1: the card opened from a section bar's [Info] without a solo (null = no such card). */
    val cardFamily: Family? = null,
    /** v0.1.1: sections muted from their bars. */
    val mutedFamilies: Set<Family> = emptySet(),
    /** v0.1.1 (D1 "move to marker"): a floor marker has been set while placing. */
    val marker: Boolean = false,
    /** Pointing falls back to the head direction (no controller, no eyes): the hint says so. */
    val headPointer: Boolean = false,
    /** v0.1.2: placing was opened with [Move orchestra] ([Cancel] shows). */
    val placeMoving: Boolean = false,
    /** D21: the world-fixed side panel has been out of view for a while: the small [Call back] pill shows. */
    val sideLost: Boolean = false,
    // ---- debug
    val freeze: Boolean = false,
    val forcedNear: Boolean = false,
    val forcedHover: String? = null,
    val forceWall: Boolean = false,
    val wallMin: Float = 3.0f,
    val floorTimeoutS: Float = 5f,
) {
    val moved: Boolean
        get() = room.placed && pose != null && home != null && pose != home

    val placing: Boolean
        get() = room == Room.PLACING

    /** The family the card shows: the solo's, else the one opened from a bar's [Info]. */
    val cardShown: Family?
        get() = if (!room.placed) null else solo?.family ?: cardFamily

    /** Design state id for logs and QA (room state plus overlays). */
    val stateId: String
        get() =
            when (room) {
                Room.STARTING -> "S0"
                Room.FINDING -> "S1"
                Room.PLACING ->
                    when {
                        tooClose -> "S2near"
                        snapped -> "S2a"
                        noWall -> "S17"
                        planesBlocked -> "S14"
                        virtualRoom -> "S13"
                        else -> "S2"
                    }
                Room.PLACED ->
                    when (transport) {
                        Transport.SEATED -> "S3"
                        Transport.PLAYING -> "S4"
                        Transport.PAUSED -> "S5"
                        Transport.DONE -> "S9"
                    }
            }

    val overlays: String
        get() =
            listOfNotNull(
                focusSeat?.let { "S6:$it" },
                solo?.let { "S7:${it.family.id}${if (it.wholeFamily) "" else ":" + it.stems.joinToString("+") { s -> s.id }}" },
                near?.let { if (it.podium) "S8:podium" else "S8:${it.stem?.id}" },
                if (menuOpen) "S10" else null,
                if (snack?.kind == SnackKind.CAPTION) "S11:${snack.pass}" else null,
                if (scale < 1f) "S12" else null,
                if (audio == AudioState.FAILED) "S15" else null,
                if (english) "S16" else null,
                if (moving) "S19" else null,
                cardFamily?.let { "card:${it.id}" },
                if (mutedFamilies.isNotEmpty()) "fmute:" + mutedFamilies.joinToString("+") { it.id } else null,
                if (marker) "marker" else null,
                if (muted) "mute" else null,
            ).joinToString(",")
}

/** One-shot things the scene or the screen must do. */
sealed interface OrchUiEffect {
    data object ExitApp : OrchUiEffect

    data object Click : OrchUiEffect

    /** Bring [seatId] 0.8 m in front of the head (I22). */
    data class Pull(val seatId: String) : OrchUiEffect

    data object Home : OrchUiEffect

    data class Orbit(val degrees: Float) : OrchUiEffect

    data class Push(val metres: Float) : OrchUiEffect

    /** Debug `stand=<stem|podium>`: move the orchestra so the head is at that stem's site (null = podium). */
    data class Stand(val stem: Stem?) : OrchUiEffect

    /** Recompute the podium balance from the current ear height. */
    data object Recalibrate : OrchUiEffect

    /** Ring flash at the end of the piece. */
    data object Finale : OrchUiEffect

    /** The ten stems start at [fromS] (piece clock). */
    data class AudioPlay(val fromS: Float) : OrchUiEffect

    data object AudioPause : OrchUiEffect

    data class AudioSeek(val s: Float) : OrchUiEffect

    /** Load the stems again (S15 retry). */
    data object AudioRetry : OrchUiEffect

    /** D21: put the side panel back at its designed place in front of the user, now. */
    data object RecallSide : OrchUiEffect

    /** v0.1.2 [Move orchestra]: the ghost starts where the orchestra stood (marker at its podium front, same turn). */
    data class StartMove(val pose: io.embodit.insideorchestra.domain.usecase.OrchPose) : OrchUiEffect

    /** v0.1.2 [Re-place]: drop the scene's marker. */
    data object ClearMarker : OrchUiEffect
}
