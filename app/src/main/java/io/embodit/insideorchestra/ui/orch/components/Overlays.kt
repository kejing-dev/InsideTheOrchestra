package io.embodit.insideorchestra.ui.orch.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Divider
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.insideorchestra.R
import io.embodit.insideorchestra.domain.model.Piece
import io.embodit.insideorchestra.ui.orch.LanguagePref
import io.embodit.insideorchestra.ui.orch.OrchEvent
import io.embodit.insideorchestra.ui.orch.OrchUiState
import io.embodit.insideorchestra.ui.orch.SnackKind
import io.embodit.insideorchestra.ui.orch.SnackUi
import io.embodit.insideorchestra.ui.orch.debug.probe
import io.embodit.insideorchestra.ui.orch.scene.PanelSpec
import io.embodit.insideorchestra.ui.theme.OrchColors

private const val FADE_IN_MS = 150

/**
 * The More menu (S10, 300 x 392 dp at 0.8, above the control panel): captions, language, size, then Back to place
 * / Place again / Exit, and in a virtual room its length / width / height. Segments and steppers act in place and
 * leave the menu open; Back to place and Place again close it.
 */
@Composable
internal fun MoreMenu(state: OrchUiState, filled: Boolean, onEvent: (OrchEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    val en = state.english
    Glass(w.dp, h.dp, corner = 24.dp, material = Material.Thick, padding = 12.dp, spacing = 8.dp, refreshKey = listOf(filled, state.english, state.language, state.captionsOn, state.scale, state.virtualRoom, state.room3, state.moved, state.room)) {
        if (!filled) return@Glass
        MenuRow(Copy.menuCaptions(en)) {
            Segments(listOf("captions_on" to Copy.on(en), "captions_off" to Copy.off(en)), if (state.captionsOn) 0 else 1, { onEvent(OrchEvent.CaptionsSelected(it == 0)) }, 84.dp, 40.dp)
        }
        MenuRow(Copy.menuLanguage(en)) {
            Segments(
                listOf("lang_system" to null, "lang_zh" to "中文", "lang_en" to "EN"),
                when (state.language) {
                    LanguagePref.SYSTEM -> 0
                    LanguagePref.ZH -> 1
                    LanguagePref.EN -> 2
                },
                { onEvent(OrchEvent.LanguageSelected(LanguagePref.entries[it])) },
                56.dp,
                40.dp,
                leadingIcon = R.drawable.ic_globe,
            )
        }
        MenuRow(Copy.menuSize(en)) {
            Segments(listOf("size_full" to "1:1", "size_half" to "1:2"), if (state.scale < 1f) 1 else 0, { onEvent(OrchEvent.ScaleSelected(if (it == 1) 0.5f else 1f)) }, 84.dp, 40.dp)
        }
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WordButton("menu_home", Copy.home(en), { onEvent(OrchEvent.HomeClicked) }, enabled = state.moved, minWidth = 0.dp, height = 44.dp, modifier = Modifier.weight(1f))
            WordButton("menu_replace", Copy.replace(en), { onEvent(OrchEvent.ReplaceClicked) }, minWidth = 0.dp, height = 44.dp, modifier = Modifier.weight(1f))
            Key("menu_exit", R.drawable.ic_exit, Copy.exit(en)) { onEvent(OrchEvent.ExitClicked) }
        }
        WordButton("menu_recall", Copy.recall(en), { onEvent(OrchEvent.SideRecallClicked) }, iconRes = R.drawable.ic_pull, minWidth = 0.dp, height = 44.dp, modifier = Modifier.fillMaxWidth())
        if (state.virtualRoom) {
            Text(text = Copy.room(en), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelMedium, maxLines = 1)
            val r = state.room3
            RoomStepper("room_l", Copy.roomLength(en, r.length)) { d -> onEvent(OrchEvent.RoomResize(d * 0.2f, 0f, 0f)) }
            RoomStepper("room_w", Copy.roomWidth(en, r.width)) { d -> onEvent(OrchEvent.RoomResize(0f, d * 0.2f, 0f)) }
            RoomStepper("room_h", Copy.roomHeight(en, r.height)) { d -> onEvent(OrchEvent.RoomResize(0f, 0f, d * 0.2f)) }
        }
    }
}

@Composable
private fun MenuRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = OrchType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
        content()
    }
}

/** A worded stepper (− value +), 0.2 m per step (design `I25`). */
@Composable
private fun RoomStepper(name: String, text: String, onStep: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("${name}_minus", R.drawable.ic_minus, "−") { onStep(-1) }
        Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = OrchType.figure, maxLines = 1, modifier = Modifier.weight(1f).probe(name))
        Key("${name}_plus", R.drawable.ic_plus, "+") { onStep(1) }
    }
}

@Composable
internal fun Segments(items: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, itemWidth: Dp, height: Dp, leadingIcon: Int? = null) {
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
                title = {
                    if (text == null && leadingIcon != null) {
                        Icon(painter = painterResource(leadingIcon), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    } else {
                        // design-style: inherited-content-color SegmentItem
                        Text(text = text ?: "", maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    }
                },
            )
        }
    }
}

/**
 * The instrument card (S7, 360 x 420 dp): family dot + name + "Solo"; what the family does in this piece; the
 * family's instruments (name, approximate range, one line), the pointed one first and highlighted; [Bring closer]
 * and [Restore all].
 */
@Composable
internal fun CardPanel(state: OrchUiState, filled: Boolean, onEvent: (OrchEvent) -> Unit) {
    val (w, h) = PanelSpec.CARD_SIZE
    val family = if (filled) state.cardShown else null
    val solo = state.solo?.takeIf { it.family == family }
    val en = state.english
    Glass(w.dp, h.dp, refreshKey = listOf(filled, family, solo, en)) {
        if (family == null) return@Glass
        val card = io.embodit.insideorchestra.domain.model.Cards.all.getValue(family)
        Row(modifier = Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dot(OrchColors.of(family), 14.dp)
            Text(text = family.name(en), color = PicoTheme.colorScheme.labelPrimary, style = OrchType.title, maxLines = 1, modifier = Modifier.weight(1f).probe("card_title"))
            if (solo != null) SmallChip(Copy.soloChip(en))
        }
        Text(text = card.inPiece.of(en), color = PicoTheme.colorScheme.labelPrimary, style = OrchType.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Divider()
        val first = if (solo == null || solo.wholeFamily) null else solo.item
        for (item in Copy.cardItems(family, first)) {
            val hi = first != null && item.id == first
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (hi) PicoTheme.colorScheme.fillTertiary else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .probe("card_item_${item.id}"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = if (en) item.en else item.zh, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleSmall, maxLines = 1)
                    Text(text = item.rangeText(en), color = PicoTheme.colorScheme.labelSecondary, style = OrchType.figure.copy(fontSize = PicoTheme.typography.labelSmall.fontSize), maxLines = 1)
                }
                Text(text = if (en) item.lineEn else item.lineZh, color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WordButton("card_pull", Copy.pull(en), { onEvent(OrchEvent.PullClicked) }, primary = true, iconRes = R.drawable.ic_pull, minWidth = 0.dp, modifier = Modifier.weight(1f))
            if (solo != null) WordButton("card_restore", Copy.restore(en), { onEvent(OrchEvent.RestoreClicked) }, minWidth = 0.dp, modifier = Modifier.weight(1f))
            else WordButton("card_close", Copy.close(en), { onEvent(OrchEvent.CardCloseClicked) }, minWidth = 0.dp, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The [Call back] pill (D21, head-locked 18 degrees below the view centre, 360 x 64 dp at 0.9): shown only while the
 * world-fixed side panel has been out of view for 2 s.
 */
@Composable
internal fun RecallPanel(state: OrchUiState, filled: Boolean, onEvent: (OrchEvent) -> Unit) {
    val (w, h) = PanelSpec.RECALL_SIZE
    val en = state.english
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (!filled || !state.sideLost) return@Box
        Row(
            modifier = Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(32.dp)).backgroundMaterial(enable = true, style = Material.Thick).padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = Copy.lostPill(en), color = PicoTheme.colorScheme.labelPrimary, style = OrchType.body, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f).probe("recall_text"))
            WordButton("recall", Copy.recall(en), { onEvent(OrchEvent.SideRecallClicked) }, primary = true, iconRes = R.drawable.ic_pull, minWidth = 0.dp)
        }
    }
}

/**
 * The snackbar (head-locked, 14 degrees above the view centre, 600 x 72 dp at 0.9): captions with the family dot,
 * the first-run coach, placing notices. Thick, 28 dp corners, up to two lines.
 */
@Composable
internal fun SnackPanel(snack: SnackUi?, state: OrchUiState, piece: Piece) {
    val (w, h) = PanelSpec.SNACK_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val ink = PicoTheme.colorScheme.labelPrimary
        val fade = remember(snack.id) { Animatable(0f) }
        LaunchedEffect(snack.id) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .widthIn(min = 240.dp, max = w.dp)
                    .defaultMinSize(minHeight = 56.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .probe("snackbar")
                    .padding(start = 18.dp, top = 6.dp, end = 22.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val fam = snack.family
            if (snack.kind == SnackKind.CAPTION && fam != null) {
                Dot(OrchColors.of(fam), 14.dp)
            } else {
                Icon(
                    painter =
                        painterResource(
                            when (snack.kind) {
                                SnackKind.CAPTION -> R.drawable.ic_music
                                SnackKind.COACH -> R.drawable.ic_point
                                SnackKind.SNAPPED -> R.drawable.ic_check
                                SnackKind.NO_WALL -> R.drawable.ic_wall
                                SnackKind.VIRTUAL -> R.drawable.ic_floor
                                SnackKind.BLOCKED -> R.drawable.ic_unavailable
                            }
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ink,
                )
            }
            Text(text = Copy.snack(snack, state, piece), color = ink, style = OrchType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
