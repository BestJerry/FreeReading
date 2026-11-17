package com.freereading.audio

internal data class TtsVoiceUiState(
    val bookId: String? = null,
    val language: String = "",
    val requestId: String? = null,
    val loading: Boolean = false,
    val switching: Boolean = false,
    val catalog: TtsVoiceCatalog? = null,
    val error: String? = null,
)
