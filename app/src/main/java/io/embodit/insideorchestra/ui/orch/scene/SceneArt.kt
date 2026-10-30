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
