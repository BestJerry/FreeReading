package com.freereading.audio

import android.icu.util.ULocale
import java.util.Locale

internal object SpeechLanguage {
    // ISO 639-2/B aliases are not resolved by Android 8/9 ICU. Keep this standard data
    // in one place: https://www.loc.gov/standards/iso639-2/php/code_list.php
    private val bibliographicLanguages = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my",
        "chi" to "zh", "cze" to "cs", "dut" to "nl", "fre" to "fr",
        "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is",
        "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
    )

    fun requestedLocale(raw: String, fallback: Locale): Locale {
        val tag = raw.trim().replace('_', '-')
        val locale = Locale.forLanguageTag(tag)
        return if (tag.isBlank() || locale.language.isBlank() || locale.language.equals("und", true)) {
            normalizeLocale(fallback)
        } else normalizeLocale(locale)
    }

    fun normalizeLocale(locale: Locale): Locale {
        // EPUBs commonly use ISO 639-2 (zho), while Android voices use ISO 639-1 (zh).
        // ICU retains script, region and extensions while resolving equivalent language IDs.
        val canonical = ULocale.createCanonical(ULocale.forLocale(locale).toString())
        val language = bibliographicLanguages[canonical.language] ?: return canonical.toLocale()
        return ULocale.Builder().setLocale(canonical).setLanguage(language).build().toLocale()
    }

    fun sameLanguage(first: Locale, second: Locale): Boolean {
        val language = normalizeLocale(first).language
        return language.isNotEmpty() && language == normalizeLocale(second).language
    }

    fun preference(candidate: Locale, requested: Locale): Int {
        val voice = normalizeLocale(candidate)
        val target = normalizeLocale(requested)
        if (voice.language != target.language) return 0
        if (voice == target) return 8
        val script = if (target.script.isNotEmpty() && voice.script == target.script) 4 else 0
        val country = if (target.country.isNotEmpty() && voice.country == target.country) 2 else 0
        return script + country
    }
}
