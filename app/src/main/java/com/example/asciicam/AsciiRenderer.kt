package com.example.asciicam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max

/**
 * One converted camera frame: [rows] lines of [cols] characters. [ink] keeps the per-cell density
 * (0..255) that picked each character from [charset], so the frame can be resampled later.
 */
class AsciiFrame(
    val cols: Int,
    val rows: Int,
    val lines: Array<CharArray>,
    val ink: ByteArray,
    val charset: CharArray,
) {
    fun toText(): String = lines.joinToString("\n") { String(it) }
}

/**
 * Draws an [AsciiFrame] with a monospace font so that the grid spans the full width.
 * Not thread-safe: give each thread its own instance.
 */
class AsciiRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = REF_SIZE
    }
    private val glyphWidthAtRef = paint.measureText("M")

    /** Height of a character cell divided by its width. */
    val cellAspect: Float = paint.fontMetrics.let { it.descent - it.ascent } / glyphWidthAtRef

    fun rowsFor(cols: Int, width: Int, height: Int): Int =
        max(1, (height / (width.toFloat() / cols * cellAspect)).toInt())

    fun heightFor(frame: AsciiFrame, width: Int): Int =
        ceil(frame.rows * width.toFloat() / frame.cols * cellAspect).toInt()

    /** Draws the grid so it fills exactly [width] x [height]. */
    fun draw(canvas: Canvas, frame: AsciiFrame, width: Int, height: Int, fg: Int, bg: Int) {
        canvas.drawColor(bg)
        paint.color = fg
        val cellW = width.toFloat() / frame.cols
        val cellH = height.toFloat() / frame.rows
        paint.textScaleX = 1f
        paint.textSize = REF_SIZE * cellW / glyphWidthAtRef
        // Glyph advances are rounded at small text sizes, so a line can come out shorter or longer
        // than the frame. Measure the real width and stretch horizontally to compensate.
        val lineWidth = paint.measureText(frame.lines[0], 0, frame.cols)
        if (lineWidth > 0f) paint.textScaleX = width / lineWidth
        val fm = paint.fontMetrics
        val baseline = (cellH - (fm.descent - fm.ascent)) / 2f - fm.ascent
        for (r in 0 until frame.rows) {
            canvas.drawText(frame.lines[r], 0, frame.cols, 0f, r * cellH + baseline, paint)
        }
    }

    fun renderBitmap(frame: AsciiFrame, width: Int, fg: Int, bg: Int): Bitmap {
        val height = heightFor(frame, width)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), frame, width, height, fg, bg)
        return bitmap
    }

    private companion object {
        const val REF_SIZE = 100f
    }
}
