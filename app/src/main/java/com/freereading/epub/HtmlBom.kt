package com.freereading.epub

/** 只移除字节流开头的 UTF-8 BOM；正文内部的 U+FEFF 属于原始内容，必须保留。 */
internal fun ByteArray.withoutLeadingUtf8Bom(): ByteArray =
    if (size >= 3 && this[0] == 0xef.toByte() && this[1] == 0xbb.toByte() && this[2] == 0xbf.toByte()) {
        copyOfRange(3, size)
    } else this

/** 对齐浏览器的解码行为；Jsoup 的字符串解析不会自动消耗开头的 BOM。 */
internal fun String.withoutLeadingHtmlBom(): String = removePrefix("\uFEFF")
