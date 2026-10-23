package com.example.asciicam

import java.io.OutputStream

/**
 * Minimal animated GIF89a writer with a single global palette.
 * [palette] size must be a power of two between 4 and 256; frames are palette indices.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    palette: IntArray,
) {
    private val paletteBits = Integer.numberOfTrailingZeros(palette.size)
    private val minCodeSize = maxOf(2, paletteBits)

    // LZW state.
    private val table = IntArray(4096 shl minCodeSize)
    private val block = ByteArray(255)
    private var blockLen = 0
    private var bitBuf = 0
    private var bitCount = 0

    init {
        require(palette.size in 4..256 && palette.size == 1 shl paletteBits)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        out.write(0x80 or 0x70 or (paletteBits - 1)) // global color table, 8-bit color resolution
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio
        for (c in palette) {
            out.write(c shr 16 and 0xFF)
            out.write(c shr 8 and 0xFF)
            out.write(c and 0xFF)
        }
        // NETSCAPE2.0 extension: loop forever.
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1); writeShort(0); out.write(0)
    }

    fun addFrame(indices: ByteArray, delayCentis: Int) {
        // Graphic control extension: frame delay, no transparency.
        out.write(0x21); out.write(0xF9); out.write(4); out.write(0)
        writeShort(delayCentis)
        out.write(0); out.write(0)
        // Image descriptor covering the whole canvas, no local palette.
        out.write(0x2C)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
        out.write(0)
        writeLzw(indices)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
        out.close()
    }

    private fun writeLzw(pixels: ByteArray) {
        out.write(minCodeSize)
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1
        table.fill(-1)

        emit(clearCode, codeSize)
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl minCodeSize) or k
            val code = table[key]
            if (code >= 0) {
                prefix = code
                continue
            }
            emit(prefix, codeSize)
            if (nextCode == 4096) {
                emit(clearCode, codeSize)
                codeSize = minCodeSize + 1
                nextCode = eoiCode + 1
                table.fill(-1)
            } else {
                if (nextCode >= 1 shl codeSize) codeSize++
                table[key] = nextCode++
            }
            prefix = k
        }
        emit(prefix, codeSize)
        emit(eoiCode, codeSize)

        if (bitCount > 0) putByte(bitBuf and 0xFF)
        bitBuf = 0
        bitCount = 0
        flushBlock()
        out.write(0) // block terminator
    }

    private fun emit(code: Int, size: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += size
        while (bitCount >= 8) {
            putByte(bitBuf and 0xFF)
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    private fun putByte(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLen == 0) return
        out.write(blockLen)
        out.write(block, 0, blockLen)
        blockLen = 0
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write(v shr 8 and 0xFF)
    }
}
