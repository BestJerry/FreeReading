package com.freereading.audio

import android.icu.util.ULocale
import java.util.Locale

/** 统一 EPUB 与 Android 音色的语言标记，处理三字母别名并保留文字体系及地区信息。 */
internal object SpeechLanguage {
    // Android 8/9 的 ICU 不解析 ISO 639-2/B 书目别名，在此集中补齐标准映射。
    // 标准来源：https://www.loc.gov/standards/iso639-2/php/code_list.php
    private val bibliographicLanguages = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my",
        "chi" to "zh", "cze" to "cs", "dut" to "nl", "fre" to "fr",
        "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is",
        "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
    )

    /** 解析书籍语言；输入为空白或解析后的基础语言为空／und 时，使用调用方提供的默认语言。 */
    fun requestedLocale(raw: String, fallback: Locale): Locale {
        val tag = raw.trim().replace('_', '-')
        val locale = Locale.forLanguageTag(tag)
        return if (tag.isBlank() || locale.language.isBlank() || locale.language.equals("und", true)) {
            normalizeLocale(fallback)
        } else normalizeLocale(locale)
    }

    /** 将等价语言编码规范化，避免 zho、chi 和 zh 等写法导致中文音色匹配失败。 */
    fun normalizeLocale(locale: Locale): Locale {
        // EPUB 常用 ISO 639-2（zho），Android 音色常用 ISO 639-1（zh）。
        // ICU 在转换等价语言编码的同时保留文字体系、地区和扩展信息。
        val canonical = ULocale.createCanonical(ULocale.forLocale(locale).toString())
        val language = bibliographicLanguages[canonical.language] ?: return canonical.toLocale()
        return ULocale.Builder().setLocale(canonical).setLanguage(language).build().toLocale()
    }

    /** 仅比较规范化后的语言，不要求地区或文字体系完全相同。 */
    fun sameLanguage(first: Locale, second: Locale): Boolean {
        val language = normalizeLocale(first).language
        return language.isNotEmpty() && language == normalizeLocale(second).language
    }

    /** 为同语言音色排序：完全匹配优先，其次文字体系，再次地区。 */
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
