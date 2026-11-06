package io.embodit.insideorchestra.ui.orch.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.domain.model.NearListen
import io.embodit.insideorchestra.domain.model.Orchestra
import io.embodit.insideorchestra.domain.model.PointerRay
import io.embodit.insideorchestra.domain.model.Quat4
import io.embodit.insideorchestra.domain.model.Seat
import io.embodit.insideorchestra.domain.model.Stem
import io.embodit.insideorchestra.domain.model.Vec3
import io.embodit.insideorchestra.domain.usecase.HeadFrame
import io.embodit.insideorchestra.domain.usecase.MergedWall
import io.embodit.insideorchestra.domain.usecase.OrchPose
import io.embodit.insideorchestra.domain.usecase.PlacementUseCase
import io.embodit.insideorchestra.domain.usecase.SnapFrame
import io.embodit.insideorchestra.domain.usecase.WallSnapUseCase
import io.embodit.insideorchestra.platform.OrchGraph
import io.embodit.insideorchestra.platform.OrchLog
import io.embodit.insideorchestra.platform.audio.OrchestraAudio
import io.embodit.insideorchestra.platform.sense.PlaneTracker
import io.embodit.insideorchestra.platform.tracking.HeadPose
import io.embodit.insideorchestra.ui.orch.AudioState
import io.embodit.insideorchestra.ui.orch.Near
import io.embodit.insideorchestra.ui.orch.OrchEvent
import io.embodit.insideorchestra.ui.orch.OrchUiState
import io.embodit.insideorchestra.ui.orch.OrchViewModel
import io.embodit.insideorchestra.ui.orch.Room
import io.embodit.insideorchestra.ui.orch.Transport
import io.embodit.insideorchestra.ui.orch.debug.OrchProbe
import io.embodit.insideorchestra.ui.window.WindowCtl
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: OrchScene? = null
}

/** The one per-frame ECS system of the app. */
class OrchFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the orchestra (23 Tripo musicians, rings, zones, podium, ten stem sources), its placing
 * ghost and snap outlines, the virtual room, the name tag, the ten stems' audio and the one small Stage chip
 * ("打开面板" / "平视前方"); every other 2D control lives in the system window (v0.1.5, D8 / D21). Compose state arrives through [render] (idempotent); everything per frame
 * (ghost, pointing and focus, stick, drag, pull / home animations, near listening, BeatMap rings and sway, audio
 * mix and clamps) happens in [onFrame], never through recomposition. Everything started in [attach] is stopped in
 * [detach]. Main thread only.
 */
class OrchScene(private val vm: OrchViewModel, private val sink: (OrchEvent) -> Unit) : OrchProbe.SceneProbe {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    /** The piece clock as seen by the BeatMap dots of the panel (refreshed at 10 Hz; read in draw only). */
    val beatTime = mutableFloatStateOf(0f)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = OrchGraph.planes
    private val headTracker = OrchGraph.head
    private val eyes = OrchGraph.eyes
    private val controller = OrchGraph.controller
    private val snapRule = WallSnapUseCase()
    private val placeRule = PlacementUseCase()
    private val nearRule = NearListen()
    private val audio = OrchestraAudio()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var materials: SceneMaterials? = null
    private var shared: SharedResources? = null
    /**
     * D24 (v0.1.5): no colliders on real planes or a room shell any more; one app-owned invisible floor slab under the
     * user catches the pinch / click that sets the marker, only while placing.
     */
    private var floorCatcher: Entity? = null
    private var keyLight: Entity? = null
    private var rig: PanelRig? = null
    private var orch: OrchestraView? = null
    private val templates = HashMap<String, Entity>()
    private var shelf: Entity? = null
    private var tag: Entity? = null
    private var tagTexture: TextureResource? = null
    private var tagMaterial: Material? = null
    private var tagKey: String? = null
    private var tagMesh: MeshResource? = null
    private var outlines = ArrayList<Entity>()
    private var outlineMeshes: Pair<MeshResource, MeshResource>? = null
    private var vroomGrid: Entity? = null
    private var vroomWall: Entity? = null
    private var vroomKey: String? = null
    private var attached = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = OrchUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var headValidSinceMs = NEVER
    private var autoPlaceSent = false
    private var inBackground = false
    private var lastFrameMs = NEVER
    private var frameGapMs = 0L
    private var lastLogMs = 0L
    private var lastBeatMs = 0L

    // ---- room
    private var lastPlaneCount = -1
    private var lastPlanesEmitMs = 0L
    private var realFloorY: Float? = null
    private var floorY: Float? = null
    private var virtualBase: Pair<Vec3, Float>? = null
    private var frames: List<SnapFrame> = emptyList()
    private var framesKey = ""
    private var framesSent = -1
    private var planesFailed = false

    // ---- ghost
    private var ghostAnchor: Vec3? = null
    /** v0.1.1 (D1 "move to marker"): the floor marker set with the ray / eyes; the ghost stands here, nowhere else. */
    private var marker: Vec3? = null
    private var cursor: Entity? = null
    private var markerDot: Entity? = null
    private var reportedHeadPointer: Boolean? = null
    private var ghostYaw = 0f
    private var userTurned = false
    private var held: SnapFrame? = null
    private var shownPose: OrchPose? = null
    private var tooClose = false
    private var reportedSnap = false
    private var reportedNear = false
    private var stickRate = 0f
    private var turnSeen = 0
    private var turnFrom = 0f
    private var turnTo = 0f
    private var turnStartMs = NEVER
    private var triggerDown = false

    // ---- the placed orchestra while it moves (S19)
    private var livePose: OrchPose? = null
    private var movingReported = false
    private var orbitRate = 0f
    private var pushRate = 0f
    private var dragging = false
    private var dragGrab: Vec3? = null
    private var dragEndMs = NEVER
    private var animFrom: OrchPose? = null
    private var animTo: OrchPose? = null
    private var animStartMs = NEVER

    // ---- pointing
    private var lastCtrlMs = NEVER
    private var lastCtrlRay: PointerRay? = null
    private var pointerSource = "head"
    private var pending: String? = null
    private var pendingSinceMs = 0L
    private var focus: String? = null
    private var lastHitMs = NEVER
    private var pointedSeat: String? = null
    private var reportedNearKey: Any? = null
    private var finaleMs = NEVER
    private var captionSeen = 0
    private var captionStartMs = NEVER

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // an entity added to the SpatialView is not at the Stage origin: move the root once so its space is Stage space
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        OrchLog.i("STAGE root offset=${OrchLog.pos(offset.domain())}")
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        val (cw, ch) = PanelSpec.CHIP_SIZE
        rig.head(PanelSpec.CHIP, CHIP_HEAD_OFFSET, PanelSpec.CHIP_SCALE, cw, ch)
        headTracker.start()
        eyes.start()
        controller.start()
        ActiveScene.scene = this
        OrchGraph.autoPlacement = { scale -> autoPose(scale) }
        OrchGraph.ghostPlacement = { placeablePose() }
        OrchGraph.debugMarker = { d -> debugMarker(d) }
        bindPanels(attachments)
        syncPlanes(ui)
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) OrchLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /** Materials, meshes, then the orchestra one seat per paced step, then the 14 models, then the ten stems. */
    private suspend fun build(root: Entity, t0: Long) {
        val context = OrchGraph.appContext
        val sh = SharedResources()
        shared = sh
        val soft = runCatching { sh.keep(TextureResource.create(SceneArt.softRect())) }.getOrNull()
        val glow = runCatching { sh.keep(TextureResource.create(SceneArt.glow())) }.getOrNull()
        val m = SceneMaterials(sh, soft, glow)
        materials = m
        frame()
        floorCatcher =
            Entity().named("floorCatcher").also {
                // gate:allow geometry invisible floor slab (no mesh): the pinch target for the placing marker
                it.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(com.pico.spatial.core.math.Vector3(CATCHER_M, 0.02f, CATCHER_M))), physicsMaterial = PhysicsMaterialResource()))
                it.components.set(InteractableComponent())
                root.addChild(it)
                it.enabled = false
            }
        // B24: a warm key light from above-front-left with shadows (MR; VR adds the hall's own lights)
        keyLight =
            Entity().named("keyLight").also {
                it.components.set(DirectionalLightComponent(KEY_COLOR, KEY_LUX, true))
                root.addChild(it)
                it.setLocal(rotation = Quat4.fromTo(Vec3(0f, 0f, -1f), KEY_DIR.normalized()))
            }
        frame()
        val ring = sh.keep(Meshes.ring(0.19f, 0.275f, "ring"))
        frame()
        val zone = sh.keep(Meshes.floorQuad("zone"))
        frame()
        val glowMesh = sh.keep(Meshes.ring(0.0f, 0.4f, "glow"))
        frame()
        val podium = sh.keep(MeshFactory.cylinder(Orchestra.PODIUM_R, Orchestra.PODIUM_H, "podium", 32))
        frame()
        val podiumTop = sh.keep(Meshes.discUv(Orchestra.PODIUM_R * 0.96f, Orchestra.PODIUM_H + 0.002f, "podiumTop"))
        frame()
        val f = Orchestra
        val foot = sh.keep(Meshes.rectOutline(f.FOOT_MIN_X, f.FOOT_BACK_Z, f.FOOT_MAX_X, f.FOOT_FRONT_Z, 0.04f, 0.14f, 0.08f, "foot"))
        frame()
        val footSolid = sh.keep(Meshes.rectOutline(f.FOOT_MIN_X, f.FOOT_BACK_Z, f.FOOT_MAX_X, f.FOOT_FRONT_Z, 0.04f, 0f, 0f, "footSolid"))
        frame()
        val outline = sh.keep(Meshes.rectOutline(f.FOOT_MIN_X, f.FOOT_BACK_Z, f.FOOT_MAX_X, f.FOOT_FRONT_Z, 0.025f, 0.14f, 0.08f, "outline"))
        frame()
        val outlineSolid = sh.keep(Meshes.rectOutline(f.FOOT_MIN_X, f.FOOT_BACK_Z, f.FOOT_MAX_X, f.FOOT_FRONT_Z, 0.025f, 0f, 0f, "outlineSolid"))
        outlineMeshes = outline to outlineSolid
        frame()
        val dot = sh.keep(MeshFactory.disc(0.08f, "podiumDot"))
        frame()
        val podiumMat = context?.let { c -> runCatching { m.textured(sh.keep(TextureResource.create(SceneArt.podiumLabel(c.resources)))) }.getOrNull()?.let { sh.keep(it) } }
        val view = OrchestraView(root, m, OrchMeshes(ring, zone, glowMesh, podium, podiumTop, foot, footSolid, outline, outlineSolid, dot), podiumMat)
        view.setShown(false)
        orch = view
        frame()
        while (!view.complete) {
            view.addSeat()
            frame()
        }
        for (k in 0 until WallSnapUseCase.MAX_FRAMES + 1) {
            val e = ModelEntity(outline, m.outline).named("snapOutline$k")
            root.addChild(e)
            e.enabled = false
            outlines += e
        }
        // placing cursor (where the ray / eyes meet the floor) and the marker the user set (v0.1.1)
        cursor = ModelEntity(sh.keep(Meshes.ring(0.10f, 0.14f, "cursor")), m.tape).named("cursor").also { root.addChild(it); it.enabled = false }
        markerDot = ModelEntity(sh.keep(Meshes.ring(0.0f, 0.12f, "marker")), m.podiumDot).named("marker").also { root.addChild(it); it.enabled = false }
        tagMesh = sh.keep(Meshes.quadUv("tag"))
        frame()
        attached = true
        OrchLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(OrchEvent.SceneReady)
        // the 14 models: one at a time in the background order of the seats, each cloned onto its seats
        val sh2 = Entity().named("shelf")
        root.addChild(sh2)
        sh2.enabled = false
        shelf = sh2
        for (model in Orchestra.models) {
            val tm = SystemClock.elapsedRealtime()
            val entity = runCatching { Entity.loadSuspend("asset://models/$model.glb") }.onFailure { OrchLog.e("MODEL $model load failed: $it", it) }.getOrNull()
            if (entity == null || !attached) continue
            sh2.addChild(entity.named("template$model"))
            templates[model] = entity
            for (v in view.seats.filter { it.seat.model == model }) {
                val clone = runCatching { entity.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true)) }.getOrNull() ?: continue
                view.attachModel(v, clone)
                frame()
            }
            OrchLog.i("MODEL $model ready in ${SystemClock.elapsedRealtime() - tm} ms")
            logModelAlpha(model)
        }
        if (hallLit) view.setEnvironmentIbl(VR_ENV_IBL)
        OrchLog.i("MODELS all ready ${templates.size}/${Orchestra.models.size} in ${SystemClock.elapsedRealtime() - t0} ms")
        loadAudio(false)
    }

    private suspend fun loadAudio(forceFail: Boolean) {
        val view = orch ?: return
        val ok = audio.load(view.sources, vm.piece.id, forceFail)
        if (attached) sink(OrchEvent.AudioLoaded(ok))
        recalibrate()
        if (ok && ui.transport == Transport.PLAYING && !ui.freeze) audio.play(vm.clock.t)
    }

    private suspend fun frame() {
        delay(if (frameGapMs > FAST_FRAME_MS) SLOW_STEP_MS else FRAME_MS)
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        OrchGraph.autoPlacement = null
        OrchGraph.ghostPlacement = null
        OrchGraph.debugMarker = null
        main.removeCallbacksAndMessages(null)
        audio.release()
        planeTracker.stop()
        headTracker.stop()
        eyes.stop()
        controller.stop()
        panelShown.clear()
        runCatching { rig?.release() }
        orch?.destroy()
        outlines.forEach { runCatching { it.destroy(true) } }
        outlines.clear()
        templates.clear()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        runCatching { tagTexture?.close() }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        floorCatcher = null
        keyLight = null
        rig = null
        orch = null
        tag = null
        tagKey = null
        vroomGrid = null
        vroomWall = null
        vroomKey = null
        hasHead = false
        headValidSinceMs = NEVER
        autoPlaceSent = false
        lastPlaneCount = -1
        OrchLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> entities
    // =========================================================================================

    fun render(state: OrchUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        syncPlanes(state)
        if (!attached) return
        val view = orch ?: return
        val placing = state.room == Room.PLACING
        val placed = state.room.placed && state.pose != null
        view.setShown((placing && floorY != null && marker != null) || placed)
        if (placed) {
            if (view.isGhost) view.setGhost(false)
            if (livePose == null && (force || previous.pose != state.pose || previous.room != state.room)) view.place(state.pose!!)
        } else {
            endMove(commit = false)
        }
        if (!placing) {
            outlines.forEach { it.enabled = false }
            if (previous.room == Room.PLACING) resetGhost()
        } else if (previous.room != Room.PLACING) {
            resetGhost()
            pendingMove?.let {
                pendingMove = null
                applyMove(it)
            }
        }
        if (state.turnSerial != turnSeen) {
            turnSeen = state.turnSerial
            if (placing) {
                userTurned = true
                turnFrom = ghostYaw
                turnTo = ghostYaw - (PI / 4).toFloat()
                turnStartMs = SystemClock.elapsedRealtime()
            }
        }
        if (state.captionSerial != captionSeen) {
            captionSeen = state.captionSerial
            captionStartMs = SystemClock.elapsedRealtime()
        }
        if (previous.scale != state.scale || previous.wallMin != state.wallMin || previous.forceWall != state.forceWall || previous.room3 != state.room3 || previous.virtualRoom != state.virtualRoom) framesKey = ""
        floorCatcher?.enabled = placing
    }

    fun onForeground(foreground: Boolean) {
        inBackground = !foreground
        // D21: the side panel stays where it was (world fixed); nothing re-parks on resume
    }

    private fun syncPlanes(state: OrchUiState) {
        if (stageRoot == null) return
        val want = state.planesOn && !state.background && !state.planesBlocked
        if (want && !planeTracker.running) {
            // D24: the planes only tell the floor height and the walls to snap to; nothing on them takes taps or rays
            val ok = planeTracker.start(PlaneTracker.Listener { _, _, _ -> })
            if (!ok && !planesFailed) {
                planesFailed = true
                sink(OrchEvent.PlaneTrackingFailed)
            }
        } else if (!want && planeTracker.running) {
            planeTracker.pause()
        }
    }

    private fun resetGhost() {
        ghostAnchor = null
        if (marker != null) {
            marker = null
            sink(OrchEvent.MarkerChanged(false))
        }
        cursor?.enabled = false
        markerDot?.enabled = false
        userTurned = false
        held = null
        shownPose = null
        turnStartMs = NEVER
        stickRate = 0f
    }

    // =========================================================================================
    // effects from the ViewModel
    // =========================================================================================

    fun audioPlay(fromS: Float) {
        if (audio.status == OrchestraAudio.Status.READY && ui.audio != AudioState.FAILED) audio.play(fromS)
    }

    fun audioPause() = audio.pause()

    fun audioSeek(s: Float) = audio.seek(s)

    /** v0.1.5: a new piece: the stems are released and the new ones loaded (the ViewModel already reset the clock). */
    fun loadPiece(id: String) {
        scope.launch {
            audio.unloadPiece()
            OrchLog.i("AUDIO piece=$id")
            loadAudio(false)
        }
    }

    /**
     * v0.1.5 "回到指挥台" (user 2026-11-05: "离近了就没法回到指挥台"): the orchestra slides so the user stands on the
     * podium centre again, the players in front along the current level heading.
     */
    fun toPodium() {
        val pose = currentPose() ?: return
        if (!hasHead) return
        val to = HeadFrame.podiumAt(head.position, head.rotation, pose.anchor.y, pose.scale)
        OrchLog.i("PODIUM to anchor=${OrchLog.pos(to.anchor)} yaw=${OrchLog.f(Math.toDegrees(to.yaw.toDouble()).toFloat(), 1)}")
        animate(to)
    }

    fun audioRetry() {
        scope.launch {
            audio.unloadPiece()
            loadAudio(false)
        }
    }

    fun finale() {
        finaleMs = SystemClock.elapsedRealtime()
    }

    /** Podium balance from the current ear height (design `audio.balance`). */
    fun recalibrate() {
        val pose = currentPose() ?: return
        if (!hasHead) return
        val ear = pose.toStage(Vec3.ZERO) + Vec3(0f, head.position.y - pose.anchor.y, 0f)
        val d = Stem.entries.associateWith { s -> pose.toStage(Vec3(s.sourceX, Orchestra.SOURCE_Y, s.sourceZ)).distanceTo(ear) }
        val max = d.values.maxOrNull() ?: return
        // v0.1.4: linear and never below 0.5 (was squared: the nearest stems sat at ~0.1, the whole band far too quiet)
        audio.setBalance(d.mapValues { (_, v) -> maxOf(BALANCE_FLOOR, v / max) })
    }

    /** [Pull] (I22): [seatId] 0.8 m straight ahead of the head, the orchestra turned so it faces the user's side. */
    fun pull(seatId: String) {
        val pose = currentPose() ?: return
        val seat = Orchestra.seat(seatId) ?: return
        if (!hasHead) return
        // v0.1.1 (D1): along the line from the user to that musician, not the head's direction
        val seatAt = pose.toStage(Vec3(seat.x, 0f, seat.z))
        val toSeat = Vec3(seatAt.x - head.position.x, 0f, seatAt.z - head.position.z).let { if (it.length < 1e-3f) Vec3(0f, 0f, -1f) else it.normalized() }
        val target = Vec3(head.position.x, pose.anchor.y, head.position.z) + toSeat * PULL_M
        var best: OrchPose? = null
        var bestScore = -Float.MAX_VALUE
        var a = 0
        while (a < 360) {
            val yaw = Math.toRadians(a.toDouble()).toFloat()
            val r = Quat4.axisAngle(Vec3.UP, yaw)
            // origin so that the seat lands on the target
            val origin = target - r.rotate(Vec3(seat.x, 0f, seat.z) * pose.scale)
            val p = OrchPose(origin + r.rotate(Orchestra.ANCHOR * pose.scale), yaw, pose.scale)
            val clear = placeRule.clearance(p, head.position)
            val front = r.rotate(Quat4.axisAngle(Vec3.UP, seat.yaw).rotate(Vec3(0f, 0f, 1f)))
            val toUser = (Vec3(head.position.x, 0f, head.position.z) - Vec3(target.x, 0f, target.z)).normalized()
            val score = (if (clear >= PlacementUseCase.NEAR_M - 0.02f) 10f else clear) + front.dot(toUser)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
            a += 5
        }
        val p = best ?: return
        OrchLog.i("PULL seat=$seatId to=${OrchLog.pos(target)} clear=${OrchLog.f(placeRule.clearance(p, head.position))}")
        animate(placeRule.pushClear(p, head.position).first)
    }

    fun goHome() {
        val home = ui.home ?: return
        OrchLog.i("HOME")
        animate(home)
    }

    /** Debug `orbit=<deg>`: the orchestra turns about the user's vertical. */
    fun orbit(degrees: Float) {
        val pose = currentPose() ?: return
        if (!hasHead) return
        animate(orbitPose(pose, Math.toRadians(degrees.toDouble()).toFloat()))
    }

    /** Debug `push=<m>`: farther (positive) / nearer (negative) along the user -> orchestra line. */
    fun push(metres: Float) {
        val pose = currentPose() ?: return
        if (!hasHead) return
        val dir = Vec3(pose.center.x - head.position.x, 0f, pose.center.z - head.position.z).normalized()
        var p = pose.copy(anchor = pose.anchor + dir * metres)
        if (placeRule.clearance(p, head.position) < PlacementUseCase.NEAR_M) p = placeRule.pushClear(p, head.position).first
        animate(p)
    }

    /** Debug `stand=<stem|podium>`: the head lands on that stem's listening site (the orchestra moves, the head does not). */
    fun stand(stem: Stem?) {
        val pose = currentPose() ?: return
        if (!hasHead) return
        val (x, z) = NearListen.standSite(stem)
        val site = pose.toStage(Vec3(x, 0f, z))
        val p = pose.copy(anchor = pose.anchor + Vec3(head.position.x - site.x, 0f, head.position.z - site.z))
        OrchLog.i("STAND ${stem?.id ?: "podium"} site=(${OrchLog.f(x)}, ${OrchLog.f(z)})")
        livePose = null
        sink(OrchEvent.Moved(p))
        orch?.place(p)
    }

    private fun animate(to: OrchPose) {
        val from = currentPose() ?: return
        animFrom = from
        animTo = to
        animStartMs = SystemClock.elapsedRealtime()
        livePose = from
        setMoving(true)
    }

    private fun orbitPose(pose: OrchPose, dYaw: Float): OrchPose {
        val pivot = Vec3(head.position.x, pose.anchor.y, head.position.z)
        val r = Quat4.axisAngle(Vec3.UP, dYaw)
        return OrchPose(pivot + r.rotate(pose.anchor - pivot), pose.yaw + dYaw, pose.scale)
    }

    private fun currentPose(): OrchPose? = livePose ?: ui.pose?.takeIf { ui.room.placed }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == NEVER) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.25f)
        if (lastFrameMs != NEVER) frameGapMs = now - lastFrameMs
        lastFrameMs = now
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        val state = ui
        // D9: the system window opens square to the gaze, so every open waits for a level head (native-window-move §7.9)
        val valid = hasHead && head.plausible
        WindowCtl.stepGate(now, if (valid) HeadFrame.pitchDeg(head.rotation) else null)
        chipFrame(rig, now)
        if (!attached) return

        vm.tick(dt, audio.medianS)
        pollPlanes(now)
        syncFloor(state)
        syncVirtualRoom(state)
        syncHall(state, now, dt)
        autoPlaceFrame(state, now, valid)
        if (state.room == Room.PLACING) {
            ghostFrame(state, now, dt)
            triggerFrame()
        } else {
            triggerDown = false
        }
        if (state.room.placed && state.pose != null) {
            moveFrame(state, now, dt)
            pointFrame(state, now)
            nearFrame(state, now)
            musiciansFrame(state, now)
        } else {
            orch?.glowAt(null)
            tag?.enabled = false
        }
        audioFrame(state, dt)
        if (now - lastBeatMs >= BEAT_MS) {
            lastBeatMs = now
            val t = if (state.transport == Transport.PLAYING) vm.clock.t else -1f
            if (beatTime.floatValue != t) beatTime.floatValue = t
        }
        if (now - lastLogMs >= LOG_MS) {
            lastLogMs = now
            logLine(state)
        }
        rig.faceViewer(head.position)
    }

    /**
     * The Stage chip (head-locked, 18 degrees below the view centre): "打开面板" while the window is closed or
     * minimised, "平视前方 / 面板就出来" while an open waits for a level head. Nothing else 2D lives in the Stage.
     */
    private fun chipFrame(rig: PanelRig, now: Long) {
        val slot = rig[PanelSpec.CHIP] ?: return
        val want = WindowCtl.levelHint || WindowCtl.chipWanted(now)
        if (want && !slot.shown) slot.show(null, null) else if (!want && slot.shown) slot.hide()
    }

    /**
     * v0.1.5 start (user: "默认就让用户在指挥台"): with a plausible head for [AUTO_PLACE_MS] (the first frames can be
     * all zero, sign-hands lesson) and a floor (real, or the virtual one after the 5 s timeout), the user is put on
     * the podium once; afterwards only [Move orchestra] / [Back to podium] move it.
     */
    private fun autoPlaceFrame(state: OrchUiState, now: Long, valid: Boolean) {
        if (!valid) {
            headValidSinceMs = NEVER
            return
        }
        if (headValidSinceMs == NEVER) headValidSinceMs = now
        if (autoPlaceSent || state.pose != null || state.placeMoving || state.marker) return
        if (state.room != Room.FINDING && state.room != Room.PLACING) return
        val fy = floorY ?: return
        if (now - headValidSinceMs < AUTO_PLACE_MS) return
        autoPlaceSent = true
        val pose = HeadFrame.podiumAt(head.position, head.rotation, fy, state.scale)
        OrchLog.i("PODIUM auto head=${OrchLog.pos(head.position)} floor=${OrchLog.f(fy)} anchor=${OrchLog.pos(pose.anchor)}")
        sink(OrchEvent.AutoPlace(pose))
    }

    /** The pointing ray: the controller when it moved in the last 5 s, else the eyes, else the head (design `input.source`). */
    private fun pointer(now: Long): PointerRay {
        val c = controller.sample()
        if (c != null) {
            val last = lastCtrlRay
            val moved = last == null || last.origin.distanceTo(c.ray.origin) > 0.01f || last.direction.dot(c.ray.direction) < 0.9995f
            if (moved || c.trigger > 0.1f || abs(c.stickX) > 0.15f || abs(c.stickY) > 0.15f) {
                if (last != null) lastCtrlMs = now
                lastCtrlRay = c.ray
            }
            if (lastCtrlMs != NEVER && now - lastCtrlMs < CTRL_ACTIVE_MS) {
                pointerSource = "controller"
                return c.ray
            }
        }
        val eye = eyes.pose(now)
        if (eye != null) {
            pointerSource = "eyes"
            return PointerRay(eye.position, eye.forward.normalized())
        }
        // both inputs missing: the head direction stands in, and the hint says so (D1: never the default)
        pointerSource = "head"
        return PointerRay(head.position, head.forward)
    }

    private fun reportPointer() {
        val headOnly = pointerSource == "head"
        if (headOnly == reportedHeadPointer) return
        reportedHeadPointer = headOnly
        OrchLog.i("POINTER source=$pointerSource")
        sink(OrchEvent.PointerChanged(headOnly))
    }

    // ---------------------------------------------------------------- placing

    private fun ghostFrame(state: OrchUiState, now: Long, dt: Float) {
        val view = orch ?: return
        val fy = floorY
        if (fy == null || !hasHead) {
            view.setShown(false)
            outlines.forEach { it.enabled = false }
            return
        }
        refreshFrames(state)
        // v0.1.1 (D1 "move to marker"): the ray / eyes only move a floor cursor; a pinch / trigger sets the marker and
        // the ghost stands there; nothing is ever put in front of the head
        val ray = pointer(now)
        reportPointer()
        val pointed = placeRule.floorPoint(ray.origin, ray.direction, fy)
        cursor?.let { c ->
            c.setLocal(position = Vec3(pointed.x, fy + 0.006f, pointed.z))
            c.enabled = true
        }
        val m = marker
        if (m == null) {
            view.setShown(false)
            outlines.forEach { it.enabled = false }
            markerDot?.enabled = false
            return
        }
        view.setShown(true)
        markerDot?.let { d ->
            d.setLocal(position = Vec3(m.x, fy + 0.008f, m.z))
            d.enabled = true
        }
        val clamped = placeRule.clamp(Vec3(m.x, fy, m.z), head.position, fy)
        val aimYaw = if (userTurned) ghostYaw else placeRule.yawFacing(clamped, head.position, ghostYaw)
        val (cleared, _) = placeRule.pushClear(OrchPose(clamped, aimYaw, state.scale), head.position)
        val a0 = ghostAnchor
        val anchor = if (a0 == null) cleared.anchor else if (dt <= 0f) cleared.anchor else a0.lerp(cleared.anchor, 1f - exp(-dt / FOLLOW_TAU_S))
        ghostAnchor = anchor

        // the stick (dead zone 0.15, 90 degrees per second, smoothed 0.15 s) turns the ghost about the podium front; Turn 45
        val x = controller.sample()?.stickX ?: 0f
        val wantRate = stickValue(x) * STICK_PLACE_RAD_S
        stickRate += (wantRate - stickRate) * (if (dt > 0f) 1f - exp(-dt / STICK_TAU_S) else 0f)
        if (abs(stickRate) > 0.01f) {
            if (!userTurned) OrchLog.i("STICK start (placing)")
            userTurned = true
            ghostYaw += stickRate * dt
        } else if (wantRate == 0f) {
            stickRate = 0f
        }
        if (turnStartMs != NEVER) {
            val q = ((now - turnStartMs) / TURN_MS).coerceIn(0f, 1f)
            ghostYaw = turnFrom + (turnTo - turnFrom) * Ease.inOutCubic(q)
            if (q >= 1f) turnStartMs = NEVER
        }
        if (!userTurned) ghostYaw = placeRule.yawFacing(anchor, head.position, ghostYaw)
        val free = OrchPose(anchor, ghostYaw, state.scale)
        val clear = placeRule.clearance(free, head.position)
        tooClose = state.forcedNear || clear < PlacementUseCase.NEAR_M - 0.03f

        val snap =
            if (state.forceWall) forcedFrame()
            else if (tooClose) null
            else placeRule.snap(free.center, ghostYaw, frames, held, state.scale)?.takeIf { f -> placeRule.clearance(OrchPose.fromCenter(f.center, f.yaw, state.scale), head.position) >= PlacementUseCase.NEAR_M - 0.02f }
        if (snap != null && held == null) OrchLog.i("SNAP on wall=${OrchLog.f(snap.wallLength)} center=${OrchLog.pos(snap.center)}")
        if (snap == null && held != null) OrchLog.i("SNAP off")
        held = snap
        val wanted = if (snap != null) OrchPose.fromCenter(snap.center, snap.yaw, state.scale) else free
        val prev = shownPose
        val shown =
            if (prev == null || dt <= 0f || state.freeze) wanted
            else {
                val k = 1f - exp(-dt / SNAP_TAU_S)
                OrchPose(prev.anchor.lerp(wanted.anchor, k), prev.yaw + PlacementUseCase.angle(wanted.yaw, prev.yaw) * k, state.scale)
            }
        shownPose = shown
        view.place(shown)
        view.setGhost(true, red = tooClose, solid = snap != null)
        view.glowAt(null)
        for (k in outlines.indices) {
            val f = frames.getOrNull(k)
            val e = outlines[k]
            if (f == null) {
                e.enabled = false
                continue
            }
            val p = OrchPose.fromCenter(f.center, f.yaw, state.scale)
            val solid = snap != null && snap.center.distanceTo(f.center) < 0.05f
            e.setLocal(position = p.origin + Vec3(0f, 0.004f, 0f), rotation = p.rotation, scale = Vec3(state.scale, 1f, state.scale))
            val d = Vec3(free.center.x - f.center.x, 0f, free.center.z - f.center.z).length
            e.setOpacity(if (solid) 1f else (0.4f + 0.6f * ((1.2f - d) / 0.8f)).coerceIn(0.4f, 0.85f))
            e.enabled = true
        }
        val snapped = snap != null
        if (snapped != reportedSnap || tooClose != reportedNear) {
            reportedSnap = snapped
            reportedNear = tooClose
            sink(OrchEvent.GhostChanged(snapped, tooClose))
        }
    }

    private fun stickValue(x: Float): Float = if (abs(x) > STICK_DEAD) -(x - STICK_DEAD * kotlin.math.sign(x)) / (1f - STICK_DEAD) else 0f

    /** Debug `place=wall`: the longest frame 1.5 - 7 m ahead of the viewer (else the longest), the only one drawn. */
    private fun forcedFrame(): SnapFrame? {
        val f = HeadFrame.heading(head.rotation)
        // only frames whose orchestra stays clear of the head, ahead of it
        val clear = frames.filter { placeRule.clearance(OrchPose.fromCenter(it.center, it.yaw, ui.scale), head.position) >= FORCED_CLEAR_M }
        // the audience side (+Z) turned towards the viewer, the footprint ahead: a frame the user would pick
        fun facing(fr: SnapFrame): Float = Quat4.axisAngle(Vec3.UP, fr.yaw).rotate(Vec3(0f, 0f, 1f)).dot(Vec3(head.position.x - fr.center.x, 0f, head.position.z - fr.center.z).normalized())
        val ahead = clear.filter { val d = (it.center - head.position).dot(f); d in 2.0f..9f && facing(it) > 0.3f }
        val pick = (ahead.ifEmpty { clear }).maxByOrNull { facing(it) * 4f + it.wallLength * 0.1f } ?: return null
        frames = listOf(pick)
        return pick
    }

    private fun triggerFrame() {
        val s = controller.sample()
        val down = s != null && (s.pressed || s.trigger > 0.6f)
        if (down && !triggerDown && s != null) {
            val fy = floorY
            if (fy != null) {
                OrchLog.i("TAP trigger marker")
                setMarker(placeRule.floorPoint(s.ray.origin, s.ray.direction, fy))
            }
        }
        triggerDown = down
    }

    /** Puts the floor marker at [p] (on the floor); the ghost moves there (v0.1.1). */
    private fun setMarker(p: Vec3) {
        val fy = floorY ?: return
        val first = marker == null
        marker = Vec3(p.x, fy, p.z)
        held = null
        if (first) ghostAnchor = null
        OrchLog.i("MARKER at=${OrchLog.pos(marker)} by=$pointerSource")
        if (first) sink(OrchEvent.MarkerChanged(true))
    }

    /** v0.1.2 [Move orchestra]: the ghost starts where the orchestra stood (marker at its podium front, same turn). */
    fun startMove(pose: OrchPose) {
        // the room switch to placing resets the ghost when it is rendered: apply after that, whichever comes first
        if (ui.room != Room.PLACING) {
            pendingMove = pose
            return
        }
        applyMove(pose)
    }

    private var pendingMove: OrchPose? = null

    private fun applyMove(pose: OrchPose) {
        val fy = floorY ?: pose.anchor.y
        marker = Vec3(pose.anchor.x, fy, pose.anchor.z)
        ghostAnchor = marker
        ghostYaw = pose.yaw
        userTurned = true
        held = null
        OrchLog.i("MOVE ghost from=${OrchLog.pos(marker)}")
    }

    /** v0.1.2 [Re-place]: no marker, no ghost; the next floor tap sets a new one. */
    fun clearMarker() {
        marker = null
        ghostAnchor = null
        held = null
        markerDot?.enabled = false
        orch?.setShown(false)
        OrchLog.i("MARKER cleared")
    }

    /** Debug `marker=<m>`: the marker that far along the level heading (QA stands in for a ray; debug builds only). */
    private fun debugMarker(d: Float): Boolean {
        if (!hasHead) return false
        val fy = floorY ?: return false
        setMarker(Vec3(head.position.x, fy, head.position.z) + HeadFrame.heading(head.rotation) * d)
        return true
    }

    private fun placeablePose(): OrchPose? {
        if (tooClose || marker == null) return null
        val pose = shownPose?.let { p -> held?.let { OrchPose.fromCenter(it.center, it.yaw, p.scale) } ?: p } ?: return null
        return if (hasHead && placeRule.clearance(pose, head.position) < PlacementUseCase.NEAR_M - 0.03f) null else pose
    }

    private fun refreshFrames(state: OrchUiState) {
        val fy = floorY ?: return
        val key = "${planeTracker.count}|${state.scale}|${state.wallMin}|${state.forceWall}|${OrchLog.f(fy)}|${state.virtualRoom}|${state.room3}"
        if (key == framesKey) return
        framesKey = key
        val walls = ArrayList(snapRule.walls(if (state.planesBlocked) emptyList() else planeTracker.all, fy, snapRule.floorCenter(planeTracker.all)))
        virtualWall(state)?.let { walls += it }
        val min = if (state.forceWall) 0f else (if (state.scale < 1f && state.wallMin == WallSnapUseCase.DEFAULT_MIN_WALL_M) WallSnapUseCase.HALF_MIN_WALL_M else state.wallMin)
        frames = snapRule.frames(walls, state.scale, head.position, min, if (state.forceWall) 64 else WallSnapUseCase.MAX_FRAMES)
        OrchLog.i("WALLS merged=${walls.size} lengths=${walls.joinToString(",") { OrchLog.f(it.length) }} frames=${frames.size} min=${OrchLog.f(min)}")
        if (frames.size != framesSent) {
            framesSent = frames.size
            sink(OrchEvent.PlanesChanged(floorY != null, frames.size, virtual = realFloorY == null && floorY != null))
        }
    }

    // ---------------------------------------------------------------- the room

    private fun pollPlanes(now: Long) {
        val count = planeTracker.count
        if (count == lastPlaneCount) return
        if (now - lastPlanesEmitMs < PLANES_EMIT_MS) return
        lastPlanesEmitMs = now
        lastPlaneCount = count
        val planes = if (ui.planesBlocked) emptyList() else planeTracker.all
        realFloorY = snapRule.floorY(planes)
        floorY = currentFloor(ui)
        framesKey = ""
        if (floorY != null) refreshFrames(ui)
        OrchLog.i("PLANES n=$count floor=${floorY?.let { OrchLog.f(it) }} real=${realFloorY != null} frames=${frames.size}")
        sink(OrchEvent.PlanesChanged(floorY != null, frames.size, virtual = realFloorY == null && floorY != null))
    }

    /** The detected floor, else the virtual one (head height - 1.60 m, design `container.virtualRoom`, N8). */
    private fun currentFloor(state: OrchUiState): Float? {
        realFloorY?.let { return it }
        if (!state.virtualRoom) return null
        return virtualFrame()?.first?.y
    }

    /** The virtual room's back-wall centre on the floor and its heading, taken once from the head (D9). */
    private fun virtualFrame(): Pair<Vec3, Float>? {
        virtualBase?.let { return it }
        if (!hasHead || !head.plausible) return null
        val h = HeadFrame.heading(head.rotation)
        val y = head.position.y - VIRTUAL_EYE_M
        val c = Vec3(head.position.x, y, head.position.z) + h * VIRTUAL_BACK_M
        OrchLog.i("VROOM base wall=${OrchLog.pos(c)} head=${OrchLog.pos(head.position)} stageOriginToFloor=${OrchLog.f(y)}")
        return (c to HeadFrame.yawOf(h)).also { virtualBase = it }
    }

    private fun virtualWall(state: OrchUiState): MergedWall? {
        if (!state.virtualRoom || realFloorY != null) return null
        val (c, yaw) = virtualFrame() ?: return null
        val r = Quat4.axisAngle(Vec3.UP, yaw)
        val along = r.rotate(Vec3.RIGHT)
        val inward = r.rotate(Vec3(0f, 0f, 1f))
        val hw = state.room3.width / 2f
        return MergedWall(c - along * hw, c + along * hw, inward)
    }

    private fun syncFloor(state: OrchUiState) {
        if (state.planesBlocked && realFloorY != null) {
            realFloorY = null
            lastPlaneCount = -1
        }
        val want = currentFloor(state)
        if (want == floorY) return
        floorY = want
        framesKey = ""
        OrchLog.i("FLOOR y=${want?.let { OrchLog.f(it) }} virtual=${realFloorY == null && want != null}")
        sink(OrchEvent.PlanesChanged(want != null, frames.size, virtual = realFloorY == null && want != null))
        if (want != null) render(ui)
    }

    /** Virtual floor grid and back-wall frame (only while the floor is virtual); rebuilt when the size changes. */
    private fun syncVirtualRoom(state: OrchUiState) {
        val root = stageRoot ?: return
        val m = materials ?: return
        val on = state.virtualRoom && realFloorY == null && floorY != null
        val key = if (on) "${state.room3}" else null
        if (key == vroomKey) return
        vroomKey = key
        runCatching { vroomGrid?.destroy(true) }
        runCatching { vroomWall?.destroy(true) }
        vroomGrid = null
        vroomWall = null
        if (!on) return
        val (c, yaw) = virtualFrame() ?: return
        val r = Quat4.axisAngle(Vec3.UP, yaw)
        val rm = state.room3
        val grid = ModelEntity(Meshes.grid(rm.width, rm.length, 0.012f, "vgrid"), m.grid).named("vroomGrid")
        root.addChild(grid)
        grid.setLocal(position = c + r.rotate(Vec3(0f, 0.002f, rm.length / 2f)), rotation = r)
        grid.setOpacity(0.10f)
        val wall = ModelEntity(Meshes.wallFrame(rm.width, rm.height, 0.012f, "vwall"), m.grid).named("vroomWall")
        root.addChild(wall)
        wall.setLocal(position = c, rotation = r)
        wall.setOpacity(0.25f)
        vroomGrid = grid
        vroomWall = wall
        OrchLog.i("VROOM shown l=${rm.length} w=${rm.width} h=${rm.height}")
    }

    /**
     * VR mode (v0.1.4; user: "VR 模式需要把场景做好看，然后基于同一平面放置，转化自然 ... 光照按模式分开"): the concert hall
     * (`assets/models/hall.glb`, tools/make_hall.py: wooden stage and acoustic shell, panelled red walls, balconies,
     * velvet seats, coffered ceiling with lamps) stands in orchestra coordinates under the orchestra's own pose, so its
     * floor is the plane the orchestra stands on and switching modes moves neither the orchestra nor the panels.
     * Before anything is placed it stands around the user's floor point. The switch cross-fades in 0.8 s. VR adds warm
     * stage spot lights and turns the real-world IBL on the musicians down; MR removes both.
     */
    private fun syncHall(state: OrchUiState, now: Long, dt: Float) {
        val root = stageRoot ?: return
        val want = if (state.vr) 1f else 0f
        if (state.vr && hallRoot == null && !hallLoading) loadHall(root)
        val hr = hallRoot ?: return
        // fade
        if (hallFade != want) {
            val step = if (dt <= 0f) 1f else dt / HALL_FADE_S
            hallFade = if (want > hallFade) minOf(want, hallFade + step) else maxOf(want, hallFade - step)
            if (hallFade > 0f && !hr.enabled) hr.enabled = true
            hr.setOpacity(hallFade)
            if (hallFade <= 0f) hr.enabled = false
        }
        // lights and the musicians' share of the real-world IBL follow the mode
        val lit = state.vr
        if (lit != hallLit) {
            hallLit = lit
            for (l in hallLights) l.enabled = lit
            orch?.setEnvironmentIbl(if (lit) VR_ENV_IBL else null)
            OrchLog.i("HALL ${if (lit) "on (VR)" else "off (MR)"} lights=${hallLights.size} envIbl=${if (lit) VR_ENV_IBL else 1f}")
        }
        if (!hr.enabled) return
        // pose: the orchestra's (placed, moving, or the placing ghost), else the user's floor point and heading
        val pose = currentPose() ?: shownPose?.takeIf { state.room == Room.PLACING && marker != null }
        val origin: Vec3
        val rot: Quat4
        if (pose != null) {
            origin = pose.origin
            rot = pose.rotation
        } else {
            val fy = floorY ?: (head.position.y - VIRTUAL_EYE_M)
            origin = Vec3(head.position.x, fy, head.position.z) + HeadFrame.heading(head.rotation) * 3.0f
            rot = Quat4.axisAngle(Vec3.UP, HeadFrame.yawOf(HeadFrame.heading(head.rotation)) + PI.toFloat())
        }
        val key = origin to rot
        if (key != hallPoseKey) {
            hallPoseKey = key
            hr.setLocal(position = origin, rotation = rot)
        }
    }

    private var hallRoot: Entity? = null
    private var hallLoading = false
    private var hallFade = 0f
    private var hallLit = false
    private var hallPoseKey: Any? = null
    private val hallLights = ArrayList<Entity>()

    private fun loadHall(root: Entity) {
        hallLoading = true
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val model = runCatching { Entity.loadSuspend("asset://models/hall.glb") }.onFailure { OrchLog.e("HALL load failed: $it", it) }.getOrNull()
            if (model == null || stageRoot == null) {
                hallLoading = false
                return@launch
            }
            val hr = Entity().named("hall")
            root.addChild(hr)
            hr.addChild(model.named("hallModel"))
            hr.enabled = false
            // warm stage lights over the orchestra (orchestra coordinates), the centre one casting shadows; a soft fill
            for ((i, p) in HALL_SPOTS.withIndex()) {
                val l = Entity().named("hallSpot$i")
                hr.addChild(l)
                l.setLocal(position = p, rotation = Quat4.axisAngle(Vec3.RIGHT, -SPOT_PITCH_RAD))
                l.components.set(com.pico.spatial.core.ecs.SpotLightComponent(WARM, SPOT_INTENSITY, SPOT_RADIUS_M, 28f, 52f, i == 1))
                l.enabled = false
                hallLights += l
            }
            val fill = Entity().named("hallFill")
            hr.addChild(fill)
            fill.setLocal(position = Vec3(0f, 6f, 5f))
            fill.components.set(com.pico.spatial.core.ecs.PointLightComponent(WARM, FILL_INTENSITY, FILL_RADIUS_M))
            fill.enabled = false
            hallLights += fill
            hallRoot = hr
            hallLit = false
            hallFade = 0f
            hallPoseKey = null
            logModelAlpha("hall")
            OrchLog.i("HALL loaded in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    /** Logs the alphaMode of every material in assets/models/<name>.glb (v0.1.4: the musicians must all be OPAQUE). */
    private fun logModelAlpha(name: String) {
        val ctx = OrchGraph.appContext ?: return
        runCatching {
            val bytes = ctx.assets.open("models/$name.glb").use { it.readBytes() }
            val len = (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8) or ((bytes[14].toInt() and 0xFF) shl 16) or ((bytes[15].toInt() and 0xFF) shl 24)
            val json = org.json.JSONObject(String(bytes, 20, len, Charsets.UTF_8))
            val mats = json.optJSONArray("materials")
            val modes = (0 until (mats?.length() ?: 0)).map { i -> mats!!.getJSONObject(i).optString("alphaMode", "OPAQUE") }
            OrchLog.i("MODEL $name alpha=${modes.distinct().joinToString("/")} materials=${modes.size} opaque=${modes.all { it == "OPAQUE" }}")
        }.onFailure { OrchLog.w("MODEL $name alpha check failed: $it") }
    }

    // ---------------------------------------------------------------- the placed orchestra

    /** Stick orbit / push (I20), controller drag (I21), pull / home animations (I22 / I23): all write [livePose]. */
    private fun moveFrame(state: OrchUiState, now: Long, dt: Float) {
        val view = orch ?: return
        var pose = livePose ?: state.pose ?: return
        val c = controller.sample()
        // animation
        val from = animFrom
        val to = animTo
        if (from != null && to != null) {
            val q = ((now - animStartMs) / ANIM_MS).coerceIn(0f, 1f)
            val t = Ease.inOutCubic(q)
            pose = OrchPose(from.anchor.lerp(to.anchor, t), from.yaw + PlacementUseCase.angle(to.yaw, from.yaw) * t, to.scale)
            livePose = pose
            view.place(pose)
            if (q >= 1f) {
                animFrom = null
                animTo = null
                endMove(commit = true)
            }
            return
        }
        // v0.1.2 (D1 "放好就锁住"): a placed orchestra is locked; the stick, drags, the trigger and the ray do not move it.
        // Only [Move orchestra] (back to placing) or the panel's [Bring closer] / [Back to place] animations do.
        if (state.room.placed) {
            if (livePose != null && !dragging) endMove(commit = true)
            return
        }
        val grab = dragGrab
        if (dragging && grab != null && c != null) {
            val fp = placeRule.floorPoint(c.ray.origin, c.ray.direction, pose.anchor.y)
            val next = pose.copy(anchor = Vec3(fp.x + grab.x, pose.anchor.y, fp.z + grab.z))
            if (placeRule.clearance(next, head.position) >= PlacementUseCase.NEAR_M) pose = next
        }
        // the stick: x turns the orchestra about the user (60 degrees per second), y pulls it closer / pushes it away
        if (!dragging && c != null && dt > 0f) {
            val kx = stickValue(c.stickX) * STICK_ORBIT_RAD_S
            val ky = -stickValue(c.stickY) * STICK_PUSH_M_S
            val k = 1f - exp(-dt / STICK_TAU_S)
            orbitRate += (kx - orbitRate) * k
            pushRate += (ky - pushRate) * k
            if (kx == 0f && abs(orbitRate) < 0.01f) orbitRate = 0f
            if (ky == 0f && abs(pushRate) < 0.01f) pushRate = 0f
            if (orbitRate != 0f) pose = orbitPose(pose, orbitRate * dt)
            if (pushRate != 0f) {
                val aim = focus?.let { Orchestra.seat(it) }?.let { pose.toStage(Vec3(it.x, 0f, it.z)) } ?: pose.center
                val dir = Vec3(aim.x - head.position.x, 0f, aim.z - head.position.z).let { if (it.length < 1e-3f) HeadFrame.heading(head.rotation) else it.normalized() }
                // pushRate > 0 = stick up = the orchestra comes closer
                val next = pose.copy(anchor = pose.anchor - dir * (pushRate * dt))
                val clearNow = placeRule.clearance(pose, head.position)
                val clearNext = placeRule.clearance(next, head.position)
                if (clearNext >= PlacementUseCase.NEAR_M || clearNext > clearNow) pose = next
            }
        } else if (c == null) {
            orbitRate = 0f
            pushRate = 0f
        }
        val moving = dragging || orbitRate != 0f || pushRate != 0f
        if (moving) {
            livePose = pose
            view.place(pose)
            setMoving(true)
        } else if (livePose != null && !dragging) {
            endMove(commit = true)
        }
    }

    private fun setMoving(value: Boolean) {
        if (value == movingReported) return
        movingReported = value
        if (value) OrchLog.i("MOVE start")
        sink(OrchEvent.MovingChanged(value))
    }

    private fun endMove(commit: Boolean) {
        val p = livePose
        livePose = null
        animFrom = null
        animTo = null
        dragging = false
        dragGrab = null
        if (p != null && commit) {
            OrchLog.i("MOVE end anchor=${OrchLog.pos(p.anchor)} yaw=${OrchLog.f(Math.toDegrees(p.yaw.toDouble()).toFloat(), 1)}")
            sink(OrchEvent.Moved(p))
            orch?.place(p)
        } else {
            ui.pose?.let { if (ui.room.placed) orch?.place(it) }
        }
        setMoving(false)
    }

    /** Pointing (I8): the first musician box the ray meets, debounced 0.15 s in, 0.4 s out. */
    private fun pointFrame(state: OrchUiState, now: Long) {
        val pose = currentPose() ?: return
        val ray = pointer(now)
        reportPointer()
        val hit = if (state.forcedHover != null) state.forcedHover else hitSeat(pose, ray)?.first
        pointedSeat = hit
        if (hit != null) lastHitMs = now
        if (hit != pending) {
            pending = hit
            pendingSinceMs = now
        }
        val next =
            when {
                state.forcedHover != null -> state.forcedHover
                hit != null && now - pendingSinceMs >= FOCUS_IN_MS -> hit
                hit == null && focus != null && now - lastHitMs >= FOCUS_OUT_MS -> null
                else -> focus
            }
        if (next != focus) {
            focus = next
            OrchLog.i("FOCUS ${next ?: "none"} by=$pointerSource")
            sink(OrchEvent.FocusChanged(next))
        }
    }

    private fun hitSeat(pose: OrchPose, ray: PointerRay): Pair<String, Float>? {
        val o = pose.toLocal(ray.origin)
        val d = pose.rotation.conjugate().rotate(ray.direction)
        var best: String? = null
        var bestT = Float.MAX_VALUE
        for (s in Orchestra.seats) {
            val h = seatHeight(s)
            val inv = Quat4.axisAngle(Vec3.UP, -s.yaw)
            val lo = inv.rotate(o - Vec3(s.x, 0f, s.z))
            val ld = inv.rotate(d)
            val t = slab(lo - Vec3(0f, h / 2f, 0f), ld, Vec3(SEAT_HALF, h / 2f, SEAT_HALF)) ?: continue
            if (t < bestT) {
                bestT = t
                best = s.id
            }
        }
        // local metres -> Stage metres (the ray direction keeps its length through the rotation)
        return best?.let { it to bestT * pose.scale }
    }

    private fun seatHeight(s: Seat): Float = Orchestra.modelHeight(s.model)

    private fun slab(o: Vec3, d: Vec3, h: Vec3): Float? {
        var t0 = 0f
        var t1 = 60f
        val os = floatArrayOf(o.x, o.y, o.z)
        val ds = floatArrayOf(d.x, d.y, d.z)
        val hs = floatArrayOf(h.x, h.y, h.z)
        for (k in 0..2) {
            if (abs(ds[k]) < 1e-6f) {
                if (abs(os[k]) > hs[k]) return null
            } else {
                var a = (-hs[k] - os[k]) / ds[k]
                var b = (hs[k] - os[k]) / ds[k]
                if (a > b) a = b.also { b = a }
                t0 = maxOf(t0, a)
                t1 = minOf(t1, b)
                if (t0 > t1) return null
            }
        }
        return t0
    }

    /** Near listening (S8): the nearest seat cluster to the head, or the podium. */
    private fun nearFrame(state: OrchUiState, now: Long) {
        val pose = currentPose() ?: return
        if (!hasHead) return
        val l = pose.toLocal(Vec3(head.position.x, pose.anchor.y, head.position.z))
        nearRule.update(l.x, l.z, pose.scale, now)
        val stem = nearRule.stem
        val key = stem to nearRule.podium
        if (key != reportedNearKey) {
            reportedNearKey = key
            val seat = stem?.let { Orchestra.nearestSeatOf(it, l.x, l.z).id }
            OrchLog.i("NEAR ${if (nearRule.podium) "podium" else stem?.id ?: "none"} d=${OrchLog.f(nearRule.distance)}")
            sink(OrchEvent.NearChanged(if (stem == null && !nearRule.podium) null else Near(stem, nearRule.podium, seat)))
        }
    }

    /** BeatMap on the musicians: rings, sway, solo dimming, D14 see-through, zones, focus glow, the name tag. */
    private fun musiciansFrame(state: OrchUiState, now: Long) {
        val view = orch ?: return
        val pose = currentPose() ?: return
        val playing = state.transport == Transport.PLAYING
        val t = vm.clock.t
        val piece = vm.piece
        val bpm = piece.passAt(t).bpm
        val solo = state.solo
        val focusSeat = focus?.let { Orchestra.seat(it) }
        val finale = finaleMs != NEVER && now - finaleMs < FINALE_MS
        val wall = now / 1000f
        val headLocal = if (hasHead) pose.toLocal(Vec3(head.position.x, pose.anchor.y, head.position.z)) else null
        for (v in view.seats) {
            val s = v.seat
            val e = if (playing) piece.envelope(s.part, t) else 0f
            val inSolo = solo == null || s.stem in solo.stems
            var ring = OrchestraView.RING_BASE + 0.75f * e
            if (!inSolo) ring = 0.05f
            if (focusSeat != null && solo == null && focusSeat.stem == s.stem) ring = minOf(1f, ring + 0.2f)
            if (finale) ring = 1f
            val ringScale = 1f + 0.25f * (if (finale) 1f else e)
            val sway = if (playing && !state.freeze) SWAY_RAD * e * sin(2f * PI.toFloat() * t * bpm / 120f) else if (playing) SWAY_RAD * e * 0.5f else 0f
            val breath = if (e < 0.05f) 1f + 0.005f * sin(2f * PI.toFloat() * (wall + s.x) / 4f) else 1f
            if (s.family in state.mutedFamilies) ring = 0.05f
            var opacity = if (inSolo && s.family !in state.mutedFamilies) 1f else SOLO_DIM
            if (headLocal != null && Orchestra.d(headLocal.x, headLocal.z, s.x, s.z) * pose.scale < PlacementUseCase.NEAR_M) opacity = minOf(opacity, NEAR_FADE)
            view.seatFrame(v, opacity, ring, ringScale, sway, breath)
        }
        // zones: 12 % base; pointed family 25 %; solo family 30 %, others 5 %; near family 25 %; caption focus breathes once
        val nearFamily = state.near?.stem?.family
        val captionFamily = state.snack?.family?.takeIf { captionStartMs != NEVER && now - captionStartMs < CAPTION_BREATH_MS }
        for (f in Family.entries) {
            var o = OrchestraView.ZONE_BASE
            if (focusSeat?.family == f) o = 0.25f
            if (nearFamily == f) o = maxOf(o, 0.25f)
            if (solo != null) o = if (solo.family == f) 0.30f else 0.05f
            if (captionFamily == f) o = maxOf(o, OrchestraView.ZONE_BASE + (0.35f - OrchestraView.ZONE_BASE) * sin(PI.toFloat() * ((now - captionStartMs) / CAPTION_BREATH_MS.toFloat()).coerceIn(0f, 1f)))
            view.zoneFrame(f, o)
        }
        view.glowAt(focusSeat)
        // D14: walking into the orchestra
        tagFrame(state, pose)
    }

    /** One name tag: the pointed musician, else the nearest one while near listening (design `nameTag`). */
    private fun tagFrame(state: OrchUiState, pose: OrchPose) {
        val root = stageRoot ?: return
        val seat = (focus ?: state.near?.seatId)?.let { Orchestra.seat(it) }
        if (seat == null || !hasHead) {
            tag?.enabled = false
            return
        }
        val context = OrchGraph.appContext ?: return
        val m = materials ?: return
        val key = "${seat.instrument}|${seat.family}|${state.english}"
        if (key != tagKey) {
            tagKey = key
            val item = io.embodit.insideorchestra.domain.model.Cards.item(seat.instrument)
            val bmp = SceneArt.nameTag(context.resources, item?.zh ?: seat.zh, item?.en ?: seat.en, seat.family, state.english)
            val tex = runCatching { TextureResource.create(bmp) }.getOrNull() ?: return
            val mat = m.textured(tex, transparent = true)
            val e = tag ?: ModelEntity(tagMesh ?: return, mat).named("nameTag").also { root.addChild(it); tag = it }
            e.setMaterial(mat)
            runCatching { tagTexture?.close() }
            tagTexture = tex
            tagMaterial = mat
        }
        val e = tag ?: return
        val top = pose.toStage(Vec3(seat.x, seatHeight(seat) + 0.25f, seat.z))
        val yaw = HeadFrame.facing(top, head.position)
        e.setLocal(position = top, rotation = Quat4.axisAngle(Vec3.UP, yaw), scale = Vec3(TAG_W_M, TAG_W_M * SceneArt.TAG_H / SceneArt.TAG_W, 1f))
        e.enabled = true
    }

    /** Group targets (mute x master x focus / solo), the close-up clamps and the sync step (design `audio`). */
    private fun audioFrame(state: OrchUiState, dt: Float) {
        if (audio.status != OrchestraAudio.Status.READY) return
        val master = if (state.muted) 0f else state.masterVol
        val solo = state.solo
        val focusStem = focus?.let { Orchestra.seat(it)?.stem }
        val targets = FloatArray(Stem.entries.size) { i ->
            val s = Stem.entries[i]
            val mix =
                when {
                    !state.room.placed -> 0f
                    s.family in state.mutedFamilies -> 0f
                    solo != null -> if (s in solo.stems) 1f else SOLO_REST
                    focusStem != null -> if (s == focusStem) 1f else FOCUS_REST
                    else -> 1f
                }
            // v0.1.4: the section's own volume slider on top
            OUTPUT_TRIM * master * mix * (state.familyVol[s.family] ?: 1f)
        }
        audio.setTargets(targets)
        val pose = currentPose()
        if (pose != null && hasHead) {
            for (s in Stem.entries) {
                val p = pose.toStage(Vec3(s.sourceX, Orchestra.SOURCE_Y, s.sourceZ))
                val h = Vec3(p.x - head.position.x, 0f, p.z - head.position.z).length
                audio.setClamp(s.ordinal, if (h < CLAMP_M) (maxOf(h, CLAMP_MIN_M) / CLAMP_M).let { it * it } else 1f)
            }
        }
        audio.frame(dt) { stem, t -> vm.piece.nextRest(stem, t) }
    }

    private fun logLine(state: OrchUiState) {
        val clock = vm.clock
        val med = audio.medianS
        val lat = if (med >= 0f) ((clock.t - med) * 1000f).toInt().toString() else "na"
        val grp = audio.groupVolumes.joinToString(",") { OrchLog.f(it, 2) }
        val ctrl = audio.clamps.joinToString(",") { OrchLog.f(it, 2) }
        val drift = audio.drift.joinToString(",")
        OrchLog.i(
            "CLOCK clock=${if (clock.followingAudio) "audio" else "app"} lat=$lat pos=${OrchLog.f(clock.t, 1)} state=${state.stateId} near=${if (nearRule.podium) "podium" else nearRule.stem?.id ?: "none"} d=${OrchLog.f(nearRule.distance)} solo=${state.solo?.let { it.family.id + ":" + it.stems.joinToString("+") { s -> s.id } } ?: "none"} focus=${focus ?: "none"} by=$pointerSource mute=${if (state.muted) 1 else 0} audio=${audio.status} grp=[$grp] ctrl=[$ctrl] drift=[$drift]ms"
        )
    }

    // =========================================================================================
    // system pointer (gaze + pinch, controller trigger, emulator mouse)
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val now = SystemClock.elapsedRealtime()
        if (dragEndMs != NEVER && now - dragEndMs < TAP_AFTER_DRAG_MS) {
            OrchLog.i("TAP ignored after drag target=$name")
            return
        }
        if (ui.room == Room.PLACING) {
            // v0.1.1 (D1 "move to marker"): a pinch / trigger / click sets the floor marker where the ray or the eyes
            // met a surface; [Move to marker] on the panel then puts the orchestra down there
            val fy = floorY
            val stage = toStage(value.position.x, value.position.y, value.position.z)
            if (fy == null || stage == null) {
                OrchLog.i("TAP placing ignored target=$name floor=$fy")
                return
            }
            val p =
                if (abs(stage.y - fy) < 0.08f) stage
                else {
                    // a wall / backstop hit: follow the same line on down to the floor (eyes or controller as origin)
                    val origin = lastCtrlRay?.takeIf { pointerSource == "controller" }?.origin ?: eyes.pose(now)?.position ?: head.position
                    val dir = (stage - origin).normalized()
                    if (dir.y < -0.05f) placeRule.floorPoint(origin, dir, fy) else null
                }
            if (p == null) {
                OrchLog.i("TAP placing no floor point target=$name stage=${OrchLog.pos(stage)}")
                return
            }
            OrchLog.i("TAP marker target=$name")
            setMarker(p)
            return
        }
        if (!ui.room.placed) return
        val seat = target?.let { orch?.seatOf(it) }
        if (seat != null) {
            OrchLog.i("TAP musician=${seat.seat.id} by=$pointerSource")
            sink(OrchEvent.MusicianTapped(seat.seat.id))
        } else {
            OrchLog.i("TAP empty target=$name")
            sink(OrchEvent.NothingTapped)
        }
    }

    /** I21: a drag that starts on the podium or a floor zone moves the orchestra (controller: ray floor point; hand: its movement). */
    fun onSpatialDrag(phase: String, value: SpatialDragValue) {
        when (phase) {
            "start" -> {
                // v0.1.2: locked once placed; dragging the orchestra needs [Move orchestra] (then it is placing)
                if (ui.room.placed) {
                    OrchLog.i("DRAG ignored (locked)")
                    return
                }
                if (!hasHead) return
                val target = value.targetEntity ?: return
                val view = orch ?: return
                if (!view.isPodium(target) && view.zoneOf(target) == null) return
                val pose = currentPose() ?: return
                dragging = true
                livePose = pose
                val c = controller.sample()
                dragGrab =
                    c?.takeIf { it.pressed || it.trigger > 0.4f }?.let { s ->
                        val fp = placeRule.floorPoint(s.ray.origin, s.ray.direction, pose.anchor.y)
                        Vec3(pose.anchor.x - fp.x, 0f, pose.anchor.z - fp.z)
                    }
                setMoving(true)
                OrchLog.i("DRAG start by=${if (dragGrab != null) "controller" else "hand"}")
            }
            "move" -> {
                if (!dragging || dragGrab != null) return
                val pose = livePose ?: return
                val a = value.dragAmount
                val dist = Vec3(pose.center.x - head.position.x, 0f, pose.center.z - head.position.z).length.coerceAtLeast(0.5f)
                val k = PX_M * (0.67f + 1.186f * dist)
                val right = Vec3(head.right.x, 0f, head.right.z).let { if (it.length < 0.1f) Vec3.RIGHT else it.normalized() }
                val fwd = HeadFrame.heading(head.rotation)
                val next = pose.copy(anchor = pose.anchor + right * (a.x * k) + fwd * (-a.y * k))
                if (placeRule.clearance(next, head.position) >= PlacementUseCase.NEAR_M) {
                    livePose = next
                    orch?.place(next)
                }
            }
            "end", "cancel" -> {
                if (!dragging) return
                dragEndMs = SystemClock.elapsedRealtime()
                OrchLog.i("DRAG end")
                endMove(commit = true)
            }
        }
    }

    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
            val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            probe.convertPositionTo(local, null).domain()
        }.getOrNull()
    }

    /**
     * Debug `place=auto` only (QA screenshots; the debug receiver exists in debug builds only): the podium front 1.5 m
     * ahead on the floor. The product never places from the head: placing goes through the marker (D1).
     */
    private fun autoPose(scale: Float): OrchPose? {
        if (!hasHead) return null
        val fy = floorY ?: (head.position.y - VIRTUAL_EYE_M)
        val p = HeadFrame.orchestraAhead(head.position, head.rotation, fy, scale)
        return placeRule.pushClear(p, head.position).first
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String =
        "POSE hmd pos=${OrchLog.pos5(head.position)} ${OrchLog.quat5(head.rotation)} fwd=${OrchLog.pos5(head.forward)} valid=$hasHead src=$pointerSource" +
            (currentPose()?.let { " anchor=${OrchLog.pos(it.anchor)} yaw=${OrchLog.f(Math.toDegrees(it.yaw.toDouble()).toFloat(), 1)} clear=${OrchLog.f(placeRule.clearance(it, head.position))} nearest=${placeRule.nearestSeat(it, head.position)}" } ?: "") +
            " floor=${floorY?.let { OrchLog.f(it) }} pointed=$pointedSeat"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f engine=%s", slot.id, OrchLog.pos5(slot.position), OrchLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp, OrchLog.pos5(slot.enginePosition()))
        }
    }

    override fun planesSummary(): List<String> =
        planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${OrchLog.pos(p.centroid)} normal=${OrchLog.pos(p.normal)} verts=${p.vertices.size}" } +
            frames.mapIndexed { k, f -> "FRAME $k center=${OrchLog.pos(f.center)} yaw=${OrchLog.f(Math.toDegrees(f.yaw.toDouble()).toFloat(), 1)} wall=${OrchLog.f(f.wallLength)}" }

    override fun parkPanel(): String = "WINDOW state=${WindowCtl.panelState} (the system window has no app-side park)"

    override fun recenter(): String {
        WindowCtl.recall("recenter")
        return "RECENTER window recall asked"
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val FRAME_MS = 20L
        private const val FAST_FRAME_MS = 40L
        private const val SLOW_STEP_MS = 400L
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_EMIT_MS = 500L
        private const val PANEL_HOLD_MS = 600L
        private const val LOG_MS = 1000L
        private const val BEAT_MS = 100L
        private const val FOLLOW_TAU_S = 0.12f
        private const val SNAP_TAU_S = 0.05f
        private const val STICK_DEAD = 0.15f
        private const val STICK_TAU_S = 0.15f
        private val STICK_PLACE_RAD_S = Math.toRadians(90.0).toFloat()
        private val STICK_ORBIT_RAD_S = Math.toRadians(60.0).toFloat()
        private const val STICK_PUSH_M_S = 0.6f
        private const val TURN_MS = 250f
        private const val ANIM_MS = 800f
        private const val PULL_M = 0.8f
        private const val CTRL_ACTIVE_MS = 5000L
        private const val FOCUS_IN_MS = 150L
        private const val FOCUS_OUT_MS = 400L
        private const val SEAT_HALF = 0.31f
        private const val FINALE_MS = 1000L
        private const val CAPTION_BREATH_MS = 1200L
        /** The start waits this long with a plausible head before putting the user on the podium. */
        private const val AUTO_PLACE_MS = 1000L

        /** The floor catcher: 30 m square, 2 cm thick, centred on the Stage origin at the floor. */
        private const val CATCHER_M = 30f

        /** B24 key light: warm, moderate (no blown-out white on the players), from above-front-left of the stage. */
        private val KEY_COLOR = com.pico.spatial.core.math.Color4(1.0f, 0.93f, 0.82f, 1f)
        private const val KEY_LUX = 2500f
        private val KEY_DIR = Vec3(0.35f, -0.8f, -0.5f)
        private const val HALL_FADE_S = 0.8f
        private val WARM = com.pico.spatial.core.math.Color4(1.0f, 0.86f, 0.68f, 1f)
        private val HALL_SPOTS = listOf(Vec3(-3f, 8f, 1.0f), Vec3(0f, 8f, 0.0f), Vec3(3f, 8f, 1.0f))
        private val SPOT_PITCH_RAD = Math.toRadians(70.0).toFloat()
        private const val SPOT_INTENSITY = 40000f
        private const val SPOT_RADIUS_M = 14f
        private const val FILL_INTENSITY = 6000f
        private const val FILL_RADIUS_M = 14f

        /** VR: the musicians take a quarter of the real-world IBL (the hall's warm lights do the rest). */
        private const val VR_ENV_IBL = 0.25f

        /** The Stage chip: 18 degrees below the view centre, 0.8 m away. */
        private val CHIP_HEAD_OFFSET = Vec3(0f, -0.2472f, -0.7608f)

        private const val TAG_W_M = 0.32f
        private const val TAP_AFTER_DRAG_MS = 300L
        private const val PX_M = 0.0004f
        private const val FORCED_CLEAR_M = 1.0f
        private val SWAY_RAD = Math.toRadians(2.0).toFloat()
        private const val SOLO_DIM = 0.4f
        private const val NEAR_FADE = 0.3f
        /** v0.1.5: the podium balance never takes a stem below 0.75 (was 0.5): loudness first. */
        private const val BALANCE_FLOOR = 0.75f

        /**
         * v0.1.5 loudness (qa/loudness-v0.1.5.wav, emulator loopback): untrimmed the whole band peaked at -0.1 dBFS with
         * RMS -7.9 dBFS; 0.6 (-4.4 dB) keeps the peak under -1 dBFS with RMS far above the -20 dBFS floor.
         */
        private const val OUTPUT_TRIM = 0.6f
        private const val SOLO_REST = 0.08f
        private const val FOCUS_REST = 0.5f
        private const val CLAMP_M = 0.5f
        private const val CLAMP_MIN_M = 0.25f

        /** Virtual floor: this far below the eyes; virtual back wall this far ahead (design `container.virtualRoom`). */
        private const val VIRTUAL_EYE_M = 1.60f
        private const val VIRTUAL_BACK_M = 4.6f
    }
}
