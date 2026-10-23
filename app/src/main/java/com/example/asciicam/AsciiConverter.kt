package com.example.asciicam

import androidx.camera.core.ImageProxy
import kotlin.math.max

/** Turns the luminance plane of a camera frame into an [AsciiFrame]. Used from one thread only. */
class AsciiConverter {
    @Volatile var charset: CharArray = Settings.CHARSETS[0].toCharArray()
    @Volatile var autoContrast = true
    @Volatile var invert = false
    @Volatile var mirror = false
    /** 0..1. Lower values need more light before a cell gets any character. */
    @Volatile var sensitivity = 1f

    // Smoothed brightness range for auto contrast, so it doesn't flicker.
    private var lo = 0f
    private var hi = 255f
    private var lum = IntArray(0)

    /** Where the last frame came from, to map screen taps back to the sensor. */
    private class Geometry(
        val sw: Int, val sh: Int, val rotation: Int, val rw: Int,
        val offX: Float, val offY: Float, val cropW: Float, val cropH: Float, val mirror: Boolean,
    )
    @Volatile private var geometry: Geometry? = null

    /**
     * Maps a point of the picture (0..1 in both axes) to normalized coordinates of the camera
     * buffer, as used by CameraX metering. Null until the first frame arrives.
     */
    fun pictureToSensor(u: Float, v: Float): Pair<Float, Float>? {
        val g = geometry ?: return null
        var x = g.offX + u * g.cropW
        val y = g.offY + v * g.cropH
        if (g.mirror) x = g.rw - x
        val (sx, sy) = when (g.rotation) {
            90 -> y to g.sh - x
            180 -> g.sw - x to g.sh - y
            270 -> g.sw - y to x
            else -> x to y
        }
        return sx / g.sw to sy / g.sh
    }

    /** [viewAspect] is width / height of the picture on screen; the camera image is center-cropped to it. */
    fun convert(image: ImageProxy, cols: Int, rows: Int, viewAspect: Float): AsciiFrame {
        // The Y plane of YUV_420_888 is a grayscale image; every device provides it.
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val sw = image.width
        val sh = image.height
        val rotation = image.imageInfo.rotationDegrees

        // Size of the image once rotated upright.
        val rw = if (rotation % 180 == 0) sw else sh
        val rh = if (rotation % 180 == 0) sh else sw

        // Buffer index of upright pixel (x, y) = base + x * ax + y * ay.
        val base: Int
        val ax: Int
        val ay: Int
        when (rotation) {
            90 -> { base = (sh - 1) * rs; ax = -rs; ay = ps }
            180 -> { base = (sh - 1) * rs + (sw - 1) * ps; ax = -ps; ay = -rs }
            270 -> { base = (sw - 1) * ps; ax = rs; ay = -ps }
            else -> { base = 0; ax = ps; ay = rs }
        }

        // Center-crop the upright image to the screen's aspect ratio.
        var cropW = rw.toFloat()
        var cropH = rh.toFloat()
        if (rw.toFloat() / rh > viewAspect) cropW = rh * viewAspect else cropH = rw / viewAspect
        val offX = (rw - cropW) / 2f
        val offY = (rh - cropH) / 2f
        val cellW = cropW / cols
        val cellH = cropH / rows
        val mirror = mirror
        geometry = Geometry(sw, sh, rotation, rw, offX, offY, cropW, cropH, mirror)

        // Average a 3x3 grid of samples per cell: smoother than a single point.
        if (lum.size < cols * rows) lum = IntArray(cols * rows)
        var mn = 255
        var mx = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var sum = 0
                for (j in 0..2) {
                    val y = (offY + (r + SUB[j]) * cellH).toInt().coerceIn(0, rh - 1)
                    for (i in 0..2) {
                        var x = (offX + (c + SUB[i]) * cellW).toInt().coerceIn(0, rw - 1)
                        if (mirror) x = rw - 1 - x
                        sum += buf.get(base + x * ax + y * ay).toInt() and 0xFF
                    }
                }
                val v = sum / 9
                lum[r * cols + c] = v
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        }

        val low: Float
        val span: Float
        if (autoContrast) {
            lo += (mn - lo) * 0.25f
            hi += (mx - hi) * 0.25f
            low = lo
            span = max(hi - lo, 32f)
        } else {
            low = 0f
            span = 255f
        }

        val chars = charset
        val last = chars.size - 1
        val invert = invert
        // Cells with less "ink" than the threshold stay blank; the rest use the full charset.
        val threshold = (1f - sensitivity) * MAX_THRESHOLD
        val ink = ByteArray(cols * rows)
        val lines = Array(rows) { r ->
            CharArray(cols) { c ->
                var t = ((lum[r * cols + c] - low) / span).coerceIn(0f, 1f)
                if (invert) t = 1f - t
                t = if (t <= threshold) 0f else (t - threshold) / (1f - threshold)
                ink[r * cols + c] = (t * 255f + 0.5f).toInt().toByte()
                chars[(t * last + 0.5f).toInt()]
            }
        }
        return AsciiFrame(cols, rows, lines, ink, chars)
    }

    private companion object {
        val SUB = floatArrayOf(1 / 6f, 3 / 6f, 5 / 6f)
        const val MAX_THRESHOLD = 0.85f
    }
}
