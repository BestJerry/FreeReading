package com.freereading.audio

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.freereading.ReadingApplication
import com.freereading.data.BookRecord
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ReadingAudioServiceChapterStopTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var controller: ServiceController<ReadingAudioService>
    private lateinit var service: ReadingAudioService
    private val oldOperationGate = CompletableDeferred<Unit>()

    @Before fun createService() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        PlaybackHub.publish(PlaybackSnapshot())
        controller = Robolectric.buildService(ReadingAudioService::class.java).create()
        service = controller.get()
        drain()
    }

    @After fun releaseService() {
        oldOperationGate.complete(Unit)
        drain()
        ReflectionHelpers.setField(service, "book", null)
        controller.destroy()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun stopImmediatelyDisablesChapterControlsWhileOldWorkIsStillFinishing() {
        val app = RuntimeEnvironment.getApplication() as ReadingApplication
        val sentences = List(3) { index ->
            SpeechSentence(index, "句子$index。", "{}", index, "章节$index")
        }
        val fixture = SpeechBook(BookRecord("book-a", "章节测试书", "作者", "zh", 0, 3), sentences)
        ReflectionHelpers.setField(service, "book", fixture)
        ReflectionHelpers.setField(service, "chapterProgressIndex", ChapterProgressIndex(sentences))
        PlaybackHub.publish(PlaybackSnapshot(bookId = "book-a", sentenceIndex = 1,
            sentenceCount = 3, canGoToPreviousChapter = true, canGoToNextChapter = true))
        val serviceScope = ReflectionHelpers.getField<CoroutineScope>(service, "serviceScope")
        val oldOperation = serviceScope.launch {
            withContext(NonCancellable) { oldOperationGate.await() }
        }
        ReflectionHelpers.setField(service, "operation", oldOperation)
        drain()

        service.onStartCommand(PlaybackHub.command(app, ReadingAudioService.ACTION_STOP), 0, 1)
        drain()

        assertNull(ReflectionHelpers.getField<SpeechBook?>(service, "book"))
        assertFalse(oldOperation.isCompleted)
        assertFalse(PlaybackHub.state.value.canGoToPreviousChapter)
        assertFalse(PlaybackHub.state.value.canGoToNextChapter)

        oldOperationGate.complete(Unit)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (PlaybackHub.state.value.bookId != null) {
            drain()
            if (System.nanoTime() >= deadline) throw AssertionError("Timed out waiting for stopped session")
            Thread.sleep(2)
        }
        drain()
        assertFalse(PlaybackHub.state.value.canGoToPreviousChapter)
        assertFalse(PlaybackHub.state.value.canGoToNextChapter)
        assertFalse(ReflectionHelpers.getField<ExoPlayer>(service, "player").playWhenReady)
    }

    private fun drain() {
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
