package com.example.asciicam

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Bottom sheet with all settings as tappable chips. Changes apply immediately so the effect is
 * visible on the camera picture behind the sheet.
 */
class SettingsSheet(
    private val activity: Activity,
    private val settings: Settings,
    /** Called after every change; true when the camera has to be rebound. */
    private val onChange: (cameraChanged: Boolean) -> Unit,
) {
    private val dp = { v: Int -> activity.dp(v) }

    fun show() {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(16))
            val r = dp(24).toFloat()
            background = GradientDrawable().apply {
                setColor(Ui.SHEET)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        content.addView(View(activity).apply {
            background = roundRect(0x55FFFFFF, dp(2).toFloat())
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(TextView(activity).apply {
            text = "НАСТРОЙКИ"
            typeface = Ui.MONO_BOLD
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, dp(4))
        })

        addRow(content, "Формат кадра", Settings.ASPECT_LABELS, settings.aspect) {
            settings.aspect = it; onChange(false)
        }
        addRow(content, "Символов в строке", Settings.DENSITIES.map { "$it" }.toTypedArray(), settings.density) {
            settings.density = it; onChange(false)
        }
        addRow(content, "Набор символов", Settings.CHARSET_LABELS, settings.charset) {
            settings.charset = it; onChange(false)
        }
        addSensitivity(content)
        addColorRow(content)
        addRow(content, "Разрешение камеры", Settings.CAMERA_RES_LABELS, settings.cameraRes) {
            settings.cameraRes = it; onChange(true)
        }
        addRow(content, "Ширина фото и видео, px", Settings.OUTPUT_WIDTHS.map { "$it" }.toTypedArray(), settings.outputSize) {
            settings.outputSize = it; onChange(false)
        }
        addRow(content, "Автоконтраст", arrayOf("Вкл", "Выкл"), if (settings.autoContrast) 0 else 1) {
            settings.autoContrast = it == 0; onChange(false)
        }
        addRow(content, "Копирование текста", CopyFormatter.PRESETS.map { it.label }.toTypedArray(), settings.copyPreset) {
            settings.copyPreset = it; onChange(false)
        }
        addRow(content, "Символов в строке при копировании", CopyFormatter.WIDTH_LABELS, settings.copyWidth) {
            settings.copyWidth = it; onChange(false)
        }

        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ripple(roundRect(Ui.TELEGRAM_BLUE, dp(14).toFloat()))
            addView(ImageView(activity).apply { setImageResource(R.drawable.ic_telegram) },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
            addView(TextView(activity).apply {
                text = "Telegram-канал автора"
                typeface = Ui.MONO_BOLD
                textSize = 14f
                setTextColor(Color.WHITE)
            })
            setOnClickListener { activity.openTelegramChannel() }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(28) })

        content.addView(TextView(activity).apply {
            text = "Готово"
            typeface = Ui.MONO_BOLD
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = ripple(roundRect(Ui.ACCENT, dp(14).toFloat()))
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(10) })

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        // Keep the sheet's content above the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            content.setPadding(dp(20) + nav.left, dp(10), dp(20) + nav.right, dp(16) + nav.bottom)
            insets
        }
        dialog.setContentView(scroll)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.25f)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.show()
    }

    private fun addSensitivity(parent: LinearLayout) {
        val title = addTitle(parent, "")
        fun label(v: Int) { title.text = "Чувствительность: $v%  (меньше — символы только на светлом)" }
        label(settings.sensitivity)
        parent.addView(activity.slider(settings.sensitivity) {
            settings.sensitivity = it
            label(it)
            onChange(false)
        }, LinearLayout.LayoutParams(MATCH, dp(32)))
    }

    private fun addTitle(parent: LinearLayout, title: String): TextView =
        TextView(activity).also { parent.addView(it) }.apply {
            text = title
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, dp(18), 0, dp(8))
        }

    private fun chipRow(parent: LinearLayout): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        return row
    }

    private fun addRow(parent: LinearLayout, title: String, labels: Array<String>, selected: Int, onSelect: (Int) -> Unit) {
        addTitle(parent, title)
        val row = chipRow(parent)
        val chips = labels.map { label ->
            TextView(activity).apply {
                text = label
                typeface = Ui.MONO_BOLD
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = dp(52)
                setPadding(dp(14), 0, dp(14), 0)
            }
        }
        fun refresh(sel: Int) = chips.forEachIndexed { i, chip ->
            val on = i == sel
            chip.setTextColor(if (on) Color.BLACK else Color.WHITE)
            chip.background = ripple(roundRect(if (on) Ui.ACCENT else Ui.CHIP, dp(18).toFloat()))
        }
        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener { refresh(i); onSelect(i) }
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginEnd = dp(8)
            })
        }
        refresh(selected)
    }

    /** Color schemes are shown as "Aa" samples in their own colors. */
    private fun addColorRow(parent: LinearLayout) {
        addTitle(parent, "Цвета")
        val row = chipRow(parent)
        val chips = Settings.COLOR_FG.indices.map { i ->
            TextView(activity).apply {
                text = "Aa"
                typeface = Ui.MONO_BOLD
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Settings.COLOR_FG[i])
            }
        }
        fun refresh(sel: Int) = chips.forEachIndexed { i, chip ->
            val stroke = if (i == sel) Ui.ACCENT else 0x40FFFFFF
            chip.background = ripple(roundRect(Settings.COLOR_BG[i], dp(18).toFloat(), stroke, dp(if (i == sel) 3 else 1)))
        }
        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener {
                refresh(i)
                settings.colorScheme = i
                onChange(false)
            }
            row.addView(chip, LinearLayout.LayoutParams(dp(56), dp(40)).apply { marginEnd = dp(8) })
        }
        refresh(settings.colorScheme)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
