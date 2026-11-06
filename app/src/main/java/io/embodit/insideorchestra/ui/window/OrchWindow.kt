package io.embodit.insideorchestra.ui.window

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.insideorchestra.R
import io.embodit.insideorchestra.domain.usecase.LevelGate
import io.embodit.insideorchestra.platform.OrchLog
import io.embodit.insideorchestra.ui.orch.OrchEvent
import io.embodit.insideorchestra.ui.orch.OrchViewModel
import io.embodit.insideorchestra.ui.orch.PAGE_LEARN
import io.embodit.insideorchestra.ui.orch.PAGE_PIECES
import io.embodit.insideorchestra.ui.orch.PAGE_PLAY
import io.embodit.insideorchestra.ui.orch.PAGE_SECTIONS
import io.embodit.insideorchestra.ui.orch.components.Copy
import io.embodit.insideorchestra.ui.orch.debug.LocalProbePanel
import io.embodit.insideorchestra.ui.theme.OrchTheme

/** Id and tag of the main window (tag != null: "reuse" mode, one instance). */
const val PANEL_ID = "OrchestraPanel"
const val PANEL_TAG = "main"

/** Window size (dp; ~0.8 mm per dp on the headset: 0.51 x 0.35 m at the system's 1.75 m, above the seated players' heads). */
const val WINDOW_W = 640f
const val WINDOW_H = 440f

/**
 * The process-wide link between the Stage and the window (native-window-move §3.4): the Stage owns the ViewModel and
 * publishes it here; the window reads it. Compose state, so the window recomposes when the Stage comes up.
 */
object OrchHub {
    var vm by mutableStateOf<OrchViewModel?>(null)
}

/**
 * The main window's lifecycle as the Stage sees it, and every open of it (native-window-move §3.5 / §7.9): the window
 * reports its lifecycle, the Stage opens / recalls it with its navigator. Main thread only.
 */
object WindowCtl {
    /** Last lifecycle event of the window ("ON_CREATE" … "ON_DESTROY"), "never" before the first open. */
    var panelState by mutableStateOf("never")
        private set

    var createdEpoch by mutableIntStateOf(0)
        private set

    /** The Stage's navigator while the Stage is composed. */
    var navigator: SpatialNavigator? = null

    private val main = Handler(Looper.getMainLooper())
    private var recallPending = false
    private var askedAtMs = 0L

    /**
     * D9: the system opens the window square to the line of sight, head pitch included, and nothing can re-level it
     * afterwards; so every open (first, chip, Bring here) waits here for a level head. The Stage steps it each frame.
     */
    private val gate = LevelGate()
    private var lastStepMs = 0L

    /** The Stage chip's "平视前方 / Look straight ahead": an open waits and the head is not level. */
    var levelHint by mutableStateOf(false)
        private set

    val open: Boolean
        get() = panelState != "never" && panelState != "ON_DESTROY" && panelState != "ON_STOP"

    /** The Stage shows "打开面板" while the window is closed / minimised, or never came up within 8 s of asking. */
    fun chipWanted(now: Long): Boolean =
        if (gate.waiting) false
        else when (panelState) {
            "ON_DESTROY", "ON_STOP" -> !recallPending
            "never" -> askedAtMs > 0 && now - askedAtMs > 8000
            else -> false
        }

    /** Ask for the window: it opens once the head is level (or anyway when the Stage sends no frames, [watchdog]). */
    fun openWindow(why: String) {
        val now = SystemClock.elapsedRealtime()
        OrchLog.i("WINDOW ask why=$why state=$panelState (opens when the head is level)")
        gate.ask(why, now)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** One Stage frame: [pitchDeg] = head pitch (+ up), null while the head pose is not valid yet. */
    fun stepGate(now: Long, pitchDeg: Float?) {
        lastStepMs = now
        if (!gate.waiting) {
            if (levelHint) levelHint = false
            return
        }
        val hint = gate.hintWanted(pitchDeg)
        if (hint != levelHint) {
            levelHint = hint
            OrchLog.i(String.format(java.util.Locale.US, "LEVEL hint=%s pitch=%.1f waited=%d ms", hint, pitchDeg ?: Float.NaN, gate.waitedMs(now)))
        }
        val why = gate.step(now, pitchDeg) ?: return
        levelHint = false
        OrchLog.i(String.format(java.util.Locale.US, "LEVEL ok pitch=%.1f -> open", pitchDeg ?: Float.NaN))
        openNow(why)
    }

    /** No Stage frames for a while: do not hold the window back (headset cold start: frames start ~6 s late). */
    private val watchdog =
        object : Runnable {
            override fun run() {
                if (!gate.waiting) return
                val now = SystemClock.elapsedRealtime()
                val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > FIRST_FRAME_MS else now - lastStepMs > WATCHDOG_MS
                if (stalled) {
                    val why = gate.pending ?: return
                    gate.cancel()
                    levelHint = false
                    openNow("$why/noframes")
                } else main.postDelayed(this, WATCHDOG_MS)
            }
        }

    private fun openNow(why: String) {
        val nav = navigator ?: return OrchLog.w("WINDOW open why=$why: no navigator")
        askedAtMs = SystemClock.elapsedRealtime()
        OrchLog.i("WINDOW open why=$why state=$panelState")
        runCatching { nav.openWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { OrchLog.w("WINDOW open failed: $it") }
    }

    /** The chip: a minimised window is restored where it was, a closed one opens in front of you. */
    fun reopen(why: String) {
        if (panelState == "ON_STOP" && navigator?.let { runCatching { it.restoreWindowContainer(PANEL_ID, PANEL_TAG) }.getOrDefault(false) } == true) {
            OrchLog.i("WINDOW restore why=$why")
            return
        }
        openWindow(why)
    }

    /**
     * "叫回面前 / Bring here" (D21): the system has no call to move a window, and a second open of an open window only
     * focuses it, so a recall closes it and opens it again once it reported ON_DESTROY (an open right after close is
     * swallowed, §3.5); the new window comes up in front of the user, level (the gate).
     */
    fun recall(why: String) {
        if (panelState == "never" || panelState == "ON_DESTROY") return openWindow(why)
        val nav = navigator ?: return
        recallPending = true
        OrchLog.i("WINDOW recall why=$why: close, then open after ON_DESTROY")
        runCatching { nav.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure {
            OrchLog.w("WINDOW close failed: $it")
            recallPending = false
        }
    }

    fun onLifecycle(event: String) {
        panelState = event
        OrchLog.i("WINDOW lifecycle $event")
        if (event == "ON_CREATE") createdEpoch++
        if (event == "ON_DESTROY" && recallPending) {
            recallPending = false
            main.postDelayed({ openWindow("recall") }, 300)
        }
    }

    /** Quit: close the window first (it would otherwise outlive the Stage in the Shared Space, §7.7). */
    fun closeForQuit() {
        recallPending = false
        gate.cancel()
        levelHint = false
        main.removeCallbacks(watchdog)
        runCatching { navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }

    private const val WATCHDOG_MS = 3000L
    private const val FIRST_FRAME_MS = 15000L
}

/**
 * The content of the one system window (v0.1.5, user 2026-11-05: "所有 UI 都集中在一个面板上…bring here…可以拖动的
 * native tab bar"): pages 演奏 (piece, transport, caption, master volume, Back to podium, Move orchestra; the placing
 * steps while placing), 曲目 (six pieces), 声部 (four sections: volume, 低 / 中 / 高, mute, solo), 乐器 (the family card),
 * then 设置 with 退出应用 last. Pages switch on the system TabBar; the system moves / closes / minimises the window.
 */
@Composable
fun OrchWindow() {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.onLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    OrchTheme {
        CompositionLocalProvider(LocalProbePanel provides "window") {
            val vm = OrchHub.vm
            if (vm == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "…", color = PicoTheme.colorScheme.labelPrimary)
                }
                return@CompositionLocalProvider
            }
            val state by vm.state.collectAsStateWithLifecycle()
            val en = state.english
            val onEvent: (OrchEvent) -> Unit = vm::onEvent
            MainWindow(
                pages =
                    listOf(
                        MainWindowPage(PAGE_PLAY, Copy.tabPlay(en), R.drawable.ic_music) { PlayPage(state, vm.piece, onEvent) },
                        MainWindowPage(PAGE_PIECES, Copy.tabPieces(en), R.drawable.ic_queue_music) { PiecesPage(state, vm.pieces, onEvent) },
                        MainWindowPage(PAGE_SECTIONS, Copy.tabSections(en), R.drawable.ic_tune) { SectionsPage(state, onEvent) },
                        MainWindowPage(PAGE_LEARN, Copy.tabLearn(en), R.drawable.ic_school) { LearnPage(state, vm.piece, onEvent) },
                    ),
                settings = MainWindowSettings(Copy.tabSettings(en), R.drawable.ic_settings) { SettingsPage(state, onEvent) },
                current = state.page,
                onSelect = { id -> onEvent(OrchEvent.PageSelected(id)) },
            )
        }
    }
}
