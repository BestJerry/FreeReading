package com.freereading.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.freereading.ReadingApplication
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = ReadingApplication::class)
class TtsVoiceSelectionTest {
    private lateinit var context: Context

    @Before fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun removePreferences() {
        context.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun catalogIncludesOnlyInstalledOfflineVoicesOfTheBookLanguage() {
        val installed = voice("installed", "zh-CN")
        val filtered = TtsVoiceSelection.eligibleVoices(listOf(
            voice("foreign", "en-US"),
            voice("network", "zh-CN", network = true),
            voice("missing-data", "zh-CN", features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)),
            installed,
        ), Locale.forLanguageTag("zho"))

        assertEquals(listOf(installed), filtered)
    }

    @Test fun automaticChoiceKeepsLocaleThenQualityThenNamePriority() {
        val voices = listOf(
            voice("high-quality-language-only", "zh-Hant-TW", quality = Voice.QUALITY_VERY_HIGH),
            voice("exact-low-quality", "zh-Hans-CN", quality = Voice.QUALITY_LOW),
            voice("z-exact", "zh-Hans-CN"),
            voice("a-exact", "zh-Hans-CN"),
            voice("matching-script", "zh-Hans-SG"),
            voice("matching-country", "zh-Hant-CN"),
        )
        val ordered = TtsVoiceSelection.eligibleVoices(voices, Locale.forLanguageTag("zh-Hans-CN"))

        assertEquals(listOf("a-exact", "z-exact", "exact-low-quality", "matching-script",
            "matching-country", "high-quality-language-only"), ordered.map { it.name })
        val catalog = TtsVoiceSelection.catalog("engine.a", ordered, null)
        assertNull(catalog.selectedVoiceId)
        assertEquals(catalog.voices.first().id, catalog.effectiveVoiceId)
    }

    @Test fun sameNameVariantsInAVoiceSetProduceOneChoiceWithThePreferredMetadata() {
        val preferred = voice("shared-name", "zh-CN", quality = Voice.QUALITY_HIGH)
        val reported = setOf(
            voice("shared-name", "zh-TW", quality = Voice.QUALITY_VERY_HIGH),
            voice("shared-name", "zh-CN", quality = Voice.QUALITY_LOW),
            preferred,
            voice("another-name", "zh-CN"),
        )
        // Android Voice equality includes locale and quality, so a Set retains these variants.
        assertEquals(4, reported.size)

        val eligible = TtsVoiceSelection.eligibleVoices(reported, Locale.CHINA)
        assertEquals(listOf("shared-name", "another-name"), eligible.map { it.name })
        assertEquals(preferred, eligible.first())
        val catalog = TtsVoiceSelection.catalog("engine.a", eligible, null)
        assertEquals(2, catalog.voices.size)
        assertEquals(catalog.voices.size, catalog.voices.map { it.id }.distinct().size)
        assertEquals(preferred.locale, catalog.voices.first().locale)
        assertEquals(preferred.quality, catalog.voices.first().quality)

        // The model boundary must remain safe even when a caller passes unfiltered variants.
        val directCatalog = TtsVoiceSelection.catalog("engine.a", listOf(preferred) + reported, null)
        assertEquals(2, directCatalog.voices.size)
        assertEquals(preferred.locale, directCatalog.voices.first().locale)
        assertEquals(preferred.quality, directCatalog.voices.first().quality)
    }

    @Test fun explicitChoiceOverridesTheAutomaticPriority() {
        val voices = listOf(voice("first", "zh-CN"), voice("chosen", "zh-TW"))
        val chosen = TtsVoiceSelection.voiceId("engine.a", "chosen")
        val catalog = TtsVoiceSelection.catalog("engine.a", voices, chosen)

        assertEquals(chosen, catalog.selectedVoiceId)
        assertEquals(chosen, catalog.effectiveVoiceId)
    }

    @Test fun missingChosenVoiceFallsBackToAutomaticInsteadOfReportingNoVoice() {
        val catalog = TtsVoiceSelection.catalog("engine.a", listOf(voice("installed", "zh-CN")),
            TtsVoiceSelection.voiceId("engine.a", "removed"))

        assertNull(catalog.selectedVoiceId)
        assertEquals(catalog.voices.single().id, catalog.effectiveVoiceId)
    }

    @Test fun engineReplacementDoesNotReuseAnIdenticallyNamedChoice() {
        val catalog = TtsVoiceSelection.catalog("engine.b", listOf(voice("same-name", "zh-CN")),
            TtsVoiceSelection.voiceId("engine.a", "same-name"))

        assertNull(catalog.selectedVoiceId)
        assertEquals(TtsVoiceSelection.voiceId("engine.b", "same-name"), catalog.effectiveVoiceId)
    }

    @Test fun emptyCatalogHasNeitherSelectionNorEffectiveVoice() {
        val catalog = TtsVoiceSelection.catalog("engine.a", emptyList(), "missing")

        assertTrue(catalog.voices.isEmpty())
        assertNull(catalog.selectedVoiceId)
        assertNull(catalog.effectiveVoiceId)
    }

    @Test fun idsIncludeBothEngineAndVoiceWithoutSeparatorCollisions() {
        assertNotEquals(TtsVoiceSelection.voiceId("engine.a", "voice"),
            TtsVoiceSelection.voiceId("engine.b", "voice"))
        assertNotEquals(TtsVoiceSelection.voiceId("ab", "c:d"),
            TtsVoiceSelection.voiceId("abc", ":d"))
        assertEquals(TtsVoiceSelection.voiceId("engine.a", "voice|:名字"),
            TtsVoiceSelection.voiceId("engine.a", "voice|:名字"))
    }

    @Test fun labelsRetainEngineProvidedNamesAndLocales() {
        val source = voice("cmn-cn-local-17", "zh-Hans-CN", quality = Voice.QUALITY_HIGH)
        val option = TtsVoiceSelection.catalog("engine.a", listOf(source), null).voices.single()

        assertEquals(source.name, option.name)
        assertEquals(source.locale, option.locale)
        assertEquals(source.quality, option.quality)
    }

    @Test fun preferenceKeysUnifyAliasesRegionsAndScriptsByLanguage() {
        for (tag in listOf("zh", "zho", "chi", "zh-Hans-CN", "zho_Hant_TW")) {
            assertEquals(tag, "zh", TtsVoiceSelection.languageKey(tag, Locale.US))
        }
        assertEquals("en", TtsVoiceSelection.languageKey(" ENG_us ", Locale.JAPAN))
        assertEquals("de", TtsVoiceSelection.languageKey("ger-DE", Locale.JAPAN))
    }

    @Test fun blankAndUndeterminedKeysUseTheSameFallbackAsSynthesis() {
        assertEquals("ja", TtsVoiceSelection.languageKey("", Locale.JAPAN))
        assertEquals("ja", TtsVoiceSelection.languageKey("und", Locale.JAPAN))
        assertEquals("qaa", TtsVoiceSelection.languageKey("qaa-Latn-ZZ", Locale.JAPAN))
    }

    @Test fun preferencesSurviveNewInstancesAndRemainSeparateBetweenLanguages() {
        val preferences = TtsVoicePreferences(context)
        preferences.select(TtsVoiceSelection.languageKey("zho"), "chinese-choice")
        preferences.select(TtsVoiceSelection.languageKey("eng"), "english-choice")

        val recreated = TtsVoicePreferences(context)
        assertEquals("chinese-choice", recreated.selected(TtsVoiceSelection.languageKey("zh-CN")))
        assertEquals("english-choice", recreated.selected(TtsVoiceSelection.languageKey("en-US")))
        assertNull(recreated.selected(TtsVoiceSelection.languageKey("ja")))
    }

    @Test fun automaticSelectionRemovesOnlyTheCorrespondingLanguagePreference() {
        val preferences = TtsVoicePreferences(context)
        preferences.select("zh", "chinese-choice")
        preferences.select("en", "english-choice")
        preferences.select("zh", null)

        assertNull(TtsVoicePreferences(context).selected("zh"))
        assertEquals("english-choice", TtsVoicePreferences(context).selected("en"))
        assertFalse(context.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE).contains("zh"))
    }

    private fun voice(name: String, tag: String, quality: Int = Voice.QUALITY_NORMAL,
        network: Boolean = false, features: Set<String> = emptySet()) =
        Voice(name, Locale.forLanguageTag(tag), quality, Voice.LATENCY_NORMAL, network, features)
}
