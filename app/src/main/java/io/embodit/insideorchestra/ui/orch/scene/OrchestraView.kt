package io.embodit.insideorchestra.ui.orch.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.domain.model.Orchestra
import io.embodit.insideorchestra.domain.model.Quat4
import io.embodit.insideorchestra.domain.model.Seat
import io.embodit.insideorchestra.domain.model.Stem
import io.embodit.insideorchestra.domain.model.Vec3
import io.embodit.insideorchestra.domain.usecase.OrchPose
import io.embodit.insideorchestra.platform.OrchLog

/** Shared meshes of the orchestra (built once per scene). */
internal class OrchMeshes(
    val ring: MeshResource,
    val zone: MeshResource,
    val glow: MeshResource,
    val podium: MeshResource,
    val podiumTop: MeshResource,
    val foot: MeshResource,
    val footSolid: MeshResource,
    val outline: MeshResource,
    val outlineSolid: MeshResource,
    val dot: MeshResource,
)

/**
 * The orchestra in orchestra coordinates under one root (design `entities3d.orchestra`): placing, turning and 1:2
 * act on the root only. Children: the podium, four floor zones, 23 seats (holder -> Tripo model clone; ring on
 * the floor; tap box), the ten stem sources and the focus glow. The same root is the placing ghost (see
 * [setGhost]): see-through, no taps, rings dark; there is no second copy of the 23 models.
 */
internal class OrchestraView(parent: Entity, private val m: SceneMaterials, private val meshes: OrchMeshes, podiumTopMaterial: Material?) {
    val root: Entity = Entity().named("orchestra")

    inner class SeatView(val seat: Seat, val holder: Entity, val body: Entity, val ring: Entity, val hit: Entity, val scaleJitter: Float) {
        /** v0.1.1: false while the musician stands between the eyes and a panel (UI first, D20). */
        var hittable = true
        var model: Entity? = null
        var lastOpacity = -1f
        var lastRing = -1f
        var lastRingScale = -1f
    }

    val seats = ArrayList<SeatView>()
    private val byEntity = HashMap<Long, SeatView>()
    val sources = HashMap<Stem, Entity>()
    private val zones = HashMap<Family, Entity>()
    private val zoneOpacity = HashMap<Family, Float>()
    private val podiumEntity: Entity
    private val glow: Entity
    private var glowOn = false
    private val foot: Entity
    private val footSolid: Entity
    private val podiumDot: Entity
    private var ghost = false
    private var shown = true
    private var faded = 1f

    init {
        parent.addChild(root)
        podiumEntity = ModelEntity(meshes.podium, m.podium).named("podium")
        root.addChild(podiumEntity)
        podiumEntity.tapBox(Vec3(Orchestra.PODIUM_R * 2f, Orchestra.PODIUM_H, Orchestra.PODIUM_R * 2f), Vec3(0f, Orchestra.PODIUM_H / 2f, 0f))
        if (podiumTopMaterial != null) root.addChild(ModelEntity(meshes.podiumTop, podiumTopMaterial).named("podiumTop"))
        for (z in Orchestra.zones) {
            val e = ModelEntity(meshes.zone, m.zone.getValue(z.family)).named("zone${z.family.id}")
            root.addChild(e)
            e.setLocal(position = Vec3(z.cx, 0.004f, z.cz), scale = Vec3(z.hx * 2f, 1f, z.hz * 2f))
            e.tapBox(Vec3(1f, 0.02f, 1f), Vec3(0f, 0.01f, 0f))
            e.setOpacity(ZONE_BASE)
            zones[z.family] = e
            zoneOpacity[z.family] = ZONE_BASE
        }
        for (stem in Stem.entries) {
            val e = Entity().named("source${stem.id}")
            root.addChild(e)
            e.setLocal(position = Vec3(stem.sourceX, Orchestra.SOURCE_Y, stem.sourceZ))
            sources[stem] = e
        }
        glow = ModelEntity(meshes.glow, m.glowMat.getValue(Family.STR)).named("focusGlow")
        root.addChild(glow)
        glow.enabled = false
        foot = ModelEntity(meshes.foot, m.tape).named("footprint")
        footSolid = ModelEntity(meshes.footSolid, m.tape).named("footprintSolid")
        podiumDot = ModelEntity(meshes.dot, m.podiumDot).named("podiumDot")
        for (e in listOf(foot, footSolid, podiumDot)) {
            root.addChild(e)
            e.enabled = false
        }
        foot.setLocal(position = Vec3(0f, 0.006f, 0f))
        footSolid.setLocal(position = Vec3(0f, 0.006f, 0f))
        podiumDot.setLocal(position = Vec3(0f, Orchestra.PODIUM_H + 0.004f, 0f))
    }

    val complete: Boolean
        get() = seats.size == Orchestra.seats.size

    /** One seat per call (paced build): holder at the seat facing the podium, ring and tap box. */
    fun addSeat() {
        val seat = Orchestra.seats[seats.size]
        val holder = Entity().named("seat${seat.id}")
        root.addChild(holder)
        holder.setLocal(position = Vec3(seat.x, 0f, seat.z), rotation = Quat4.axisAngle(Vec3.UP, seat.yaw))
        val body = Entity().named("body${seat.id}")
        holder.addChild(body)
        val jitter = 0.97f + 0.06f * ((seat.id.hashCode() and 0xFF) / 255f)
        body.setUniformScale(jitter)
        val ring = ModelEntity(meshes.ring, m.ring.getValue(seat.family)).named("ring${seat.id}")
        holder.addChild(ring)
        ring.setLocal(position = Vec3(0f, 0.008f, 0f))
        ring.setOpacity(RING_BASE)
        val h = if (seat.model == "bassist") 1.9f else if (seat.part.family == Family.PERC) 1.6f else 1.3f
        // the tap box is its own child so it can be switched off while the musician covers a panel (v0.1.1, D20);
        // 0.95 of the model height: the bars float above the heads, out of every box
        val hit = Entity().named("hit${seat.id}")
        holder.addChild(hit)
        hit.tapBox(Vec3(0.56f, h * 0.95f, 0.56f), Vec3(0f, h * 0.95f / 2f, 0f))
        val v = SeatView(seat, holder, body, ring, hit, jitter)
        seats += v
        byEntity[holder.id] = v
        byEntity[body.id] = v
        byEntity[hit.id] = v
    }

    /** The model clone of [seat] arrives (templates load in the background). */
    fun attachModel(v: SeatView, clone: Entity) {
        v.model?.let { runCatching { it.destroy(true) } }
        v.body.addChild(clone)
        clone.enabled = true
        v.model = clone
        byEntity[clone.id] = v
        v.lastOpacity = -1f
    }

    /** The seat a tapped entity belongs to (its holder, body, model or any child of the model). */
    fun seatOf(entity: Entity): SeatView? {
        var e: Entity? = entity
        var depth = 0
        while (e != null && depth < 12) {
            byEntity[e.id]?.let { return it }
            e = runCatching { e?.getParent() }.getOrNull()
            depth++
        }
        return null
    }

    fun isPodium(entity: Entity): Boolean = entity.id == podiumEntity.id

    /** Switches a musician's tap box on / off (off while it covers a panel). */
    fun setHittable(v: SeatView, on: Boolean) {
        if (v.hittable == on) return
        v.hittable = on
        v.hit.enabled = on
    }

    fun zoneOf(entity: Entity): Family? = zones.entries.firstOrNull { it.value.id == entity.id }?.key

    fun place(pose: OrchPose) {
        root.setLocal(position = pose.origin, rotation = pose.rotation, scale = Vec3(pose.scale, pose.scale, pose.scale))
    }

    fun setShown(value: Boolean) {
        if (value == shown) return
        shown = value
        root.enabled = value
    }

    /** Placing: the orchestra is its own ghost (see-through, footprint tape, podium dot, no taps). */
    fun setGhost(value: Boolean, red: Boolean = false, solid: Boolean = false) {
        if (value != ghost) {
            ghost = value
            for (s in seats) s.lastOpacity = -1f
            for (z in zones.values) z.enabled = !value
            if (!value) {
                foot.enabled = false
                footSolid.enabled = false
                podiumDot.enabled = false
            }
        }
        if (value) {
            foot.enabled = !solid
            footSolid.enabled = solid
            podiumDot.enabled = true
            val mat = if (red) m.tapeRed else m.tape
            foot.setMaterial(mat)
            footSolid.setMaterial(mat)
            root.setOpacity(if (red) GHOST_RED else if (solid) GHOST_BRIGHT else GHOST_OPACITY)
        } else {
            root.setOpacity(faded)
        }
    }

    val isGhost: Boolean
        get() = ghost

    /** A seat's look this frame: [opacity] of the musician, [ring] opacity, ring scale, sway (radians) and breath. */
    fun seatFrame(v: SeatView, opacity: Float, ring: Float, ringScale: Float, sway: Float, breath: Float) {
        val model = v.model
        if (model != null && kotlin.math.abs(opacity - v.lastOpacity) > 0.01f) {
            model.setOpacity(opacity)
            v.lastOpacity = opacity
        }
        if (kotlin.math.abs(ring - v.lastRing) > 0.01f) {
            v.ring.setOpacity(ring)
            v.lastRing = ring
        }
        if (kotlin.math.abs(ringScale - v.lastRingScale) > 0.005f) {
            v.ring.setLocal(scale = Vec3(ringScale, 1f, ringScale))
            v.lastRingScale = ringScale
        }
        // sway about the seat (chair-centre floor point): a small forward / back lean, the whole static mesh
        v.body.setLocal(rotation = Quat4.axisAngle(Vec3.RIGHT, sway), scale = Vec3(v.scaleJitter * breath, v.scaleJitter * breath, v.scaleJitter * breath))
    }

    fun zoneFrame(f: Family, opacity: Float) {
        val old = zoneOpacity[f] ?: -1f
        if (kotlin.math.abs(old - opacity) < 0.005f) return
        zoneOpacity[f] = opacity
        zones[f]?.setOpacity(opacity)
    }

    /** The focus glow under [seat] (null hides it). */
    fun glowAt(seat: Seat?) {
        if (seat == null) {
            if (glowOn) {
                glow.enabled = false
                glowOn = false
            }
            return
        }
        glow.setMaterial(m.glowMat.getValue(seat.family))
        glow.setLocal(position = Vec3(seat.x, 0.010f, seat.z))
        if (!glowOn) {
            glow.enabled = true
            glowOn = true
        }
    }

    /** D14: the whole orchestra goes see-through while the head is inside it (per seat in the frame system). */
    fun setFaded(value: Float) {
        faded = value
        if (!ghost) root.setOpacity(value)
    }

    fun destroy() {
        runCatching { root.destroy(true) }
        seats.clear()
        byEntity.clear()
        sources.clear()
        OrchLog.i("ORCH destroyed")
    }

    companion object {
        const val ZONE_BASE = 0.12f
        const val RING_BASE = 0.15f
        const val GHOST_OPACITY = 0.35f
        const val GHOST_BRIGHT = 0.6f
        const val GHOST_RED = 0.45f
    }
}
