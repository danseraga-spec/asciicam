package com.example.asciicam

import android.text.Html
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Prepares a frame for pasting elsewhere. ASCII art only lines up in a monospace font, so most
 * presets wrap it in a code block and shrink it to a width that fits the target without wrapping.
 * Where no code block is available (Telegram captions, posts with media, articles) the Braille
 * presets help: all Braille glyphs, including the blank one, have the same width in any font.
 */
object CopyFormatter {

    class Preset(
        val label: String,
        /** Characters per line that fit without wrapping, 0 = keep the on-screen width. */
        val width: Int,
        /** Message length limit of the target, 0 = none. */
        val maxChars: Int,
        val prefix: String = "",
        val suffix: String = "",
        /** Also put an HTML <pre> version on the clipboard (apps that paste rich text use it). */
        val html: Boolean = false,
        /** Draw with Braille dots instead of the ASCII charset. */
        val braille: Boolean = false,
    )

    val PRESETS = listOf(
        // Telegram turns ``` into a monospace block in normal messages; phone bubbles fit ~30 chars.
        Preset("Telegram сообщение", 30, 4096, "```\n", "\n```", html = true),
        // Captions, posts with media and articles don't parse ```, so use Braille there.
        Preset("Telegram пост (Брайль)", 30, 4096, braille = true),
        Preset("Подпись к фото (Брайль)", 30, 1024, braille = true),
        Preset("Discord", 60, 2000, "```\n", "\n```"),
        Preset("Форум (BBCode)", 80, 0, "[code]\n", "\n[/code]"),
        Preset("Markdown", 60, 0, "```\n", "\n```"),
        Preset("Брайль", 0, 0, braille = true),
        Preset("Без форматирования", 0, 0),
    )

    /** User override of the preset's width, 0 = "auto". */
    val WIDTHS = intArrayOf(0, 20, 24, 30, 36, 48, 64, 80)
    val WIDTH_LABELS = WIDTHS.map { if (it == 0) "Авто" else "$it" }.toTypedArray()

    class Result(val text: String, val html: String?, val cols: Int, val rows: Int)

    private val cellAspect by lazy { AsciiRenderer().cellAspect }

    fun format(frame: AsciiFrame, preset: Preset, widthOverride: Int): Result {
        val wanted = if (widthOverride > 0) widthOverride else if (preset.width > 0) preset.width else frame.cols
        // Never upsample ASCII: it adds blockiness, not detail. Braille has 2 dots per character,
        // so half the columns already carry the full on-screen detail.
        var cols = min(wanted, if (preset.braille) max(1, frame.cols / 2) else frame.cols)
        var rows = rowsFor(frame, cols, preset.braille)
        if (preset.maxChars > 0) {
            val overhead = preset.prefix.length + preset.suffix.length
            while (cols > 8 && overhead + rows * (cols + 1) > preset.maxChars) {
                cols--
                rows = rowsFor(frame, cols, preset.braille)
            }
        }

        val lines = if (preset.braille) braille(frame, cols, rows) else resample(frame, cols, rows)
        val fenced = preset.prefix.startsWith("```")
        val body = lines.joinToString("\n") { line ->
            // Trailing spaces only waste the length limit; backticks would close a ``` block.
            val s = line.trimEnd()
            if (fenced) s.replace('`', '\'') else s
        }
        val html = if (preset.html) "<pre>${Html.escapeHtml(body)}</pre>" else null
        return Result(preset.prefix + body + preset.suffix, html, cols, rows)
    }

    private fun rowsFor(frame: AsciiFrame, cols: Int, braille: Boolean): Int {
        if (!braille) {
            // Character cells keep the same shape in any monospace font, so rows scale with columns.
            return max(1, (frame.rows * cols.toFloat() / frame.cols).roundToInt())
        }
        // A Braille glyph is roughly twice as tall as wide and holds 2x4 dots, so dots are ~square.
        val pictureAspect = frame.rows * cellAspect / frame.cols
        return max(1, (cols * 2 * pictureAspect / 4f).roundToInt())
    }

    private fun resample(frame: AsciiFrame, cols: Int, rows: Int): List<String> {
        if (cols == frame.cols && rows == frame.rows) return frame.lines.map { String(it) }
        val chars = frame.charset
        val last = chars.size - 1
        val sx = frame.cols.toFloat() / cols
        val sy = frame.rows.toFloat() / rows
        return List(rows) { r ->
            String(CharArray(cols) { c ->
                val avg = averageInk(frame, c * sx, (c + 1) * sx, r * sy, (r + 1) * sy)
                chars[(avg * last + 0.5f).toInt()]
            })
        }
    }

    private fun braille(frame: AsciiFrame, cols: Int, rows: Int): List<String> {
        val dotsW = cols * 2
        val dotsH = rows * 4
        val sx = frame.cols.toFloat() / dotsW
        val sy = frame.rows.toFloat() / dotsH
        return List(rows) { r ->
            String(CharArray(cols) { c ->
                var bits = 0
                for (dy in 0 until 4) {
                    for (dx in 0 until 2) {
                        val x = c * 2 + dx
                        val y = r * 4 + dy
                        val avg = averageInk(frame, x * sx, (x + 1) * sx, y * sy, (y + 1) * sy)
                        // Ordered dithering turns gray levels into dot density.
                        if (avg > (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f) bits = bits or DOT_BITS[dy * 2 + dx]
                    }
                }
                (0x2800 + bits).toChar()
            })
        }
    }

    /** Mean ink (0..1) of the frame cells covered by the given range, in cell units. */
    private fun averageInk(frame: AsciiFrame, x0f: Float, x1f: Float, y0f: Float, y1f: Float): Float {
        val x0 = x0f.toInt().coerceIn(0, frame.cols - 1)
        val y0 = y0f.toInt().coerceIn(0, frame.rows - 1)
        val x1 = max(x0 + 1, x1f.toInt()).coerceAtMost(frame.cols)
        val y1 = max(y0 + 1, y1f.toInt()).coerceAtMost(frame.rows)
        var sum = 0
        for (y in y0 until y1) for (x in x0 until x1) sum += frame.ink[y * frame.cols + x].toInt() and 0xFF
        return sum.toFloat() / ((y1 - y0) * (x1 - x0)) / 255f
    }

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    // Unicode Braille bit for the dot at (column, row) inside a 2x4 cell, indexed row * 2 + column.
    private val DOT_BITS = intArrayOf(0x01, 0x08, 0x02, 0x10, 0x04, 0x20, 0x40, 0x80)
}
