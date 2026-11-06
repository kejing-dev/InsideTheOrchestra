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

/** v0.1.5 window pages (system TabBar, one Planar window): play, pieces, sections, instruments, then settings. */
const val PAGE_PLAY = "play"
const val PAGE_PIECES = "pieces"
const val PAGE_SECTIONS = "sections"
const val PAGE_LEARN = "learn"
const val SETTINGS_PAGE = "settings"

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
    /** v0.1.5: the family shown on the Instruments page (the pointed / soloed one, or picked there). */
    val cardFamily: Family = Family.STR,
    /** v0.1.1: sections muted from their bars. */
    val mutedFamilies: Set<Family> = emptySet(),
    /** v0.1.1 (D1 "move to marker"): a floor marker has been set while placing. */
    val marker: Boolean = false,
    /** Pointing falls back to the head direction (no controller, no eyes): the hint says so. */
    val headPointer: Boolean = false,
    /**
     * VR (true) or MR (false), user 2026-10-29: "这应用 VR MR 应该都可以用". VR = an opaque virtual concert hall (dome +
     * stage floor) around the orchestra inside the same Mixed Stage; MR = passthrough. Placing is the same in both.
     */
    val vr: Boolean = false,
    /** v0.1.5 master volume 0..1 (Play page; snaps at 低 / 中 / 高 = 35 / 70 / 100 %), remembered; default 高. */
    val masterVol: Float = 1f,
    /** Per-section volume 0..1 (Sections page; same snaps). */
    val familyVol: Map<Family, Float> = Family.entries.associateWith { 1f },
    /** v0.1.5: the loaded piece (assets/pieces/<id>). */
    val pieceId: String = "",
    /** v0.1.5: the window page shown ([PAGE_PLAY] ... [SETTINGS_PAGE]). */
    val page: String = PAGE_PLAY,
    /** D44: a piece was picked while another is in progress; the confirm row is open on the Pieces page. */
    val pendingPiece: String? = null,
    /** D26: [Exit app] was pressed once; the confirm row is open on the Settings page. */
    val exitConfirm: Boolean = false,
    /** v0.1.2: placing was opened with [Move orchestra] ([Cancel] shows). */
    val placeMoving: Boolean = false,
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
                if (page != PAGE_PLAY) "page:$page" else null,
                if (exitConfirm) "S10:exit" else null,
                if (snack?.kind == SnackKind.CAPTION) "S11:${snack.pass}" else null,
                if (scale < 1f) "S12" else null,
                if (audio == AudioState.FAILED) "S15" else null,
                if (english) "S16" else null,
                if (moving) "S19" else null,
                if (page == PAGE_LEARN) "card:${cardFamily.id}" else null,
                "piece:$pieceId",
                if (mutedFamilies.isNotEmpty()) "fmute:" + mutedFamilies.joinToString("+") { it.id } else null,
                if (marker) "marker" else null,
                if (vr) "vr" else null,
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

    /** D21 "Bring here": the system window closes and opens again in front of the user (when the head is level). */
    data object BringWindow : OrchUiEffect

    /** v0.1.5 "回到指挥台": the orchestra moves so the user stands on the podium, the players in front. */
    data object Podium : OrchUiEffect

    /** v0.1.5: release the stems and load this piece's. */
    data class LoadPiece(val id: String) : OrchUiEffect

    /** v0.1.2 [Move orchestra]: the ghost starts where the orchestra stood (marker at its podium front, same turn). */
    data class StartMove(val pose: io.embodit.insideorchestra.domain.usecase.OrchPose) : OrchUiEffect

    /** v0.1.2 [Re-place]: drop the scene's marker. */
    data object ClearMarker : OrchUiEffect
}
