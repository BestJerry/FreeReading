package com.freereading.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** The name and locale are supplied by the engine; Android does not expose voice gender. */
internal data class TtsVoiceOption(
    val id: String,
    val name: String,
    val locale: Locale,
    val quality: Int,
)

/** A null selectedVoiceId means automatic; effectiveVoiceId identifies its current result. */
internal data class TtsVoiceCatalog(
    val engineId: String,
    val voices: List<TtsVoiceOption>,
    val selectedVoiceId: String?,
    val effectiveVoiceId: String?,
)

internal object TtsVoiceSelection {
    // Length-prefix the engine package so names containing separators cannot collide.
    fun voiceId(engineId: String, voiceName: String): String = "${engineId.length}:$engineId$voiceName"

    fun languageKey(language: String, fallback: Locale = Locale.getDefault()): String =
        SpeechLanguage.requestedLocale(language, fallback).language

    fun eligibleVoices(voices: Collection<Voice>, requested: Locale): List<Voice> = voices
        .filter { SpeechLanguage.sameLanguage(it.locale, requested) }
        .filter { !it.isNetworkConnectionRequired }
        .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        .sortedWith(compareByDescending<Voice> { SpeechLanguage.preference(it.locale, requested) }
            .thenByDescending { it.quality }.thenBy { it.name })
        // Engines load a voice by name; keep the highest-ranked metadata for repeated names.
        .distinctBy { it.name }

    fun catalog(engineId: String, voices: List<Voice>, selectedVoiceId: String?): TtsVoiceCatalog {
        val options = voices.map { TtsVoiceOption(voiceId(engineId, it.name), it.name, it.locale, it.quality) }
            .distinctBy { it.id }
        val selection = selectedVoiceId?.takeIf { id -> options.any { it.id == id } }
        return TtsVoiceCatalog(engineId, options, selection, selection ?: options.firstOrNull()?.id)
    }
}

/** One explicit choice per canonical language, shared by books and ISO language aliases. */
internal class TtsVoicePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE)

    fun selected(languageKey: String): String? = preferences.getString(languageKey, null)

    fun select(languageKey: String, voiceId: String?) {
        preferences.edit().apply {
            if (voiceId == null) remove(languageKey) else putString(languageKey, voiceId)
        }.apply()
    }
}
