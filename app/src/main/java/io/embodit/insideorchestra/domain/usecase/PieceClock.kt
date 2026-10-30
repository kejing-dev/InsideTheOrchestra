package io.embodit.insideorchestra.domain.usecase

/**
 * The app's own piece clock (design `audio.sync` layer 2): it runs while playing, stops on pause, jumps on seek.
 * Picture, progress, captions and the end of the piece read this clock only, so the emulator works without sound.
 * When the stems report positions ([follow] gets their median), the clock leans towards it by at most 20 ms per
 * second (clock=audio); a median that is missing or stuck for 0.5 s leaves the clock free (clock=app).
 */
class PieceClock(private val durationS: Float) {
    var t = 0f
        private set
    var running = false
        private set
    var followingAudio = false
        private set
    private var lastMedian = -1f
    private var medianStuckS = 0f

    fun start() {
        running = true
    }

    fun pause() {
        running = false
    }

    fun seek(s: Float) {
        t = s.coerceIn(0f, durationS)
        lastMedian = -1f
        medianStuckS = 0f
    }

    /** Advances by [dt]; returns true when the end was reached this step. */
    fun advance(dt: Float): Boolean {
        if (!running) return false
        t += dt
        if (t >= durationS) {
            t = durationS
            running = false
            return true
        }
        return false
    }

    /** [medianS] = the stems' median position in seconds, or a negative value when unavailable. */
    fun follow(medianS: Float, dt: Float) {
        if (!running || dt <= 0f) return
        if (medianS < 0f) {
            followingAudio = false
            return
        }
        if (lastMedian >= 0f && kotlin.math.abs(medianS - lastMedian) < 1e-4f) medianStuckS += dt else medianStuckS = 0f
        lastMedian = medianS
        if (medianStuckS > STUCK_S) {
            followingAudio = false
            return
        }
        followingAudio = true
        val maxStep = FOLLOW_RATE * dt
        val d = (medianS - t).coerceIn(-maxStep, maxStep)
        t = (t + d).coerceIn(0f, durationS)
    }

    companion object {
        /** At most 20 ms of correction per second of playing. */
        const val FOLLOW_RATE = 0.02f
        const val STUCK_S = 0.5f
    }
}
