package io.embodit.insideorchestra.ui.orch.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pico.spatial.ui.design.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Text
import io.embodit.insideorchestra.R
import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.domain.model.Piece
import io.embodit.insideorchestra.ui.orch.AudioState
import io.embodit.insideorchestra.ui.orch.OrchEvent
import io.embodit.insideorchestra.ui.orch.OrchUiState
import io.embodit.insideorchestra.ui.orch.Room
import io.embodit.insideorchestra.ui.orch.Transport
import io.embodit.insideorchestra.ui.orch.debug.probe
import io.embodit.insideorchestra.ui.orch.scene.PanelSpec
import io.embodit.insideorchestra.ui.theme.OrchColors

/**
 * Title row of both control forms: the drag handle (D21: the panel is fixed in the room and moved only by this),
 * title, the 1:2 chip, mute (D15) and More.
 */
@Composable
private fun ControlTitle(state: OrchUiState, onEvent: (OrchEvent) -> Unit, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val en = state.english
    TitleRow(
        title = Copy.title(en),
        leading = { DragHandle(Copy.dragHint(en), onDrag, onDragEnd) },
        trailing = {
            if (state.scale < 1f) SmallChip(Copy.halfChip(en))
            Key("mute", if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume, Copy.muted(en, state.muted), active = state.muted) { onEvent(OrchEvent.MuteClicked) }
            Key("more", R.drawable.ic_more, "More", active = state.menuOpen) { onEvent(OrchEvent.MoreClicked) }
        },
    )
}

/**
 * The control panel while starting and placing (S0 / S1 / S2 / S2a / S13 / S14 / S17, 440 x 184): title, the hint
 * (with a spinner while getting ready), and while placing [Turn 45] and [Move to marker] (v0.1.1, D1: the marker is
 * set with the controller ray or the eyes on the floor; the orchestra never appears from the head's direction).
 */
@Composable
internal fun PlacingPanel(state: OrchUiState, filled: Boolean, onEvent: (OrchEvent) -> Unit, onDrag: (Float, Float) -> Unit = { _, _ -> }, onDragEnd: () -> Unit = {}) {
    val (w, h) = PanelSpec.PLACING_SIZE
    val en = state.english
    Glass(w.dp, h.dp, refreshKey = listOf(filled, state.room, state.snapped, state.tooClose, state.english, state.muted, state.scale, state.virtualRoom, state.noWall, state.marker, state.headPointer)) {
        if (!filled) return@Glass
        ControlTitle(state, onEvent, onDrag, onDragEnd)
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.room == Room.STARTING || state.room == Room.FINDING) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(28.dp))
            }
            Text(text = Copy.hint(state), color = if (state.tooClose) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelPrimary, style = OrchType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("hint"))
        }
        if (state.room == Room.PLACING) {
            // v0.1.2: placing ends here: [Confirm] locks the orchestra; [Re-place] drops the marker; [Cancel] after [Move]
            Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WordButton("turn45", Copy.turn45(en), { onEvent(OrchEvent.Turn45Clicked) }, enabled = state.marker, minWidth = 0.dp, modifier = Modifier.weight(1f))
                WordButton("unmark", Copy.replaceMarker(en), { onEvent(OrchEvent.ReplaceMarkerClicked) }, enabled = state.marker, minWidth = 0.dp, modifier = Modifier.weight(1f))
                if (state.placeMoving) WordButton("cancel", Copy.cancel(en), { onEvent(OrchEvent.MoveCancelClicked) }, minWidth = 0.dp, modifier = Modifier.weight(1f))
                WordButton("confirm", Copy.confirm(en), { onEvent(OrchEvent.PlaceAtMarkerClicked) }, primary = true, enabled = state.marker && !state.tooClose, iconRes = R.drawable.ic_check, minWidth = 0.dp, modifier = Modifier.weight(1.2f))
            }
        }
    }
}

/**
 * The side panel once placed (S3 - S19, 440 x 220; v0.1.1): title with the 1:2 chip, mute and More; the piece;
 * transport (play / pause, from the start, progress, time, or [Play again] at the end); the hint line with
 * [Back to place] / [Retry]. Section controls live on the bars above the sections ([SectionBar]).
 */
@Composable
internal fun ControlPanel(state: OrchUiState, filled: Boolean, onEvent: (OrchEvent) -> Unit, onDrag: (Float, Float) -> Unit = { _, _ -> }, onDragEnd: () -> Unit = {}) {
    val (w, h) = PanelSpec.CONTROL_SIZE
    val en = state.english
    Glass(w.dp, h.dp, refreshKey = listOf(filled, state.room, state.transport, state.english, state.solo, state.audio, state.muted, state.scale, state.moved)) {
        if (!filled) return@Glass
        ControlTitle(state, onEvent, onDrag, onDragEnd)
        Text(text = Copy.pieceLine(en), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(20.dp).probe("piece"))
        TransportRow(state, onEvent)
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = Copy.hint(state), color = PicoTheme.colorScheme.labelPrimary, style = OrchType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe("hint"))
            if (state.audio == AudioState.FAILED) {
                WordButton("retry", Copy.retry(en), { onEvent(OrchEvent.RetryAudioClicked) }, iconRes = R.drawable.ic_retry, minWidth = 96.dp, height = 40.dp)
            }
            // v0.1.2: the orchestra is locked; this worded button is the only way to move it again
            WordButton("move", Copy.moveOrchestra(en), { onEvent(OrchEvent.MoveOrchestraClicked) }, iconRes = R.drawable.ic_move, minWidth = 0.dp, height = 40.dp)
        }
    }
}

@Composable
private fun TransportRow(state: OrchUiState, onEvent: (OrchEvent) -> Unit) {
    val en = state.english
    var dragging by remember { mutableStateOf<Float?>(null) }
    val audioOk = state.audio != AudioState.FAILED
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.transport) {
            Transport.DONE -> WordButton("again", Copy.again(en), { onEvent(OrchEvent.AgainClicked) }, primary = true, iconRes = R.drawable.ic_redo, minWidth = 132.dp)
            Transport.PLAYING -> Key("pause", R.drawable.ic_pause, "Pause", primary = true) { onEvent(OrchEvent.PauseClicked) }
            else -> Key("play", R.drawable.ic_play, "Play", primary = true, enabled = audioOk) { onEvent(OrchEvent.PlayClicked) }
        }
        if (state.transport != Transport.DONE) Key("restart", R.drawable.ic_redo, "From the start") { onEvent(OrchEvent.RestartClicked) }
        val pos = dragging ?: state.posS
        Slider(
            pos.coerceIn(0f, state.durationS),
            { v: Float -> dragging = v },
            Modifier.weight(1f).probe("progress"),
            0f..state.durationS,
            {
                dragging?.let { onEvent(OrchEvent.SeekReleased(it)) }
                dragging = null
            },
            colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = PicoTheme.colorScheme.interaction, progressHighColor = PicoTheme.colorScheme.interaction),
        )
        Text(text = "${Copy.time(pos)} / ${Copy.time(state.durationS)}", color = PicoTheme.colorScheme.labelPrimary, style = OrchType.figure, maxLines = 1, modifier = Modifier.width(92.dp).probe("time"), textAlign = TextAlign.End)
    }
}

/**
 * A section bar (v0.1.1, 440 x 72 dp, floating above its section and turned to the user): family dot + name, one
 * small dot per part (size and brightness = the part's envelope: the BeatMap), [Solo] [Mute] [Info]. Solo = the
 * whole section alone (tap again = everyone), Mute = this section silent (state on the button), Info = its card.
 */
@Composable
internal fun SectionBar(f: Family, state: OrchUiState, piece: Piece, beat: MutableFloatState, filled: Boolean, onEvent: (OrchEvent) -> Unit) {
    val (w, h) = PanelSpec.BAR_SIZE
    val en = state.english
    val solo = state.solo
    val soloOn = solo != null && solo.family == f
    val muted = f in state.mutedFamilies
    val infoOn = state.cardShown == f
    val dimmed = (solo != null && solo.family != f) || muted
    Glass(w.dp, h.dp, corner = 24.dp, padding = 10.dp, spacing = 0.dp, refreshKey = listOf(filled, en, soloOn, muted, infoOn, dimmed)) {
        if (!filled) return@Glass
        Row(modifier = Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(OrchColors.of(f), 14.dp)
            Text(text = f.name(en), color = if (dimmed) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary, style = OrchType.title, maxLines = 1, softWrap = false, modifier = Modifier.width(if (en) 96.dp else 40.dp).probe("bar_${f.id}_name"))
            val parts = f.parts
            val soloPart = solo?.seatId?.let { io.embodit.insideorchestra.domain.model.Orchestra.seat(it)?.part }
            Canvas(modifier = Modifier.size(width = if (en) 40.dp else 80.dp, height = 24.dp)) {
                val t = beat.floatValue
                val gap = size.width / parts.size
                parts.forEachIndexed { i, p ->
                    val e = if (t >= 0f) piece.envelope(p, t) else 0f
                    val lit = if (dimmed || (solo != null && !solo.wholeFamily && solo.family == f && p != soloPart)) 0.3f else 1f
                    val r = size.height * (0.10f + 0.18f * e)
                    drawCircle(Color.White, radius = r, center = Offset(gap * (i + 0.5f), size.height / 2f), alpha = (0.35f + 0.65f * e) * lit)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            BarButton("bar_${f.id}_solo", Copy.solo(en), soloOn) { onEvent(OrchEvent.FamilyChipClicked(f)) }
            BarButton("bar_${f.id}_mute", Copy.sectionMute(en, muted), muted) { onEvent(OrchEvent.FamilyMuteClicked(f)) }
            BarButton("bar_${f.id}_info", Copy.info(en), infoOn) { onEvent(OrchEvent.FamilyInfoClicked(f)) }
        }
    }
}

/** A worded 48 dp bar button; [on] = toggled (interaction fill with ink), else fillPrimary. */
@Composable
private fun BarButton(name: String, text: String, on: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 60.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillPrimary, contentColor = if (on) OrchColors.InkOnFill else PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        Text(text = text, color = if (on) OrchColors.InkOnFill else PicoTheme.colorScheme.labelPrimaryLight, style = OrchType.figure, maxLines = 1, softWrap = false)
    }
}

/** The drag handle (40 x 48): press and drag moves the whole side panel in its own plane (D21). */
@Composable
private fun DragHandle(description: String, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Box(
        modifier =
            Modifier
                .size(40.dp, 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PicoTheme.colorScheme.fillTertiary)
                .probe("drag_handle")
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, amount ->
                        change.consume()
                        onDrag(amount.x / density, amount.y / density)
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(R.drawable.ic_move), contentDescription = description, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
    }
}

@Composable
internal fun SmallChip(text: String) {
    Box(modifier = Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(PicoTheme.colorScheme.fillSecondary).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
internal fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun Gap(h: androidx.compose.ui.unit.Dp) = Spacer(modifier = Modifier.height(h))
