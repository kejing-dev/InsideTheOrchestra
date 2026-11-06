package io.embodit.insideorchestra.domain

import io.embodit.insideorchestra.domain.usecase.VolumeSnap
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.4: the models are opaque (user: "MR 乐队模型好像还有点半透明"), the hall floor is the orchestra's plane, the sliders snap. */
class AssetsTest {
    private val models: File = listOf(File("src/main/assets/models"), File("app/src/main/assets/models")).first { it.isDirectory }

    private fun gltf(f: File): JSONObject {
        val b = f.readBytes()
        val len = (b[12].toInt() and 0xFF) or ((b[13].toInt() and 0xFF) shl 8) or ((b[14].toInt() and 0xFF) shl 16) or ((b[15].toInt() and 0xFF) shl 24)
        return JSONObject(String(b, 20, len, Charsets.UTF_8))
    }

    @Test
    fun everyMaterialIsOpaque() {
        val files = models.listFiles { f -> f.name.endsWith(".glb") }!!.sortedBy { it.name }
        assertEquals(15, files.size) // 14 musicians + the hall
        for (f in files) {
            val mats = gltf(f).getJSONArray("materials")
            for (i in 0 until mats.length()) {
                val m = mats.getJSONObject(i)
                val mode = m.optString("alphaMode", "OPAQUE")
                println("MODEL ${f.name} material=${m.optString("name")} alphaMode=$mode")
                assertEquals("${f.name} ${m.optString("name")}", "OPAQUE", mode)
                val factor = m.optJSONObject("pbrMetallicRoughness")?.optJSONArray("baseColorFactor")
                if (factor != null) assertTrue("${f.name} base alpha", factor.getDouble(3) >= 0.999)
            }
        }
    }

    @Test
    fun hallFloorIsTheOrchestraPlane() {
        val j = gltf(File(models, "hall.glb"))
        // every mesh position accessor's min y is the floor: 0 (+-2 mm), nothing below it
        val acc = j.getJSONArray("accessors")
        var minY = Double.MAX_VALUE
        val meshes = j.getJSONArray("meshes")
        for (m in 0 until meshes.length()) {
            val prims = meshes.getJSONObject(m).getJSONArray("primitives")
            for (p in 0 until prims.length()) {
                val a = acc.getJSONObject(prims.getJSONObject(p).getJSONObject("attributes").getInt("POSITION"))
                minY = minOf(minY, a.getJSONArray("min").getDouble(1))
            }
        }
        assertEquals(0.0, minY, 0.003)
    }

    @Test
    fun slidersSnapAtThreeDetents() {
        // v0.1.5: 低 / 中 / 高 = 35 / 70 / 100 %, caught within 5 points
        assertEquals(0.35f, VolumeSnap.snap(0.32f), 1e-6f)
        assertEquals(0.7f, VolumeSnap.snap(0.74f), 1e-6f)
        assertEquals(1f, VolumeSnap.snap(0.96f), 1e-6f)
        assertEquals(0.52f, VolumeSnap.snap(0.52f), 1e-6f)
        assertEquals(0f, VolumeSnap.snap(-0.2f), 1e-6f)
        assertEquals("70%", VolumeSnap.percent(0.7f))
        assertEquals(0, VolumeSnap.level(VolumeSnap.snap(0.37f)))
        assertEquals(1, VolumeSnap.level(VolumeSnap.snap(0.68f)))
        assertEquals(2, VolumeSnap.level(VolumeSnap.DEFAULT))
        assertEquals(-1, VolumeSnap.level(0.52f))
    }
}
