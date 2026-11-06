package io.embodit.insideorchestra.domain

import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.domain.model.Orchestra
import io.embodit.insideorchestra.domain.model.Piece
import io.embodit.insideorchestra.domain.model.PieceInfo
import io.embodit.insideorchestra.domain.model.Quat4
import io.embodit.insideorchestra.domain.model.Stem
import io.embodit.insideorchestra.domain.model.Vec3
import io.embodit.insideorchestra.domain.usecase.HeadFrame
import io.embodit.insideorchestra.domain.usecase.PieceClock
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.5 (user 2026-11-05 on the headset): more pieces, natural sampled sound, start on the podium and get back to it,
 * every player at the same real scale, all UI in one system window.
 */
class V015Test {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { File(it, "pieces/index.json").exists() }

    private fun load(id: String): Piece = Piece.parse(File(assets, "pieces/$id/piece.json").readText(), File(assets, "pieces/$id/parts.bin").readBytes())

    // ---------------------------------------------------------------- repertoire ("可以选的曲子多几个")

    @Test
    fun atLeastSixPiecesEachComplete() {
        val index = PieceInfo.parseIndex(File(assets, "pieces/index.json").readText())
        assertTrue("pieces: ${index.size}", index.size >= 6)
        assertEquals(index.size, index.map { it.id }.toSet().size)
        for (info in index) {
            val p = load(info.id)
            assertEquals(info.id, p.id)
            assertEquals(info.durationS, p.durationS, 0.05f)
            assertTrue("${info.id} too short: ${p.durationS}", p.durationS >= 60f)
            assertTrue("${info.id} title", p.title.zh.isNotEmpty() && p.title.en.isNotEmpty())
            assertTrue("${info.id}: passes ${p.passes.size}", p.passes.size >= 4)
            assertTrue("${info.id}: first pass has a caption", p.passes[0].caption != null)
            assertEquals("${info.id}: every family has its line", Family.entries.toSet(), p.familyNotes.keys)
            // the ten stems of this piece are in the APK
            for (s in Stem.entries) assertTrue("${info.id}/${s.id}.ogg", File(assets, "audio/${info.id}/${s.id}.ogg").length() > 0)
            // "各声部分好": at least three of the four families play somewhere in the piece
            val playing = Family.entries.count { f -> f.parts.any { part -> (0 until (p.durationS * 4).toInt()).any { k -> p.envelope(part, k / 4f) > 0.05f } } }
            assertTrue("${info.id}: families playing $playing", playing >= 3)
        }
    }

    @Test
    fun clockTakesTheNewPiecesLength() {
        val c = PieceClock(171.7f)
        c.start()
        c.advance(10f)
        c.load(81f)
        assertEquals(0f, c.t, 1e-6f)
        assertEquals(81f, c.durationS, 1e-6f)
        c.start()
        assertTrue(c.advance(100f))
        assertEquals(81f, c.t, 1e-6f)
    }

    // ---------------------------------------------------------------- podium ("默认就让用户在指挥台", "回到指挥台")

    private fun head(yawDeg: Float, pitchDeg: Float = 0f, rollDeg: Float = 0f): Quat4 {
        val yaw = Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat())
        val pitch = Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg.toDouble()).toFloat())
        val roll = Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(rollDeg.toDouble()).toFloat())
        return yaw * pitch * roll
    }

    @Test
    fun podiumPutsTheUserOnThePodiumWithThePlayersAhead() {
        val pos = Vec3(1.2f, 1.62f, -0.4f)
        val floor = 0.02f
        for (yaw in listOf(0f, 37f, 90f, 180f, -135f)) for ((pitch, roll) in listOf(0f to 0f, -30f to 0f, 30f to 0f, 0f to 20f)) {
            val r = head(yaw, pitch, roll)
            val pose = HeadFrame.podiumAt(pos, r, floor, 1f)
            // the head stands over the podium centre, on the floor
            val o = pose.origin
            assertEquals(pos.x, o.x, 0.01f)
            assertEquals(pos.z, o.z, 0.01f)
            assertEquals(floor, o.y, 1e-4f)
            // the players are ahead: the orchestra's -Z points along the level heading (< 2 degrees)
            val ahead = pose.dirToStage(Vec3(0f, 0f, -1f))
            val h = HeadFrame.heading(r)
            val ang = Math.toDegrees(acos(ahead.dot(h).coerceIn(-1f, 1f)).toDouble())
            assertTrue("yaw=$yaw pitch=$pitch roll=$roll: $ang deg", ang < 2.0)
            // every player in front (within 70 degrees of the heading) and >= 0.8 m from the head (D14, large objects)
            for (s in Orchestra.seats) {
                val p = pose.toStage(Vec3(s.x, 0f, s.z))
                val d = Vec3(p.x - pos.x, 0f, p.z - pos.z)
                assertTrue("${s.id} ${d.length} m", d.length >= 0.8f)
                val b = Math.toDegrees(acos(d.normalized().dot(h).coerceIn(-1f, 1f)).toDouble())
                assertTrue("${s.id} bearing $b", b <= 70.0)
            }
        }
    }

    @Test
    fun backToPodiumFromWhereverTheUserWalked() {
        // placed at the start, then the user walked up to the cellos and turned: [Back to podium] puts them back
        val start = HeadFrame.podiumAt(Vec3(0f, 1.6f, 0f), head(0f), 0f, 1f)
        val cello = Orchestra.seat("VCa")!!
        val walked = start.toStage(Vec3(cello.x - 0.4f, 0f, cello.z + 0.5f)) + Vec3(0f, 1.6f, 0f)
        for (yaw in listOf(-50f, 0f, 75f)) {
            val back = HeadFrame.podiumAt(walked, head(yaw, -20f), start.anchor.y, start.scale)
            val local = back.toLocal(Vec3(walked.x, back.anchor.y, walked.z))
            assertEquals(0f, local.x, 0.01f)
            assertEquals(0f, local.z, 0.01f)
            assertTrue(Orchestra.d(local.x, local.z, 0f, 0f) < Orchestra.PODIUM_R)
        }
    }

    // ---------------------------------------------------------------- players' scale ("右边大提琴人太大")

    @Test
    fun everySeatedPlayerIsTheSameSize() {
        val seated = Orchestra.seats.filter { it.family != Family.PERC }.map { Orchestra.modelHeight(it.model) }
        val lo = seated.min()
        val hi = seated.max()
        assertTrue("seated heights $lo .. $hi", hi / lo < 1.10f)
        for (s in Orchestra.seats) assertTrue("${s.model} ${Orchestra.modelHeight(s.model)}", Orchestra.modelHeight(s.model) in 1.2f..1.65f)
        // the bassist's box top is the player's head: shrunk from 1.9 m, one shared scale for both basses (D25)
        assertEquals(1.30f / 1.90f, Orchestra.modelScale("bassist"), 1e-4f)
        assertEquals(1f, Orchestra.modelScale("violinist"), 1e-6f)
    }

    // ---------------------------------------------------------------- the window (D8 / D9 / D21)

    @Test
    fun pitchOfTheHead() {
        assertEquals(0f, HeadFrame.pitchDeg(head(30f)), 0.01f)
        assertEquals(-30f, HeadFrame.pitchDeg(head(30f, -30f)), 0.01f)
        assertEquals(30f, HeadFrame.pitchDeg(head(-90f, 30f)), 0.01f)
        assertEquals(0f, HeadFrame.pitchDeg(head(0f, 0f, 20f)), 0.01f)
    }

    /**
     * The system opens the window level (LevelGate) 1.75 m ahead at eye height. Standing on the podium (eyes 1.6 m), the
     * seated players' heads stay under the window's lower edge plus the system's collapsed handle dot below it (~24 dp),
     * so the window floats over them instead of hiding them; only far, standing players can sit behind it (the user
     * moves the window with the system handle). Hovered, the handle strip grows to ~60 dp and may touch the back row.
     */
    @Test
    fun seatedPlayersStayUnderTheWindow() {
        val dpM = 0.0008f
        val dist = 1.75f
        val halfW = io.embodit.insideorchestra.ui.window.WINDOW_W * dpM / 2f
        val bottom = -(io.embodit.insideorchestra.ui.window.WINDOW_H * dpM / 2f + 24f * dpM)
        val bottomDeg = Math.toDegrees(atan(bottom / dist).toDouble())
        val eye = 1.6f
        for (s in Orchestra.seats.filter { it.family != Family.PERC }) {
            val d = kotlin.math.sqrt(s.x * s.x + s.z * s.z)
            val bearing = Math.toDegrees(atan2(s.x, -s.z).toDouble())
            val halfDeg = Math.toDegrees(atan(halfW / dist).toDouble())
            if (abs(bearing) > halfDeg + 10.0) continue
            val topDeg = Math.toDegrees(atan((Orchestra.modelHeight(s.model) - eye) / d).toDouble())
            assertTrue("${s.id}: head top $topDeg deg vs window bottom $bottomDeg deg", topDeg < bottomDeg - 0.1)
        }
    }
}
