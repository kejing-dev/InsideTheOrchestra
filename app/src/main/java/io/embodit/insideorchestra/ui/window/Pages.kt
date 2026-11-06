package io.embodit.insideorchestra.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Divider
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Text
import io.embodit.insideorchestra.R
import io.embodit.insideorchestra.domain.model.Cards
import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.domain.model.Piece
import io.embodit.insideorchestra.domain.model.PieceInfo
import io.embodit.insideorchestra.domain.usecase.VolumeSnap
import io.embodit.insideorchestra.platform.OrchLog
import io.embodit.insideorchestra.ui.orch.AudioState
import io.embodit.insideorchestra.ui.orch.LanguagePref
import io.embodit.insideorchestra.ui.orch.OrchEvent
import io.embodit.insideorchestra.ui.orch.OrchUiState
import io.embodit.insideorchestra.ui.orch.Room
import io.embodit.insideorchestra.ui.orch.Transport
import io.embodit.insideorchestra.ui.orch.components.Copy
import io.embodit.insideorchestra.ui.orch.components.Key
import io.embodit.insideorchestra.ui.orch.components.KeyHeight
import io.embodit.insideorchestra.ui.orch.components.OrchType
import io.embodit.insideorchestra.ui.orch.components.WordButton
import io.embodit.insideorchestra.ui.orch.debug.LocalProbePanel
import io.embodit.insideorchestra.ui.orch.debug.probe
import io.embodit.insideorchestra.ui.theme.OrchColors

private val Gap = 12.dp

/** A text that logs `OVERFLOW <panel>/<name>` when it does not fit (D13: no silent ellipsis). */
@Composable
internal fun FitText(name: String, text: String, style: TextStyle, color: Color = PicoTheme.colorScheme.labelPrimary, maxLines: Int = 1, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    val panel = LocalProbePanel.current
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier.probe(name),
        onTextLayout = { if (it.hasVisualOverflow) OrchLog.w("OVERFLOW $panel/$name text=\"$text\"") },
    )
}

// =============================================================================================
// 演奏 / Play
// =============================================================================================

/**
 * The Play page: the piece and [Bring here]; then either the placing steps (only after [Move orchestra]; the app starts
 * with the user on the podium) or the caption line, transport, master volume with 低 / 中 / 高 and mute, and
 * [Back to podium] [Move orchestra].
 */
@Composable
internal fun PlayPage(state: OrchUiState, piece: Piece, onEvent: (OrchEvent) -> Unit) {
    val en = state.english
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            Column(modifier = Modifier.weight(1f)) {
                FitText("piece_title", piece.title.of(en).ifEmpty { Copy.title(en) }, PicoTheme.typography.titleLarge)
                FitText("piece_composer", "${piece.composer.of(en)} · ${Copy.time(state.durationS)}", PicoTheme.typography.bodyMedium, PicoTheme.colorScheme.labelSecondary)
            }
            WordButton("bring", Copy.bringHere(en), { onEvent(OrchEvent.BringHereClicked) }, iconRes = R.drawable.ic_pull, minWidth = 0.dp)
        }
        if (!state.room.placed) {
            PlacingSteps(state, onEvent)
            return@Column
        }
        Box(modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
            FitText("hint", Copy.line(state, piece), OrchType.body, if (state.tooClose || state.audio == AudioState.FAILED) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelPrimary, maxLines = 2)
        }
        TransportRow(state, onEvent)
        VolumeRow("master", Copy.master(en), state.masterVol, state.muted, en, { v, done -> onEvent(OrchEvent.MasterVolume(v, done)) }) { onEvent(OrchEvent.MuteClicked) }
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            WordButton("podium", Copy.podium(en), { onEvent(OrchEvent.PodiumClicked) }, primary = state.near?.podium != true, iconRes = R.drawable.ic_podium, minWidth = 0.dp, modifier = Modifier.weight(1f))
            WordButton("move", Copy.moveOrchestra(en), { onEvent(OrchEvent.MoveOrchestraClicked) }, iconRes = R.drawable.ic_move, minWidth = 0.dp, modifier = Modifier.weight(1f))
            if (state.audio == AudioState.FAILED) WordButton("retry", Copy.retry(en), { onEvent(OrchEvent.RetryAudioClicked) }, iconRes = R.drawable.ic_retry, minWidth = 0.dp)
        }
    }
}

@Composable
private fun PlacingSteps(state: OrchUiState, onEvent: (OrchEvent) -> Unit) {
    val en = state.english
    Row(modifier = Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
        if (state.room == Room.STARTING || state.room == Room.FINDING) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(28.dp))
        }
        FitText("hint", Copy.hint(state), OrchType.body, if (state.tooClose) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelPrimary, maxLines = 3, modifier = Modifier.weight(1f))
    }
    if (state.room == Room.PLACING) {
        // v0.1.2 (D1 "放置模式必须有终点"): [Confirm] locks the orchestra; [Re-place] drops the marker; [Cancel] after [Move]
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WordButton("turn45", Copy.turn45(en), { onEvent(OrchEvent.Turn45Clicked) }, enabled = state.marker, minWidth = 0.dp, modifier = Modifier.weight(1f))
            WordButton("unmark", Copy.replaceMarker(en), { onEvent(OrchEvent.ReplaceMarkerClicked) }, enabled = state.marker, minWidth = 0.dp, modifier = Modifier.weight(1f))
            if (state.placeMoving) WordButton("cancel", Copy.cancel(en), { onEvent(OrchEvent.MoveCancelClicked) }, minWidth = 0.dp, modifier = Modifier.weight(1f))
            WordButton("confirm", Copy.confirm(en), { onEvent(OrchEvent.PlaceAtMarkerClicked) }, primary = true, enabled = state.marker && !state.tooClose, iconRes = R.drawable.ic_check, minWidth = 0.dp, modifier = Modifier.weight(1.2f))
        }
    }
}

@Composable
private fun TransportRow(state: OrchUiState, onEvent: (OrchEvent) -> Unit) {
    val en = state.english
    var dragging by remember { mutableStateOf<Float?>(null) }
    val audioOk = state.audio == AudioState.READY
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state.transport) {
            Transport.DONE -> WordButton("again", Copy.again(en), { onEvent(OrchEvent.AgainClicked) }, primary = true, iconRes = R.drawable.ic_redo, minWidth = 132.dp)
            Transport.PLAYING -> Key("pause", R.drawable.ic_pause, Copy.pauseLabel(en), primary = true) { onEvent(OrchEvent.PauseClicked) }
            else -> Key("play", R.drawable.ic_play, Copy.playLabel(en), primary = true, enabled = audioOk) { onEvent(OrchEvent.PlayClicked) }
        }
        if (state.transport != Transport.DONE) Key("restart", R.drawable.ic_redo, Copy.fromStart(en)) { onEvent(OrchEvent.RestartClicked) }
        val pos = dragging ?: state.posS
        Slider(
            pos.coerceIn(0f, state.durationS),
            { v: Float -> dragging = v },
            Modifier.weight(1f).height(48.dp).probe("progress"),
            0f..state.durationS,
            {
                dragging?.let { onEvent(OrchEvent.SeekReleased(it)) }
                dragging = null
            },
            colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = PicoTheme.colorScheme.interaction, progressHighColor = PicoTheme.colorScheme.interaction),
        )
        FitText("time", "${Copy.time(pos)} / ${Copy.time(state.durationS)}", OrchType.figure, modifier = Modifier.width(96.dp), textAlign = TextAlign.End)
    }
}

/**
 * A volume block (user 2026-11-05: "需要有几个等级和滑动"): label, slider (0 - 100 %, catching at 低 / 中 / 高), the
 * percentage, then 低 / 中 / 高 keys and mute on the next row. [onChange] gets (value, released).
 */
@Composable
internal fun VolumeRow(name: String, label: String, value: Float, muted: Boolean, en: Boolean, onChange: (Float, Boolean) -> Unit, onMute: () -> Unit) {
    var last by remember { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painter = painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume), contentDescription = label, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
            FitText("${name}_label", label, OrchType.figure, modifier = Modifier.width(if (en) 80.dp else 56.dp))
            Slider(
                value.coerceIn(0f, 1f),
                { v: Float ->
                    last = v
                    onChange(v, false)
                },
                Modifier.weight(1f).height(48.dp).probe(name),
                0f..1f,
                { onChange(last, true) },
                colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = if (muted) PicoTheme.colorScheme.labelTertiary else PicoTheme.colorScheme.interaction, progressHighColor = PicoTheme.colorScheme.interaction),
            )
            FitText("${name}_pct", if (muted) Copy.mutedShort(en) else VolumeSnap.percent(value), OrchType.figure, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        }
        Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FitText("${name}_levels", Copy.levels(en), PicoTheme.typography.labelMedium, PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(if (en) 112.dp else 88.dp))
            Segments(
                listOf("${name}_low" to Copy.low(en), "${name}_mid" to Copy.mid(en), "${name}_high" to Copy.high(en)),
                VolumeSnap.level(value),
                { i -> onChange(VolumeSnap.DETENTS[i], true) },
                if (en) 76.dp else 64.dp,
                40.dp,
            )
            Spacer(modifier = Modifier.weight(1f))
            ToggleButton("${name}_mute", Copy.muted(en, muted), muted, onMute)
        }
    }
}

// =============================================================================================
// 曲目 / Pieces
// =============================================================================================

@Composable
internal fun PiecesPage(state: OrchUiState, pieces: List<PieceInfo>, onEvent: (OrchEvent) -> Unit) {
    val en = state.english
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FitText("pieces_title", Copy.piecesTitle(en, pieces.size), PicoTheme.typography.titleMedium)
        val pending = state.pendingPiece?.let { id -> pieces.firstOrNull { it.id == id } }
        if (pending != null) {
            // D44: a piece in progress is not dropped by one tap; the switch asks first, on this page
            Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
                FitText("switch_ask", Copy.switchAsk(en, pending.title.of(en)), OrchType.title, modifier = Modifier.weight(1f))
                WordButton("switch_cancel", Copy.cancel(en), { onEvent(OrchEvent.PieceSwitchAnswer(false)) }, minWidth = 88.dp)
                WordButton("switch_confirm", Copy.switchPiece(en), { onEvent(OrchEvent.PieceSwitchAnswer(true)) }, primary = true, minWidth = 88.dp)
            }
        }
        for ((i, p) in pieces.withIndex()) {
            val current = p.id == state.pieceId
            val onClick = { onEvent(OrchEvent.PieceSelected(p.id)) }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (current) PicoTheme.colorScheme.fillSecondary else PicoTheme.colorScheme.fillTertiary)
                        .probe("piece_$i", onClick)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gap),
            ) {
                Icon(painter = painterResource(if (current && state.transport == Transport.PLAYING) R.drawable.ic_volume else R.drawable.ic_music), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Column(modifier = Modifier.weight(1f)) {
                    FitText("piece_${i}_title", p.title.of(en), PicoTheme.typography.titleSmall)
                    FitText("piece_${i}_sub", "${p.composer.of(en)} · ${p.year} · ${Copy.time(p.durationS)}", PicoTheme.typography.bodySmall, PicoTheme.colorScheme.labelSecondary)
                }
                if (current) FitText("piece_${i}_now", if (state.audio == AudioState.LOADING) Copy.loading(en) else Copy.nowPlaying(en), OrchType.figure, PicoTheme.colorScheme.labelSecondary)
            }
        }
    }
}

// =============================================================================================
// 声部 / Sections
// =============================================================================================

/** The four sections (were four bars floating above the players): name, Solo, Mute, Info; volume with 低 / 中 / 高. */
@Composable
internal fun SectionsPage(state: OrchUiState, onEvent: (OrchEvent) -> Unit) {
    val en = state.english
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (f in Family.entries) {
            val solo = state.solo
            val soloOn = solo != null && solo.family == f && solo.wholeFamily
            val muted = f in state.mutedFamilies
            val v = state.familyVol[f] ?: 1f
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(OrchColors.of(f), 14.dp)
                    FitText("sec_${f.id}_name", f.name(en), OrchType.title, modifier = Modifier.width(if (en) 112.dp else 56.dp))
                    var last by remember(f) { mutableStateOf(v) }
                    Slider(
                        v.coerceIn(0f, 1f),
                        { x: Float ->
                            last = x
                            onEvent(OrchEvent.FamilyVolume(f, x, false))
                        },
                        Modifier.weight(1f).height(48.dp).probe("sec_${f.id}_vol"),
                        0f..1f,
                        { onEvent(OrchEvent.FamilyVolume(f, last, true)) },
                        colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = if (muted) PicoTheme.colorScheme.labelTertiary else PicoTheme.colorScheme.interaction, progressHighColor = PicoTheme.colorScheme.interaction),
                    )
                    FitText("sec_${f.id}_pct", if (muted) Copy.mutedShort(en) else VolumeSnap.percent(v), OrchType.figure, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                }
                Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segments(
                        listOf("sec_${f.id}_low" to Copy.low(en), "sec_${f.id}_mid" to Copy.mid(en), "sec_${f.id}_high" to Copy.high(en)),
                        VolumeSnap.level(v),
                        { i -> onEvent(OrchEvent.FamilyVolume(f, VolumeSnap.DETENTS[i], true)) },
                        if (en) 64.dp else 52.dp,
                        40.dp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ToggleButton("sec_${f.id}_solo", Copy.solo(en), soloOn) { onEvent(OrchEvent.FamilyChipClicked(f)) }
                    ToggleButton("sec_${f.id}_mute", Copy.sectionMute(en, muted), muted) { onEvent(OrchEvent.FamilyMuteClicked(f)) }
                    ToggleButton("sec_${f.id}_info", Copy.info(en), false) { onEvent(OrchEvent.FamilyInfoClicked(f)) }
                }
            }
        }
    }
}

// =============================================================================================
// 乐器 / Instruments (the family card)
// =============================================================================================

@Composable
internal fun LearnPage(state: OrchUiState, piece: Piece, onEvent: (OrchEvent) -> Unit) {
    val en = state.english
    val family = state.cardFamily
    val solo = state.solo?.takeIf { it.family == family }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            Text(text = Copy.sectionLabel(en), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelMedium, maxLines = 1)
            Segments(Family.entries.map { "learn_${it.id}" to it.short(en) }, family.ordinal, { i -> onEvent(OrchEvent.FamilyInfoClicked(Family.entries[i])) }, if (en) 104.dp else 84.dp, 40.dp)
            Spacer(modifier = Modifier.weight(1f))
            if (solo != null) Chip(Copy.soloChip(en))
        }
        FitText("learn_inpiece", Copy.inPieceLine(en, piece.familyNotes[family]?.of(en) ?: Cards.all.getValue(family).inPiece.of(en)), OrchType.body, maxLines = 3)
        Divider()
        val first = if (solo == null || solo.wholeFamily) null else solo.item
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (item in Copy.cardItems(family, first)) {
                val hi = first != null && item.id == first
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (hi) PicoTheme.colorScheme.fillSecondary else Color.Transparent).padding(horizontal = 8.dp, vertical = 4.dp).probe("card_item_${item.id}")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = if (en) item.en else item.zh, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleSmall, maxLines = 1)
                        Text(text = item.rangeText(en), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelSmall, maxLines = 1)
                    }
                    FitText("card_line_${item.id}", if (en) item.lineEn else item.lineZh, PicoTheme.typography.bodySmall, PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            if (solo != null) {
                WordButton("card_pull", Copy.pull(en), { onEvent(OrchEvent.PullClicked) }, primary = true, iconRes = R.drawable.ic_pull, minWidth = 0.dp, modifier = Modifier.weight(1f))
                WordButton("card_restore", Copy.restore(en), { onEvent(OrchEvent.RestoreClicked) }, minWidth = 0.dp, modifier = Modifier.weight(1f))
            } else {
                WordButton("card_solo", Copy.soloFamily(en, family.name(en)), { onEvent(OrchEvent.FamilyChipClicked(family)) }, minWidth = 0.dp, modifier = Modifier.weight(1f))
            }
        }
    }
}

// =============================================================================================
// 设置 / Settings (last tab; exit app at the bottom, D26)
// =============================================================================================

@Composable
internal fun ColumnScope.SettingsPage(state: OrchUiState, onEvent: (OrchEvent) -> Unit) {
    val en = state.english
    Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(Copy.menuCaptions(en)) {
            Segments(listOf("captions_on" to Copy.on(en), "captions_off" to Copy.off(en)), if (state.captionsOn) 0 else 1, { onEvent(OrchEvent.CaptionsSelected(it == 0)) }, 88.dp, 40.dp)
        }
        SettingRow(Copy.menuLanguage(en)) {
            Segments(
                listOf("lang_system" to Copy.system(en), "lang_zh" to "中文", "lang_en" to "English"),
                when (state.language) {
                    LanguagePref.SYSTEM -> 0
                    LanguagePref.ZH -> 1
                    LanguagePref.EN -> 2
                },
                { onEvent(OrchEvent.LanguageSelected(LanguagePref.entries[it])) },
                88.dp,
                40.dp,
            )
        }
        SettingRow(Copy.menuMode(en)) {
            Segments(listOf("mode_mr" to Copy.modeMr(en), "mode_vr" to Copy.modeVr(en)), if (state.vr) 1 else 0, { onEvent(OrchEvent.VrSelected(it == 1)) }, if (en) 112.dp else 88.dp, 40.dp)
        }
        SettingRow(Copy.menuSize(en)) {
            Segments(listOf("size_full" to "1:1", "size_half" to "1:2"), if (state.scale < 1f) 1 else 0, { onEvent(OrchEvent.ScaleSelected(if (it == 1) 0.5f else 1f)) }, 88.dp, 40.dp)
        }
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            WordButton("set_bring", Copy.bringHere(en), { onEvent(OrchEvent.BringHereClicked) }, iconRes = R.drawable.ic_pull, minWidth = 0.dp, modifier = Modifier.weight(1f))
            WordButton("set_podium", Copy.podium(en), { onEvent(OrchEvent.PodiumClicked) }, iconRes = R.drawable.ic_podium, minWidth = 0.dp, modifier = Modifier.weight(1f))
            WordButton("set_move", Copy.moveOrchestra(en), { onEvent(OrchEvent.MoveOrchestraClicked) }, iconRes = R.drawable.ic_move, minWidth = 0.dp, modifier = Modifier.weight(1f))
        }
        if (state.virtualRoom) {
            FitText("room_title", Copy.room(en), PicoTheme.typography.labelMedium, PicoTheme.colorScheme.labelSecondary)
            val r = state.room3
            RoomStepper("room_l", Copy.roomLength(en, r.length)) { d -> onEvent(OrchEvent.RoomResize(d * 0.2f, 0f, 0f)) }
            RoomStepper("room_w", Copy.roomWidth(en, r.width)) { d -> onEvent(OrchEvent.RoomResize(0f, d * 0.2f, 0f)) }
            RoomStepper("room_h", Copy.roomHeight(en, r.height)) { d -> onEvent(OrchEvent.RoomResize(0f, 0f, d * 0.2f)) }
        }
        FitText("credits", Copy.credits(en), PicoTheme.typography.bodySmall, PicoTheme.colorScheme.labelSecondary, maxLines = 3)
    }
    Divider()
    if (!state.exitConfirm) {
        WordButton("exit", Copy.exitApp(en), { onEvent(OrchEvent.ExitAsked(true)) }, iconRes = R.drawable.ic_exit, minWidth = 0.dp, modifier = Modifier.fillMaxWidth())
    } else {
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            FitText("exit_ask", Copy.exitAsk(en), OrchType.title, modifier = Modifier.weight(1f))
            WordButton("exit_cancel", Copy.cancel(en), { onEvent(OrchEvent.ExitAsked(false)) }, minWidth = 96.dp)
            WordButton("exit_confirm", Copy.exit(en), { onEvent(OrchEvent.ExitClicked) }, danger = true, minWidth = 96.dp)
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = OrchType.figure, maxLines = 1, modifier = Modifier.width(128.dp))
        content()
    }
}

/** A worded stepper (− value +), 0.2 m per step (design `I25`). */
@Composable
private fun RoomStepper(name: String, text: String, onStep: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("${name}_minus", R.drawable.ic_minus, "−") { onStep(-1) }
        FitText(name, text, OrchType.figure, modifier = Modifier.weight(1f))
        Key("${name}_plus", R.drawable.ic_plus, "+") { onStep(1) }
    }
}

// =============================================================================================
// small parts
// =============================================================================================

/** Options inside one page (volume levels, captions, language …); pages themselves switch on the system TabBar. */
@Composable
internal fun Segments(items: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit, itemWidth: Dp, height: Dp) {
    SegmentControl(modifier = Modifier.width(itemWidth * items.size).height(height), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemWidth).height(height).probe(name, onClick),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
            )
        }
    }
}

/** A worded 48 dp toggle; [on] = interaction fill with ink, else the primary fill. */
@Composable
internal fun ToggleButton(name: String, text: String, on: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 44.dp, minWidth = 64.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillPrimary, contentColor = if (on) OrchColors.InkOnFill else PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        FitText("${name}_text", text, OrchType.figure, if (on) OrchColors.InkOnFill else PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
private fun Chip(text: String) {
    Box(modifier = Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillSecondary).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun Dot(color: Color, size: Dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(color))
}
