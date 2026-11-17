package com.freereading.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.freereading.audio.TtsVoiceUiState
import java.util.Locale

/** 展示系统离线音色；切换期间仍按服务确认的音色目录标记选中项，避免提前显示成功。 */
@Composable
internal fun TtsVoicePicker(
    state: TtsVoiceUiState,
    paper: Color,
    ink: Color,
    accent: Color,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String?) -> Unit,
    onVoiceSettings: () -> Unit,
) {
    val muted = ink.copy(alpha = 0.62f)
    val catalog = state.catalog
    // 部分引擎会返回重复音色，按 ID 去重以保证列表 key 唯一。
    val voices = catalog?.voices.orEmpty().distinctBy { it.id }
    val busy = state.loading || state.switching
    val effectiveVoice = catalog?.voices?.firstOrNull { it.id == catalog.effectiveVoiceId }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = paper, contentColor = ink, shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, ink.copy(alpha = 0.12f)),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).widthIn(max = 520.dp)
                .fillMaxWidth().testTag("tts-voice-dialog")) {
            Column(Modifier.heightIn(max = 560.dp).padding(16.dp)) {
                Text("朗读音色", color = ink, fontFamily = FontFamily.Serif, fontSize = 21.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()
                    .testTag("tts-voice-list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        Text("仅显示系统默认引擎已安装的本书语言离线音色。", color = muted,
                            fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(8.dp))
                    }
                    if (busy) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(if (state.switching) "正在切换音色…" else "正在加载离线音色…",
                                    color = ink, fontSize = 13.sp)
                            }
                        }
                    }
                    state.error?.takeIf { it.isNotBlank() }?.let { error ->
                        item {
                            Text(error, color = ink, fontSize = 13.sp, lineHeight = 21.sp,
                                modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("tts-voice-error"))
                        }
                    }
                    item {
                        VoiceChoiceRow(title = "自动选择",
                            subtitle = effectiveVoice?.let { "当前使用：${it.name}" } ?: "根据本书语言自动选择离线音色",
                            selected = catalog != null && catalog.selectedVoiceId == null,
                            enabled = catalog != null && !busy, ink = ink, accent = accent,
                            modifier = Modifier.testTag("tts-voice-automatic"), onClick = { onSelect(null) })
                    }
                    items(voices, key = { it.id }) { voice ->
                        VoiceChoiceRow(title = voice.name,
                            subtitle = voice.locale.getDisplayName(Locale.getDefault()),
                            selected = catalog?.selectedVoiceId == voice.id, enabled = !busy,
                            ink = ink, accent = accent, modifier = Modifier.testTag("tts-voice-${voice.id}"),
                            onClick = { onSelect(voice.id) })
                    }
                    if (!busy && state.error.isNullOrBlank() && catalog?.voices.isNullOrEmpty()) {
                        item {
                            Text("暂无可用的离线音色。可在系统语音设置中安装本书语言的语音数据，然后重试。",
                                color = muted, fontSize = 13.sp, lineHeight = 21.sp,
                                modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("tts-voice-empty"))
                        }
                    }
                    item {
                        TextButton(onClick = onVoiceSettings, modifier = Modifier.fillMaxWidth()
                            .testTag("tts-voice-settings")) {
                            Text("系统语音设置", color = accent, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRetry, enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("tts-voice-retry")) {
                        Text("重新加载", color = if (busy) muted else accent, fontSize = 13.sp)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).testTag("tts-voice-done")) {
                        Text("完成", color = accent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 将整行作为单选热区；内部单选钮共享选中与禁用状态，避免重复点击入口。 */
@Composable
private fun VoiceChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    ink: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = accent, unselectedColor = ink.copy(alpha = 0.5f),
                disabledSelectedColor = accent.copy(alpha = 0.5f), disabledUnselectedColor = ink.copy(alpha = 0.25f)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = ink, fontSize = 14.sp, lineHeight = 21.sp)
            Text(subtitle, color = ink.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}
