package io.embodit.insideorchestra.ui.orch

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.domain.model.Orchestra
import io.embodit.insideorchestra.domain.model.Piece
import io.embodit.insideorchestra.domain.model.Stem
import io.embodit.insideorchestra.domain.usecase.PieceClock
import io.embodit.insideorchestra.domain.usecase.PlaceInput
import io.embodit.insideorchestra.domain.usecase.PlaceState
import io.embodit.insideorchestra.domain.usecase.PlacementFlow
import io.embodit.insideorchestra.platform.OrchGraph
import io.embodit.insideorchestra.platform.OrchLog
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The whole state machine of the app (design `states`, `stateModel`, `interactions`). Panel controls, scene
 * callbacks, lifecycle and the debug receiver all come in through [onEvent]; the scene reads [state] and the
 * [clock]. Main thread only.
 */
class OrchViewModel(private val appContext: Context) : ViewModel() {

    val piece: Piece = loadPiece(appContext)
    val clock = PieceClock(piece.durationS)

    private val prefs: SharedPreferences = appContext.getSharedPreferences("orchestra", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OrchUiState> = _state

    private val effectChannel = Channel<OrchUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    /** v0.1.2: the placing state machine (D1: placing has an end, then the orchestra is locked). */
    var flow = PlaceState()
        private set

    private fun step(input: PlaceInput): PlaceState {
        val before = flow
        flow = PlacementFlow.reduce(flow, input)
        if (flow != before) OrchLog.i("FLOW ${input::class.simpleName} -> ${flow.mode} marker=${flow.marker != null} moving=${flow.moving}")
        return flow
    }

    private var snackSerial = 0
    private var snackJob: Job? = null
    private var floorJob: Job? = null
    private var wallJob: Job? = null
    private var planesJob: Job? = null
    private var framesKnown = 0
    private var lastPass = -1
    private var playedS = 0f
    private var noWallShown = false
    private var lastStateLine = ""

    private fun initialState(): OrchUiState {
        val lang = runCatching { LanguagePref.valueOf(prefs.getString("lang", "SYSTEM")!!) }.getOrDefault(LanguagePref.SYSTEM)
        return OrchUiState(
            language = lang,
            english = englishFor(lang),
            captionsOn = prefs.getBoolean("captions", true),
            scale = if (prefs.getFloat("scale", 1f) < 1f) 0.5f else 1f,
            muted = prefs.getBoolean("muted", false),
            coachSeen = prefs.getBoolean("coachSeen", false),
            durationS = piece.durationS,
        )
    }

    private fun englishFor(lang: LanguagePref): Boolean =
        when (lang) {
            LanguagePref.EN -> true
            LanguagePref.ZH -> false
            LanguagePref.SYSTEM -> appContext.resources.configuration.let { c: Configuration -> c.locales[0]?.language != "zh" }
        }

    private fun set(transform: (OrchUiState) -> OrchUiState) {
        val before = _state.value
        val after = transform(before)
        if (after == before) return
        _state.value = after
        val line = "STATE ${after.stateId} overlays=${after.overlays}"
        if (line != lastStateLine) {
            lastStateLine = line
            OrchLog.i(line)
        }
    }

    private fun effect(e: OrchUiEffect) {
        effectChannel.trySend(e)
    }

    // =========================================================================================
    // the piece clock (called by the scene once per frame)
    // =========================================================================================

    /** Advances the piece clock; [medianS] = the stems' median position or -1. */
    fun tick(dt: Float, medianS: Float) {
        val s = _state.value
        if (s.transport != Transport.PLAYING || s.freeze || s.background) return
        clock.follow(medianS, dt)
        val ended = clock.advance(dt)
        playedS += dt
        val t = clock.t
        val pass = piece.passAt(t)
        if (pass.index != lastPass) {
            lastPass = pass.index
            set { it.copy(pass = pass.index) }
            if (pass.caption != null && s.captionsOn && t - pass.startS < 1.0f) showCaption(pass.index, pass.focus)
        }
        if (!s.coachSeen && playedS >= COACH_AFTER_S) {
            prefs.edit().putBoolean("coachSeen", true).apply()
            set { it.copy(coachSeen = true) }
            snack(SnackKind.COACH, COACH_MS)
        }
        if (ended) {
            OrchLog.i("PIECE done t=${OrchLog.f(t)}")
            set { it.copy(transport = Transport.DONE, posS = piece.durationS) }
            effect(OrchUiEffect.AudioPause)
            effect(OrchUiEffect.Finale)
            return
        }
        if (abs(t - s.posS) >= 0.25f) set { it.copy(posS = t) }
    }

    private fun showCaption(pass: Int, focus: Family?) {
        set { it.copy(captionSerial = it.captionSerial + 1) }
        snack(SnackKind.CAPTION, CAPTION_MS, pass = pass, family = focus)
        OrchLog.i("CAPTION pass=$pass focus=${focus?.id}")
    }

    private fun snack(kind: SnackKind, ms: Long, pass: Int = -1, family: Family? = null) {
        val id = ++snackSerial
        set { it.copy(snack = SnackUi(id, kind, pass, family)) }
        snackJob?.cancel()
        snackJob =
            viewModelScope.launch {
                delay(ms)
                if (_state.value.snack?.id == id && !_state.value.freeze) set { it.copy(snack = null) }
            }
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(event: OrchEvent) {
        when (event) {
            OrchEvent.SceneReady -> sceneReady()
            is OrchEvent.PlanesChanged -> planesChanged(event)
            OrchEvent.PlaneTrackingFailed -> {
                OrchLog.i("PLANES blocked -> virtual room")
                set { it.copy(planesBlocked = true, virtualRoom = true, room = if (it.room.placed) it.room else Room.PLACING) }
                snack(SnackKind.BLOCKED, NOTICE_MS)
            }
            is OrchEvent.GhostChanged -> {
                val was = _state.value.snapped
                set { it.copy(snapped = event.snapped, tooClose = event.tooClose) }
                if (event.snapped && !was) snack(SnackKind.SNAPPED, SNAPPED_MS)
            }
            is OrchEvent.PlaceRequested -> place(event)
            is OrchEvent.MusicianTapped -> musicianTapped(event.seatId)
            OrchEvent.NothingTapped -> {
                val s = _state.value
                when {
                    s.menuOpen -> set { it.copy(menuOpen = false) }
                    s.solo != null -> restore("tap-empty")
                    s.cardFamily != null -> set { it.copy(cardFamily = null) }
                }
            }
            is OrchEvent.FamilyMuteClicked -> {
                val f = event.family
                val now = _state.value.mutedFamilies.let { if (f in it) it - f else it + f }
                OrchLog.i("FMUTE ${f.id}=${if (f in now) 1 else 0}")
                set { it.copy(mutedFamilies = now) }
            }
            is OrchEvent.FamilyInfoClicked -> {
                val s = _state.value
                val open = s.cardShown == event.family
                OrchLog.i("INFO ${event.family.id} ${if (open) "close" else "open"}")
                set { it.copy(cardFamily = if (open) null else event.family, solo = if (open || it.solo?.family == event.family) it.solo else null) }
                if (open && s.solo?.family == event.family) restore("info-close")
            }
            is OrchEvent.MarkerChanged -> {
                step(if (event.set) PlaceInput.FloorTap(io.embodit.insideorchestra.domain.model.Vec3.ZERO) else PlaceInput.Replace)
                set { it.copy(marker = event.set) }
            }
            OrchEvent.PlaceAtMarkerClicked -> {
                val pose = OrchGraph.ghostPlacement?.invoke()
                OrchLog.i("CONFIRM pose=${pose != null} marker=${_state.value.marker}")
                if (flow.canConfirm && pose != null) place(OrchEvent.PlaceRequested(pose))
            }
            OrchEvent.ReplaceMarkerClicked -> {
                step(PlaceInput.Replace)
                set { it.copy(marker = false, snapped = false, tooClose = false) }
                effect(OrchUiEffect.ClearMarker)
            }
            OrchEvent.MoveOrchestraClicked -> moveOrchestra()
            OrchEvent.MoveCancelClicked -> {
                val before = flow
                step(PlaceInput.Cancel)
                if (before.placing && flow.locked) {
                    OrchLog.i("MOVE cancelled")
                    set { it.copy(room = Room.PLACED, pose = flow.pose, marker = false, placeMoving = false, snapped = false, tooClose = false) }
                }
            }
            is OrchEvent.PointerChanged -> set { it.copy(headPointer = event.head) }
            OrchEvent.SideRecallClicked -> {
                OrchLog.i("SIDE recall")
                set { it.copy(menuOpen = false, sideLost = false) }
                effect(OrchUiEffect.RecallSide)
            }
            is OrchEvent.SideLost -> set { it.copy(sideLost = event.lost) }
            OrchEvent.CardCloseClicked -> {
                restore("card-close")
                set { it.copy(cardFamily = null) }
            }
            is OrchEvent.FocusChanged -> set { it.copy(focusSeat = event.seatId) }
            is OrchEvent.NearChanged -> set { it.copy(near = event.near) }
            is OrchEvent.Moved -> {
                // only the panel's own actions ([Bring closer], [Back to place], debug) move a locked orchestra
                if (flow.locked) flow = flow.copy(pose = event.pose)
                set { it.copy(pose = event.pose) }
            }
            is OrchEvent.MovingChanged -> set { it.copy(moving = event.moving) }
            is OrchEvent.AudioLoaded -> set { it.copy(audio = if (event.ok) AudioState.READY else AudioState.FAILED) }
            OrchEvent.PlayClicked -> play("panel")
            OrchEvent.PauseClicked -> pause("panel")
            OrchEvent.RestartClicked -> {
                seek(0f)
                play("restart")
            }
            OrchEvent.AgainClicked -> {
                seek(0f)
                play("again")
            }
            is OrchEvent.SeekReleased -> {
                val s = _state.value
                seek(event.s)
                if (s.transport == Transport.DONE || s.transport == Transport.SEATED) set { it.copy(transport = Transport.PAUSED) }
            }
            is OrchEvent.FamilyChipClicked -> familyChip(event.family)
            OrchEvent.Turn45Clicked -> set { it.copy(turnSerial = it.turnSerial + 1) }
            OrchEvent.MuteClicked -> {
                val m = !_state.value.muted
                prefs.edit().putBoolean("muted", m).apply()
                OrchLog.i("MUTE mute=${if (m) 1 else 0}")
                set { it.copy(muted = m) }
            }
            OrchEvent.MoreClicked -> set { it.copy(menuOpen = !it.menuOpen) }
            OrchEvent.RetryAudioClicked -> {
                set { it.copy(audio = AudioState.LOADING) }
                effect(OrchUiEffect.AudioRetry)
            }
            OrchEvent.HomeClicked -> {
                set { it.copy(menuOpen = false) }
                effect(OrchUiEffect.Home)
            }
            OrchEvent.RestoreClicked -> restore("card")
            OrchEvent.PullClicked -> {
                val solo = _state.value.solo ?: return
                val seat = solo.seatId ?: nearestOf(solo)
                if (seat != null) effect(OrchUiEffect.Pull(seat))
            }
            is OrchEvent.CaptionsSelected -> {
                prefs.edit().putBoolean("captions", event.on).apply()
                set { it.copy(captionsOn = event.on, snack = if (!event.on && it.snack?.kind == SnackKind.CAPTION) null else it.snack) }
            }
            is OrchEvent.LanguageSelected -> {
                prefs.edit().putString("lang", event.language.name).apply()
                set { it.copy(language = event.language, english = englishFor(event.language)) }
            }
            is OrchEvent.ScaleSelected -> setScale(event.scale)
            OrchEvent.ReplaceClicked -> moveOrchestra()
            OrchEvent.ExitClicked -> {
                OrchLog.i("EXIT")
                effect(OrchUiEffect.AudioPause)
                effect(OrchUiEffect.ExitApp)
            }
            is OrchEvent.RoomResize ->
                set {
                    val r = it.room3
                    it.copy(
                        room3 =
                            r.copy(
                                length = (r.length + event.dLength).coerceIn(3f, 10f).round1(),
                                width = (r.width + event.dWidth).coerceIn(3f, 10f).round1(),
                                height = (r.height + event.dHeight).coerceIn(2.2f, 4f).round1(),
                            )
                    )
                }
            OrchEvent.WentBackground -> {
                val s = _state.value
                if (s.transport == Transport.PLAYING) pause("background")
                set { it.copy(background = true, focusSeat = null) }
            }
            OrchEvent.CameForeground -> set { it.copy(background = false, planesOn = if (it.room.placed) it.planesOn else true) }
            is OrchEvent.Debug -> debug(event.key, event.value)
        }
    }

    private fun Float.round1(): Float = Math.round(this * 10f) / 10f

    private fun sceneReady() {
        val s = _state.value
        if (s.room != Room.STARTING) return
        set { it.copy(room = if (it.planesBlocked) Room.PLACING else Room.FINDING) }
        armFloorTimeout(s.floorTimeoutS)
    }

    private fun armFloorTimeout(seconds: Float) {
        floorJob?.cancel()
        floorJob =
            viewModelScope.launch {
                delay((seconds * 1000f).toLong())
                val s = _state.value
                if (s.room == Room.FINDING && !s.floorKnown) {
                    OrchLog.i("FLOOR timeout ${OrchLog.f(seconds, 1)}s -> virtual room")
                    set { it.copy(virtualRoom = true, room = Room.PLACING) }
                    snack(SnackKind.VIRTUAL, NOTICE_MS)
                    armWallTimer()
                }
            }
    }

    private fun planesChanged(e: OrchEvent.PlanesChanged) {
        framesKnown = e.frames
        val s = _state.value
        set {
            it.copy(
                floorKnown = e.floor,
                virtualRoom = if (e.floor && !e.virtual) false else it.virtualRoom,
                room = if (it.room == Room.FINDING && e.floor) Room.PLACING else it.room,
                noWall = if (e.frames > 0) false else it.noWall,
            )
        }
        if (s.room == Room.FINDING && e.floor) armWallTimer()
    }

    /** S17: still no snap frame 8 s into placing -> the notice, once per placing. */
    private fun armWallTimer() {
        wallJob?.cancel()
        wallJob =
            viewModelScope.launch {
                delay(NO_WALL_AFTER_MS)
                val s = _state.value
                if (s.room == Room.PLACING && framesKnown == 0 && !s.forceWall) {
                    set { it.copy(noWall = true) }
                    if (!noWallShown) {
                        noWallShown = true
                        snack(SnackKind.NO_WALL, NOTICE_MS)
                    }
                }
            }
    }

    private fun place(e: OrchEvent.PlaceRequested) {
        val s = _state.value
        if (s.room != Room.PLACING) return
        val pose = e.pose
        if (pose == null) {
            OrchLog.i("PLACE refused (too close / no floor)")
            return
        }
        flow = PlaceState(io.embodit.insideorchestra.domain.usecase.PlaceMode.LOCKED, null, pose, moving = false)
        OrchLog.i("PLACE (locked) anchor=${OrchLog.pos(pose.anchor)} yaw=${OrchLog.f(Math.toDegrees(pose.yaw.toDouble()).toFloat(), 1)} scale=${pose.scale} snapped=${s.snapped}")
        set { it.copy(room = Room.PLACED, pose = pose, home = pose, transport = if (clock.t > 0.01f) Transport.PAUSED else Transport.SEATED, snapped = false, tooClose = false, noWall = false, planesOn = true, marker = false, placeMoving = false, snack = if (it.snack?.kind == SnackKind.SNAPPED || it.snack?.kind == SnackKind.NO_WALL) null else it.snack) }
        wallJob?.cancel()
        effect(OrchUiEffect.Recalibrate)
        planesJob?.cancel()
        planesJob =
            viewModelScope.launch {
                delay(PLANES_AFTER_PLACE_MS)
                if (_state.value.room.placed) set { it.copy(planesOn = false) }
            }
    }

    private fun musicianTapped(seatId: String) {
        val s = _state.value
        if (!s.room.placed) return
        if (s.menuOpen) {
            set { it.copy(menuOpen = false) }
            return
        }
        val seat = Orchestra.seat(seatId) ?: return
        val solo = s.solo
        if (solo != null && !solo.wholeFamily && solo.seatId == seat.id) {
            restore("same-musician")
            return
        }
        OrchLog.i("SOLO seat=${seat.id} stem=${seat.stem.id} family=${seat.family.id}")
        set { it.copy(solo = Solo(seat.family, setOf(seat.stem), seat.id, seat.instrument, wholeFamily = false), cardFamily = null) }
        autoPlay(s.transport)
    }

    private fun familyChip(f: Family) {
        val s = _state.value
        if (!s.room.placed) return
        val solo = s.solo
        if (solo != null && solo.wholeFamily && solo.family == f) {
            restore("chip")
            return
        }
        OrchLog.i("SOLO family=${f.id} stems=${f.stems.joinToString("+") { it.id }}")
        set { it.copy(solo = Solo(f, f.stems.toSet(), null, null, wholeFamily = true), menuOpen = false, cardFamily = null) }
        autoPlay(s.transport)
    }

    /** Only S3 starts playing by itself; S9 goes back to 0:00 first; S5 stays paused (design `S7.reach`). */
    private fun autoPlay(t: Transport) {
        when (t) {
            Transport.SEATED -> play("solo")
            Transport.DONE -> {
                seek(0f)
                play("solo")
            }
            else -> Unit
        }
    }

    private fun restore(why: String) {
        if (_state.value.solo == null) return
        OrchLog.i("SOLO none ($why)")
        set { it.copy(solo = null, cardFamily = null) }
    }

    private fun nearestOf(solo: Solo): String? = solo.stems.firstOrNull()?.let { st -> Orchestra.seats.filter { it.stem in solo.stems }.minByOrNull { it.z * it.z + it.x * it.x }?.id ?: st.id }

    private fun play(why: String) {
        val s = _state.value
        if (!s.room.placed) return
        if (s.transport == Transport.DONE) seek(0f)
        clock.start()
        OrchLog.i("PLAY ($why) from=${OrchLog.f(clock.t)} audio=${s.audio}")
        set { it.copy(transport = Transport.PLAYING) }
        effect(OrchUiEffect.Recalibrate)
        if (!s.freeze) effect(OrchUiEffect.AudioPlay(clock.t))
    }

    private fun pause(why: String) {
        clock.pause()
        OrchLog.i("PAUSE ($why) at=${OrchLog.f(clock.t)}")
        set { it.copy(transport = if (it.transport == Transport.PLAYING) Transport.PAUSED else it.transport, posS = clock.t) }
        effect(OrchUiEffect.AudioPause)
    }

    private fun seek(s: Float) {
        clock.seek(s)
        lastPass = piece.passAt(clock.t).index
        set { it.copy(posS = clock.t, pass = lastPass) }
        effect(OrchUiEffect.AudioSeek(clock.t))
    }

    private fun setScale(k: Float) {
        val scale = if (k < 1f) 0.5f else 1f
        prefs.edit().putFloat("scale", scale).apply()
        set {
            it.copy(
                scale = scale,
                pose = it.pose?.copy(scale = scale),
                home = it.home?.copy(scale = scale),
                wallMin = if (it.wallMin == 3.0f || it.wallMin == 1.5f) (if (scale < 1f) 1.5f else 3.0f) else it.wallMin,
            )
        }
        effect(OrchUiEffect.Recalibrate)
    }

    /** [Move orchestra] (side panel / More menu): unlocks into placing, the ghost starting where it stood. */
    private fun moveOrchestra() {
        val s = _state.value
        if (!s.room.placed || s.pose == null) {
            replace()
            return
        }
        if (flow.locked.not()) flow = PlaceState(io.embodit.insideorchestra.domain.usecase.PlaceMode.LOCKED, null, s.pose)
        val pose = s.pose
        step(PlaceInput.Move)
        if (s.transport == Transport.PLAYING) pause("move")
        OrchLog.i("MOVE start from=${OrchLog.pos(pose.anchor)}")
        noWallShown = false
        set { it.copy(room = Room.PLACING, solo = null, cardFamily = null, menuOpen = false, focusSeat = null, near = null, moving = false, planesOn = true, snapped = false, tooClose = false, forceWall = false, marker = true, placeMoving = true) }
        effect(OrchUiEffect.StartMove(pose))
        armWallTimer()
    }

    private fun replace() {
        step(PlaceInput.Reset)
        val s = _state.value
        if (s.transport == Transport.PLAYING) pause("replace")
        OrchLog.i("REPLACE")
        noWallShown = false
        set { it.copy(room = Room.PLACING, solo = null, cardFamily = null, menuOpen = false, focusSeat = null, near = null, moving = false, planesOn = true, snapped = false, tooClose = false, forceWall = false, marker = false) }
        armWallTimer()
    }

    // =========================================================================================
    // debug hooks (design `debugHooks`)
    // =========================================================================================

    private fun debug(key: String, value: String) {
        val on = value != "0" && value != "off" && value != "false"
        when (key) {
            "state" -> debugState(value.uppercase(Locale.US))
            "place" -> {
                if (_state.value.room != Room.PLACING) debugState("S2")
                if (value == "wall") {
                    // the snap needs a ghost, and the ghost needs a marker (v0.1.1)
                    OrchGraph.debugMarker?.invoke(2.5f)
                    set { it.copy(forceWall = true) }
                    OrchLog.i("CMD place=wall (ghost snaps to the longest wall ahead)")
                } else {
                    val pose = OrchGraph.autoPlacement?.invoke(_state.value.scale)
                    if (pose == null) OrchLog.i("CMD place=auto ignored: no head yet") else place(OrchEvent.PlaceRequested(pose))
                }
            }
            "near" -> set { it.copy(forcedNear = on) }
            "floor_timeout" -> value.toFloatOrNull()?.let { t ->
                set { it.copy(floorTimeoutS = t) }
                if (_state.value.room == Room.FINDING) armFloorTimeout(t)
            }
            "wallmin" -> value.toFloatOrNull()?.let { m -> set { it.copy(wallMin = m) } }
            "play" -> if (on) {
                ensurePlaced()
                play("debug")
            } else pause("debug")
            "pause" -> if (on) pause("debug")
            "pos" -> value.toFloatOrNull()?.let { t ->
                ensurePlaced()
                seek(t)
                // QA frames inside a caption window show that caption (a user's seek never replays skipped ones)
                val pass = piece.passAt(clock.t)
                if (pass.caption != null && _state.value.captionsOn && clock.t - pass.startS < CAPTION_MS / 1000f) showCaption(pass.index, pass.focus)
            }
            "done" -> if (on) {
                ensurePlaced()
                clock.seek(piece.durationS)
                set { it.copy(transport = Transport.DONE, posS = piece.durationS) }
                effect(OrchUiEffect.AudioPause)
                effect(OrchUiEffect.Finale)
            }
            "solo" -> {
                ensurePlaced()
                val f = Family.of(value)
                val stem = Stem.of(value)
                when {
                    value == "none" -> restore("debug")
                    f != null -> set { it.copy(solo = Solo(f, f.stems.toSet(), null, null, wholeFamily = true)) }
                    stem != null -> {
                        val seat = Orchestra.seats.first { it.stem == stem }
                        set { it.copy(solo = Solo(stem.family, setOf(stem), seat.id, seat.instrument, wholeFamily = false)) }
                    }
                    else -> Orchestra.seat(value)?.let { musicianTapped(it.id) }
                }
            }
            "item" -> {
                val solo = _state.value.solo ?: return
                val seat = Orchestra.seats.firstOrNull { it.instrument == value && it.family == solo.family }
                set { it.copy(solo = solo.copy(item = value, seatId = seat?.id ?: solo.seatId, stems = if (seat != null) setOf(seat.stem) else solo.stems, wholeFamily = seat == null && solo.wholeFamily)) }
            }
            "hover" -> {
                val seat = value.substringBefore(',').let { if (it == "0" || it == "none") null else Orchestra.seat(it)?.id }
                set { it.copy(forcedHover = seat, focusSeat = seat) }
            }
            "focus" -> Unit
            "pull" -> Orchestra.seat(value)?.let { ensurePlaced(); effect(OrchUiEffect.Pull(it.id)) }
            "orbit" -> value.toFloatOrNull()?.let { ensurePlaced(); effect(OrchUiEffect.Orbit(it)) }
            "push" -> value.toFloatOrNull()?.let { ensurePlaced(); effect(OrchUiEffect.Push(it)) }
            "home" -> if (on) effect(OrchUiEffect.Home)
            "mute" -> {
                prefs.edit().putBoolean("muted", on).apply()
                OrchLog.i("MUTE mute=${if (on) 1 else 0}")
                set { it.copy(muted = on) }
            }
            "stand" -> {
                ensurePlaced()
                effect(OrchUiEffect.Stand(if (value == "podium") null else Stem.of(value)))
            }
            "scale" -> setScale(if (value == "2" || value == "0.5") 0.5f else 1f)
            "lang" -> onEvent(OrchEvent.LanguageSelected(when (value) { "en" -> LanguagePref.EN; "zh" -> LanguagePref.ZH; else -> LanguagePref.SYSTEM }))
            "captions" -> onEvent(OrchEvent.CaptionsSelected(on))
            "coach" -> if (on) snack(SnackKind.COACH, if (_state.value.freeze) 600_000L else COACH_MS)
            "coach_reset" -> {
                prefs.edit().putBoolean("coachSeen", false).apply()
                playedS = 0f
                set { it.copy(coachSeen = false) }
            }
            "caption" -> value.toIntOrNull()?.let { p -> piece.passes.getOrNull(p)?.let { showCaption(it.index, it.focus) } }
            "audiofail" -> if (on) {
                OrchLog.i("AUDIO forced failure")
                effect(OrchUiEffect.AudioPause)
                set { it.copy(audio = AudioState.FAILED) }
            }
            "retry" -> onEvent(OrchEvent.RetryAudioClicked)
            "freeze" -> {
                OrchLog.i("FREEZE ${if (on) 1 else 0}")
                // a snackbar whose timer ran out while frozen goes now
                set { it.copy(freeze = on, snack = if (!on) null else it.snack) }
                if (on) effect(OrchUiEffect.AudioPause) else if (_state.value.transport == Transport.PLAYING) effect(OrchUiEffect.AudioPlay(clock.t))
            }
            "menu" -> set { it.copy(menuOpen = on) }
            "fmute" -> Family.of(value)?.let { onEvent(OrchEvent.FamilyMuteClicked(it)) } ?: set { it.copy(mutedFamilies = emptySet()) }
            "info" -> Family.of(value)?.let { onEvent(OrchEvent.FamilyInfoClicked(it)) } ?: set { it.copy(cardFamily = null) }
            "atmarker", "confirm" -> onEvent(OrchEvent.PlaceAtMarkerClicked)
            "move" -> onEvent(OrchEvent.MoveOrchestraClicked)
            "cancel" -> onEvent(OrchEvent.MoveCancelClicked)
            "unmark" -> onEvent(OrchEvent.ReplaceMarkerClicked)
            "recall" -> onEvent(OrchEvent.SideRecallClicked)
            "sidedrag" -> {
                val (dx, dy) = value.split(',').map { it.trim().toFloatOrNull() ?: 0f }.let { (it.getOrElse(0) { 0f }) to (it.getOrElse(1) { 0f }) }
                OrchGraph.sideDrag?.invoke(dx, dy)
                OrchGraph.sideDrag?.invoke(Float.NaN, Float.NaN)
            }
            "marker" -> {
                if (_state.value.room != Room.PLACING) debugState("S2")
                val d = value.toFloatOrNull()?.takeIf { it > 0.5f } ?: 2.0f
                OrchLog.i("CMD marker ahead=${OrchLog.f(d)} set=${OrchGraph.debugMarker?.invoke(d)}")
            }
            "replace" -> if (on) replace()
            "turn45" -> onEvent(OrchEvent.Turn45Clicked)
            "exit" -> if (on) onEvent(OrchEvent.ExitClicked)
            else -> OrchLog.i("CMD unknown key=$key value=$value")
        }
    }

    /** Debug keys that need a placed orchestra place it ahead first. */
    private fun ensurePlaced() {
        if (_state.value.room.placed) return
        if (_state.value.room != Room.PLACING) debugState("S2")
        OrchGraph.autoPlacement?.invoke(_state.value.scale)?.let { place(OrchEvent.PlaceRequested(it)) }
    }

    private fun debugState(id: String) {
        when (id) {
            "S0" -> set { it.copy(room = Room.STARTING) }
            "S1" -> set { it.copy(room = Room.FINDING, virtualRoom = false) }
            "S2" -> set { it.copy(room = Room.PLACING, floorKnown = true, solo = null, menuOpen = false, forceWall = false) }
            "S13" -> {
                set { it.copy(room = Room.PLACING, virtualRoom = true, floorKnown = true, solo = null, forceWall = false) }
                snack(SnackKind.VIRTUAL, NOTICE_MS)
            }
            "S14" -> onEvent(OrchEvent.PlaneTrackingFailed)
            "S17" -> {
                set { it.copy(room = Room.PLACING, floorKnown = true, wallMin = 99f, noWall = true, forceWall = false) }
                snack(SnackKind.NO_WALL, NOTICE_MS)
            }
            else -> OrchLog.i("CMD state=$id not reachable directly (use place / play / solo / ...)")
        }
    }

    fun dumpJson(): String {
        val s = _state.value
        return JSONObject()
            .put("state", s.stateId)
            .put("overlays", s.overlays)
            .put("room", s.room.name)
            .put("transport", s.transport.name)
            .put("pos", OrchLog.f(clock.t))
            .put("pass", s.pass)
            .put("virtualRoom", s.virtualRoom)
            .put("solo", s.solo?.let { "${it.family.id}:${it.stems.joinToString("+") { st -> st.id }}:${it.item}" } ?: JSONObject.NULL)
            .put("focus", s.focusSeat ?: JSONObject.NULL)
            .put("near", s.near?.let { if (it.podium) "podium" else it.stem?.id } ?: JSONObject.NULL)
            .put("scale", s.scale)
            .put("english", s.english)
            .put("muted", s.muted)
            .put("audio", s.audio.name)
            .put("moved", s.moved)
            .put("snack", s.snack?.kind?.name ?: JSONObject.NULL)
            .toString()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = OrchViewModel(context.applicationContext) as T
    }

    companion object {
        private const val CAPTION_MS = 6_000L
        private const val COACH_MS = 8_000L
        private const val NOTICE_MS = 4_000L
        private const val SNAPPED_MS = 800L
        private const val COACH_AFTER_S = 3f
        private const val NO_WALL_AFTER_MS = 8_000L
        private const val PLANES_AFTER_PLACE_MS = 15_000L

        private fun loadPiece(context: Context): Piece =
            runCatching {
                val json = context.assets.open("piece.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val bin = context.assets.open("parts.bin").use { it.readBytes() }
                Piece.parse(json, bin)
            }.onFailure { OrchLog.e("PIECE load failed: $it", it) }.getOrDefault(Piece.EMPTY)
    }
}
