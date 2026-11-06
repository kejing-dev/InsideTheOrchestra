package io.embodit.insideorchestra.domain

import io.embodit.insideorchestra.domain.model.Part
import io.embodit.insideorchestra.domain.model.Piece
import io.embodit.insideorchestra.domain.model.PieceInfo
import io.embodit.insideorchestra.domain.model.Stem
import io.embodit.insideorchestra.domain.usecase.PieceClock
import io.embodit.insideorchestra.domain.usecase.StemSlots
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.6 (user on the headset: "no sound after switching to a new song"): any two of the six pieces, switched back and
 * forth three times, always load into open mixer groups and always have something to play.
 */
class PieceSwitchTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { File(it, "pieces/index.json").exists() }
    private val index = PieceInfo.parseIndex(File(assets, "pieces/index.json").readText())

    private class FakeGroup(val name: String) {
        var closed = false
    }

    /** What OrchestraAudio does per load: take the groups, check every stem file of the piece, "bind" players. */
    private fun load(slots: StemSlots<Stem, FakeGroup>, id: String): Map<Stem, FakeGroup> {
        val groups = slots.forLoad()
        for (s in Stem.entries) assertTrue("$id/${s.id}.ogg", File(assets, "audio/$id/${s.id}.ogg").length() > 1000)
        return groups
    }

    @Test
    fun anyTwoPiecesBackAndForthThreeTimesAlwaysPlayIntoOpenGroups() {
        assertTrue(index.size >= 6)
        for (a in index) for (b in index) {
            if (a.id == b.id) continue
            val closed = ArrayList<FakeGroup>()
            val slots = StemSlots(Stem.entries, { s -> FakeGroup("stem" + s.id) }, { g -> g.closed = true; closed += g })
            val clock = PieceClock(1f)
            var groupsBefore: Map<Stem, FakeGroup>? = null
            repeat(3) {
                for (id in listOf(a.id, b.id)) {
                    val groups = load(slots, id)
                    // the same, still-open group objects on every switch (v0.1.5 closed and remade them: silence)
                    groupsBefore?.let { before -> for (s in Stem.entries) assertSame(before[s], groups[s]) }
                    for (g in groups.values) assertFalse("$id: ${g.name} closed", g.closed)
                    groupsBefore = groups
                    // the new piece has its own length and starts at 0, and something sounds in its first 15 s
                    val piece = Piece.parse(File(assets, "pieces/$id/piece.json").readText(), File(assets, "pieces/$id/parts.bin").readBytes())
                    clock.load(piece.durationS)
                    assertEquals(0f, clock.t, 0f)
                    assertEquals(piece.durationS, clock.durationS, 0f)
                    val sounding = (0 until 60).any { k -> Part.entries.any { p -> piece.envelope(p, k / 4f) > 0.05f } }
                    assertTrue("$id: nothing plays in the first 15 s", sounding)
                }
            }
            assertEquals(1, slots.generation)
            assertTrue(closed.isEmpty())
            slots.close()
            assertTrue(closed.size == Stem.entries.size && closed.all { it.closed })
        }
    }
}
