package io.embodit.insideorchestra.domain.usecase

import io.embodit.insideorchestra.domain.model.Orchestra
import io.embodit.insideorchestra.domain.model.PlaneSurface
import io.embodit.insideorchestra.domain.model.SurfaceKind
import io.embodit.insideorchestra.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor

/** A merged wall: a horizontal segment [a]..[b] at floor height and its unit normal into the room. */
data class MergedWall(val a: Vec3, val b: Vec3, val inward: Vec3) {
    val length: Float
        get() = a.distanceTo(b)
}

/**
 * A snap frame in front of a wall (design `entities3d.snapOutline`): [center] = footprint centre on the
 * floor, [yaw] = rack heading (its +Z = [MergedWall.inward]).
 */
data class SnapFrame(val center: Vec3, val yaw: Float, val wallLength: Float)

/**
 * The room rules of the placement: floor and ceiling heights, coplanar walls merged into one, and up to
 * three snap frames 0.10 m off the long-enough walls (design `I5`, `entities3d.snapOutline`).
 */
class WallSnapUseCase {

    /** Floor height: the largest FLOOR plane (by vertex spread), else null. */
    fun floorY(planes: Collection<PlaneSurface>): Float? =
        planes.filter { it.kind == SurfaceKind.FLOOR && it.horizontal }.maxByOrNull { spread(it) }?.centroid?.y

    /** Centre of the largest FLOOR plane: the room side of every wall. */
    fun floorCenter(planes: Collection<PlaneSurface>): Vec3? =
        planes.filter { it.kind == SurfaceKind.FLOOR && it.horizontal }.maxByOrNull { spread(it) }?.centroid

    /** The lowest CEILING plane (there may be several). */
    fun ceilingY(planes: Collection<PlaneSurface>): Float? =
        planes.filter { it.kind == SurfaceKind.CEILING && it.horizontal }.minOfOrNull { it.centroid.y }

    private fun spread(p: PlaneSurface): Float {
        val xs = p.vertices.map { it.x }
        val zs = p.vertices.map { it.z }
        return (xs.max() - xs.min()) * (zs.max() - zs.min())
    }

    /**
     * WALL planes as horizontal segments, coplanar ones (normals within 10 degrees, planes within
     * 0.15 m, gap below 0.3 m) merged. The room side is where the floor's centre is (wall quad
     * normals are not trusted).
     */
    fun walls(planes: Collection<PlaneSurface>, floorY: Float, roomCenter: Vec3?): List<MergedWall> {
        val segments = ArrayList<MergedWall>()
        for (p in planes) {
            if (p.kind != SurfaceKind.WALL || p.horizontal) continue
            var n = Vec3(p.normal.x, 0f, p.normal.z)
            if (n.length < 0.5f) continue
            n = n.normalized()
            val u = Vec3.UP.cross(n).normalized()
            val c = p.centroid
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (v in p.vertices) {
                val t = (v - c).dot(u)
                lo = minOf(lo, t)
                hi = maxOf(hi, t)
            }
            if (hi - lo < 0.05f) continue
            val base = Vec3(c.x, floorY, c.z)
            val inward =
                if (roomCenter != null) {
                    val side = (Vec3(roomCenter.x, floorY, roomCenter.z) - base).dot(n)
                    if (side >= 0f) n else -n
                } else n
            segments += MergedWall(base + u * lo, base + u * hi, inward)
        }
        // merge coplanar neighbours until nothing changes
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in segments.indices) for (j in i + 1 until segments.size) {
                val m = merge(segments[i], segments[j]) ?: continue
                segments[i] = m
                segments.removeAt(j)
                merged = true
                break@loop
            }
        }
        return segments
    }

    private fun merge(p: MergedWall, q: MergedWall): MergedWall? {
        if (p.inward.dot(q.inward) < COS_10) return null
        val u = (p.b - p.a).normalized()
        // plane offset of q's points from p's line
        val off1 = abs((q.a - p.a).dot(p.inward))
        val off2 = abs((q.b - p.a).dot(p.inward))
        if (off1 > PLANE_TOL_M || off2 > PLANE_TOL_M) return null
        val p0 = 0f
        val p1 = (p.b - p.a).dot(u)
        val q0 = (q.a - p.a).dot(u)
        val q1 = (q.b - p.a).dot(u)
        val qLo = minOf(q0, q1)
        val qHi = maxOf(q0, q1)
        val gap = maxOf(qLo - p1, p0 - qHi)
        if (gap > GAP_M) return null
        val lo = minOf(p0, qLo)
        val hi = maxOf(p1, qHi)
        return MergedWall(p.a + u * lo, p.a + u * hi, p.inward)
    }

    /**
     * Snap frames for the rack at [scale] (1 or 0.5): one per wall of 2.5 - 6.0 m (1.3 m at 1:2, or
     * [minLength] when set), one every 3.0 m beyond; at most three, nearest to [head] first.
     */
    fun frames(walls: List<MergedWall>, scale: Float, head: Vec3?, minLength: Float, max: Int = MAX_FRAMES): List<SnapFrame> {
        val out = ArrayList<SnapFrame>()
        val depthFromWall = (WALL_GAP_M + Orchestra.FOOT_DEPTH / 2f) * scale
        for (w in walls) {
            val len = w.length
            if (len < minLength) continue
            val u = (w.b - w.a).normalized()
            val width = Orchestra.FOOT_WIDTH * scale
            val count = if (len <= 9.0f * scale) 1 else (floor((len - width) / (4.8f * scale)).toInt() + 1).coerceAtLeast(1)
            val span = (count - 1) * 4.8f * scale
            val start = len / 2f - span / 2f
            val yaw = atan2(w.inward.x, w.inward.z)
            for (k in 0 until count) {
                val along = start + k * 4.8f * scale
                out += SnapFrame(w.a + u * along + w.inward * depthFromWall, yaw, len)
            }
        }
        val sorted = if (head == null) out else out.sortedBy { Vec3(it.center.x - head.x, 0f, it.center.z - head.z).length }
        return sorted.take(max)
    }

    companion object {
        const val DEFAULT_MIN_WALL_M = 3.0f
        const val HALF_MIN_WALL_M = 1.5f
        const val WALL_GAP_M = 0.15f
        const val MAX_FRAMES = 3
        private const val COS_10 = 0.985f
        private const val PLANE_TOL_M = 0.15f
        private const val GAP_M = 0.3f
    }
}
