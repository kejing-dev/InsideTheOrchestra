package io.embodit.insideorchestra.ui.orch.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.insideorchestra.domain.model.Piece
import io.embodit.insideorchestra.ui.orch.OrchEvent
import io.embodit.insideorchestra.ui.orch.OrchUiState
import io.embodit.insideorchestra.ui.orch.debug.LocalProbePanel
import io.embodit.insideorchestra.ui.orch.scene.OrchScene
import io.embodit.insideorchestra.ui.orch.scene.PanelSpec

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every attachment
 * panel is declared here once, with a fixed size, whether it is showing or not; the scene parents each panel
 * entity under its holder and shows or hides the holder.
 *
 * Window model: single panel with popup. The control panel (placing or placed form) is the only standing panel;
 * menu, card and snackbar are floating surfaces.
 */
@Composable
internal fun OrchStage(state: OrchUiState, piece: Piece, scene: OrchScene, onEvent: (OrchEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { _, value -> scene.onSpatialDrag("start", value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", value) },
                    ) { value ->
                        scene.onSpatialDrag("move", value)
                    }
                },
        attachments = {
            AttachmentPanel(PanelSpec.CONTROL) {
                PoolPanel(PanelSpec.CONTROL, scene, watched = true) { inView -> ControlPanel(state = state, filled = inView, onEvent = onEvent, onDrag = scene::dragSide, onDragEnd = scene::endSideDrag) }
            }
            AttachmentPanel(PanelSpec.PLACING) {
                PoolPanel(PanelSpec.PLACING, scene, watched = true) { inView -> PlacingPanel(state = state, filled = inView, onEvent = onEvent, onDrag = scene::dragSide, onDragEnd = scene::endSideDrag) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, watched = true) { inView -> MoreMenu(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.CARD) {
                PoolPanel(PanelSpec.CARD, scene) { inView -> CardPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            // v0.1.1: one bar above each section (dot, name, BeatMap dots, Solo / Mute / Info)
            for (f in io.embodit.insideorchestra.domain.model.Family.entries) {
                AttachmentPanel(PanelSpec.bar(f)) {
                    PoolPanel(PanelSpec.bar(f), scene) { inView -> SectionBar(f = f, state = state, piece = piece, beat = scene.beatTime, filled = inView, onEvent = onEvent) }
                }
            }
            AttachmentPanel(PanelSpec.RECALL) {
                PoolPanel(PanelSpec.RECALL, scene) { inView -> RecallPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.SNACK) {
                PoolPanel(PanelSpec.SNACK, scene) { inView -> SnackPanel(snack = if (inView) state.snack else null, state = state, piece = piece) }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled panel must draw
 * its content only then (what it draws while parked out of sight stays broken on the emulator). [watched]:
 * presses on it hold the lazy follow still.
 */
@Composable
private fun PoolPanel(id: String, scene: OrchScene, watched: Boolean = false, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) {
        if (watched) {
            Box(
                modifier =
                    Modifier.pointerInput(id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) scene.onPanelPointer(true)
                                if (event.type == PointerEventType.Release) scene.onPanelPointer(false)
                            }
                        }
                    }
            ) {
                content(inView)
            }
        } else {
            content(inView)
        }
    }
}
