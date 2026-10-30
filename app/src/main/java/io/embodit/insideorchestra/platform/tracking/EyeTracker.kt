package io.embodit.insideorchestra.platform.tracking

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import io.embodit.insideorchestra.domain.model.Quat4
import io.embodit.insideorchestra.domain.model.Vec3
import io.embodit.insideorchestra.platform.OrchLog

/**
 * Eye gaze source (`EyeTrackingProvider`, SDK doc "Eye tracking": Full Space only, runtime permission
 * `com.picovr.permission.EYE_TRACKING`). Read once per frame from the ECS system, like [HeadTracker]. The gaze pose is in
 * global space with the gaze along local -Z (SDK sample: the marker sits at local (0, 0, -0.4)); the stage root's local
 * space equals that space, so it is used as is.
 *
 * [pose] is null whenever the eyes cannot be used (no permission, device not supporting it, no fresh sample): the
 * scene then falls back to the head direction.
 */
class EyeTracker {
    private var provider: EyeTrackingProvider? = null
    private var lastStamp = 0L
    private var lastFreshMs = 0L

    /** Debug: pretend the eyes are unavailable. */
    var forceOff = false

    fun start() {
        if (provider != null) return
        val p = EyeTrackingProvider()
        val result = runCatching { p.start() }
        OrchLog.i("TRACK eye start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        lastStamp = 0L
    }

    fun supportState(): DataProvider.SupportState? = provider?.let { p -> runCatching { p.supportState }.getOrNull() }

    /** Why the eyes are not used now: null = eyes in use; else "permission", "unsupported", "no-data", "off". */
    fun unavailableReason(nowMs: Long): String? {
        if (forceOff) return "off"
        return when (supportState()) {
            DataProvider.SupportState.SUPPORTED -> if (nowMs - lastFreshMs > STALE_MS) "no-data" else null
            DataProvider.SupportState.WITHOUT_PERMISSION -> "permission"
            null -> "no-data"
            else -> "unsupported"
        }
    }

    /** Latest gaze pose (position + rotation, gaze along local -Z), or null when not usable. */
    fun pose(nowMs: Long): HeadPose? {
        val p = provider ?: return null
        if (forceOff || runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        if (data.timestamp == 0L) return null
        if (data.timestamp != lastStamp) {
            lastStamp = data.timestamp
            lastFreshMs = nowMs
        }
        if (nowMs - lastFreshMs > STALE_MS) return null
        val pos = data.eyePose.position
        val rot = data.eyePose.rotation
        val pose = HeadPose(Vec3(pos.x, pos.y, pos.z), Quat4(rot.x, rot.y, rot.z, rot.w))
        if (!pose.position.isFinite() || pose.position.length < 1e-4f) return null
        return pose
    }

    private companion object {
        const val STALE_MS = 1_000L
    }
}
