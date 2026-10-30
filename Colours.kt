package com.raceengineer.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Colour tests on the captured frame. No text reading needed for any of these. */
object Colours {

    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    /** Returns hue (0-360), saturation (0-1), value (0-1). */
    fun hsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val mx = maxOf(rf, gf, bf)
        val mn = minOf(rf, gf, bf)
        val d = mx - mn
        var h = 0f
        if (d > 0f) {
            h = when (mx) {
                rf -> ((gf - bf) / d) % 6f
                gf -> (bf - rf) / d + 2f
                else -> (rf - gf) / d + 4f
            } * 60f
            if (h < 0f) h += 360f
        }
        val s = if (mx == 0f) 0f else d / mx
        return floatArrayOf(h, s, mx)
    }

    /**
     * Colour of the tyre ring on a tower row (row 0 = first row).
     * R = red, Y = yellow, W = white, G = green, B = blue, ? = unclear.
     */
    fun ringColour(g: PixelGrid, row: Int): String {
        val cx = Cfg.RING_CX * g.width
        val cy = (Cfg.ROW_Y0 + row * Cfg.ROW_DY) * g.height
        val r = Cfg.RING_R * g.width
        val pts = ArrayList<Int>()
        for (k in floatArrayOf(0.85f, 1.0f)) {
            for (i in 0 until 24) {
                val a = 2.0 * PI * i / 24.0
                val x = (cx + k * r * cos(a)).roundToInt()
                val y = (cy + k * r * sin(a)).roundToInt()
                if (x >= 0 && x < g.width && y >= 0 && y < g.height) pts.add(g.pixel(x, y))
            }
        }
        if (pts.size < 8) return "?"
        // The ring is the bright part; ignore the dark centre and outline.
        val top = pts.sortedByDescending { maxOf(red(it), green(it), blue(it)) }
            .take(maxOf(4, pts.size * 2 / 5))
        var sr = 0
        var sg = 0
        var sb = 0
        for (c in top) {
            sr += red(c)
            sg += green(c)
            sb += blue(c)
        }
        val hsv = hsv(sr / top.size, sg / top.size, sb / top.size)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        return when {
            s < 0.25f && v > 0.6f -> "W"
            s >= 0.35f && (h < 25f || h > 335f) -> "R"
            s >= 0.35f && h >= 35f && h < 75f -> "Y"
            s >= 0.35f && h >= 75f && h < 170f -> "G"
            s >= 0.35f && h >= 170f && h < 260f -> "B"
            else -> "?"
        }
    }

    /**
     * True when this tower row is MY row: the gap cell has a green background.
     * We test the left edge of the cell (no text there). The green goes pale over bright
     * scenery, so the test is deliberately loose.
     */
    fun isMeRow(g: PixelGrid, row: Int): Boolean {
        val cy = (Cfg.ROW_Y0 + row * Cfg.ROW_DY) * g.height
        val x0 = (0.1535f * g.width).toInt()
        val x1 = (0.1665f * g.width).toInt()
        val y0 = (cy - 0.008f * g.height).toInt().coerceAtLeast(0)
        val y1 = (cy + 0.008f * g.height).toInt().coerceAtMost(g.height - 1)
        var total = 0
        var hits = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = g.pixel(x, y)
                total++
                if (green(c) - red(c) > 35 && green(c) - blue(c) > 35) hits++
                x += 2
            }
            y += 2
        }
        return total > 0 && hits.toFloat() / total > 0.2f
    }

    /** True when the red "!" penalty marker is next to this row. */
    fun hasPenaltyMarker(g: PixelGrid, row: Int): Boolean {
        val cy = (Cfg.ROW_Y0 + row * Cfg.ROW_DY) * g.height
        val x0 = (0.2135f * g.width).toInt()
        val x1 = (0.2215f * g.width).toInt()
        val y0 = (cy - 0.006f * g.height).toInt().coerceAtLeast(0)
        val y1 = (cy + 0.006f * g.height).toInt().coerceAtMost(g.height - 1)
        var total = 0
        var hits = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = g.pixel(x, y)
                total++
                if (red(c) > 150 && green(c) < 80 && blue(c) < 80) hits++
                x += 2
            }
            y += 2
        }
        return total > 0 && hits.toFloat() / total > 0.1f
    }

    /**
     * Colour state of the tyre icon in the top-right widget:
     * "cold" (cyan), "ok" (green), "hot?" (orange/red, not yet confirmed), or "?".
     */
    fun tyreTempState(g: PixelGrid): String {
        val x0 = (0.912f * g.width).toInt()
        val x1 = (0.945f * g.width).toInt().coerceAtMost(g.width - 1)
        val y0 = (0.268f * g.height).toInt()
        val y1 = (0.31f * g.height).toInt().coerceAtMost(g.height - 1)
        val hues = ArrayList<Float>()
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = g.pixel(x, y)
                val hsv = hsv(red(c), green(c), blue(c))
                if (hsv[1] > 0.8f && hsv[2] > 0.8f) hues.add(hsv[0])
                x += 2
            }
            y += 2
        }
        if (hues.size < 30) return "?"
        hues.sort()
        val h = hues[hues.size / 2]
        return when {
            h >= 160f && h <= 210f -> "cold"
            h >= 80f && h < 160f -> "ok"
            h < 70f || h > 330f -> "hot?"
            else -> "?"
        }
    }
}
