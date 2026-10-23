package com.example.asciicam

import android.content.Context
import android.util.Size
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** User preferences, persisted in SharedPreferences. Stored values are indices into the arrays below. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var cameraRes by intPref("cameraRes", 1)
    var density by intPref("density", 2)
    var charset by intPref("charset", 0)
    var colorScheme by intPref("colorScheme", 0)
    var mode by intPref("mode", MODE_PHOTO)
    var outputSize by intPref("outputSize", 1)
    var autoContrast by boolPref("autoContrast", true)
    var aspect by intPref("aspect", 0)
    /** 0..100, see [AsciiConverter.sensitivity]. */
    var sensitivity by intPref("sensitivity", 100)
    var copyPreset by intPref("copyPreset", 0)
    var copyWidth by intPref("copyWidth", 0)

    val copyPresetValue: CopyFormatter.Preset
        get() = CopyFormatter.PRESETS[copyPreset.clamp(CopyFormatter.PRESETS.size)]
    /** Columns forced by the user for copying, 0 = use the preset's width. */
    val copyWidthValue: Int get() = CopyFormatter.WIDTHS[copyWidth.clamp(CopyFormatter.WIDTHS.size)]

    /** Width / height of the picture in portrait, or 0 to fill the whole screen. */
    val aspectRatio: Float get() = ASPECT_RATIOS[aspect.clamp(ASPECT_RATIOS.size)]

    val cameraSize: Size get() = CAMERA_RES_SIZES[cameraRes.clamp(CAMERA_RES_SIZES.size)]
    val columns: Int get() = DENSITIES[density.clamp(DENSITIES.size)]
    val charsetChars: CharArray get() = CHARSETS[charset.clamp(CHARSETS.size)].toCharArray()
    val foreground: Int get() = COLOR_FG[colorScheme.clamp(COLOR_FG.size)]
    val background: Int get() = COLOR_BG[colorScheme.clamp(COLOR_BG.size)]
    /** Dark text on a light background: dense glyphs must represent dark pixels. */
    val inverted: Boolean get() = colorScheme.clamp(COLOR_FG.size) == COLOR_FG.size - 1
    val outputWidth: Int get() = OUTPUT_WIDTHS[outputSize.clamp(OUTPUT_WIDTHS.size)]

    private fun Int.clamp(size: Int) = coerceIn(0, size - 1)

    private fun intPref(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            prefs.edit().putInt(key, value).apply()
    }

    private fun boolPref(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        const val MODE_PHOTO = 0
        const val MODE_VIDEO = 1
        const val MODE_GIF = 2
        val MODE_LABELS = arrayOf("ФОТО", "ВИДЕО", "GIF")

        val ASPECT_LABELS = arrayOf("Полное", "16:9", "4:3", "1:1")
        val ASPECT_RATIOS = floatArrayOf(0f, 9f / 16f, 3f / 4f, 1f)

        val CAMERA_RES_LABELS = arrayOf("240p", "480p", "720p", "1080p")
        val CAMERA_RES_SIZES = arrayOf(Size(320, 240), Size(640, 480), Size(1280, 720), Size(1920, 1080))

        val DENSITIES = intArrayOf(40, 60, 90, 120, 160)

        val CHARSET_LABELS = arrayOf("Стандарт", "Подробный", "Минимал", "Двоичный")
        // Ordered from dark (sparse) to bright (dense).
        val CHARSETS = arrayOf(
            " .:-=+*#%@",
            " .'`^\",:;Il!i><~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$",
            " .oO@",
            " .01",
        )

        // The last scheme is "dark on light" (see [inverted]).
        val COLOR_FG = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF33FF66.toInt(), 0xFFFFB000.toInt(), 0xFF4FC3F7.toInt(), 0xFF000000.toInt()
        )
        val COLOR_BG = intArrayOf(
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()
        )

        val OUTPUT_WIDTHS = intArrayOf(720, 1080, 1440)
    }
}
