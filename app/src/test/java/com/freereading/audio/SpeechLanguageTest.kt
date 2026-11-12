package com.freereading.audio

import com.freereading.ReadingApplication
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs against Android ICU at the minimum supported API and the existing test baseline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = ReadingApplication::class)
class SpeechLanguageTest {
    @Test fun zhoBookMetadataMatchesZhOfflineVoice() {
        val bookLocale = Locale.forLanguageTag("zho")
        val voiceLocale = Locale.forLanguageTag("zh")
        assertTrue(
            "EPUB 的 zho 应匹配手机返回的 zh 离线声音，不能被误判为缺少语音包",
            SpeechLanguage.sameLanguage(bookLocale, voiceLocale),
        )
    }

    @Test fun isoLanguageAliasesAreEquivalentInBothDirections() {
        val groups = listOf(
            listOf("zh", "zho", "chi"),
            listOf("de", "deu", "ger"),
            listOf("fr", "fra", "fre"),
            listOf("ja", "jpn"),
            listOf("en", "eng"),
        )
        for (aliases in groups) {
            for (alias in aliases) {
                val locale = Locale.forLanguageTag(alias)
                assertEquals("$alias 应规范化为 ${aliases.first()}",
                    aliases.first(), SpeechLanguage.normalizeLocale(locale).language)
                assertEquals(aliases.first(), SpeechLanguage.requestedLocale(alias, Locale.US).language)
                for (other in aliases) {
                    assertTrue("$alias 与 $other 应属于同一语言",
                        SpeechLanguage.sameLanguage(locale, Locale.forLanguageTag(other)))
                }
            }
        }
    }

    @Test fun canonicalizationPreservesExplicitScriptAndCountry() {
        val requested = SpeechLanguage.requestedLocale("zho-Hans-CN", Locale.US)
        assertEquals("zh", requested.language)
        assertEquals("Hans", requested.script)
        assertEquals("CN", requested.country)
        assertEquals("zh-Hans-CN", requested.toLanguageTag())

        val normalized = SpeechLanguage.normalizeLocale(Locale.forLanguageTag("chi-Hant-TW"))
        assertEquals("zh-Hant-TW", normalized.toLanguageTag())
    }

    @Test fun legacySeparatorsWhitespaceAndCaseAreNormalized() {
        assertEquals(Locale.US, SpeechLanguage.requestedLocale(" ENG_us ", Locale.JAPAN))
    }

    @Test fun aValidUnknownLanguageIsRetainedRatherThanReplacedWithFallback() {
        val locale = SpeechLanguage.requestedLocale("qaa-Latn-ZZ", Locale.US)
        assertEquals("qaa", locale.language)
        assertEquals("Latn", locale.script)
        assertEquals("ZZ", locale.country)
        assertEquals("qaa-Latn-ZZ", SpeechLanguage.normalizeLocale(locale).toLanguageTag())
    }

    @Test fun blankAndUndeterminedLanguageUseTheProvidedFallback() {
        val fallback = Locale.CANADA_FRENCH
        for (raw in listOf("", " ", "\t\n", "und", " UND ")) {
            assertEquals("$raw 应使用调用方提供的回退语言", fallback,
                SpeechLanguage.requestedLocale(raw, fallback))
        }
    }

    @Test fun normalizingAnAlreadyNormalizedLocaleIsIdempotent() {
        for (tag in listOf("zho-Hans-CN", "chi-Hant-TW", "deu-DE", "fra-CA", "jpn-JP", "eng-US", "qaa-Latn-ZZ")) {
            val once = SpeechLanguage.normalizeLocale(Locale.forLanguageTag(tag))
            assertEquals("$tag 的第二次归一化不应改变结果", once, SpeechLanguage.normalizeLocale(once))
        }
    }

    @Test fun voicePreferenceOrdersExactThenScriptThenCountryThenLanguageOnly() {
        val requested = Locale.forLanguageTag("zh-Hans-CN")
        val exact = SpeechLanguage.preference(Locale.forLanguageTag("zh-Hans-CN"), requested)
        val script = SpeechLanguage.preference(Locale.forLanguageTag("zh-Hans-SG"), requested)
        val country = SpeechLanguage.preference(Locale.forLanguageTag("zh-Hant-CN"), requested)
        val languageOnly = SpeechLanguage.preference(Locale.forLanguageTag("zh-Hant-TW"), requested)
        assertTrue("完整 Locale 相同应优先于仅书写系统匹配", exact > script)
        assertTrue("书写系统匹配应优先于仅地区匹配", script > country)
        assertTrue("地区匹配应优先于仅语言匹配", country > languageOnly)
    }

    @Test fun aliasEquivalentExactVoiceHasTheSamePreferenceAsCanonicalVoice() {
        val requested = Locale.forLanguageTag("zho-Hans-CN")
        assertEquals(
            SpeechLanguage.preference(Locale.forLanguageTag("zh-Hans-CN"), requested),
            SpeechLanguage.preference(Locale.forLanguageTag("chi-Hans-CN"), requested),
        )
    }

    @Test fun matchingScriptAndCountryCannotMakeADifferentLanguageEligible() {
        val requested = Locale.forLanguageTag("zh-Hans-CN")
        val unrelated = Locale.forLanguageTag("de-Hans-CN")
        assertFalse(SpeechLanguage.sameLanguage(unrelated, requested))
        assertEquals(0, SpeechLanguage.preference(unrelated, requested))
    }

    @Test fun unrelatedLanguagesRemainDifferentAfterNormalization() {
        assertFalse(SpeechLanguage.sameLanguage(Locale.forLanguageTag("zho"), Locale.forLanguageTag("jpn")))
        assertFalse(SpeechLanguage.sameLanguage(Locale.forLanguageTag("ger"), Locale.forLanguageTag("fre")))
    }

    @Test fun unicodeExtensionsSurviveLanguageCanonicalization() {
        val locale = Locale.forLanguageTag("chi-Hans-CN-u-nu-hanidec")
        val normalized = SpeechLanguage.normalizeLocale(locale)
        assertEquals("zh", normalized.language)
        assertEquals("Hans", normalized.script)
        assertEquals("CN", normalized.country)
        assertEquals("hanidec", normalized.getUnicodeLocaleType("nu"))
    }

    @Test fun emptyLanguageDoesNotMatchAnUntypedVoice() {
        assertFalse(SpeechLanguage.sameLanguage(Locale.ROOT, Locale.ROOT))
    }
}
