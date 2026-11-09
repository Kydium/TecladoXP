package com.kydium.tecladoxp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Your own theme: a picture behind the keys, see-through keys and, if you want, an outline on the
 * keys whose color is taken automatically from the picture, from a spot you tap, or plain white/black.
 *
 * Settings (in "settings"): ct_dim 0..70 (darken the picture %), ct_keys 0 transparent / 1 medium /
 * 2 solid, ct_light (light keys with dark letters), ct_border (outline on), ct_bmode 0 auto /
 * 1 picked spot / 2 white / 3 black, ct_px / ct_py (picked spot, 0..1 of the visible picture).
 */
object CustomTheme {
    const val ID = "propio"
    private const val FILE = "tema_fondo.jpg"

    private var cached: Bitmap? = null
    private var cachedStamp = 0L
    private var autoColor: Int? = null

    fun file(ctx: Context) = File(ctx.filesDir, FILE)
    fun hasImage(ctx: Context) = file(ctx).exists()

    /** Copies the picture you chose (made smaller so it loads fast). */
    fun importImage(ctx: Context, uri: Uri): Boolean {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1080 && bounds.outHeight / (sample * 2) >= 700) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
        val scale = min(1f, 1440f / max(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        file(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        cached = null
        autoColor = null
        return true
    }

    fun remove(ctx: Context) {
        file(ctx).delete()
        cached = null
        autoColor = null
    }

    fun bitmap(ctx: Context): Bitmap? {
        val f = file(ctx)
        if (!f.exists()) return null
        val stamp = f.lastModified()
        if (cached == null || stamp != cachedStamp) {
            cached = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
            cachedStamp = stamp
            autoColor = null
        }
        return cached
    }

    /** The most colorful tone of the picture (what "automatic" uses). */
    fun autoColor(ctx: Context): Int {
        autoColor?.let { return it }
        val bmp = bitmap(ctx) ?: return Color.WHITE
        val small = Bitmap.createScaledBitmap(bmp, 48, 48, true)
        val bins = FloatArray(24)
        val sumR = FloatArray(24); val sumG = FloatArray(24); val sumB = FloatArray(24)
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            if (hsv[2] < 0.3f || hsv[1] < 0.25f) continue
            val w = hsv[1] * hsv[2]
            val b = ((hsv[0] / 15f).toInt()).coerceIn(0, 23)
            bins[b] += w
            sumR[b] += Color.red(c) * w; sumG[b] += Color.green(c) * w; sumB[b] += Color.blue(c) * w
        }
        var best = -1
        for (i in bins.indices) if (bins[i] > 0f && (best < 0 || bins[i] > bins[best])) best = i
        val col = if (best < 0 || bins[best] < 3f) {
            // grey picture: white outline
            Color.WHITE
        } else {
            val w = bins[best]
            val c = Color.rgb((sumR[best] / w).toInt(), (sumG[best] / w).toInt(), (sumB[best] / w).toInt())
            // make it a bit brighter so it shows over the picture
            Color.colorToHSV(c, hsv)
            hsv[1] = max(hsv[1], 0.55f); hsv[2] = max(hsv[2], 0.85f)
            Color.HSVToColor(hsv)
        }
        autoColor = col
        return col
    }

    /** Color of the picture at a spot (0..1 of the part the keyboard shows). */
    fun colorAt(ctx: Context, fx: Float, fy: Float, aspect: Float): Int {
        val bmp = bitmap(ctx) ?: return Color.WHITE
        val r = visibleRect(bmp.width, bmp.height, aspect, position(ctx))
        val x = (r[0] + fx.coerceIn(0f, 1f) * (r[2] - r[0])).toInt().coerceIn(0, bmp.width - 1)
        val y = (r[1] + fy.coerceIn(0f, 1f) * (r[3] - r[1])).toInt().coerceIn(0, bmp.height - 1)
        return bmp.getPixel(x, y) or 0xFF000000.toInt()
    }

    /**
     * Part of the picture that fills an area of [aspect] = height / width.
     * [pos] = x, y where the crop sits (0..1, 0.5 = centered) and zoom (1 = whole width or height).
     */
    fun visibleRect(w: Int, h: Int, aspect: Float, pos: FloatArray = floatArrayOf(0.5f, 0.5f, 1f)): IntArray {
        var cw: Float
        var ch: Float
        if (w * aspect <= h) { cw = w.toFloat(); ch = w * aspect } else { ch = h.toFloat(); cw = h / aspect }
        val z = pos[2].coerceIn(1f, 4f)
        cw /= z; ch /= z
        val left = ((w - cw) * pos[0].coerceIn(0f, 1f))
        val top = ((h - ch) * pos[1].coerceIn(0f, 1f))
        return intArrayOf(left.toInt(), top.toInt(), (left + cw).toInt(), (top + ch).toInt())
    }

    fun position(ctx: Context): FloatArray {
        val s = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return floatArrayOf(s.getFloat("ct_ox", 0.5f), s.getFloat("ct_oy", 0.5f), s.getFloat("ct_zoom", 1f))
    }

    /** Height / width of the keyboard (about the same on every screen). */
    const val KEYBOARD_ASPECT = 0.72f

    fun borderColor(ctx: Context): Int {
        val s = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return when (s.getInt("ct_bmode", 0)) {
            1 -> colorAt(ctx, s.getFloat("ct_px", 0.5f), s.getFloat("ct_py", 0.5f), KEYBOARD_ASPECT)
            2 -> Color.WHITE
            3 -> Color.BLACK
            else -> autoColor(ctx)
        }
    }

    fun theme(ctx: Context): KbTheme {
        val s = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val light = s.getBoolean("ct_light", false)
        // transparency of the keys, 0 = solid … 100 = invisible
        val transp = s.getInt("ct_transp", when (s.getInt("ct_keys", 1)) { 0 -> 72; 2 -> 15; else -> 45 })
        val alpha = (255 * (100 - transp.coerceIn(0, 100)) / 100)
        val accent = autoColor(ctx)
        val border = if (s.getBoolean("ct_border", true)) borderColor(ctx) else 0
        val keyRgb = if (light) Color.rgb(250, 250, 252) else Color.rgb(18, 18, 24)
        fun a(c: Int, al: Int) = Color.argb(al.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
        return KbTheme(
            ID, "Mi tema", 1,
            if (light) Color.rgb(230, 230, 236) else Color.rgb(16, 16, 20),
            if (light) Color.rgb(215, 215, 222) else Color.rgb(8, 8, 12),
            a(keyRgb, alpha),
            a(keyRgb, min(255, alpha + 40)),
            a(accent, 170),
            if (light) Color.rgb(20, 20, 26) else Color.WHITE,
            if (light) Color.rgb(70, 70, 80) else Color.rgb(205, 205, 215),
            accent,
            if (light) Color.rgb(40, 40, 50) else Color.WHITE,
            border = border,
            dim = s.getInt("ct_dim", 25)
        )
    }
}
