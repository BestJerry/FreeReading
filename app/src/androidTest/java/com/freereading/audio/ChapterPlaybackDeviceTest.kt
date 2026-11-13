package com.freereading.audio

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.freereading.MainActivity
import com.freereading.ReadingApplication
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an unlocked device with an installed offline Chinese voice and no active listening session. */
@RunWith(AndroidJUnit4::class)
class ChapterPlaybackDeviceTest {
    @Test fun quickStartKeepsChapterProgressAcrossSentenceTransitionPauseSeekAndChapterChange() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = (context.applicationContext as ReadingApplication).repository
        // A paused real book is still a listening session. Never replace it merely to run a test.
        assumeTrue("End the current listening session before this test", PlaybackHub.state.value.bookId == null)
        val uuid = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "chapter-playback-$uuid.epub")
        var ownedBookId: String? = null
        try {
            withTimeout(120_000) {
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                instrumentation.waitForIdleSync()
                val expectedHash = withContext(Dispatchers.IO) {
                    writeBook(source, uuid)
                    MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                }
                assertFalse(repository.books.value.any { it.id == expectedHash })
                // Also owns a commit whose result is discarded by timeout during the IO handoff.
                ownedBookId = expectedHash
                val record = repository.importBook(Uri.fromFile(source))
                assertEquals(expectedHash, record.id)
                assertTrue(record.title.endsWith(uuid))
                val book = repository.loadSpeechBook(record.id)
                assertEquals("zh-CN", book.book.language)
                val firstChapter = book.sentences.first().chapterIndex
                val firstSentences = book.sentences.takeWhile { it.chapterIndex == firstChapter }
                val firstCount = firstSentences.size
                assertTrue(firstCount >= 16)
                assertEquals(firstCount, firstSentences.map { it.text }.distinct().size)
                assertTrue(firstCount < book.sentences.size)
                val secondStart = firstCount
                val secondSentences = book.sentences.drop(secondStart)
                val secondChapter = secondSentences.first().chapterIndex
                assertTrue(secondChapter != firstChapter)
                assertTrue(secondSentences.size >= 4)
                assertTrue(secondSentences.all { it.chapterIndex == secondChapter })
                val firstRange = 0 until secondStart
                val secondRange = secondStart until book.sentences.size

                val playbackRequestedAt = withContext(Dispatchers.Main) {
                    val startedAt = SystemClock.elapsedRealtime()
                    PlaybackHub.playBook(context, record.id, 0)
                    startedAt
                }
                val ready = awaitSnapshot(record.id) {
                    if (it.isPlaying) assertEquals(firstCount, it.chapterSentenceCount)
                    it.isPlaying && !it.isPreparing && it.durationMs > 0
                }
                val startLatencyMs = SystemClock.elapsedRealtime() - playbackRequestedAt
                assertEquals(0, ready.sentenceIndex)
                val firstFiles = cachedWavs(context, record.id)
                assertTrue(firstFiles.isNotEmpty())
                assertTrue("Playback must start before the complete chapter is synthesized", firstFiles.size < firstCount)
                report(instrumentation, "first_audio_ready", ready,
                    "startLatencyMs=$startLatencyMs cachedWavs=${firstFiles.size}/$firstCount")

                val advanced = awaitSnapshot(record.id) {
                    it.isPlaying && !it.isPreparing && it.sentenceIndex > ready.sentenceIndex
                }
                assertTrue("Natural transition must remain in the first chapter", advanced.sentenceIndex in firstRange)
                assertEquals(firstCount, advanced.chapterSentenceCount)
                assertTrue("Natural sentence transition must not reset chapter progress",
                    advanced.chapterProgress > ready.chapterProgress && advanced.chapterProgress > 0f)
                report(instrumentation, "natural_transition", advanced)

                withContext(Dispatchers.Main) { PlaybackHub.toggle(context) }
                val paused = awaitSnapshot(record.id) { !it.isPlaying && !it.isPreparing && it.durationMs > 0 }
                repeat(12) {
                    delay(100)
                    val stable = PlaybackHub.state.value
                    assertEquals(record.id, stable.bookId)
                    assertNull(stable.error)
                    assertFalse(stable.isPlaying)
                    assertFalse(stable.isPreparing)
                    assertEquals(paused.sentenceIndex, stable.sentenceIndex)
                    assertEquals(firstCount, stable.chapterSentenceCount)
                    assertValidFraction(stable)
                    assertTrue("Paused media position must stay stable", abs(stable.positionMs - paused.positionMs) <= 250)
                    assertTrue("Paused chapter progress must stay stable", abs(stable.chapterProgress - paused.chapterProgress) <= 0.01f)
                }
                report(instrumentation, "paused", paused)

                withContext(Dispatchers.Main) { PlaybackHub.toggle(context) }
                val resumed = awaitSnapshot(record.id) {
                    it.isPlaying && !it.isPreparing && it.chapterProgress > paused.chapterProgress &&
                        (it.sentenceIndex > paused.sentenceIndex ||
                            it.sentenceIndex == paused.sentenceIndex && it.positionMs > paused.positionMs + 100)
                }
                assertTrue(resumed.sentenceIndex in firstRange)
                assertEquals(firstCount, resumed.chapterSentenceCount)
                report(instrumentation, "resumed", resumed)

                withContext(Dispatchers.Main) { PlaybackHub.toggle(context) }
                val seekStart = awaitSnapshot(record.id) { !it.isPlaying && !it.isPreparing && it.durationMs > 0 }
                withContext(Dispatchers.Main) { PlaybackHub.seekBy(context, 15_000) }
                val sought = awaitSnapshot(record.id) {
                    !it.isPlaying && !it.isPreparing && it.durationMs > 0 &&
                        it.chapterProgress > seekStart.chapterProgress &&
                        (it.sentenceIndex > seekStart.sentenceIndex ||
                            it.sentenceIndex == seekStart.sentenceIndex && it.positionMs > seekStart.positionMs + 100)
                }
                assertTrue(sought.sentenceIndex in firstRange)
                assertEquals(firstCount, sought.chapterSentenceCount)
                assertTrue("Paused real-audio seek must advance the chapter fraction",
                    sought.chapterProgress > seekStart.chapterProgress)
                assertFalse(sought.isPlaying)
                report(instrumentation, "seek_paused", sought)

                withContext(Dispatchers.Main) { PlaybackHub.playBook(context, record.id, secondStart) }
                val next = awaitSnapshot(record.id) {
                    it.sentenceIndex in secondRange && it.isPlaying && !it.isPreparing && it.durationMs > 0 &&
                        it.chapterSentenceCount == secondSentences.size
                }
                assertEquals(secondStart, next.sentenceIndex)
                val firstWeight = secondSentences.first().text.codePointCount(0, secondSentences.first().text.length)
                val chapterWeight = secondSentences.sumOf { it.text.codePointCount(0, it.text.length).toLong() }
                val expectedStart = firstWeight.toDouble() / chapterWeight *
                    (next.positionMs.coerceIn(0L, next.durationMs).toDouble() / next.durationMs)
                assertEquals("A new chapter fraction must start from its own first sentence",
                    expectedStart.toFloat(), next.chapterProgress, 0.0001f)
                report(instrumentation, "next_chapter_ready", next)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    ownedBookId?.let { id ->
                        val active = PlaybackHub.state.value.bookId
                        check(active == null || active == id) { "Another book became active; refusing to stop its session" }
                        if (active == id) {
                            withContext(Dispatchers.Main) { PlaybackHub.stop(context) }
                            withTimeout(15_000) { PlaybackHub.state.first { it.bookId == null } }
                        }
                        // stop's empty snapshot is published after its synthesis workers are joined.
                        repository.removeBook(id)
                        report(instrumentation, "cleaned", PlaybackHub.state.value)
                    }
                } finally {
                    withContext(Dispatchers.IO) { source.delete() }
                }
            }
        }
    }

    private suspend fun awaitSnapshot(bookId: String, ready: (PlaybackSnapshot) -> Boolean): PlaybackSnapshot =
        withTimeout(120_000) {
            PlaybackHub.state.first { snapshot ->
                if (snapshot.bookId != bookId) return@first false
                assertNull("Playback failed during device regression", snapshot.error)
                assertValidFraction(snapshot)
                ready(snapshot)
            }
        }

    private suspend fun cachedWavs(context: Context, bookId: String): List<File> = withContext(Dispatchers.IO) {
        File(context.cacheDir, "speech/$bookId").listFiles().orEmpty().filter { it.isFile && it.extension == "wav" }
    }

    private fun assertValidFraction(state: PlaybackSnapshot) {
        assertTrue("Chapter fraction must remain finite", state.chapterProgress.isFinite())
        assertTrue("Chapter fraction must stay within zero and one", state.chapterProgress in 0f..1f)
    }

    private fun report(instrumentation: Instrumentation, phase: String, state: PlaybackSnapshot, detail: String = "") {
        instrumentation.sendStatus(0, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                "CHAPTER_PLAYBACK phase=$phase sentence=${state.sentenceIndex} " +
                    "chapterFraction=${state.chapterProgress} chapterSentences=${state.chapterSentenceCount} " +
                    "withinMs=${state.positionMs}/${state.durationMs} $detail\n")
        })
    }

    private fun writeBook(file: File, uuid: String) {
        val first = listOf(
            "清晨的风推开窗边的一片薄云，屋里慢慢亮了起来。",
            "我把新写的小书放在桌上，纸页还带着淡淡的香气。",
            "朋友沿着山间的小路走来，手里捧着一杯温热的茶。",
            "门前的树叶轻轻摇动，把细碎的阳光洒在台阶上。",
            "我们决定今天走得慢一些，认真看一看路旁的花。",
            "小溪绕过平整的石头，把清亮的声音送到桥边。",
            "远处的鸟儿停在枝头，耐心等候另一阵柔软的风。",
            "我在空白的纸角记下一句话，留给未来的自己。",
            "午后的天空渐渐放晴，窗外的颜色也变得明亮。",
            "朋友讲起一段新的故事，语气从容又带着笑意。",
            "我们沿着旧路回到村口，看见有人正在整理花园。",
            "院子里的木椅十分安静，旁边放着刚洗好的杯子。",
            "傍晚的灯光照亮桌面，也照亮了尚未读完的书页。",
            "我把今天听见的声音记下，让每个句子各有位置。",
            "夜色慢慢落在远山，回家的脚步依旧平稳而轻柔。",
            "故事停在温暖的一页，明天还会有新的阅读旅程。",
        )
        val second = listOf(
            "第二天的午后，我们走过河边的小桥，听水流在石头之间转弯，也听远处的鸟儿回应树林里的风。",
            "小路尽头有一间明亮的屋子，窗边摆着几本刚写好的故事，每一页都留着从容阅读和认真倾听的位置。",
            "我把昨天记下的句子重新读了一遍，发现熟悉的文字也能带来新的感受，于是又慢慢写下一段温柔的回答。",
            "夜色降临之前，我们把书放回原来的地方，约好明天继续这次旅程，让安静的声音陪伴又一个普通的日子。",
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()
            fun entry(path: String, value: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
            """.trimIndent())
            entry("EPUB/package.opf", """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">urn:uuid:$uuid</dc:identifier><dc:title>Chapter Playback Device Test $uuid</dc:title>
                <dc:creator>FreeReading device regression</dc:creator><dc:language>zh-CN</dc:language>
                <meta property="dcterms:modified">2026-11-13T00:00:00Z</meta></metadata>
                <manifest><item id="one" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="two" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>
                <spine><itemref idref="one"/><itemref idref="two"/></spine></package>
            """.trimIndent())
            entry("EPUB/nav.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>目录</title></head><body><nav epub:type="toc"><ol>
                <li><a href="chapter1.xhtml">第一章</a></li><li><a href="chapter2.xhtml">第二章</a></li>
                </ol></nav></body></html>
            """.trimIndent())
            for ((index, paragraphs) in listOf(first, second).withIndex()) {
                entry("EPUB/chapter${index + 1}.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
                    <head><title>原创第${index + 1}章</title></head><body>
                    ${paragraphs.joinToString("\n") { "<p>$it</p>" }}</body></html>
                """.trimIndent())
            }
        }
    }
}
