package com.freereading.audio

/** 音色弹窗的异步状态；书籍、语言与请求标识共同隔离过期的查询或切换结果。 */
internal data class TtsVoiceUiState(
    val bookId: String? = null,
    val language: String = "",
    val requestId: String? = null,
    val loading: Boolean = false,
    val switching: Boolean = false,
    val catalog: TtsVoiceCatalog? = null,
    val error: String? = null,
)
