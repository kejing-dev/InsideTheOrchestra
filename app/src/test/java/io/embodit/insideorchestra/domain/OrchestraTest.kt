package io.embodit.insideorchestra.domain

import io.embodit.insideorchestra.domain.model.Cards
import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.domain.model.NearListen
import io.embodit.insideorchestra.domain.model.Orchestra
import io.embodit.insideorchestra.domain.model.Piece
import io.embodit.insideorchestra.domain.model.Stem
import io.embodit.insideorchestra.domain.model.Vec3
import io.embodit.insideorchestra.domain.usecase.HeadFrame
import io.embodit.insideorchestra.domain.model.Quat4
import io.embodit.insideorchestra.domain.usecase.OrchPose
import io.embodit.insideorchestra.domain.usecase.PieceClock
import io.embodit.insideorchestra.domain.usecase.PlacementUseCase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestraTest {
    @Test
    fun seatsMatchTheDesignTable() {
        assertEquals(23, Orchestra.seats.size)
        assertEquals(12, Orchestra.seats.count { it.family == Family.STR })
        assertEquals(4, Orchestra.seats.count { it.family == Family.WW })
        assertEquals(5, Orchestra.seats.count { it.family == Family.BR })
        assertEquals(2, Orchestra.seats.count { it.family == Family.PERC })
        assertEquals(14, Orchestra.models.size)
        for (s in Orchestra.seats) {
            assertTrue("${s.id} in front of the back edge", s.z < 0f && s.z > Orchestra.FOOT_BACK_Z)
            assertTrue("${s.id} inside the footprint", s.x > Orchestra.FOOT_MIN_X && s.x < Orchestra.FOOT_MAX_X)
            assertNotNull("${s.id} card line", Cards.item(s.instrument))
            assertEquals(s.family, s.stem.family)
        }
    }

    @Test
    fun everySeatIsNearestToItsOwnSource() {
        // design `audio.whyStringsSplit`: no seat is nearer another stem's source than its own
        for (s in Orchestra.seats) {
            val best = Stem.entries.minBy { Orchestra.d(it.sourceX, it.sourceZ, s.x, s.z) }
            assertEquals("seat ${s.id}", s.stem, best)
        }
    }

    @Test
    fun musiciansFaceThePodium() {
        for (s in Orchestra.seats) {
            val front = Quat4.axisAngle(Vec3.UP, s.yaw).rotate(Vec3(0f, 0f, 1f))
            val toPodium = Vec3(-s.x, 0f, -s.z).normalized()
            assertTrue("${s.id} faces the podium", front.dot(toPodium) > 0.999f)
        }
    }

    @Test
    fun standSitesAreJudgedAsTheirStem() {
        for (stem in Stem.entries) {
            val (x, z) = NearListen.standSite(stem)
            val near = NearListen()
            near.update(x, z, 1f, 0L)
            assertEquals("stand=${stem.id}", stem, near.stem)
            assertFalse(near.podium)
        }
        val podium = NearListen()
        podium.update(0f, 0f, 1f, 0L)
        assertTrue(podium.podium)
    }

    @Test
    fun nearListenHasHysteresis() {
        val n = NearListen()
        val vc = Orchestra.seat("VCb")!!
        // in front of the second cello, towards the audience
        n.update(vc.x, vc.z + 0.8f, 1f, 0L)
        assertEquals(Stem.VC, n.stem)
        // a little farther than 0.9 m but under 1.1 m stays
        n.update(vc.x, vc.z + 1.0f, 1f, 100L)
        assertEquals(Stem.VC, n.stem)
    }

    @Test
    fun placementKeepsEveryoneClearOfTheHead() {
        val rule = PlacementUseCase()
        val head = Vec3(0f, 1.6f, 0f)
        // the podium front right under the head: pushed out until 0.6 m clear
        val (p, moved) = rule.pushClear(OrchPose(Vec3(0f, 0f, -0.2f), 0f, 1f), head)
        assertTrue(moved)
        assertTrue(rule.clearance(p, head) >= PlacementUseCase.NEAR_M - 0.02f)
        // place=auto: 1.5 m ahead, facing the user, nobody within 0.6 m
        val ahead = HeadFrame.orchestraAhead(head, Quat4.IDENTITY, 0f, 1f)
        assertEquals(-1.5f, ahead.anchor.z, 1e-4f)
        assertTrue(rule.clearance(ahead, head) >= 0.6f)
        // the orchestra spreads away from the user: every seat farther than the anchor
        for (s in Orchestra.seats) assertTrue(ahead.toStage(Vec3(s.x, 0f, s.z)).z < ahead.anchor.z)
    }

    @Test
    fun snapFrameRoundTrip() {
        val p = OrchPose(Vec3(1f, 0f, -2f), 0.7f, 1f)
        val q = OrchPose.fromCenter(p.center, p.yaw, 1f)
        assertEquals(p.anchor.x, q.anchor.x, 1e-4f)
        assertEquals(p.anchor.z, q.anchor.z, 1e-4f)
        val back = p.toLocal(p.toStage(Vec3(0.3f, 0f, -1.2f)))
        assertEquals(0.3f, back.x, 1e-4f)
        assertEquals(-1.2f, back.z, 1e-4f)
    }

    @Test
    fun clockFollowsAudioSlowly() {
        val c = PieceClock(170f)
        c.start()
        c.advance(1f)
        c.follow(1.5f, 1f)
        assertEquals(1.02f, c.t, 1e-4f)
        c.pause()
        assertFalse(c.advance(1f))
        c.start()
        c.seek(169.9f)
        assertTrue(c.advance(0.5f))
    }

    @Test
    fun pieceAssetsParse() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { File(it, "piece.json").exists() }
        val piece = Piece.parse(File(assets, "piece.json").readText(), File(assets, "parts.bin").readBytes())
        assertEquals(11, piece.passes.size)
        assertEquals(5.71f, piece.passes[1].startS, 0.05f)
        assertEquals(148.3f, piece.passes[9].startS, 0.1f)
        // pass 1: bassoon and plucked low strings play, flute does not
        val t = piece.passes[1].startS + 1f
        assertTrue(piece.envelope(io.embodit.insideorchestra.domain.model.Part.VC, t) > 0.05f || piece.envelope(io.embodit.insideorchestra.domain.model.Part.VC, t + 0.2f) > 0.05f)
        assertEquals(0f, piece.envelope(io.embodit.insideorchestra.domain.model.Part.FL, t), 1e-3f)
        assertTrue(piece.pizzicato(io.embodit.insideorchestra.domain.model.Part.VC, piece.passes[1].startS + 0.05f))
        assertNotNull(piece.passes[9].caption)
        assertEquals(Family.PERC, piece.passes[9].focus)
    }
}
