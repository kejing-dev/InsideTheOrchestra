package io.embodit.insideorchestra.platform.audio

import com.pico.spatial.core.ecs.AudioMixerGroupsComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.audio.AudioResourceConfig
import com.pico.spatial.core.ecs.audio.Directivity
import com.pico.spatial.core.ecs.audio.DistanceAttenuationMode
import com.pico.spatial.core.ecs.resource.AudioMixerGroupResource
import com.pico.spatial.core.ecs.resource.AudioResource
import io.embodit.insideorchestra.domain.model.Stem
import io.embodit.insideorchestra.domain.usecase.StemSlots
import io.embodit.insideorchestra.platform.OrchLog
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The ten stems as object audio (design `audio`): one source entity per stem (children of the orchestra, so they
 * move, turn and scale with it), `ObjectAudioComponent` (omnidirectional, INVERSE_SQUARED, reverb 0.3), one
 * `AudioMixerGroupResource` per stem bound at load time through `AudioResourceConfig`.
 *
 * Volumes, three layers that each have one job:
 * - component volume = the podium balance ([setBalance], design `audio.balance`);
 * - mixer group volume = master x mute x focus / solo mix, eased with a 300 ms time constant ([frame]);
 * - controller volume = the close-up clamp ([setClamp], design `audio.nearClamp`).
 *
 * Sync (design `audio.sync` layer 1): once a second the stems' positions are compared with their median; 20 - 80 ms
 * off -> that controller plays at 1.01 / 0.99 until back within 10 ms; more than 80 ms -> it seeks to the median at
 * its next rest (from `piece.json`). Main thread only (the SDK calls are @MainThread); files load on IO.
 */
class OrchestraAudio {
    enum class Status { IDLE, LOADING, READY, FAILED }

    var status = Status.IDLE
        private set

    private val resources = HashMap<Stem, AudioResource>()
    private val groups = StemSlots(Stem.entries, { stem -> AudioMixerGroupResource(name = groupName(stem), volume = 0f, playbackSpeed = 1f) }, { g -> runCatching { g.close() } })
    private val controllers = HashMap<Stem, AudioPlayerController>()
    private val entities = HashMap<Stem, Entity>()
    private val target = FloatArray(Stem.entries.size) { 1f }
    private val current = FloatArray(Stem.entries.size) { 0f }
    private val written = FloatArray(Stem.entries.size) { -1f }
    private val clamp = FloatArray(Stem.entries.size) { 1f }
    private val clampWritten = FloatArray(Stem.entries.size) { -1f }
    private val speed = FloatArray(Stem.entries.size) { 1f }
    private val pendingSeek = arrayOfNulls<Float>(Stem.entries.size)
    private var syncTimer = 0f

    /** The entity whose AudioMixerGroupsComponent holds [groups] (set once; a piece switch keeps it). */
    private var groupsOn = -1L
    private var playing = false

    /** Last sync reading, per stem: position minus median, ms (for the log). */
    val drift = IntArray(Stem.entries.size)

    /** Median position of the stems in seconds, or -1 when no controller reports one. */
    var medianS = -1f
        private set

    /** Loads [pieceId]'s ten files (IO thread), then binds them to [sources] (main thread). One retry per stem. */
    suspend fun load(sources: Map<Stem, Entity>, pieceId: String, forceFail: Boolean): Boolean {
        status = Status.LOADING
        entities.clear()
        entities.putAll(sources)
        val made = groups.forLoad()
        // the groups live on the orchestra root's AudioMixerGroupsComponent (optional, keeps them findable)
        val root = sources.values.firstOrNull()?.getParent()
        if (root != null && root.id != groupsOn) {
            runCatching { root.components.set(AudioMixerGroupsComponent().apply { made.values.forEach { addMixerGroup(it) } }) }
            groupsOn = root.id
        }
        var ok = true
        for (stem in Stem.entries) {
            if (controllers[stem] != null) continue
            var res: AudioResource? = null
            for (attempt in 1..2) {
                res = if (forceFail) null else withContext(Dispatchers.IO) { runCatching { AudioResource.load("$pieceId-${stem.id}", "asset://audio/$pieceId/${stem.id}.ogg", LoadType.FROM_ASSETS, AudioResourceConfig(groupName(stem))) }.onFailure { OrchLog.e("AUDIO load ${stem.id} attempt=$attempt failed: $it", it) }.getOrNull() }
                if (res != null) break
            }
            val entity = sources[stem]
            if (res == null || entity == null) {
                ok = false
                continue
            }
            val controller =
                runCatching {
                    entity.components.set(ObjectAudioComponent(volume = 1f, directivity = Directivity(0f, 1f), distanceAttenuationMode = DistanceAttenuationMode.INVERSE_SQUARED, reverbVolume = REVERB, soundRadiusLevel = SOUND_RADIUS))
                    entity.prepareAudio(res)
                }.onFailure { OrchLog.e("AUDIO prepare ${stem.id} failed: $it", it) }.getOrNull()
            if (controller == null) {
                runCatching { res.close() }
                ok = false
                continue
            }
            resources[stem] = res
            controllers[stem] = controller
        }
        status = if (ok) Status.READY else Status.FAILED
        OrchLog.i("AUDIO load piece=$pieceId ok=$ok stems=${controllers.size} durations=${controllers.values.joinToString(",") { runCatching { it.getDuration() }.getOrDefault(-1L).toString() }}")
        return ok
    }

    /** Podium balance: component volume per stem (design `audio.balance`). */
    fun setBalance(volumes: Map<Stem, Float>) {
        for ((stem, v) in volumes) {
            val e = entities[stem] ?: continue
            runCatching { e.components[ObjectAudioComponent::class.java]?.volume = v.coerceIn(0f, 1f) }
        }
        OrchLog.i("AUDIO balance ${Stem.entries.joinToString(" ") { s -> "${s.id}=${OrchLog.f(volumes[s] ?: 1f)}" }}")
    }

    /** The mix the groups ease towards (master and mute folded in by the caller). */
    fun setTargets(values: FloatArray) {
        for (i in target.indices) target[i] = values[i].coerceIn(0f, 1f)
    }

    /** Close-up clamp gains per stem (controller volume). */
    fun setClamp(i: Int, gain: Float) {
        clamp[i] = gain.coerceIn(0f, 1f)
    }

    val targets: FloatArray
        get() = target

    val groupVolumes: FloatArray
        get() = current

    val clamps: FloatArray
        get() = clamp

    fun play(fromS: Float) {
        playing = true
        val ms = (fromS * 1000f).toLong()
        for ((stem, c) in controllers) {
            runCatching {
                c.seekTo(ms)
                c.setPlaybackSpeed(1f)
                c.play()
            }.onFailure { OrchLog.w("AUDIO play ${stem.id} failed: $it") }
            speed[stem.ordinal] = 1f
            pendingSeek[stem.ordinal] = null
        }
        syncTimer = 0f
        OrchLog.i("AUDIO play from=${OrchLog.f(fromS)} n=${controllers.size}")
    }

    fun pause() {
        playing = false
        for (c in controllers.values) runCatching { c.pause() }
    }

    fun seek(s: Float) {
        val ms = (s * 1000f).toLong()
        for (c in controllers.values) runCatching { c.seekTo(ms) }
    }

    /**
     * Once per frame: eases the group volumes, writes the clamps, reads positions and corrects drift. [restAfter]
     * gives the next rest of a stem after a time (seconds) or null.
     */
    fun frame(dt: Float, restAfter: (Stem, Float) -> Pair<Float, Float>?) {
        if (controllers.isEmpty()) return
        val k = if (dt <= 0f) 1f else 1f - exp(-dt / EASE_TAU_S)
        for (stem in Stem.entries) {
            val i = stem.ordinal
            current[i] += (target[i] - current[i]) * k
            if (abs(current[i] - target[i]) < 0.002f) current[i] = target[i]
            if (abs(current[i] - written[i]) > 0.002f || (current[i] == target[i] && written[i] != target[i])) {
                groups[stem]?.let { g -> if (g.valid) runCatching { g.setVolume(current[i]) } }
                written[i] = current[i]
            }
            if (abs(clamp[i] - clampWritten[i]) > 0.01f) {
                controllers[stem]?.let { c -> runCatching { c.setVolume(clamp[i]) } }
                clampWritten[i] = clamp[i]
            }
        }
        if (!playing) return
        syncTimer += dt
        if (syncTimer < SYNC_EVERY_S) {
            applyPendingSeeks(restAfter)
            return
        }
        syncTimer = 0f
        val positions = HashMap<Stem, Long>()
        for ((stem, c) in controllers) {
            val p = runCatching { c.getCurrentPosition() }.getOrDefault(-1L)
            if (p >= 0) positions[stem] = p
        }
        if (positions.size < controllers.size / 2 + 1) {
            medianS = -1f
            return
        }
        val sorted = positions.values.sorted()
        val median = sorted[sorted.size / 2]
        medianS = median / 1000f
        for ((stem, p) in positions) {
            val i = stem.ordinal
            val off = (p - median).toInt()
            drift[i] = off
            val a = abs(off)
            val c = controllers[stem] ?: continue
            when {
                a > HARD_MS -> if (pendingSeek[i] == null) pendingSeek[i] = medianS
                a > SOFT_MS -> {
                    val want = if (off > 0) 0.99f else 1.01f
                    if (speed[i] != want) {
                        runCatching { c.setPlaybackSpeed(want) }
                        speed[i] = want
                    }
                }
                a <= BACK_MS && speed[i] != 1f -> {
                    runCatching { c.setPlaybackSpeed(1f) }
                    speed[i] = 1f
                }
            }
        }
        applyPendingSeeks(restAfter)
    }

    /** A stem far off waits for its next rest and then jumps to the median (no audible cut mid-note). */
    private fun applyPendingSeeks(restAfter: (Stem, Float) -> Pair<Float, Float>?) {
        if (medianS < 0f) return
        for (stem in Stem.entries) {
            val i = stem.ordinal
            if (pendingSeek[i] == null) continue
            val c = controllers[stem] ?: continue
            val own = runCatching { c.getCurrentPosition() }.getOrDefault(-1L) / 1000f
            val rest = restAfter(stem, own)
            if (rest == null || own >= rest.first) {
                val target = medianS + syncTimer
                runCatching { c.seekTo((target * 1000f).toLong()) }
                pendingSeek[i] = null
                OrchLog.i("AUDIO resync ${stem.id} own=${OrchLog.f(own)} to=${OrchLog.f(target)}")
            }
        }
    }

    /**
     * v0.1.6 (user on the headset: "no sound after switching to a new song"): a piece switch stops and closes the ten
     * controllers and resources but keeps the mixer groups and their component. v0.1.5 closed the groups too and made
     * new ones with the same names; the new piece's resources then played into the closed groups: positions advanced,
     * nothing came out. The group volumes are written again on the next frame.
     */
    fun unloadPiece() {
        playing = false
        for (c in controllers.values) runCatching {
            c.stop()
            c.close()
        }
        controllers.clear()
        for (r in resources.values) runCatching { r.close() }
        resources.clear()
        speed.fill(1f)
        pendingSeek.fill(null)
        written.fill(-1f)
        clampWritten.fill(-1f)
        medianS = -1f
        status = Status.IDLE
    }

    /** Stops and closes the ten controllers, resources and groups (design `I17`, scene detach). */
    fun release() {
        playing = false
        for (c in controllers.values) runCatching {
            c.stop()
            c.close()
        }
        controllers.clear()
        for (r in resources.values) runCatching { r.close() }
        resources.clear()
        groups.close()
        groupsOn = -1L
        entities.clear()
        written.fill(-1f)
        clampWritten.fill(-1f)
        current.fill(0f)
        status = Status.IDLE
    }

    private fun groupName(stem: Stem): String = "stem" + stem.id

    companion object {
        private const val REVERB = 0.3f

        /**
         * The source's effective radius (SDK: the attenuation reference). v0.1.4 0.1 -> 1.0 m; v0.1.5 (user 2026-11-05
         * again: "这个声音太小") 2.0 m: from the podium every section is 1.2 - 3.2 m away, so the far rows lose at most
         * ~4 dB and walking up to a section still makes it clearly louder.
         */
        private const val SOUND_RADIUS = 2.0f
        private const val EASE_TAU_S = 0.1f
        private const val SYNC_EVERY_S = 1f
        private const val SOFT_MS = 20
        private const val HARD_MS = 80
        private const val BACK_MS = 10
    }
}
