package io.embodit.insideorchestra.ui.orch.scene

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.insideorchestra.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Collects vertices and triangles of a programmatic mesh. */
internal class MeshBuilder {
    private val positions = ArrayList<Vector3>()
    private val normals = ArrayList<Vector3>()
    private val uvs = ArrayList<Vector2>()
    private val indices = ArrayList<Int>()

    fun vertex(p: Vec3, n: Vec3, u: Float = 0f, v: Float = 0f): Int {
        positions += Vector3(p.x, p.y, p.z)
        normals += Vector3(n.x, n.y, n.z)
        uvs += Vector2(u, v)
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        indices += a
        indices += b
        indices += c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    /** A flat strip on the floor plane (XZ, facing +Y) from [a] to [b], [band] wide. */
    fun floorStrip(a: Vec3, b: Vec3, band: Float) {
        val dir = (b - a).normalized()
        val side = Vec3.UP.cross(dir).normalized() * (band / 2f)
        quad(vertex(a - side, Vec3.UP), vertex(a + side, Vec3.UP), vertex(b + side, Vec3.UP), vertex(b - side, Vec3.UP))
    }

    /** A strip standing in a vertical plane with normal [n], from [a] to [b], [band] wide (virtual wall lines). */
    fun wallStrip(a: Vec3, b: Vec3, band: Float, n: Vec3) {
        val dir = (b - a).normalized()
        val side = n.cross(dir).normalized() * (band / 2f)
        quad(vertex(a - side, n), vertex(a + side, n), vertex(b + side, n), vertex(b - side, n))
    }

    fun build(name: String): MeshResource {
        var lo = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var hi = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in positions) {
            lo = Vec3(minOf(lo.x, p.x), minOf(lo.y, p.y), minOf(lo.z, p.z))
            hi = Vec3(maxOf(hi.x, p.x), maxOf(hi.y, p.y), maxOf(hi.z, p.z))
        }
        val c = (lo + hi) / 2f
        val h = (hi - lo) / 2f
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs),
            bounds = BoundingBox(center = Vector3(c.x, c.y, c.z), halfExtent = Vector3(maxOf(h.x, 0.001f), maxOf(h.y, 0.001f), maxOf(h.z, 0.001f))),
            name = name,
        )
    }
}

internal object Meshes {
    /**
     * A unit quad in the XY plane facing +Z (-0.5 .. 0.5) with texture coordinates. v = 1 on the top edge: the
     * engine samples v = 0 at the bitmap's bottom row (Chime Bells / Light Planner / Sky Dome all hit this).
     */
    fun quadUv(name: String): MeshResource {
        val m = MeshBuilder()
        val n = Vec3(0f, 0f, 1f)
        m.quad(
            m.vertex(Vec3(-0.5f, -0.5f, 0f), n, 0f, 0f),
            m.vertex(Vec3(0.5f, -0.5f, 0f), n, 1f, 0f),
            m.vertex(Vec3(0.5f, 0.5f, 0f), n, 1f, 1f),
            m.vertex(Vec3(-0.5f, 0.5f, 0f), n, 0f, 1f),
        )
        return m.build(name)
    }

    /** A unit square on the floor (XZ, -0.5 .. 0.5, facing +Y) with uv: zone decals, scaled per zone. */
    fun floorQuad(name: String): MeshResource {
        val m = MeshBuilder()
        m.quad(
            m.vertex(Vec3(-0.5f, 0f, 0.5f), Vec3.UP, 0f, 0f),
            m.vertex(Vec3(0.5f, 0f, 0.5f), Vec3.UP, 1f, 0f),
            m.vertex(Vec3(0.5f, 0f, -0.5f), Vec3.UP, 1f, 1f),
            m.vertex(Vec3(-0.5f, 0f, -0.5f), Vec3.UP, 0f, 1f),
        )
        return m.build(name)
    }

    /**
     * A rectangle outline on the floor from ([x0], [z0]) to ([x1], [z1]), band [band]; with [dash] > 0 broken into
     * dash / gap pieces (masking-tape footprint, snap outline).
     */
    fun rectOutline(x0: Float, z0: Float, x1: Float, z1: Float, band: Float, dash: Float, gap: Float, name: String): MeshResource {
        val m = MeshBuilder()
        fun edge(a: Vec3, b: Vec3) {
            val len = a.distanceTo(b)
            if (dash <= 0f) {
                m.floorStrip(a, b, band)
                return
            }
            val dir = (b - a) / len
            var s = 0f
            while (s < len) {
                m.floorStrip(a + dir * s, a + dir * minOf(s + dash, len), band)
                s += dash + gap
            }
        }
        val c = listOf(Vec3(x0, 0f, z0), Vec3(x1, 0f, z0), Vec3(x1, 0f, z1), Vec3(x0, 0f, z1))
        for (i in 0 until 4) edge(c[i], c[(i + 1) % 4])
        return m.build(name)
    }

    /** A floor grid of 1 m cells over [w] x [d] metres centred on the origin (virtual floor). */
    fun grid(w: Float, d: Float, band: Float, name: String): MeshResource {
        val m = MeshBuilder()
        val hw = w / 2f
        val hd = d / 2f
        var x = -kotlin.math.floor(hw)
        while (x <= hw + 1e-3f) {
            m.floorStrip(Vec3(x, 0f, -hd), Vec3(x, 0f, hd), band)
            x += 1f
        }
        var z = -kotlin.math.floor(hd)
        while (z <= hd + 1e-3f) {
            m.floorStrip(Vec3(-hw, 0f, z), Vec3(hw, 0f, z), band)
            z += 1f
        }
        return m.build(name)
    }

    /** A wall frame in the XY plane (facing +Z): rectangle [w] x [h] from y = 0 plus 1 m vertical lines. */
    fun wallFrame(w: Float, h: Float, band: Float, name: String): MeshResource {
        val m = MeshBuilder()
        val n = Vec3(0f, 0f, 1f)
        val hw = w / 2f
        m.wallStrip(Vec3(-hw, 0f, 0f), Vec3(hw, 0f, 0f), band, n)
        m.wallStrip(Vec3(-hw, h, 0f), Vec3(hw, h, 0f), band, n)
        var x = -hw
        while (x <= hw + 1e-3f) {
            m.wallStrip(Vec3(x, 0f, 0f), Vec3(x, h, 0f), band, n)
            x += 1f
        }
        m.wallStrip(Vec3(hw, 0f, 0f), Vec3(hw, h, 0f), band, n)
        return m.build(name)
    }

    /** Flat ring on the floor (XZ, facing +Y). */
    fun ring(inner: Float, outer: Float, name: String, segments: Int = 40): MeshResource {
        val m = MeshBuilder()
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            val p0i = Vec3((cos(a0) * inner).toFloat(), 0f, (sin(a0) * inner).toFloat())
            val p0o = Vec3((cos(a0) * outer).toFloat(), 0f, (sin(a0) * outer).toFloat())
            val p1o = Vec3((cos(a1) * outer).toFloat(), 0f, (sin(a1) * outer).toFloat())
            val p1i = Vec3((cos(a1) * inner).toFloat(), 0f, (sin(a1) * inner).toFloat())
            m.quad(m.vertex(p0i, Vec3.UP), m.vertex(p1i, Vec3.UP), m.vertex(p1o, Vec3.UP), m.vertex(p0o, Vec3.UP))
        }
        return m.build(name)
    }

    /**
     * A sphere seen from inside (normals inward) with equirectangular uv: u around (0 at -Z, growing to the right
     * seen from the centre), v = 1 at the top (the engine samples v = 0 at the bitmap's bottom row).
     */
    fun sphereInside(radius: Float, name: String, rings: Int = 24, sectors: Int = 48): MeshResource {
        val m = MeshBuilder()
        val grid = Array(rings + 1) { IntArray(sectors + 1) }
        for (r in 0..rings) {
            val v = r.toFloat() / rings
            val phi = PI * v // 0 top .. pi bottom
            for (k in 0..sectors) {
                val u = k.toFloat() / sectors
                val th = 2 * PI * u
                val x = (sin(phi) * sin(th)).toFloat()
                val y = cos(phi).toFloat()
                val z = (-sin(phi) * cos(th)).toFloat()
                grid[r][k] = m.vertex(Vec3(x * radius, y * radius, z * radius), Vec3(-x, -y, -z), u, 1f - v)
            }
        }
        for (r in 0 until rings) for (k in 0 until sectors) m.quad(grid[r][k], grid[r][k + 1], grid[r + 1][k + 1], grid[r + 1][k])
        return m.build(name)
    }

    /** Disc on the floor (XZ, facing +Y) with uv mapping the unit square (podium top label). */
    fun discUv(radius: Float, y: Float, name: String, segments: Int = 40): MeshResource {
        val m = MeshBuilder()
        val c = m.vertex(Vec3(0f, y, 0f), Vec3.UP, 0.5f, 0.5f)
        val ring = (0 until segments).map { i ->
            val a = 2 * PI * i / segments
            val x = cos(a).toFloat()
            val z = sin(a).toFloat()
            // top of the label (v = 1) towards -Z, the far side seen from the audience
            m.vertex(Vec3(x * radius, y, z * radius), Vec3.UP, 0.5f + x * 0.5f, 0.5f - z * 0.5f)
        }
        for (i in 0 until segments) m.tri(c, ring[(i + 1) % segments], ring[i])
        return m.build(name)
    }
}
