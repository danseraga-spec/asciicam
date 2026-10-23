package com.example.asciicam

import java.io.File

/** Records a stream of ASCII frames into [file]. */
interface Recorder {
    val file: File
    val mimeType: String
    val isVideo: Boolean

    /** Called on the camera analysis thread for every new frame. */
    fun offer(frame: AsciiFrame)

    /** Finalizes the file; blocking. Returns false if nothing usable was recorded. */
    fun finish(): Boolean
}
