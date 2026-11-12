package com.freereading.audio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class WavDurationTest {
    @Test fun readsDurationWithExtraOddSizedChunk() {
        val file = wavWithData(32_000, 16_000, includeJunk = true)
        try { assertEquals(2_000L, WavDuration.readMs(file)) }
        finally { file.delete() }
    }
    @Test(expected = IOException::class) fun rejectsTruncatedAudioRatherThanInventingDuration() {
        val file = wavWithData(32_000, 16_000, truncate = true)
        try { WavDuration.readMs(file) } finally { file.delete() }
    }

    private fun wavWithData(bytes: Int, byteRate: Int, includeJunk: Boolean = false,
        truncate: Boolean = false): File {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + bytes))
            output.writeBytes("WAVE")
            if (includeJunk) {
                output.writeBytes("JUNK")
                output.writeInt(Integer.reverseBytes(3))
                output.write(byteArrayOf(1, 2, 3, 0))
            }
            output.writeBytes("fmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            output.writeInt(Integer.reverseBytes(byteRate / 2))
            output.writeInt(Integer.reverseBytes(byteRate))
            output.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(bytes))
            output.write(ByteArray(if (truncate) bytes / 2 else bytes))
        }
        return File.createTempFile("freereading", ".wav").apply { writeBytes(buffer.toByteArray()) }
    }
}
