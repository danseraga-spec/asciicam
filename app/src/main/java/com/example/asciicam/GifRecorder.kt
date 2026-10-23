package com.example.asciicam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records frames into an animated GIF at ~10 fps. Frames are rendered in grayscale and mapped to a
 * 16-step palette between [bg] and [fg]; LZW encoding runs on its own thread.
 */
class GifRecorder(
    override val file: File,
    private val width: Int,
    private val height: Int,
    fg: Int,
    bg: Int,
) : Recorder {
    override val mimeType = "image/gif"
    override val isVideo = false

    private val renderer = AsciiRenderer()
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val pixels = IntArray(width * height)
    private val encoderThread = Executors.newSingleThreadExecutor()
    private val queued = AtomicInteger(0)
    private val encoder = GifEncoder(
        BufferedOutputStream(FileOutputStream(file)), width, height,
        IntArray(16) { blend(bg, fg, it / 15f) },
    )
    private var lastOfferTime = 0L

    // Touched only on encoderThread. A frame is written once the next one arrives, to know its delay.
    private var held: ByteArray? = null
    private var heldTime = 0L
    private var written = 0

    override fun offer(frame: AsciiFrame) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOfferTime < FRAME_INTERVAL_MS || queued.get() > 2) return
        lastOfferTime = now

        renderer.draw(canvas, frame, width, height, Color.WHITE, Color.BLACK)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val indices = ByteArray(pixels.size) { ((pixels[it] shr 16 and 0xFF) shr 4).toByte() }

        queued.incrementAndGet()
        encoderThread.execute {
            writeHeld(now)
            held = indices
            heldTime = now
            queued.decrementAndGet()
        }
    }

    override fun finish(): Boolean {
        encoderThread.submit(Runnable {
            writeHeld(heldTime + FRAME_INTERVAL_MS)
            encoder.finish()
        }).get()
        encoderThread.shutdown()
        bitmap.recycle()
        return written > 0
    }

    private fun writeHeld(now: Long) {
        val frame = held ?: return
        encoder.addFrame(frame, ((now - heldTime) / 10).toInt().coerceIn(2, 100))
        held = null
        written++
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 100L

        fun blend(from: Int, to: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val a = from shr shift and 0xFF
                val b = to shr shift and 0xFF
                return (a + (b - a) * t + 0.5f).toInt() shl shift
            }
            return Color.BLACK or ch(16) or ch(8) or ch(0)
        }
    }
}
