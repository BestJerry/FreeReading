package com.freereading.data

import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Original, deterministic text fixtures. Stream XHTML into ZIP entries, never build a whole novel in memory. */
internal object OriginalEpubFixture {
    fun repeatedParagraph(target: File, repetitions: Int = 4_000): File {
        require(repetitions > 0)
        return writeBook(target, "重复句定位容量回归", 1) { writer, _ ->
            writer.write("<p>")
            repeat(repetitions) { writer.write("甲。") }
            writer.write("</p>")
        }
    }

    fun numberedChapters(
        target: File,
        totalSentences: Int = 100_001,
        sentencesPerChapter: Int = 1_000,
        sentencesPerParagraph: Int = 20,
    ): File {
        require(totalSentences > 0 && sentencesPerChapter > 0 && sentencesPerParagraph > 0)
        val chapters = ((totalSentences - 1) / sentencesPerChapter) + 1
        return writeBook(target, "溪谷长篇容量回归", chapters) { writer, chapter ->
            val first = chapter * sentencesPerChapter + 1
            val last = minOf(totalSentences, first + sentencesPerChapter - 1)
            var number = first
            while (number <= last) {
                writer.write("<p>")
                val paragraphEnd = minOf(last, number + sentencesPerParagraph - 1)
                while (number <= paragraphEnd) {
                    writer.write("第${number}句，溪水静静流过山谷。")
                    number += 1
                }
                writer.write("</p>")
            }
        }
    }

    /** Cross the former 8M-character cap with 16,001 sentences and paragraphs of only 5,000 characters. */
    fun longBody(target: File): File {
        val sentence = "溪水".repeat(249) + "山。"
        val totalSentences = 16_001
        val sentencesPerChapter = 200
        val sentencesPerParagraph = 10
        val chapters = ((totalSentences - 1) / sentencesPerChapter) + 1
        return writeBook(target, "长正文容量回归", chapters) { writer, chapter ->
            val first = chapter * sentencesPerChapter
            val end = minOf(totalSentences, first + sentencesPerChapter)
            var number = first
            while (number < end) {
                writer.write("<p>")
                val paragraphEnd = minOf(end, number + sentencesPerParagraph)
                while (number < paragraphEnd) {
                    writer.write(sentence)
                    number += 1
                }
                writer.write("</p>")
            }
        }
    }

    private fun writeBook(
        target: File,
        title: String,
        chapters: Int,
        writeBody: (Writer, Int) -> Unit,
    ): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
                time = 0
            })
            zip.write(mimetype)
            zip.closeEntry()
            zip.textEntry("META-INF/container.xml") {
                write("""<?xml version="1.0" encoding="UTF-8"?>
                    |<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                    |<rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    |</container>""".trimMargin())
            }
            zip.textEntry("EPUB/package.opf") {
                write("""<?xml version="1.0" encoding="UTF-8"?>
                    |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                    |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    |<dc:identifier id="book-id">urn:freereading:test:${target.nameWithoutExtension}</dc:identifier>
                    |<dc:title>$title</dc:title><dc:creator>FreeReading 原创测试</dc:creator><dc:language>zh</dc:language>
                    |<meta property="dcterms:modified">2026-11-16T00:00:00Z</meta></metadata>
                    |<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""".trimMargin())
                repeat(chapters) { chapter ->
                    write("<item id=\"chapter-$chapter\" href=\"chapter-$chapter.xhtml\" media-type=\"application/xhtml+xml\"/>")
                }
                write("</manifest><spine>")
                repeat(chapters) { chapter -> write("<itemref idref=\"chapter-$chapter\"/>") }
                write("</spine></package>")
            }
            zip.textEntry("EPUB/nav.xhtml") {
                write("""<?xml version="1.0" encoding="UTF-8"?>
                    |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh">
                    |<head><title>目录</title></head><body><nav epub:type="toc" id="toc"><ol>""".trimMargin())
                repeat(chapters) { chapter ->
                    write("<li><a href=\"chapter-$chapter.xhtml\">第${chapter + 1}章</a></li>")
                }
                write("</ol></nav></body></html>")
            }
            repeat(chapters) { chapter ->
                zip.textEntry("EPUB/chapter-$chapter.xhtml") {
                    write("""<?xml version="1.0" encoding="UTF-8"?>
                        |<html xmlns="http://www.w3.org/1999/xhtml" lang="zh"><head><title>第${chapter + 1}章</title></head><body>""".trimMargin())
                    writeBody(this, chapter)
                    write("</body></html>")
                }
            }
        }
        return target
    }

    private fun ZipOutputStream.textEntry(name: String, write: Writer.() -> Unit) {
        putNextEntry(ZipEntry(name).apply { time = 0 })
        // Closing this writer would close the shared ZIP stream; the outer use owns it.
        OutputStreamWriter(this, Charsets.UTF_8).apply { write(); flush() }
        closeEntry()
    }
}
