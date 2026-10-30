package io.embodit.insideorchestra.domain.usecase

/**
 * Volume sliders (v0.1.4: master on the side panel, one per section on its bar): 0..1, with three detents at 50 / 75
 * / 100 % that catch the value within 4 points, so the common levels are easy to hit with a ray.
 */
object VolumeSnap {
    val DETENTS = floatArrayOf(0.5f, 0.75f, 1f)
    const val CATCH = 0.04f

    fun snap(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        for (d in DETENTS) if (kotlin.math.abs(c - d) <= CATCH) return d
        return c
    }

    fun percent(v: Float): String = "${Math.round(v * 100f)}%"
}
