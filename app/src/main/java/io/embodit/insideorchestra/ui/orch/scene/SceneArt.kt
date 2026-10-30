package io.embodit.insideorchestra.ui.orch.scene

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import io.embodit.insideorchestra.R
import io.embodit.insideorchestra.domain.model.Family
import io.embodit.insideorchestra.ui.theme.OrchColors

/**
 * Bitmaps drawn at run time: the hover name tag ("大提琴 Cello" + family dot), the podium label and the soft
 * floor-zone texture. Drawn upright (row 0 = top); the meshes map v = 1 to the top edge (see [Meshes.quadUv]).
 */
internal object SceneArt {
    const val TAG_W = 512
    const val TAG_H = 128

    private fun typeface(resources: Resources, weight: Int): Typeface =
        runCatching { Typeface.create(resources.getFont(R.font.noto_sans_sc), weight, false) }.getOrDefault(Typeface.DEFAULT_BOLD)

    /** The name tag: dark capsule, family dot, Chinese name 15 sp-equivalent and the English name (design `typography.nameTag`). */
    fun nameTag(resources: Resources, zh: String, en: String, family: Family, english: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(TAG_W, TAG_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = OrchColors.ConcertBlack.copy(alpha = 0.92f).toArgb()
        c.drawRoundRect(RectF(4f, 8f, TAG_W - 4f, TAG_H - 8f), 56f, 56f, p)
        p.color = OrchColors.of(family).toArgb()
        c.drawCircle(64f, TAG_H / 2f, 18f, p)
        p.color = OrchColors.TagInk.toArgb()
        val first = if (english) en else zh
        val second = if (english) zh else en
        p.typeface = typeface(resources, 600)
        p.textSize = 46f
        var x = 100f
        c.drawText(first, x, TAG_H / 2f + 16f, p)
        x += p.measureText(first) + 18f
        p.typeface = typeface(resources, 500)
        p.textSize = 34f
        p.alpha = 200
        if (x + p.measureText(second) < TAG_W - 30f) c.drawText(second, x, TAG_H / 2f + 14f, p)
        return bmp
    }

    /** Podium top: "指挥台 · Podium" around a light disc (256 x 256). */
    fun podiumLabel(resources: Resources): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = OrchColors.Podium.toArgb()
        c.drawCircle(n / 2f, n / 2f, n / 2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 6f
        p.color = OrchColors.Strings.toArgb()
        c.drawCircle(n / 2f, n / 2f, n / 2f - 10f, p)
        p.style = Paint.Style.FILL
        p.color = OrchColors.TagInk.toArgb()
        p.textAlign = Paint.Align.CENTER
        p.typeface = typeface(resources, 600)
        p.textSize = 44f
        c.drawText("指挥台", n / 2f, n / 2f - 4f, p)
        p.typeface = typeface(resources, 500)
        p.textSize = 30f
        c.drawText("Podium", n / 2f, n / 2f + 38f, p)
        return bmp
    }

    /**
     * The VR concert hall seen from inside (equirectangular 1024 x 512, row 0 = zenith): a dark ceiling with a ring
     * of warm lights, balcony bands with little lamps, dark red walls and a dim floor band (the stage floor itself is
     * its own disc). Calm and dark so the orchestra and the panels stay the brightest things.
     */
    fun concertHall(): Bitmap {
        val w = 1024
        val h = 512
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF0E0B10.toInt(), 0xFF1A1114.toInt(), 0xFF3A1A1E.toInt(), 0xFF2A1416.toInt(), 0xFF120C0C.toInt()), floatArrayOf(0f, 0.3f, 0.5f, 0.62f, 1f), Shader.TileMode.CLAMP)
        p.shader = top
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // ceiling lights ring
        p.color = 0xFFFFE2A8.toInt()
        for (i in 0 until 48) c.drawCircle((i + 0.5f) * w / 48f, h * 0.18f, 3f, p)
        // two balcony bands with lamps
        for ((y, a) in listOf(h * 0.40f to 0xFF5A2A22.toInt(), h * 0.48f to 0xFF4A221C.toInt())) {
            p.color = a
            c.drawRect(0f, y, w.toFloat(), y + 6f, p)
            p.color = 0xFFFFC978.toInt()
            for (i in 0 until 64) c.drawCircle((i + 0.5f) * w / 64f, y - 4f, 2f, p)
        }
        // pilasters
        p.color = 0x30FFFFFF
        for (i in 0 until 24) {
            val x = (i + 0.5f) * w / 24f
            c.drawRect(x - 2f, h * 0.50f, x + 2f, h * 0.62f, p)
        }
        return bmp
    }

    /** A warm wooden stage floor (256 x 256, planks along v). */
    fun stageFloor(): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        for (i in 0 until 16) {
            p.color = if (i % 2 == 0) 0xFF6B4528.toInt() else 0xFF5E3C22.toInt()
            c.drawRect(i * 16f, 0f, i * 16f + 16f, n.toFloat(), p)
            p.color = 0xFF3E2716.toInt()
            c.drawRect(i * 16f, 0f, i * 16f + 1f, n.toFloat(), p)
        }
        return bmp
    }

    /** White soft-edged rectangle (alpha falls off over the outer 30 %): the floor zones tint it. */
    fun softRect(): Bitmap {
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = kotlin.math.abs((x + 0.5f) / n * 2f - 1f)
            val dy = kotlin.math.abs((y + 0.5f) / n * 2f - 1f)
            val e = maxOf(dx, dy)
            val a = ((1f - e) / 0.3f).coerceIn(0f, 1f)
            px[y * n + x] = ((a * 255).toInt() shl 24) or 0xFFFFFF
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return bmp
    }

    /** White radial glow (the focus glow under the pointed musician). */
    fun glow(): Bitmap {
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0.35f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(n / 2f, n / 2f, n / 2f, p)
        return bmp
    }
}
