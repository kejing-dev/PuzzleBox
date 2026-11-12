package com.kejing.puzzlebox.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.core.graphics.PathParser
import com.kejing.puzzlebox.domain.Cell
import com.kejing.puzzlebox.domain.Dims
import com.kejing.puzzlebox.domain.Layout
import com.kejing.puzzlebox.domain.Orient
import com.kejing.puzzlebox.domain.PieceType
import com.kejing.puzzlebox.domain.Turn
import com.kejing.puzzlebox.platform.TAG

/**
 * Canvas textures of the volume (the wooden models are GLBs from tools/build_models.py): the brass name plate, the
 * tab faces (Material Symbols icon + word), the mouth overlay (grid lines + "还能放几格" ticks), the tray outline of a
 * lifted block, the anchor dot. Image up = +Y for upright planes; for planes laid flat with -90° about X image up is the
 * far edge (-Z) (D10, checked with the F↑ test).
 */
object BoardTextures {
    // Material Symbols Rounded (Apache-2.0), viewBox 0 -960 960 960 (third_party/material-symbols, tools/symbols.py)
    private const val SYM_360 = "M374-288q-128-17-211-70T80-480q0-83 115.5-141.5T480-680q169 0 284.5 58.5T880-480q0 56-54.5 101.5T681-307q-16 5-28.5-4.5T640-337q0-18 10.5-32t27.5-20q60-20 91-45.5t31-45.5q0-32-85.5-76T480-600q-149 0-234.5 44T160-480q0 24 51 57.5T356-372l-24-24q-11-11-11-28t11-28q11-11 28-11t28 11l104 104q12 12 12 28t-12 28L388-188q-11 11-28 11t-28-11q-11-11-11-28t11-28l42-44Z"
    private const val SYM_MOVE_DOWN = "M120-520q0 72 46 127t117 69l-31-31q-11-11-11-27.5t11-28.5q12-12 28.5-12t28.5 12l103 103q12 12 12 28t-12 28L308-148q-11 11-28 11t-29-12q-11-11-11-27t11-28l36-38q-106-12-176.5-91.5T40-520q0-117 81.5-198.5T320-800h120q17 0 28.5 11.5T480-760q0 17-11.5 28.5T440-720H320q-83 0-141.5 58.5T120-520Zm480 0q-33 0-56.5-23.5T520-600v-120q0-33 23.5-56.5T600-800h200q33 0 56.5 23.5T880-720v120q0 33-23.5 56.5T800-520H600Zm0-80h200v-120H600v120Zm0 440q-33 0-56.5-23.5T520-240v-120q0-33 23.5-56.5T600-440h200q33 0 56.5 23.5T880-360v120q0 33-23.5 56.5T800-160H600Z"
    private const val SYM_U_TURN_RIGHT = "M280-120q-17 0-28.5-11.5T240-160v-440q0-100 70-170t170-70q100 0 170 70t70 170v168l36-36q11-11 28-11t28 11q11 11 11 28t-11 28L708-308q-6 6-13 8.5t-15 2.5q-8 0-15-2.5t-13-8.5L548-412q-11-11-11-28t11-28q11-11 28-11t28 11l36 36v-168q0-66-47-113t-113-47q-66 0-113 47t-47 113v440q0 17-11.5 28.5T280-120Z"

    val PLATE_FACE = Color.rgb(0xC9, 0xA2, 0x4E)
    val PLATE_EDGE = Color.rgb(0x7A, 0x5A, 0x22)
    val PLATE_INK = Color.rgb(0x2A, 0x1C, 0x0C)
    val TAB_FACE = Color.rgb(0xEE, 0xD9, 0xB4)
    val TAB_INK = Color.rgb(0x2E, 0x22, 0x18)

    private fun symbol(d: String, size: Float, mirror: Boolean): Path {
        val p = PathParser.createPathFromPathData(d)
        val m = Matrix()
        m.postTranslate(0f, 960f)
        if (mirror) { m.postScale(-1f, 1f); m.postTranslate(960f, 0f) }
        m.postScale(size / 960f, size / 960f)
        p.transform(m)
        return p
    }

    fun symbolFor(t: Turn): Pair<String, Boolean> = when (t) {
        // design D23: 转 = 360 mirrored (clockwise from above), 前翻 = move_down, 侧翻 = u_turn_right
        Turn.YAW -> SYM_360 to true
        Turn.PITCH -> SYM_MOVE_DOWN to false
        Turn.ROLL -> SYM_U_TURN_RIGHT to false
    }

    private fun font(bold: Boolean = true) = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)

    /**
     * A tab face 300 x 200 px for 6 x 4 cm: icon (1.4 cm) above the word (1.1 cm cap). A word wider than the face logs
     * OVERFLOW and is drawn smaller (never cut, design widthCheck).
     */
    fun tab(t: Turn, word: String, testF: Boolean = false): Bitmap {
        val w = 300; val h = 200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(TAB_FACE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TAB_INK }
        if (testF) {
            p.textSize = 150f; p.typeface = font(); p.textAlign = Paint.Align.CENTER
            c.drawText("F↑", w / 2f, h * 0.78f, p)
            return bmp
        }
        val (d, mirror) = symbolFor(t)
        val icon = symbol(d, 84f, mirror)
        c.save(); c.translate((w - 84f) / 2, 14f); c.drawPath(icon, p); c.restore()
        p.textSize = 54f; p.typeface = font(); p.textAlign = Paint.Align.CENTER
        val tw = p.measureText(word)
        val max = w - 24f
        if (tw > max) { Log.w(TAG, "OVERFLOW tab/$t \"$word\" ${tw.toInt()} px > ${max.toInt()}"); p.textSize *= max / tw }
        c.drawText(word, w / 2f, h - 26f, p)
        return bmp
    }

    /**
     * Hint ghost face (M16): a bright 12 px white edge and a faint centre (alpha 0.3), tinted by the block colour, so the
     * ghost reads as an outline of where the block goes, not as a block (QA K1).
     */
    fun ghostEdge(): Bitmap {
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.argb(77, 255, 255, 255))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 12f }
        c.drawRect(6f, 6f, n - 6f, n - 6f, p)
        return bmp
    }

    /** Red-brown for the 2.5 s reason and the S15 "tap here" (design standPlate). */
    val PLATE_ACCENT = Color.rgb(0x5E, 0x18, 0x0A)  // 5.44:1 on the brass face (ui-audit contrast table)

    /** Text width (px) of a plate line at its full size. */
    fun plateWidth(text: String, second: Boolean): Float = platePaint(second).measureText(text)

    private fun platePaint(second: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PLATE_INK; textAlign = Paint.Align.CENTER; typeface = font(true)
        // design standPlate: 1024 x 400 px for 0.18 x 0.07 m; line 1 86 px (1.5 cm em), line 2 76 px (1.3 cm em)
        textSize = if (second) 76f else 86f
    }

    /**
     * The brass name plate (design spatialLayout.standPlate, q1-1), 1024 x 400 px for 0.18 x 0.07 m, always two lines:
     * "鞋盒 · 3 / 6" over "1:12" (full: "鞋盒" over "1:42"); the second line turns red-brown for the 2.5 s reason and the
     * S15 "tap here". A line wider than 960 px logs OVERFLOW and shrinks (never cut).
     */
    fun plate(line1: String, line2: String, accent: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(Layout.PLATE_PX_W, Layout.PLATE_PX_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(0f, 0f, Layout.PLATE_PX_W.toFloat(), Layout.PLATE_PX_H.toFloat())
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE_EDGE }
        c.drawRect(r, bg)
        bg.color = PLATE_FACE
        c.drawRoundRect(RectF(14f, 14f, r.right - 14f, r.bottom - 14f), 28f, 28f, bg)
        fun line(text: String, second: Boolean, cy: Float) {
            val p = platePaint(second)
            if (second && accent) { p.color = PLATE_ACCENT; p.typeface = font(true) }
            val w = p.measureText(text)
            val max = Layout.PLATE_PX_W - 64f
            if (w > max) { Log.w(TAG, "OVERFLOW plate/${if (second) "line2" else "line1"} \"$text\" ${w.toInt()} px > ${max.toInt()}"); p.textSize *= max / w }
            val fm = p.fontMetrics
            c.drawText(text, r.centerX(), cy - (fm.ascent + fm.descent) / 2, p)
        }
        line(line1, false, r.height() * 0.32f)
        line(line2, true, r.height() * 0.70f)
        return bmp
    }

    /**
     * Mouth overlay for a [d.x] x [d.z] mouth, 128 px a cell (laid flat: image up = the back wall, -Z): faint grid lines
     * (bright while a block is selected) and on the user-facing edge of every cell 0-3 short white ticks = how many cells
     * that column still takes (design gameSpec.columnDots).
     */
    fun mouth(d: Dims, depths: (Int, Int) -> Int, lit: Boolean): Bitmap {
        val px = 128
        val bmp = Bitmap.createBitmap(d.x * px, d.z * px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(if (lit) 230 else 90, 255, 255, 255); strokeWidth = if (lit) 4f else 2.5f }
        for (x in 1 until d.x) c.drawLine(x * px.toFloat(), 0f, x * px.toFloat(), d.z * px.toFloat(), grid)
        for (z in 1 until d.z) c.drawLine(0f, z * px.toFloat(), d.x * px.toFloat(), z * px.toFloat(), grid)
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(245, 255, 255, 255); strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        for (x in 0 until d.x) for (z in 0 until d.z) {
            val n = depths(x, z).coerceIn(0, 3)
            // ticks along the near (bottom) edge of the cell image, centred, one above the other
            val cx = x * px + px / 2f
            val by = (z + 1) * px - 14f
            for (k in 0 until n) c.drawLine(cx - 22f, by - k * 14f, cx + 22f, by - k * 14f, tick)
        }
        return bmp
    }

    /** The faint outline left in the tray where a lifted block sits (design S03 "托盘原位淡轮廓"), 128 px a cell. */
    fun outline(type: PieceType): Bitmap {
        val cells = Orient.cells(type, type.flatRot)
        val s = Orient.size(cells)
        val px = 128
        val bmp = Bitmap.createBitmap(s.x * px, s.z * px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0x6B, 0x4A, 0x2E); style = Paint.Style.STROKE; strokeWidth = 8f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0x6B, 0x4A, 0x2E) }
        val set = cells.filter { it.y == 0 }.map { it.x to it.z }.toSet()
        for ((x, z) in set) {
            // image up = far (-Z) = z 0: row z of the image is cell row z
            val top = z * px.toFloat()
            c.drawRect(x * px + 6f, top + 6f, (x + 1) * px - 6f, top + px - 6f, fill)
            if ((x to z - 1) !in set) c.drawLine(x * px + 6f, top + 6f, (x + 1) * px - 6f, top + 6f, p)
            if ((x to z + 1) !in set) c.drawLine(x * px + 6f, top + px - 6f, (x + 1) * px - 6f, top + px - 6f, p)
            if ((x - 1 to z) !in set) c.drawLine(x * px + 6f, top + 6f, x * px + 6f, top + px - 6f, p)
            if ((x + 1 to z) !in set) c.drawLine((x + 1) * px - 6f, top + 6f, (x + 1) * px - 6f, top + px - 6f, p)
        }
        return bmp
    }

    /** A soft white disc (the anchor dot on the block's top face). */
    fun dot(): Bitmap {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(32f, 32f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawCircle(32f, 32f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 60, 40, 20); style = Paint.Style.STROKE; strokeWidth = 4f })
        return bmp
    }

    @Suppress("unused")
    fun cellLabel(c: Cell) = "$c"
}
