package com.freereading.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Reads WAV chunks rather than assuming the optional chunks always form a 44-byte header. */
internal object WavDuration {
    fun readMs(file: File): Long = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 12 || input.tag() != "RIFF") throw IOException("Invalid WAV")
        input.uint32()
        if (input.tag() != "WAVE") throw IOException("Invalid WAV")
        var byteRate = 0L
        var dataSize = -1L
        while (input.filePointer + 8 <= input.length()) {
            val tag = input.tag()
            val size = input.uint32()
            val start = input.filePointer
            if (size > input.length() - start) throw IOException("Incomplete WAV")
            if (tag == "fmt ") {
                if (size < 16) throw IOException("Invalid WAV format")
                input.skipBytes(8)
                byteRate = input.uint32()
            } else if (tag == "data") {
                dataSize = size
            }
            input.seek(start + size + (size and 1L))
        }
        if (byteRate <= 0 || dataSize <= 0) throw IOException("Empty WAV")
        ((dataSize * 1000) / byteRate).coerceAtLeast(1)
    }
    private fun RandomAccessFile.tag(): String {
        val bytes = ByteArray(4)
        readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
    private fun RandomAccessFile.uint32(): Long =
        Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
}
