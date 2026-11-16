package com.freereading.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HtmlBomTest {
    @Test fun onlyACompleteUtf8BomAtByteZeroIsRemoved() {
        val marker = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        val body = "<p>甲\uFEFF乙。</p>".toByteArray(Charsets.UTF_8)
        assertArrayEquals(body, (marker + body).withoutLeadingUtf8Bom())
        assertArrayEquals(marker + body, (marker + marker + body).withoutLeadingUtf8Bom())
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xef.toByte()),
            byteArrayOf(0xef.toByte(), 0xbb.toByte()), body, byteArrayOf(0) + marker + body)) {
            assertSame(bytes, bytes.withoutLeadingUtf8Bom())
        }
    }

    @Test fun stringNormalizationPreservesInteriorAndSecondFeffCharacters() {
        assertEquals("<p>甲\uFEFF乙。</p>", "\uFEFF<p>甲\uFEFF乙。</p>".withoutLeadingHtmlBom())
        assertEquals("\uFEFF正文", "\uFEFF\uFEFF正文".withoutLeadingHtmlBom())
        assertEquals("前文\uFEFF后文", "前文\uFEFF后文".withoutLeadingHtmlBom())
        assertEquals("", "".withoutLeadingHtmlBom())
    }
}
