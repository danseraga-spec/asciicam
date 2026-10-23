package com.example.asciicam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Round shutter: white for photos, red for recording; morphs into a stop square while recording. */
class ShutterButton(context: Context) : View(context) {

    var mode = Settings.MODE_PHOTO
        set(value) {
            field = value
            invalidate()
        }

    var recording = false
        set(value) {
            if (field == value) return
            field = value
            animator?.cancel()
            animator = ValueAnimator.ofFloat(morph, if (value) 1f else 0f).apply {
                duration = 200
                addUpdateListener {
                    morph = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    private var morph = 0f
    private var animator: ValueAnimator? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = context.dp(4).toFloat()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Ui.MONO_BOLD
        textAlign = Paint.Align.CENTER
        textSize = context.dp(13).toFloat()
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy)
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)

        val inner = r - context.dp(8)
        val half = inner - inner * 0.55f * morph
        val corner = half + (context.dp(6) - half) * morph
        fill.color = if (mode == Settings.MODE_PHOTO) Color.WHITE else Ui.RED
        rect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(rect, corner, corner, fill)

        if (mode == Settings.MODE_GIF && morph == 0f) {
            canvas.drawText("GIF", cx, cy - (label.ascent() + label.descent()) / 2, label)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.9f else 1f
        animate().scaleX(s).scaleY(s).setDuration(90).start()
    }
}
