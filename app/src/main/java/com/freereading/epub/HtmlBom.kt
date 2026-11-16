package com.freereading.epub

/** A leading UTF-8 signature is transport metadata; an interior U+FEFF belongs to the document. */
internal fun ByteArray.withoutLeadingUtf8Bom(): ByteArray =
    if (size >= 3 && this[0] == 0xef.toByte() && this[1] == 0xbb.toByte() && this[2] == 0xbf.toByte()) {
        copyOfRange(3, size)
    } else this

/** Jsoup's String parser does not consume the byte-order mark that a browser consumes when decoding. */
internal fun String.withoutLeadingHtmlBom(): String = removePrefix("\uFEFF")
