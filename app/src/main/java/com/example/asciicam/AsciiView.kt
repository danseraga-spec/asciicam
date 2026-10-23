package com.example.asciicam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import kotlin.math.roundToInt

/**
 * Shows the latest [AsciiFrame] in a centered content area of the chosen aspect ratio
 * (the whole view when [aspect] is 0).
 */
class AsciiView(context: Context) : View(context) {

    private val renderer = AsciiRenderer()
    private var fg = Color.WHITE
    private var bg = Color.BLACK

    /** Grid size the camera frames should be converted to. Read from the analysis thread. */
    @Volatile var columns = 90
        set(value) {
            field = value
            updateGrid()
        }
    @Volatile var rows = 0
        private set

    /** Width / height of the content area, 0 = fill the view. */
    var aspect = 0f
        set(value) {
            field = value
            updateGrid()
            invalidate()
        }

    // Content area inside the view, in pixels.
    @Volatile var contentLeft = 0
        private set
    @Volatile var contentTop = 0
        private set
    @Volatile var contentWidth = 0
        private set
    @Volatile var contentHeight = 0
        private set

    @Volatile var frame: AsciiFrame? = null
        private set

    fun setColors(foreground: Int, background: Int) {
        fg = foreground
        bg = background
        invalidate()
    }

    fun setFrame(f: AsciiFrame) {
        frame = f
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateGrid()

    private fun updateGrid() {
        if (width == 0 || height == 0) return
        var cw = width
        var ch = height
        if (aspect > 0f) {
            ch = (width / aspect).roundToInt()
            if (ch > height) {
                ch = height
                cw = (height * aspect).roundToInt()
            }
        }
        contentLeft = (width - cw) / 2
        // Cropped formats sit above center (half way between the top and the centered position),
        // so the bottom controls cover less of the picture.
        contentTop = ((height - ch) * VERTICAL_BIAS).toInt()
        contentWidth = cw
        contentHeight = ch
        rows = renderer.rowsFor(columns, cw, ch)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val f = frame
        canvas.save()
        canvas.translate(contentLeft.toFloat(), contentTop.toFloat())
        canvas.clipRect(0, 0, contentWidth, contentHeight)
        if (f == null) canvas.drawColor(bg)
        else renderer.draw(canvas, f, contentWidth, contentHeight, fg, bg)
        canvas.restore()
    }

    private companion object {
        const val VERTICAL_BIAS = 0.25f
    }
}
