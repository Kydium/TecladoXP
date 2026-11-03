package com.kydium.tecladoxp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** A cosmetic option that unlocks at a level. */
class StyleOption(val id: String, val name: String, val unlockLevel: Int)

object XpBarStyles {
    val all = listOf(
        StyleOption("clasica", "Clásica", 1),
        StyleOption("club", "Club Campestre", 1),
        StyleOption("minima", "Mínima", 1),
        StyleOption("neon", "Neón", 5),
        StyleOption("pixel", "Pixel retro", 10)
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all[0]
}

object TrailStyles {
    val all = listOf(
        StyleOption("clasica", "Clásica", 1),
        StyleOption("cometa", "Cometa", 1),
        StyleOption("neon", "Neón", 1),
        StyleOption("arcoiris", "Arcoíris", 4),
        StyleOption("fuego", "Fuego", 8),
        StyleOption("chispas", "Chispas", 12)
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all[0]
}

/**
 * Draws the level badge, the XP bar and the glide trail in every style.
 * Shared by the keyboard and by the previews in the settings screen.
 */
class StyleRenderer(private val dm: DisplayMetrics) {

    fun dp(v: Float) = v * dm.density
    fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val bounds = Rect()
    private val path = Path()

    private val italicBold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)

    // Club Campestre palette (same as club-xp.js)
    private val clubBlue = Color.parseColor("#3FA9FF")
    private val clubLight = Color.parseColor("#7CC8FF")
    private val clubDark = Color.parseColor("#2F8BE6")

    companion object {
        fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).toInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
            )
        }
    }

    /** Draws [s] so that its real glyph box is centered on (cx, cy). */
    private fun drawCentered(c: Canvas, s: String, cx: Float, cy: Float) {
        text.getTextBounds(s, 0, s.length, bounds)
        // textAlign is CENTER: the advance box is centered on x, so correct by the glyph box offset
        c.drawText(s, cx + text.measureText(s) / 2f - bounds.exactCenterX(), cy - bounds.exactCenterY(), text)
    }

    // ================================================================ badge

    fun drawBadge(c: Canvas, style: String, t: KbTheme, cx: Float, cy: Float, r: Float, level: Int, frac: Float, pressed: Boolean) {
        fill.shader = null
        stroke.shader = null
        val num = level.toString()
        when (style) {
            "club" -> {
                // glow
                fill.color = withAlpha(clubBlue, 50)
                c.drawCircle(cx, cy, r + dp(4f), fill)
                fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.4f, r * 1.4f,
                    Color.parseColor("#2A2F3A"), Color.parseColor("#0B0D12"), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, fill)
                fill.shader = null
                stroke.strokeWidth = dp(2.6f)
                stroke.color = if (pressed) Color.WHITE else clubBlue
                c.drawCircle(cx, cy, r, stroke)
                text.typeface = italicBold
                text.textSize = sp(if (level >= 100) 13f else 16f)
                text.color = Color.BLACK
                drawCentered(c, num, cx, cy + dp(1.2f))
                text.color = Color.WHITE
                drawCentered(c, num, cx, cy)
            }
            "pixel" -> {
                val s = r * 0.92f
                fill.color = t.accent
                c.drawRect(cx - s, cy - s, cx + s, cy + s, fill)
                fill.color = if (pressed) t.keyPressed else t.keySpecial
                val b = dp(3f)
                c.drawRect(cx - s + b, cy - s + b, cx + s - b, cy + s - b, fill)
                // progress as pixel border on the bottom
                fill.color = t.accent2
                c.drawRect(cx - s, cy + s - b, cx - s + 2 * s * frac, cy + s, fill)
                text.typeface = Typeface.MONOSPACE
                text.isFakeBoldText = true
                text.textSize = sp(if (level >= 100) 12f else 15f)
                text.color = t.text
                drawCentered(c, num, cx, cy)
                text.isFakeBoldText = false
            }
            "neon" -> {
                fill.color = if (pressed) t.keyPressed else t.keySpecial
                c.drawCircle(cx, cy, r, fill)
                for ((w, a) in listOf(9f to 35, 6f to 70, 3f to 255)) {
                    stroke.strokeWidth = dp(w)
                    stroke.color = withAlpha(t.accent, a)
                    rect.set(cx - r, cy - r, cx + r, cy + r)
                    c.drawArc(rect, -90f, 360f * max(frac, 0.02f), false, stroke)
                }
                stroke.strokeWidth = dp(1f)
                stroke.color = withAlpha(t.accent2, 90)
                c.drawCircle(cx, cy, r - dp(4f), stroke)
                text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = sp(if (level >= 100) 12f else 15f)
                text.color = withAlpha(t.accent, 90)
                drawCentered(c, num, cx, cy)
                text.color = t.text
                drawCentered(c, num, cx, cy)
            }
            "minima" -> {
                if (pressed) {
                    fill.color = t.keyPressed
                    c.drawCircle(cx, cy, r, fill)
                }
                text.typeface = Typeface.DEFAULT
                text.textSize = sp(8f)
                text.color = t.textMuted
                text.getTextBounds("NV", 0, 2, bounds)
                val hNv = bounds.height().toFloat()
                text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = sp(if (level >= 100) 15f else 19f)
                text.getTextBounds(num, 0, num.length, bounds)
                val hNum = bounds.height().toFloat()
                val gap = dp(3f)
                val top = cy - (hNv + gap + hNum) / 2f
                text.color = t.accent
                c.drawText(num, cx, top + hNv + gap + hNum, text)
                text.typeface = Typeface.DEFAULT
                text.textSize = sp(8f)
                text.color = t.textMuted
                c.drawText("NV", cx, top + hNv, text)
            }
            else -> { // clasica
                fill.color = if (pressed) t.keyPressed else t.keySpecial
                c.drawCircle(cx, cy, r, fill)
                stroke.strokeWidth = dp(3.2f)
                stroke.color = withAlpha(t.textMuted, 70)
                c.drawCircle(cx, cy, r, stroke)
                stroke.color = t.accent
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, -90f, 360f * frac, false, stroke)
                // "NV" + number as one block, centered in the circle
                text.typeface = Typeface.DEFAULT
                text.textSize = sp(7.5f)
                text.getTextBounds("NV", 0, 2, bounds)
                val hNv = bounds.height().toFloat()
                text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = sp(if (level >= 100) 12f else 15f)
                text.getTextBounds(num, 0, num.length, bounds)
                val hNum = bounds.height().toFloat()
                val gap = dp(2.5f)
                val top = cy - (hNv + gap + hNum) / 2f
                text.color = t.text
                c.drawText(num, cx, top + hNv + gap + hNum, text)
                text.typeface = Typeface.DEFAULT
                text.textSize = sp(7.5f)
                text.color = t.textMuted
                c.drawText("NV", cx, top + hNv, text)
            }
        }
        text.typeface = Typeface.DEFAULT
    }

    // ================================================================ bar

    /** Bar along [left, right], vertically centered on [cy]. */
    fun drawBar(c: Canvas, style: String, t: KbTheme, left: Float, right: Float, cy: Float, frac: Float, level: Int) {
        fill.shader = null
        val f = frac.coerceIn(0f, 1f)
        when (style) {
            "club" -> {
                val nr = dp(8.5f)
                val barR = right - nr * 2 - dp(2f)
                val h = dp(7f)
                val top = cy - h / 2f
                fill.color = Color.argb(160, 0, 0, 0)
                c.drawRect(left, top, barR, top + h, fill)
                val fr = left + (barR - left) * f
                if (fr > left) {
                    fill.color = withAlpha(clubBlue, 70)
                    c.drawRect(left, top - dp(2f), fr, top + h + dp(2f), fill)
                    fill.shader = LinearGradient(0f, top, 0f, top + h, clubLight, clubDark, Shader.TileMode.CLAMP)
                    c.drawRect(left, top, fr, top + h, fill)
                    fill.shader = null
                }
                // 10 segments
                fill.color = Color.argb(160, 0, 0, 0)
                val seg = (barR - left) / 10f
                for (i in 1..9) {
                    val x = left + seg * i
                    c.drawRect(x - dp(1f), top, x + dp(1f), top + h, fill)
                }
                fill.color = Color.argb(45, 255, 255, 255)
                c.drawRect(left, top - dp(0.7f), barR, top, fill)
                c.drawRect(left, top + h, barR, top + h + dp(0.7f), fill)
                // next level circle
                val ncx = right - nr
                fill.color = Color.parseColor("#12151C")
                c.drawCircle(ncx, cy, nr, fill)
                stroke.shader = null
                stroke.strokeWidth = dp(1.8f)
                stroke.color = Color.argb(95, 255, 255, 255)
                c.drawCircle(ncx, cy, nr, stroke)
                text.typeface = italicBold
                text.textSize = sp(if (level >= 99) 7f else 9f)
                text.color = Color.WHITE
                drawCentered(c, (level + 1).toString(), ncx, cy)
                text.typeface = Typeface.DEFAULT
            }
            "pixel" -> {
                val blocks = 20
                val gap = dp(1.5f)
                val h = dp(5f)
                val bw = (right - left - gap * (blocks - 1)) / blocks
                val filled = (f * blocks).toInt()
                for (i in 0 until blocks) {
                    val x = left + i * (bw + gap)
                    fill.color = when {
                        i < filled -> if (i % 2 == 0) t.accent else t.accent2
                        else -> withAlpha(t.textMuted, 45)
                    }
                    c.drawRect(x, cy - h / 2f, x + bw, cy + h / 2f, fill)
                }
            }
            "neon" -> {
                val h = dp(4f)
                fill.color = withAlpha(t.textMuted, 35)
                rect.set(left, cy - h / 2f, right, cy + h / 2f)
                c.drawRoundRect(rect, h, h, fill)
                val fr = left + (right - left) * f
                if (fr > left + 1f) {
                    for ((grow, a) in listOf(5f to 30, 2.5f to 70)) {
                        fill.color = withAlpha(t.accent, a)
                        rect.set(left - dp(grow), cy - h / 2f - dp(grow), fr + dp(grow), cy + h / 2f + dp(grow))
                        c.drawRoundRect(rect, h + dp(grow), h + dp(grow), fill)
                    }
                    fill.shader = LinearGradient(left, 0f, right, 0f, t.accent, t.accent2, Shader.TileMode.CLAMP)
                    rect.set(left, cy - h / 2f, fr, cy + h / 2f)
                    c.drawRoundRect(rect, h, h, fill)
                    fill.shader = null
                    fill.color = Color.WHITE
                    c.drawCircle(fr, cy, dp(2.6f), fill)
                }
            }
            "minima" -> {
                val h = dp(2f)
                fill.color = withAlpha(t.textMuted, 40)
                c.drawRect(left, cy - h / 2f, right, cy + h / 2f, fill)
                fill.color = t.accent
                c.drawRect(left, cy - h / 2f, left + (right - left) * f, cy + h / 2f, fill)
            }
            else -> { // clasica
                val h = dp(3.5f)
                fill.color = withAlpha(t.textMuted, 45)
                rect.set(left, cy - h / 2f, right, cy + h / 2f)
                c.drawRoundRect(rect, h, h, fill)
                val fr = left + (right - left) * f
                if (fr > left + 1f) {
                    fill.shader = LinearGradient(left, 0f, right, 0f, t.accent, t.accent2, Shader.TileMode.CLAMP)
                    rect.set(left, cy - h / 2f, fr, cy + h / 2f)
                    c.drawRoundRect(rect, h, h, fill)
                    fill.shader = null
                }
            }
        }
    }

    // ================================================================ trail

    private class TP(
        var x: Float, var y: Float, val vx: Float, val vy: Float,
        val born: Long, val life: Long, val color: Int, val size: Float, val star: Boolean
    )

    private val parts = ArrayList<TP>()
    private var spawnCounter = 0

    /** Called for each new finger point while gliding. */
    fun onTrailPoint(style: String, t: KbTheme, x: Float, y: Float) {
        if (style != "fuego" && style != "chispas") return
        spawnCounter++
        if (spawnCounter % 2 != 0) return
        val now = SystemClock.uptimeMillis()
        if (parts.size > 140) parts.removeAt(0)
        if (style == "fuego") {
            val col = lerpColor(Color.parseColor("#FF3C00"), Color.parseColor("#FFD24A"), Random.nextFloat())
            parts.add(TP(x + (Random.nextFloat() - 0.5f) * dp(6f), y, (Random.nextFloat() - 0.5f) * dp(1.2f),
                -dp(0.8f) - Random.nextFloat() * dp(1.5f), now, 450L + Random.nextLong(250), col, dp(2f + Random.nextFloat() * 3f), false))
        } else {
            val col = if (Random.nextBoolean()) Color.WHITE else t.accent2
            parts.add(TP(x + (Random.nextFloat() - 0.5f) * dp(14f), y + (Random.nextFloat() - 0.5f) * dp(14f),
                (Random.nextFloat() - 0.5f) * dp(0.6f), (Random.nextFloat() - 0.5f) * dp(0.6f),
                now, 500L + Random.nextLong(300), col, dp(3f + Random.nextFloat() * 4f), true))
        }
    }

    fun clearParticles() = parts.clear()

    /** @return true while particles are still animating */
    fun drawParticles(c: Canvas): Boolean {
        if (parts.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        val it = parts.iterator()
        fill.shader = null
        while (it.hasNext()) {
            val p = it.next()
            val age = (now - p.born).toFloat() / p.life
            if (age >= 1f) { it.remove(); continue }
            p.x += p.vx
            p.y += p.vy
            drawParticle(c, p.x, p.y, p.size * (1f - age * 0.6f), p.color, (255 * (1f - age)).toInt(), p.star, age * 4f)
        }
        return parts.isNotEmpty()
    }

    private fun drawParticle(c: Canvas, x: Float, y: Float, size: Float, color: Int, alpha: Int, star: Boolean, rot: Float) {
        if (star) {
            stroke.shader = null
            stroke.strokeWidth = max(dp(1f), size / 4f)
            stroke.color = withAlpha(color, alpha)
            val dx = cos(rot) * size
            val dy = sin(rot) * size
            c.drawLine(x - dx, y - dy, x + dx, y + dy, stroke)
            c.drawLine(x + dy, y - dx, x - dy, y + dx, stroke)
        } else {
            fill.color = withAlpha(color, alpha)
            c.drawCircle(x, y, size / 2f, fill)
        }
    }

    /**
     * Draws a finger path. [alpha] 0..255 lets the trail fade out after the finger lifts.
     * [preview] adds a few static particles for the settings screen.
     */
    fun drawTrail(c: Canvas, style: String, t: KbTheme, xs: FloatArray, ys: FloatArray, n: Int, alpha: Int, preview: Boolean = false) {
        if (n < 2) return
        stroke.shader = null
        when (style) {
            "cometa" -> {
                val start = max(1, n - 160)
                val span = (n - start).toFloat()
                for (i in start until n) {
                    val f = (i - start + 1) / span
                    stroke.strokeWidth = dp(1.5f + 8f * f)
                    stroke.color = withAlpha(lerpColor(t.accent, t.accent2, f), (alpha * f.pow(0.7f)).toInt())
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], stroke)
                }
                fill.color = withAlpha(Color.WHITE, alpha)
                c.drawCircle(xs[n - 1], ys[n - 1], dp(4.5f), fill)
            }
            "arcoiris" -> {
                for (i in 1 until n) {
                    stroke.strokeWidth = dp(5.5f)
                    stroke.color = Color.HSVToColor(alpha, floatArrayOf((i * 7f) % 360f, 0.85f, 1f))
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], stroke)
                }
            }
            "fuego" -> {
                val start = max(1, n - 160)
                val span = (n - start).toFloat()
                val red = Color.parseColor("#FF2A00")
                val yellow = Color.parseColor("#FFE066")
                for (pass in 0..1) for (i in start until n) {
                    val f = (i - start + 1) / span
                    val w = dp(2f + 8f * f)
                    if (pass == 0) {
                        stroke.strokeWidth = w * 1.9f
                        stroke.color = withAlpha(Color.parseColor("#FF6A00"), (alpha * 0.22f * f).toInt())
                    } else {
                        stroke.strokeWidth = w
                        stroke.color = withAlpha(lerpColor(red, yellow, f), (alpha * f.pow(0.5f)).toInt())
                    }
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], stroke)
                }
                if (preview) {
                    val rnd = Random(3)
                    for (k in 0 until 10) {
                        val i = n / 3 + rnd.nextInt(n - n / 3)
                        drawParticle(c, xs[i] + rnd.nextFloat() * dp(8f) - dp(4f), ys[i] - rnd.nextFloat() * dp(10f),
                            dp(3f), lerpColor(red, yellow, rnd.nextFloat()), 220, false, 0f)
                    }
                }
            }
            "neon" -> {
                path.reset()
                path.moveTo(xs[0], ys[0])
                for (i in 1 until n) path.lineTo(xs[i], ys[i])
                for ((w, a) in listOf(15f to 0.10f, 9f to 0.22f, 5.5f to 0.55f)) {
                    stroke.strokeWidth = dp(w)
                    stroke.color = withAlpha(t.accent, (alpha * a).toInt())
                    c.drawPath(path, stroke)
                }
                stroke.strokeWidth = dp(2f)
                stroke.color = withAlpha(Color.WHITE, alpha)
                c.drawPath(path, stroke)
            }
            "chispas" -> {
                path.reset()
                path.moveTo(xs[0], ys[0])
                for (i in 1 until n) path.lineTo(xs[i], ys[i])
                stroke.strokeWidth = dp(2.2f)
                stroke.color = withAlpha(t.accent2, (alpha * 0.7f).toInt())
                c.drawPath(path, stroke)
                if (preview) {
                    val rnd = Random(7)
                    for (k in 0 until 9) {
                        val i = rnd.nextInt(n)
                        drawParticle(c, xs[i] + rnd.nextFloat() * dp(14f) - dp(7f), ys[i] + rnd.nextFloat() * dp(14f) - dp(7f),
                            dp(3f + rnd.nextFloat() * 3f), if (k % 2 == 0) Color.WHITE else t.accent2, 230, true, rnd.nextFloat() * 3f)
                    }
                }
            }
            else -> { // clasica
                path.reset()
                path.moveTo(xs[0], ys[0])
                for (i in 1 until n) path.lineTo(xs[i], ys[i])
                stroke.strokeWidth = dp(7f)
                stroke.color = withAlpha(t.accent, alpha / 3)
                c.drawPath(path, stroke)
                stroke.strokeWidth = dp(3.5f)
                stroke.color = withAlpha(t.accent2, alpha)
                c.drawPath(path, stroke)
            }
        }
    }
}
