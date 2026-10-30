package io.embodit.insideorchestra.ui.orch.components

import io.embodit.insideorchestra.domain.model.Cards
import io.embodit.insideorchestra.domain.model.Orchestra
import io.embodit.insideorchestra.domain.model.Piece
import io.embodit.insideorchestra.ui.orch.AudioState
import io.embodit.insideorchestra.ui.orch.OrchUiState
import io.embodit.insideorchestra.ui.orch.Room
import io.embodit.insideorchestra.ui.orch.SnackKind
import io.embodit.insideorchestra.ui.orch.SnackUi
import io.embodit.insideorchestra.ui.orch.Transport

/**
 * All panel copy in both languages (design `bilingual`). The app switches language in place, so copy is chosen
 * from the state's `english` flag rather than Android resources. No line of copy says "walk" (design `input.rule`).
 */
internal object Copy {
    fun t(en: Boolean, zh: String, english: String): String = if (en) english else zh

    fun title(en: Boolean) = t(en, "走进乐团", "Inside the Orchestra")

    fun halfChip(en: Boolean) = t(en, "1:2 缩小显示", "1:2 view")

    fun familyShort(en: Boolean, i: Int) = io.embodit.insideorchestra.domain.model.Family.entries[i].short(en)

    fun time(s: Float): String {
        val k = s.toInt().coerceAtLeast(0)
        return "${k / 60}:${(k % 60).toString().padStart(2, '0')}"
    }

    private const val TURN_ZH = " · 摇杆左右转动"
    private const val TURN_EN = " · Thumbstick turns it"

    /** The hint line (design `stateModel.hintPriority`). */
    fun hint(s: OrchUiState): String {
        val en = s.english
        fun place(zh: String, english: String) = t(en, zh + TURN_ZH, english + TURN_EN)
        return when {
            s.room == Room.STARTING -> t(en, "正在准备…", "Getting ready…")
            s.room == Room.FINDING -> t(en, "环顾一下房间，正在找地面…（最多 5 秒）", "Look around the room, finding the floor… (up to 5 s)")
            // v0.1.2 (D1 "放置模式必须有终点"): every placing hint says what is happening now -> what comes next
            s.room == Room.PLACING && !s.marker ->
                movePrefix(s) + t(en, "在地上点一下放乐团（手柄射线或目光 + 捏合）→ 确认", "Tap the floor to place the orchestra (controller ray or eyes + pinch) → Confirm") + headNote(s)
            s.room == Room.PLACING && s.tooClose -> movePrefix(s) + t(en, "离你太近 → 在远一点的地上再点一下", "Too close to you → tap the floor farther away")
            s.room == Room.PLACING && s.snapped -> movePrefix(s) + place("已对齐墙边 → 点「确认」锁定", "Lined up with the wall → tap Confirm to lock it")
            s.room == Room.PLACING -> movePrefix(s) + place("乐团在标记处 → 点「确认」锁定", "Orchestra at the marker → tap Confirm to lock it")
            s.audio == AudioState.FAILED -> t(en, "声音没加载出来", "The sound did not load")
            s.moving -> t(en, "松开即停", "Let go to stop")
            s.solo != null -> {
                val name = s.solo.seatId?.let { id -> Orchestra.seat(id)?.instrumentName(en) } ?: s.solo.family.name(en)
                t(en, "只听：$name · 点 [恢复全体] 或点空处恢复", "Only: $name · Tap Restore or an empty spot")
            }
            s.transport == Transport.DONE -> t(en, "演奏完了！再听一遍，或者把乐团转过来听", "That's the end! Play it again, or turn the orchestra round")
            s.near?.podium == true -> t(en, "你站在指挥的位置：全团平衡", "You're on the podium: the whole orchestra in balance")
            s.near?.stem != null -> t(en, "你在${s.near.stem.name(false)}旁边", "You're next to the ${s.near.stem.name(true).lowercase()}")
            s.focusSeat != null -> {
                val name = Orchestra.seat(s.focusSeat)?.instrumentName(en) ?: ""
                t(en, "正在突出：$name · 捏一下 / 扣扳机只听它", "Bringing out: $name · Pinch or pull the trigger to hear only it")
            }
            s.transport == Transport.PAUSED -> t(en, "已暂停", "Paused")
            s.transport == Transport.PLAYING -> t(en, "看着或指着乐手听听 · 点一下只听它", "Look at or point to a player · Tap to hear only them")
            else -> t(en, "按播放 · 看着或指着乐手听听", "Press play · Look at or point to a player")
        }
    }

    fun snack(s: SnackUi, state: OrchUiState, piece: Piece): String {
        val en = state.english
        return when (s.kind) {
            SnackKind.CAPTION -> piece.passes.getOrNull(s.pass)?.caption?.of(en) ?: ""
            SnackKind.COACH -> t(en, "看着或用射线指着乐手，就突出它的声部；捏一下 / 扣扳机只听它", "Look or point at a player to bring their part forward; pinch or pull the trigger to hear only it")
            SnackKind.SNAPPED -> t(en, "已对齐墙边", "Lined up with the wall")
            SnackKind.NO_WALL -> if (state.scale < 1f) t(en, "没有 1.5 m 长的墙，可自由摆放", "No 1.5 m wall here: place it anywhere") else t(en, "没有 3 m 长的墙，可自由摆放，或在更多里缩到 1:2", "No 3 m wall here: place it anywhere, or switch to 1:2 in More")
            SnackKind.VIRTUAL -> t(en, "没扫到地面，先用虚拟房间 · 更多里可调尺寸", "No floor found yet: using a virtual room · Resize it in More")
            SnackKind.BLOCKED -> t(en, "空间识别不可用，已用虚拟房间", "Room detection unavailable: using a virtual room")
        }
    }

    private fun movePrefix(s: OrchUiState): String = if (!s.placeMoving) "" else t(s.english, "移动乐团：", "Moving: ")

    /** D1: the head direction is only a stand-in when there is neither a controller nor eye tracking. */
    private fun headNote(s: OrchUiState): String = if (!s.headPointer) "" else t(s.english, "（没检测到手柄和眼动，暂用头部朝向）", " (no controller or eye tracking: using head direction)")

    fun soloChip(en: Boolean) = t(en, "独奏中", "Solo")

    fun solo(en: Boolean) = t(en, "独奏", "Solo")

    fun sectionMute(en: Boolean, m: Boolean) = if (m) t(en, "已静音", "Muted") else t(en, "静音", "Mute")

    fun info(en: Boolean) = t(en, "讲解", "Info")

    fun atMarker(en: Boolean) = t(en, "移到标记处", "Move to marker")

    fun confirm(en: Boolean) = t(en, "确认", "Confirm")

    fun replaceMarker(en: Boolean) = t(en, "重放", "Re-place")

    fun cancel(en: Boolean) = t(en, "取消", "Cancel")

    fun moveOrchestra(en: Boolean) = t(en, "移动乐团", "Move orchestra")

    fun close(en: Boolean) = t(en, "关闭", "Close")

    fun master(en: Boolean) = t(en, "总音量", "Master")

    fun volume(en: Boolean) = t(en, "音量", "Volume")

    fun dragHint(en: Boolean) = t(en, "拖动移动面板", "Drag to move the panel")

    fun recall(en: Boolean) = t(en, "叫回面板", "Call back panel")

    fun lostPill(en: Boolean) = t(en, "控制面板不在视野里", "The controls are out of view")

    fun inPiece(en: Boolean) = t(en, "在这首曲子里", "In this piece")

    fun pull(en: Boolean) = t(en, "拉到面前", "Bring closer")

    fun restore(en: Boolean) = t(en, "恢复全体", "Restore all")

    fun home(en: Boolean) = t(en, "回到原位", "Back to place")

    fun again(en: Boolean) = t(en, "再听一遍", "Play again")

    fun retry(en: Boolean) = t(en, "重试", "Retry")

    fun turn45(en: Boolean) = t(en, "转 45°", "Turn 45°")

    fun menuCaptions(en: Boolean) = t(en, "导赏字幕", "Captions")

    fun menuLanguage(en: Boolean) = t(en, "语言", "Language")

    fun menuSize(en: Boolean) = t(en, "大小", "Size")

    fun menuMode(en: Boolean) = t(en, "透视 / 音乐厅", "MR / VR")

    fun on(en: Boolean) = t(en, "开", "On")

    fun off(en: Boolean) = t(en, "关", "Off")

    fun replace(en: Boolean) = t(en, "重新摆放", "Place again")

    fun exit(en: Boolean) = t(en, "退出", "Exit")

    fun room(en: Boolean) = t(en, "虚拟房间", "Virtual room")

    fun roomLength(en: Boolean, v: Float) = t(en, "长 ${fmt(v)} m", "L ${fmt(v)} m")

    fun roomWidth(en: Boolean, v: Float) = t(en, "宽 ${fmt(v)} m", "W ${fmt(v)} m")

    fun roomHeight(en: Boolean, v: Float) = t(en, "高 ${fmt(v)} m", "H ${fmt(v)} m")

    fun muted(en: Boolean, m: Boolean) = if (m) t(en, "已静音", "Muted") else t(en, "静音", "Mute")

    fun snapped(en: Boolean) = t(en, "已对齐墙边", "Lined up")

    fun pieceLine(en: Boolean) = t(en, "格里格《在山魔王的宫殿里》", "Grieg · In the Hall of the Mountain King")

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    /** The card's lines, the pointed instrument first (design `instrumentCards.layout`). */
    fun cardItems(family: io.embodit.insideorchestra.domain.model.Family, first: String?) = Cards.ordered(family, first)
}
