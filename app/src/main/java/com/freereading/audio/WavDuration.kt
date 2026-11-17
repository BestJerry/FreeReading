package com.freereading.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** 按 WAV 数据块读取真实音频时长，兼容包含可选块、头部不固定为 44 字节的文件。 */
internal object WavDuration {
    /** 根据 fmt 块字节率和 data 块长度计算毫秒数；检测到缺失格式、无效字节率、空数据或越界块时抛出 IOException。 */
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
