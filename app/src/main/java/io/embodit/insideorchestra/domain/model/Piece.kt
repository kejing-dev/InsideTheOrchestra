package io.embodit.insideorchestra.domain.model

import org.json.JSONObject

/** One pass of the theme (design `piece.arrangement`): start on the piece clock, bpm and the guide caption. */
data class Pass(val index: Int, val startS: Float, val bpm: Int, val caption: L?, val focus: Family?)

/**
 * The one piece of the app, Grieg's "In the Hall of the Mountain King": timings from `assets/piece.json`, the
 * 15 part envelopes from `assets/parts.bin` (both written by `tools/make_music.py`). Pure data, no Android.
 *
 * parts.bin: "ORCP", u16 parts, u16 frames, u16 rate (Hz), then parts x frames bytes: bit 7 = pizzicato,
 * bits 0..6 = envelope 0..127.
 */
class Piece(val durationS: Float, val passes: List<Pass>, val rests: Map<Stem, List<Pair<Float, Float>>>, private val rate: Int, private val frames: Int, private val env: ByteArray) {

    /** Envelope 0..1 of [part] at [t] seconds (linear between 20 Hz frames). */
    fun envelope(part: Part, t: Float): Float {
        if (frames == 0) return 0f
        val f = (t * rate).coerceIn(0f, (frames - 1).toFloat())
        val i = f.toInt()
        val j = minOf(i + 1, frames - 1)
        val a = (env[part.ordinal * frames + i].toInt() and 0x7F) / 127f
        val b = (env[part.ordinal * frames + j].toInt() and 0x7F) / 127f
        return a + (b - a) * (f - i)
    }

    fun pizzicato(part: Part, t: Float): Boolean {
        if (frames == 0) return false
        val i = (t * rate).toInt().coerceIn(0, frames - 1)
        return (env[part.ordinal * frames + i].toInt() and 0x80) != 0
    }

    /** The pass playing at [t]. */
    fun passAt(t: Float): Pass = passes.lastOrNull { it.startS <= t + 1e-3f } ?: passes.first()

    /** Next rest of [stem] that starts at or after [t] (seek there to resync without a click), or null. */
    fun nextRest(stem: Stem, t: Float): Pair<Float, Float>? = rests[stem]?.firstOrNull { it.second > t + 0.1f }

    companion object {
        fun parse(json: String, parts: ByteArray): Piece {
            val o = JSONObject(json)
            val passes = ArrayList<Pass>()
            val arr = o.getJSONArray("passes")
            for (k in 0 until arr.length()) {
                val p = arr.getJSONObject(k)
                val zh = p.optString("captionZh").takeIf { it.isNotEmpty() && it != "null" }
                val en = p.optString("captionEn").takeIf { it.isNotEmpty() && it != "null" }
                passes += Pass(
                    p.getInt("pass"),
                    p.getDouble("startS").toFloat(),
                    p.getInt("bpm"),
                    if (zh != null && en != null) L(zh, en) else null,
                    p.optString("focus").takeIf { it.isNotEmpty() && it != "null" }?.let { Family.of(it) },
                )
            }
            val rests = HashMap<Stem, List<Pair<Float, Float>>>()
            val ro = o.getJSONObject("rests")
            for (stem in Stem.entries) {
                val list = ro.optJSONArray(stem.id) ?: continue
                rests[stem] = (0 until list.length()).map { i -> list.getJSONArray(i).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } }
            }
            require(parts.size >= 10 && String(parts, 0, 4, Charsets.US_ASCII) == "ORCP") { "parts.bin: bad header" }
            val n = (parts[4].toInt() and 0xFF) or ((parts[5].toInt() and 0xFF) shl 8)
            val frames = (parts[6].toInt() and 0xFF) or ((parts[7].toInt() and 0xFF) shl 8)
            val rate = (parts[8].toInt() and 0xFF) or ((parts[9].toInt() and 0xFF) shl 8)
            require(n == Part.entries.size && parts.size >= 10 + n * frames) { "parts.bin: $n parts x $frames frames does not match" }
            return Piece(o.getDouble("durationS").toFloat(), passes, rests, rate, frames, parts.copyOfRange(10, 10 + n * frames))
        }

        /** A silent stand-in while the assets are missing (keeps the UI working). */
        val EMPTY = Piece(171f, listOf(Pass(0, 0f, 84, null, null)), emptyMap(), 20, 0, ByteArray(0))
    }
}
