package com.freereading.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** 引擎提供的可选音色信息；名称和语言来自系统，Android 不提供音色性别。 */
internal data class TtsVoiceOption(
    val id: String,
    val name: String,
    val locale: Locale,
    val quality: Int,
)

/** 音色目录；selectedVoiceId 为 null 表示自动选择，effectiveVoiceId 是按当前策略算出的目标音色，不代表已设置到引擎。 */
internal data class TtsVoiceCatalog(
    val engineId: String,
    val voices: List<TtsVoiceOption>,
    val selectedVoiceId: String?,
    val effectiveVoiceId: String?,
)

/** 提供离线音色筛选、稳定标识和自动选择策略，不直接修改 TTS 引擎状态。 */
internal object TtsVoiceSelection {
    // 引擎包名前加长度，避免名称中包含分隔符时产生相同标识。
    fun voiceId(engineId: String, voiceName: String): String = "${engineId.length}:$engineId$voiceName"

    /** 生成规范化基础语言的偏好键，让同语言书籍与语言别名共用选择；此方法不写入偏好。 */
    fun languageKey(language: String, fallback: Locale = Locale.getDefault()): String =
        SpeechLanguage.requestedLocale(language, fallback).language

    /** 只保留同语言且已安装的离线音色，按语言匹配度、音质和名称稳定排序。 */
    fun eligibleVoices(voices: Collection<Voice>, requested: Locale): List<Voice> = voices
        .filter { SpeechLanguage.sameLanguage(it.locale, requested) }
        .filter { !it.isNetworkConnectionRequired }
        .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        .sortedWith(compareByDescending<Voice> { SpeechLanguage.preference(it.locale, requested) }
            .thenByDescending { it.quality }.thenBy { it.name })
        // 引擎按名称加载音色，重复名称仅保留排序最高的元数据。
        .distinctBy { it.name }

    /** 去重并校验已存选择；选择失效时回退到列表首项，没有可用项时目标音色为 null。 */
    fun catalog(engineId: String, voices: List<Voice>, selectedVoiceId: String?): TtsVoiceCatalog {
        val options = voices.map { TtsVoiceOption(voiceId(engineId, it.name), it.name, it.locale, it.quality) }
            .distinctBy { it.id }
        val selection = selectedVoiceId?.takeIf { id -> options.any { it.id == id } }
        return TtsVoiceCatalog(engineId, options, selection, selection ?: options.firstOrNull()?.id)
    }
}

/** 按规范化语言持久化用户明确选择的音色，书籍之间及等价语言编码之间共用。 */
internal class TtsVoicePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("tts_voice_selection", Context.MODE_PRIVATE)

    fun selected(languageKey: String): String? = preferences.getString(languageKey, null)

    /** 保存明确的音色标识；传入 null 删除偏好，恢复自动选择。 */
    fun select(languageKey: String, voiceId: String?) {
        preferences.edit().apply {
            if (voiceId == null) remove(languageKey) else putString(languageKey, voiceId)
        }.apply()
    }
}
