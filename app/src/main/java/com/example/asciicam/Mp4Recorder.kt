package com.example.asciicam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.math.min

/**
 * H.264 MP4 recorder. Frames are drawn straight onto the encoder's input surface, which avoids
 * device-specific YUV buffer formats. Needs Android 6+ for Surface.lockHardwareCanvas().
 */
@RequiresApi(23)
class Mp4Recorder private constructor(
    override val file: File,
    private val codec: MediaCodec,
    private val width: Int,
    private val height: Int,
    private val fg: Int,
    private val bg: Int,
) : Recorder {
    override val mimeType = "video/mp4"
    override val isVideo = true

    private val renderer = AsciiRenderer()
    private val surface = codec.createInputSurface()
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var firstPts = -1L
    private var samples = 0

    init {
        codec.start()
    }

    override fun offer(frame: AsciiFrame) {
        val canvas = surface.lockHardwareCanvas()
        try {
            renderer.draw(canvas, frame, width, height, fg, bg)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        drain(endOfStream = false)
    }

    override fun finish(): Boolean {
        try {
            codec.signalEndOfInputStream()
            drain(endOfStream = true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            surface.release()
            try { if (track >= 0) muxer.stop() } catch (_: Exception) { samples = 0 }
            muxer.release()
        }
        return samples > 0
    }

    private fun drain(endOfStream: Boolean) {
        var idleTries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleTries > 200) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && track >= 0) {
                        if (firstPts < 0) firstPts = info.presentationTimeUs
                        info.presentationTimeUs -= firstPts
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                        samples++
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * [aspect] is height / width of the frames. Tries [desiredWidth] first, then smaller sizes
         * until the device's encoder accepts one.
         */
        fun create(file: File, desiredWidth: Int, aspect: Float, fg: Int, bg: Int): Mp4Recorder {
            val codec = MediaCodec.createEncoderByType(MIME)
            try {
                val caps = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
                val widths = (listOf(desiredWidth) + listOf(1080, 720, 540, 360).filter { it < desiredWidth })
                for (w0 in widths) {
                    // Many encoders require dimensions that are multiples of 16.
                    val w = w0 / 16 * 16
                    val h = (w * aspect).toInt() / 16 * 16
                    if (h <= 0 || !caps.isSizeSupported(w, h)) continue
                    val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        // Sharp text needs a generous bitrate.
                        setInteger(MediaFormat.KEY_BIT_RATE, min(w * h * 6, caps.bitrateRange.upper))
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                    try {
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        return Mp4Recorder(file, codec, w, h, fg, bg)
                    } catch (e: Exception) {
                        codec.reset()
                    }
                }
            } catch (e: Exception) {
                codec.release()
                throw e
            }
            codec.release()
            throw IllegalStateException("Видеокодек не поддерживает подходящий размер")
        }
    }
}
