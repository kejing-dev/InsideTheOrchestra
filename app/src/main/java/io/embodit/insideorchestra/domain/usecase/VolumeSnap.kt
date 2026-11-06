package io.embodit.insideorchestra.domain.usecase

/**
 * Volume sliders (v0.1.5, user 2026-11-05: "声音太小，需要有几个等级和滑动"): master and one per section, 0..1, with
 * three named detents 低 / 中 / 高 (35 / 70 / 100 %) that catch the value within 5 points, so the levels are easy to
 * hit with a ray; mute is a separate toggle next to each slider (the slider keeps its value while muted).
 */
object VolumeSnap {
    const val LOW = 0.35f
    const val MID = 0.7f
    const val HIGH = 1f
    val DETENTS = floatArrayOf(LOW, MID, HIGH)
    const val CATCH = 0.05f

    /** Default for the master and every section: 高 (the loudness profile wants it loud out of the box). */
    const val DEFAULT = HIGH

    fun snap(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        for (d in DETENTS) if (kotlin.math.abs(c - d) <= CATCH) return d
        return c
    }

    /** Which detent [v] sits on (0 低, 1 中, 2 高) or -1 between them. */
    fun level(v: Float): Int = DETENTS.indexOfFirst { kotlin.math.abs(it - v) < 1e-3f }

    fun percent(v: Float): String = "${Math.round(v * 100f)}%"
}
