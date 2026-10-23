package com.example.asciicam

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.widget.SeekBar
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import kotlin.math.roundToInt

/** Shared look of the app. */
internal object Ui {
    const val ACCENT = 0xFFFFD54F.toInt()
    const val RED = 0xFFF44336.toInt()
    const val SCRIM = 0x73000000
    const val CHIP = 0x26FFFFFF
    const val TEXT_DIM = 0xB3FFFFFF.toInt()
    const val SHEET = 0xF2161616.toInt()
    const val TELEGRAM_BLUE = 0xFF2AABEE.toInt()
    const val TELEGRAM_URL = "https://t.me/eptahalflaif2"
    val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

/** Accent-colored 0..100 slider; [onChange] fires only for user drags. */
internal fun Context.slider(value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
    max = 100
    progress = value
    progressTintList = ColorStateList.valueOf(Ui.ACCENT)
    thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
    progressBackgroundTintList = ColorStateList.valueOf(0x4DFFFFFF)
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    })
}

internal fun Context.openTelegramChannel() {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Ui.TELEGRAM_URL)))
    } catch (e: ActivityNotFoundException) {
        // No browser or Telegram app installed; nothing sensible to open.
    }
}

internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

internal fun roundRect(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

internal fun oval(color: Int, strokeColor: Int = 0, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/** Ripple on top of [content], clipped to [content]'s shape. */
internal fun ripple(content: Drawable): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, content)
