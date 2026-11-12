package com.freereading.audio

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.freereading.data.SpeechSentence
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an installed offline Chinese voice; does not change system settings or install data. */
@RunWith(AndroidJUnit4::class)
class SpeechEngineDeviceTest {
    @Test fun synthesizesEquivalentChineseLanguageTags() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cache = OfflineSpeechCache(context)
        try {
            for (language in listOf("zh-CN", "zho")) {
                val sentence = SpeechSentence(0, "这是中文离线听书测试。", "{}", 0, "语音测试")
                val clip = cache.ensure("device-language-probe", sentence, language)
                assertTrue(clip.durationMs > 0 && clip.file.length() > 44)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                        "LANGUAGE_SYNTHESIS $language durationMs=${clip.durationMs}\n")
                })
            }
        } finally {
            withContext(Dispatchers.Main) { cache.close() }
            File(context.cacheDir, "speech/device-language-probe").deleteRecursively()
        }
    }
}
