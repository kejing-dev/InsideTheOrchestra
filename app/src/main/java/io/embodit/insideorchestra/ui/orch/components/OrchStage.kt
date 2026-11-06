package io.embodit.insideorchestra.ui.orch.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.insideorchestra.R
import io.embodit.insideorchestra.ui.orch.OrchUiState
import io.embodit.insideorchestra.ui.orch.debug.LocalProbePanel
import io.embodit.insideorchestra.ui.orch.scene.OrchScene
import io.embodit.insideorchestra.ui.orch.scene.PanelSpec
import io.embodit.insideorchestra.ui.window.FitText
import io.embodit.insideorchestra.ui.window.WindowCtl

/**
 * The Stage content: one full-size SpatialView with the 3D scene. v0.1.5 (D8 / D21, user 2026-11-05 "所有 UI 都集中在
 * 一个面板上"): every control is in the one system window; the Stage keeps only the chip that brings that window back
 * ("打开面板") or asks for a level head before it opens ("平视前方"). Taps and drags on the orchestra stay here.
 */
@Composable
internal fun OrchStage(state: OrchUiState, scene: OrchScene) {
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
            AttachmentPanel(PanelSpec.CHIP) {
                CompositionLocalProvider(LocalProbePanel provides PanelSpec.CHIP) {
                    WindowChip(state, inView = scene.panelShown[PanelSpec.CHIP] == true)
                }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/** "打开面板 [Open]" while the window is closed / minimised; "平视前方，面板就出来" while an open waits for a level head. */
@Composable
private fun WindowChip(state: OrchUiState, inView: Boolean) {
    val (w, h) = PanelSpec.CHIP_SIZE
    val en = state.english
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (!inView) return@Box
        val waiting = WindowCtl.levelHint
        Row(
            modifier = Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(36.dp)).backgroundMaterial(enable = true, style = Material.Thick).padding(start = 20.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FitText("chip_text", if (waiting) Copy.levelHint(en) else Copy.title(en), OrchType.body, PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
            if (!waiting) WordButton("open_panel", Copy.openPanel(en), { WindowCtl.reopen("chip") }, primary = true, iconRes = R.drawable.ic_pull, minWidth = 0.dp)
        }
    }
}
