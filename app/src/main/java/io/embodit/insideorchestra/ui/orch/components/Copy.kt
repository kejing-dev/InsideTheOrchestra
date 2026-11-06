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
            // v0.1.5: no placing step at the start; the user is put on the podium as soon as a floor is known
            s.room == Room.FINDING -> t(en, "看看四周，马上把你放到指挥台上…", "Look around: putting you on the podium in a moment…")
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
            s.transport == Transport.DONE -> t(en, "演奏完了！再听一遍，或者在「曲目」里换一首", "That's the end! Play it again, or pick another in Pieces")
            s.near?.podium == true -> t(en, "你站在指挥的位置：全团平衡", "You're on the podium: the whole orchestra in balance")
            s.near?.stem != null -> t(en, "你在${s.near.stem.name(false)}旁边 · 点「回到指挥台」回去", "You're next to the ${s.near.stem.name(true).lowercase()} · Back to podium returns you")
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
            SnackKind.NO_WALL -> if (state.scale < 1f) t(en, "没有 1.5 m 长的墙，可自由摆放", "No 1.5 m wall here: place it anywhere") else t(en, "没有 3 m 长的墙，可自由摆放，或在设置里缩到 1:2", "No 3 m wall here: place it anywhere, or switch to 1:2 in Settings")
            SnackKind.VIRTUAL -> t(en, "还没认出地面，先用虚拟地面 · 设置里可调房间大小", "No floor recognised yet: using a virtual floor · Room size is in Settings")
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

    // ---- v0.1.5 window
    fun tabPlay(en: Boolean) = t(en, "演奏", "Play")

    fun tabPieces(en: Boolean) = t(en, "曲目", "Pieces")

    fun tabSections(en: Boolean) = t(en, "声部", "Sections")

    fun tabLearn(en: Boolean) = t(en, "乐器", "Instruments")

    fun tabSettings(en: Boolean) = t(en, "设置", "Settings")

    fun bringHere(en: Boolean) = t(en, "叫回面前", "Bring here")

    fun podium(en: Boolean) = t(en, "回到指挥台", "Back to podium")

    fun openPanel(en: Boolean) = t(en, "打开面板", "Open panel")

    fun levelHint(en: Boolean) = t(en, "平视前方，面板就出来", "Look straight ahead and the panel opens")

    fun low(en: Boolean) = t(en, "低", "Low")

    fun mid(en: Boolean) = t(en, "中", "Mid")

    fun high(en: Boolean) = t(en, "高", "High")

    fun levels(en: Boolean) = t(en, "快捷音量", "Quick level")

    fun mutedShort(en: Boolean) = t(en, "静音", "Off")

    fun playLabel(en: Boolean) = t(en, "播放", "Play")

    fun pauseLabel(en: Boolean) = t(en, "暂停", "Pause")

    fun fromStart(en: Boolean) = t(en, "从头开始", "From the start")

    fun piecesTitle(en: Boolean, n: Int) = t(en, "选一首曲子（$n 首，都是公有领域的古典名曲）", "Pick a piece ($n public-domain classics)")

    fun switchAsk(en: Boolean, title: String) = t(en, "换成《$title》？现在这首会停下", "Switch to $title? This one stops")

    fun switchPiece(en: Boolean) = t(en, "换曲", "Switch")

    fun nowPlaying(en: Boolean) = t(en, "当前", "Current")

    fun loading(en: Boolean) = t(en, "加载中", "Loading")

    fun sectionLabel(en: Boolean) = t(en, "声部", "Section")

    fun inPieceLine(en: Boolean, text: String) = t(en, "在这首曲子里：$text", "In this piece: $text")

    fun soloFamily(en: Boolean, name: String) = t(en, "只听$name", "Hear only the ${name.lowercase()}")

    fun system(en: Boolean) = t(en, "跟随系统", "System")

    fun modeMr(en: Boolean) = t(en, "透视", "Passthrough")

    fun modeVr(en: Boolean) = t(en, "音乐厅", "Concert hall")

    fun exitApp(en: Boolean) = t(en, "退出应用", "Exit app")

    fun exitAsk(en: Boolean) = t(en, "退出走进乐团？", "Exit Inside the Orchestra?")

    fun credits(en: Boolean) =
        t(
            en,
            "乐器音色：GeneralUser GS 2.0.3（S. Christian Collins，可免费商用）。曲目均为公有领域作品，编配为本应用自编。",
            "Instrument sounds: GeneralUser GS 2.0.3 by S. Christian Collins (free for commercial use). All pieces are public domain; the arrangements are our own.",
        )

    /** The Play page's line: the caption or notice showing now, else the hint (design `stateModel.hintPriority`). */
    fun line(s: OrchUiState, piece: Piece): String = s.snack?.let { snack(it, s, piece) }?.takeIf { it.isNotEmpty() } ?: hint(s)

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

    fun menuMode(en: Boolean) = t(en, "场景", "Scene")

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

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    /** The card's lines, the pointed instrument first (design `instrumentCards.layout`). */
    fun cardItems(family: io.embodit.insideorchestra.domain.model.Family, first: String?) = Cards.ordered(family, first)
}
