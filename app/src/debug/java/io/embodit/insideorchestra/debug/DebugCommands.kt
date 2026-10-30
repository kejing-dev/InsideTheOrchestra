package io.embodit.insideorchestra.debug

import android.content.Context
import io.embodit.insideorchestra.platform.OrchLog
import io.embodit.insideorchestra.ui.orch.OrchEvent
import io.embodit.insideorchestra.ui.orch.debug.OrchProbe
import io.embodit.insideorchestra.ui.orch.scene.PanelSpec
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `debugHooks`). The design's `key=value` words, in one string or as separate
 * extras, applied in a fixed order (settings, freeze, room, actions, queries):
 *
 *   adb shell "am broadcast -a io.embodit.insideorchestra.DEBUG -p io.embodit.insideorchestra --es cmd 'place=auto play=1,pos=95 freeze=1'"
 *
 * Keys: `state=S0|S1|S2|S13|S14|S17`, `place=auto|wall`, `near=1`, `floor_timeout=<s>`, `wallmin=<m>`, `play=1|0`,
 * `pause=1`, `pos=<s>`, `done=1`, `solo=str|ww|br|perc|<stem>|<seat>|none`, `item=<instrument>`, `hover=<seat>|0`
 * (`hover=VCa,focus=1`), `pull=<seat>`, `orbit=<deg>`, `push=<m>`, `home=1`, `mute=0|1`,
 * `stand=vln|vla|vc|cb|ww|hn|tpt|lbr|timp|bd|podium`, `scale=1|2`, `lang=zh|en|system`, `captions=0|1`,
 * `caption=<pass>`, `coach=1`, `coach_reset=1`, `audiofail=1`, `retry=1`, `freeze=0|1`, `menu=0|1`, `replace=1`,
 * `turn45=1`, `exit=1`; v0.1.1: `marker=<m>` (floor marker that far ahead, as if set by a ray), `atmarker=1`
 * ([Move to marker]), `recall=1` (side panel back in front), `sidedrag=<dx>,<dy>` (drag handle, dp), `fmute=<family>|none` (section bar mute), `info=<family>|none` (section bar card); queries `dump=1` (one JSON line `DUMP {...}`), `hmd=1`, `bounds=1`, `planes=1`, `park=1`,
 * `recenter=1`, `ui=<control>` (presses a panel control by its probe name: play, pause, restart, again, more,
 * mute, chip_str, chip_ww, chip_br, chip_perc, card_pull, card_restore, home, retry, turn45, captions_on,
 * captions_off, lang_system, lang_zh, lang_en, size_full, size_half, menu_home, menu_replace, menu_exit,
 * room_l_plus ...). Every state change logs `STATE Sxx overlays=...`; every second `CLOCK ...`.
 */
internal object DebugCommands {

    /** Application order: settings first, then the room, then what happens in it, then queries. */
    private val ORDER =
        listOf(
            "lang", "captions", "scale", "wallmin", "floor_timeout", "coach_reset", "mute", "freeze_off",
            "state", "marker", "atmarker", "place", "near", "pos", "play", "pause", "done", "solo", "item", "hover", "focus", "pull", "orbit", "push",
            "home", "stand", "fmute", "info", "recall", "sidedrag", "caption", "coach", "audiofail", "retry", "freeze", "menu", "replace", "turn45", "ui",
            "dump", "hmd", "bounds", "planes", "park", "recenter", "exit",
        )

    fun run(context: Context, extras: Map<String, String>) {
        val host = OrchProbe.host
        if (host == null) {
            OrchLog.i("CMD ignored: the Stage is not running")
            return
        }
        val pairs = LinkedHashMap<String, String>()
        extras["cmd"]?.let { raw -> parse(raw).forEach { (k, v) -> pairs[k] = v } }
        for ((k, v) in extras) if (k != "cmd") pairs[k.lowercase(Locale.US)] = v
        // freeze goes before the room keys: a frozen demo does not run on, a frozen strike holds its swing
        pairs["freeze"]?.let { if (it == "0" || it == "off") { pairs.remove("freeze"); pairs["freeze_off"] = "0" } }
        val keys = pairs.keys.sortedBy { k -> ORDER.indexOf(k).let { if (it < 0) ORDER.size else it } }
        for (k in keys) {
            val v = pairs[k] ?: continue
            try {
                apply(context, host, if (k == "freeze_off") "freeze" else k, v)
            } catch (t: Throwable) {
                OrchLog.e("CMD $k=$v failed: $t", t)
            }
        }
    }

    /**
     * `a=1,b=2 c=3` -> pairs; a comma or space only separates pairs when a `key=` follows, so
     * `strike=6,zheng` keeps its value. A bare word is a key with value "1" (`dump`, `S5` -> state).
     */
    fun parse(raw: String): List<Pair<String, String>> =
        raw.trim().split(Regex("\\s+"))
            .flatMap { chunk -> chunk.split(Regex(",(?=[A-Za-z_]+=)")) }
            .map { it.trim().trim(',') }
            .filter { it.isNotEmpty() }
            .map { token ->
                val i = token.indexOf('=')
                if (i < 0) {
                    if (token.uppercase(Locale.US).matches(Regex("S\\d+A?"))) "state" to token else token.lowercase(Locale.US) to "1"
                } else token.substring(0, i).lowercase(Locale.US) to token.substring(i + 1)
            }

    private fun apply(context: Context, host: OrchProbe.Host, key: String, value: String) {
        val scene = host.scene()
        when (key) {
            "ui" -> OrchLog.i("UI $value pressed=${host.pressControl(value)}")
            "dump" -> {
                val json = host.dumpJson()
                OrchLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "hmd" -> OrchLog.i(scene?.poseLine() ?: NO_SCENE)
            "bounds" -> {
                scene?.panelLines()?.forEach { OrchLog.i(it) } ?: OrchLog.i(NO_SCENE)
                for (panel in PanelSpec.pool) {
                    for ((ctrl, b) in OrchProbe.controlsOf(panel)) {
                        OrchLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, ctrl, b.cx, b.cy, b.w, b.h))
                    }
                }
            }
            "planes" -> scene?.planesSummary()?.forEach { OrchLog.i(it) } ?: OrchLog.i(NO_SCENE)
            "park" -> OrchLog.i(scene?.parkPanel() ?: NO_SCENE)
            "recenter" -> scene?.recenter() ?: OrchLog.i(NO_SCENE)
            else -> host.dispatch(OrchEvent.Debug(key, value))
        }
    }

    private const val NO_SCENE = "CMD ignored: no scene"
}
