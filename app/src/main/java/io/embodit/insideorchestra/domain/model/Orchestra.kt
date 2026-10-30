package io.embodit.insideorchestra.domain.model

import kotlin.math.atan2
import kotlin.math.sqrt

/** One string in both languages; the UI picks by the current language. */
data class L(val zh: String, val en: String) {
    fun of(english: Boolean): String = if (english) en else zh
}

/** The four section groups (design `families`): solo by capsule, cards and colours are per family. */
enum class Family(val id: String, val zh: String, val en: String, val shortEn: String) {
    STR("str", "弦乐", "Strings", "Strings"),
    WW("ww", "木管", "Woodwinds", "Winds"),
    BR("br", "铜管", "Brass", "Brass"),
    PERC("perc", "打击", "Percussion", "Perc.");

    fun name(english: Boolean): String = if (english) en else zh

    fun short(english: Boolean): String = if (english) shortEn else zh

    val stems: List<Stem>
        get() = Stem.entries.filter { it.family == this }

    val parts: List<Part>
        get() = Part.entries.filter { it.family == this }

    companion object {
        fun of(id: String): Family? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The 15 playing parts (design `audio.envelope`): each musician pulses with its own part only. Order = the
 * order of rows in `assets/parts.bin`.
 */
enum class Part(val family: Family) {
    V1(Family.STR), V2(Family.STR), VA(Family.STR), VC(Family.STR), CB(Family.STR),
    FL(Family.WW), OB(Family.WW), CL(Family.WW), BSN(Family.WW),
    HN(Family.BR), TPT(Family.BR), TBN(Family.BR), TUBA(Family.BR),
    TIMP(Family.PERC), BD(Family.PERC),
}

/**
 * The 10 audio stems (design `audio.stems`): one object-audio source each, placed at the horizontal centre of
 * its seat cluster ([sourceX], [sourceZ], orchestra coordinates), 1.0 m above the floor.
 */
enum class Stem(val id: String, val zh: String, val en: String, val family: Family, val sourceX: Float, val sourceZ: Float) {
    VLN("vln", "小提琴", "Violins", Family.STR, -1.07f, -1.10f),
    VLA("vla", "中提琴", "Violas", Family.STR, 0.66f, -1.48f),
    VC("vc", "大提琴", "Cellos", Family.STR, 1.04f, -0.73f),
    CB("cb", "低音提琴", "Double basses", Family.STR, 1.63f, -1.02f),
    WW("ww", "木管", "Woodwinds", Family.WW, -0.02f, -2.25f),
    HN("hn", "圆号", "Horns", Family.BR, -1.30f, -2.21f),
    TPT("tpt", "小号", "Trumpet", Family.BR, 0.89f, -2.44f),
    LBR("lbr", "长号与大号", "Trombone & tuba", Family.BR, 1.94f, -1.69f),
    TIMP("timp", "定音鼓", "Timpani", Family.PERC, -1.11f, -3.05f),
    BD("bd", "大鼓与镲", "Bass drum & cymbals", Family.PERC, 1.00f, -3.09f);

    fun name(english: Boolean): String = if (english) en else zh

    companion object {
        fun of(id: String): Stem? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One chair (design `seats.list`): orchestra coordinates, origin = podium centre on the floor, +Z towards the
 * audience, +X to the audience's right; every musician sits at z < 0 facing the podium. [model] = the Tripo
 * model in `assets/models/<model>.glb`; [instrument] = the line of the family card that is highlighted first.
 */
data class Seat(
    val id: String,
    val zh: String,
    val en: String,
    val part: Part,
    val stem: Stem,
    val x: Float,
    val z: Float,
    val model: String,
    val instrument: String,
) {
    val family: Family
        get() = part.family

    /** Yaw (about +Y) that turns the model's front (+Z) towards the podium centre. */
    val yaw: Float
        get() = atan2(-x, -z)

    fun name(english: Boolean): String = if (english) en else zh

    /** Short name for the name tag and hints: the instrument without "first / second / principal". */
    fun instrumentName(english: Boolean): String = Cards.item(instrument)?.let { if (english) it.en else it.zh } ?: name(english)
}

/** One line of an instrument card (design `instrumentCards`). */
data class CardItem(val id: String, val zh: String, val en: String, val lineZh: String, val lineEn: String, val range: String) {
    fun rangeText(english: Boolean): String =
        when {
            range == "无固定音高" -> if (english) "unpitched" else "无固定音高"
            english -> "c. $range"
            else -> "约 $range"
        }
}

data class FamilyCard(val family: Family, val inPiece: L, val items: List<CardItem>)

object Cards {
    val all: Map<Family, FamilyCard> =
        listOf(
            FamilyCard(
                Family.STR,
                L("开头大提琴和低音提琴用手指拨弦，迈出山魔王的脚步；后来全体拿起弓来拉。", "Cellos and basses pluck the opening steps; later every string player picks up the bow."),
                listOf(
                    CardItem("violin", "小提琴", "Violin", "乐团里音最高、人数最多的弦乐器，分第一、第二两组，常拉主旋律。", "The highest and most numerous strings, split into firsts and seconds; often carries the tune.", "G3–A7"),
                    CardItem("viola", "中提琴", "Viola", "比小提琴大一圈，声音更暗更暖，常在中间填满和声。", "A size up from the violin, darker and warmer; fills in the middle harmony.", "C3–E6"),
                    CardItem("cello", "大提琴", "Cello", "坐着夹在两腿间拉，音域接近男声，既能唱旋律也能打低音。", "Played seated between the knees; close to a man's voice, singing tunes or holding the bass.", "C2–A5"),
                    CardItem("doubleBass", "低音提琴", "Double bass", "乐团里最大的弦乐器，约 1.8 米高，给全团打地基。", "The biggest string instrument, about 1.8 m tall; the foundation of the orchestra.", "E1–G4"),
                ),
            ),
            FamilyCard(
                Family.WW,
                L("大管最先和低音弦乐一起奏出主题；双簧管、单簧管、长笛一遍比一遍高地接过来。", "The bassoon states the theme first with the low strings; oboe, clarinet and flute take it ever higher."),
                listOf(
                    CardItem("flute", "长笛", "Flute", "横着吹、没有簧片，声音明亮轻盈。", "Held sideways, no reed; bright and airy.", "C4–C7"),
                    CardItem("oboe", "双簧管", "Oboe", "吹两片簧片；乐团演出前就是它吹出 A 音让大家调音。", "Played with a double reed; it sounds the A the orchestra tunes to.", "B♭3–A6"),
                    CardItem("clarinet", "单簧管", "Clarinet", "一片簧片，从很低到很高都能吹，低音特别浓。", "A single reed with a wide range and a rich low end.", "D3–B♭6"),
                    CardItem("bassoon", "大管", "Bassoon", "木管里的低音，管子折了起来，拉直约有 2.5 米长。", "The bass of the woodwinds; its folded tube is about 2.5 m long.", "B♭1–E5"),
                ),
            ),
            FamilyCard(
                Family.BR,
                L("圆号一个长音开场；后半段小号、长号、大号陆续加入，把音量推到最响。", "A held horn note opens the piece; later trumpet, trombone and tuba pile in for full volume."),
                listOf(
                    CardItem("horn", "圆号", "Horn", "管子盘成一圈，拉直约 3.7 米，声音圆润，喇叭口朝后。", "Coiled tubing about 3.7 m long; mellow, with the bell facing backwards.", "B1–F5"),
                    CardItem("trumpet", "小号", "Trumpet", "铜管里最高最亮，用三个活塞变音。", "The highest, brightest brass; three valves change the notes.", "F♯3–D6"),
                    CardItem("trombone", "长号", "Trombone", "前后推拉滑管来改变音高。", "Changes pitch by sliding its tube in and out.", "E2–F5"),
                    CardItem("tuba", "大号", "Tuba", "铜管里最低最大，喇叭口朝上。", "The lowest and largest brass, bell pointing up.", "D1–F4"),
                ),
            ),
            FamilyCard(
                Family.PERC,
                L("定音鼓敲出重拍，最后几遍大鼓和镲一起砸下来，把高潮推到顶。", "Timpani mark the beats; in the last rounds bass drum and cymbals crash in for the climax."),
                listOf(
                    CardItem("timpani", "定音鼓", "Timpani", "能调准音高的大铜锅鼓，用脚踏板改音。", "Big copper drums tuned to real pitches, changed with a pedal.", "D2–C4"),
                    CardItem("bassDrum", "大鼓", "Bass drum", "乐团里最大的鼓，一声像打雷。", "The orchestra's largest drum, a roll of thunder.", "无固定音高"),
                    CardItem("cymbals", "镲", "Cymbals", "两片铜盘对击，一下就让全场亮起来。", "Two bronze plates clashed together to light up a climax.", "无固定音高"),
                ),
            ),
        ).associateBy { it.family }

    fun item(id: String): CardItem? = all.values.firstNotNullOfOrNull { c -> c.items.firstOrNull { it.id == id } }

    /** The card's lines with [first] (the instrument that was pointed at) moved to the top; null = default order. */
    fun ordered(family: Family, first: String?): List<CardItem> {
        val items = all.getValue(family).items
        if (first == null) return items
        val hit = items.firstOrNull { it.id == first } ?: return items
        return listOf(hit) + items.filter { it !== hit }
    }
}

/** The orchestra layout (design `seats`). */
object Orchestra {
    val seats: List<Seat> =
        listOf(
            Seat("V1a", "第一小提琴（首席）", "Violin I (concertmaster)", Part.V1, Stem.VLN, -1.19f, -0.53f, "violinist", "violin"),
            Seat("V1b", "第一小提琴", "Violin I", Part.V1, Stem.VLN, -0.80f, -1.02f, "violinist", "violin"),
            Seat("V2a", "第二小提琴", "Violin II", Part.V2, Stem.VLN, -0.25f, -1.28f, "violinist", "violin"),
            Seat("VAa", "中提琴", "Viola", Part.VA, Stem.VLA, 0.34f, -1.26f, "violist", "viola"),
            Seat("VCa", "大提琴", "Cello", Part.VC, Stem.VC, 0.87f, -0.97f, "cellist", "cello"),
            Seat("VCb", "大提琴", "Cello", Part.VC, Stem.VC, 1.21f, -0.49f, "cellist", "cello"),
            Seat("V1c", "第一小提琴", "Violin I", Part.V1, Stem.VLN, -1.78f, -0.79f, "violinist", "violin"),
            Seat("V2b", "第二小提琴", "Violin II", Part.V2, Stem.VLN, -1.45f, -1.30f, "violinist", "violin"),
            Seat("V2c", "第二小提琴", "Violin II", Part.V2, Stem.VLN, -0.97f, -1.69f, "violinist", "violin"),
            Seat("FL", "长笛", "Flute", Part.FL, Stem.WW, -0.34f, -1.92f, "flutist", "flute"),
            Seat("OB", "双簧管", "Oboe", Part.OB, Stem.WW, 0.34f, -1.92f, "oboist", "oboe"),
            Seat("VAb", "中提琴", "Viola", Part.VA, Stem.VLA, 0.97f, -1.69f, "violist", "viola"),
            Seat("CBa", "低音提琴", "Double bass", Part.CB, Stem.CB, 1.47f, -1.28f, "bassist", "doubleBass"),
            Seat("CBb", "低音提琴", "Double bass", Part.CB, Stem.CB, 1.79f, -0.76f, "bassist", "doubleBass"),
            Seat("HNa", "圆号", "Horn", Part.HN, Stem.HN, -1.64f, -2.02f, "hornist", "horn"),
            Seat("HNb", "圆号", "Horn", Part.HN, Stem.HN, -0.97f, -2.41f, "hornist", "horn"),
            Seat("CL", "单簧管", "Clarinet", Part.CL, Stem.WW, -0.36f, -2.57f, "clarinetist", "clarinet"),
            Seat("BSN", "大管", "Bassoon", Part.BSN, Stem.WW, 0.27f, -2.59f, "bassoonist", "bassoon"),
            Seat("TPT", "小号", "Trumpet", Part.TPT, Stem.TPT, 0.89f, -2.44f, "trumpeter", "trumpet"),
            Seat("TBN", "长号", "Trombone", Part.TBN, Stem.LBR, 1.67f, -1.99f, "trombonist", "trombone"),
            Seat("TUBA", "大号", "Tuba", Part.TUBA, Stem.LBR, 2.20f, -1.38f, "tubist", "tuba"),
            Seat("TIMP", "定音鼓", "Timpani", Part.TIMP, Stem.TIMP, -1.11f, -3.05f, "timpanist", "timpani"),
            Seat("BD", "大鼓与镲", "Bass drum & cymbals", Part.BD, Stem.BD, 1.00f, -3.09f, "bassdrummer", "bassDrum"),
        )

    val models: List<String> = seats.map { it.model }.distinct()

    fun seat(id: String): Seat? = seats.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Footprint (design `seats.footprint`): x -2.20 .. 2.60, z -3.65 .. 0.35 (4.8 x 4.0 m incl. the podium). */
    const val FOOT_MIN_X = -2.20f
    const val FOOT_MAX_X = 2.60f
    const val FOOT_BACK_Z = -3.65f
    const val FOOT_FRONT_Z = 0.35f
    val FOOT_WIDTH = FOOT_MAX_X - FOOT_MIN_X
    val FOOT_DEPTH = FOOT_FRONT_Z - FOOT_BACK_Z
    val FOOT_CENTER = Vec3((FOOT_MIN_X + FOOT_MAX_X) / 2f, 0f, (FOOT_BACK_Z + FOOT_FRONT_Z) / 2f)

    /** The placement anchor: the podium's front edge (design `seats.placeAnchor`). */
    val ANCHOR = Vec3(0f, 0f, 0.35f)

    /** Podium: 0.70 m across, 0.12 m high. */
    const val PODIUM_R = 0.35f
    const val PODIUM_H = 0.12f

    /** Stem sources sit 1.0 m above the floor (seated instrument height). */
    const val SOURCE_Y = 1.0f

    /** Each family's floor zone: its seats grown by 0.35 m (centre + half sizes, axis aligned in orchestra space). */
    data class Zone(val family: Family, val cx: Float, val cz: Float, val hx: Float, val hz: Float)

    val zones: List<Zone> =
        Family.entries.map { f ->
            val s = seats.filter { it.family == f }
            val minX = s.minOf { it.x } - 0.35f
            val maxX = s.maxOf { it.x } + 0.35f
            val minZ = s.minOf { it.z } - 0.35f
            val maxZ = s.maxOf { it.z } + 0.35f
            Zone(f, (minX + maxX) / 2f, (minZ + maxZ) / 2f, (maxX - minX) / 2f, (maxZ - minZ) / 2f)
        }

    /** The seat of [stem] nearest to a level point (orchestra coordinates). */
    fun nearestSeatOf(stem: Stem, x: Float, z: Float): Seat = seats.filter { it.stem == stem }.minBy { d(it.x, it.z, x, z) }

    fun d(ax: Float, az: Float, bx: Float, bz: Float): Float = sqrt((ax - bx) * (ax - bx) + (az - bz) * (az - bz))
}

/**
 * Near listening (design `S8`, `I11`): the stem of the seat nearest to the head (level distance, orchestra metres
 * scaled by the display scale). Enter under 0.9 m, leave over 1.1 m (half at 1:2); another stem takes over only
 * when it is 0.1 m nearer for 0.4 s; the podium (head within 0.35 m of its centre) wins over everything.
 */
class NearListen {
    var stem: Stem? = null
        private set
    var podium = false
        private set
    var distance = 0f
        private set
    private var challenger: Stem? = null
    private var challengerSinceMs = 0L

    /** [hx], [hz]: the head in orchestra coordinates (unscaled). Returns true when the result changed. */
    fun update(hx: Float, hz: Float, scale: Float, nowMs: Long): Boolean {
        val before = stem to podium
        val onPodium = Orchestra.d(hx, hz, 0f, 0f) * scale < Orchestra.PODIUM_R * scale + 1e-4f
        podium = onPodium
        val best = Orchestra.seats.minBy { Orchestra.d(it.x, it.z, hx, hz) }
        val bestD = Orchestra.d(best.x, best.z, hx, hz) * scale
        val cur = stem
        if (onPodium) {
            stem = null
            challenger = null
        } else if (cur == null) {
            if (bestD < ENTER_M * scale) stem = best.stem
        } else {
            val curSeat = Orchestra.nearestSeatOf(cur, hx, hz)
            val curD = Orchestra.d(curSeat.x, curSeat.z, hx, hz) * scale
            if (curD > LEAVE_M * scale) {
                stem = if (bestD < ENTER_M * scale) best.stem else null
                challenger = null
            } else if (best.stem != cur && bestD < curD - SWITCH_M * scale) {
                if (challenger != best.stem) {
                    challenger = best.stem
                    challengerSinceMs = nowMs
                } else if (nowMs - challengerSinceMs >= SWITCH_HOLD_MS) {
                    stem = best.stem
                    challenger = null
                }
            } else {
                challenger = null
            }
        }
        distance = stem?.let { s -> Orchestra.nearestSeatOf(s, hx, hz).let { Orchestra.d(it.x, it.z, hx, hz) * scale } } ?: bestD
        return before != (stem to podium)
    }

    fun reset() {
        stem = null
        podium = false
        challenger = null
    }

    companion object {
        const val ENTER_M = 0.9f
        const val LEAVE_M = 1.1f
        const val SWITCH_M = 0.1f
        const val SWITCH_HOLD_MS = 400L

        /**
         * Debug `stand=<stem|podium>` sites (design `debugHooks`: computed with the same rule): a level point
         * 0.55 m in front of the stem's seat nearest to the podium, on the podium side; checked by the unit test to
         * be judged as that stem. Orchestra coordinates (unscaled).
         */
        fun standSite(stem: Stem?): Pair<Float, Float> {
            if (stem == null) return 0f to 0f
            val seat = Orchestra.seats.filter { it.stem == stem }.minBy { Orchestra.d(it.x, it.z, 0f, 0f) }
            val len = Orchestra.d(seat.x, seat.z, 0f, 0f).coerceAtLeast(1e-3f)
            var k = 0.55f
            var site = (seat.x - seat.x / len * k) to (seat.z - seat.z / len * k)
            // step closer until no other stem's seat is nearer (back-row seats sit between other sections)
            while (k > 0.2f) {
                site = (seat.x - seat.x / len * k) to (seat.z - seat.z / len * k)
                val best = Orchestra.seats.minBy { Orchestra.d(it.x, it.z, site.first, site.second) }
                if (best.stem == stem) break
                k -= 0.05f
            }
            return site
        }
    }
}
