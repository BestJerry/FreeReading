package com.freereading.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freereading.audio.PlaybackSnapshot
import com.freereading.audio.chapterProgressFraction
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Presentation only: closing this surface leaves the reader and the playback service intact. */
@Composable
internal fun ListeningPlayerScreen(
    bookTitle: String,
    author: String,
    coverPath: String?,
    snapshot: PlaybackSnapshot,
    activeBook: Boolean,
    enabled: Boolean,
    followAudio: Boolean,
    paper: Color,
    ink: Color,
    accent: Color,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onFromReading: () -> Unit,
    onFollowToggle: () -> Unit,
    onLocate: () -> Unit,
    onStop: () -> Unit,
    onVoiceSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = ink.copy(alpha = 0.62f)
    val controlsEnabled = activeBook
    val readingActionsEnabled = enabled && activeBook
    val title = bookTitle.ifBlank { if (activeBook) snapshot.bookTitle else "未命名书籍" }.ifBlank { "未命名书籍" }
    val fraction = if (activeBook) snapshot.chapterProgressFraction else 0f
    val progressLabel = if (activeBook && snapshot.chapterSentenceCount > 0) "${(fraction * 100).toInt()}%" else "—"
    val playing = activeBook && snapshot.isPlaying
    val preparing = activeBook && snapshot.isPreparing
    var speedMenu by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = accent, onPrimary = paper,
        background = paper, onBackground = ink,
        surface = paper, onSurface = ink, onSurfaceVariant = muted,
        surfaceContainer = paper, surfaceContainerLow = paper, surfaceContainerLowest = paper,
        surfaceContainerHigh = paper, surfaceContainerHighest = paper,
        outline = ink.copy(alpha = 0.2f),
    )) {
        Column(modifier.fillMaxSize().background(paper).windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("listening-screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "返回阅读" }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(20.dp), tint = ink)
                    Spacer(Modifier.width(8.dp))
                    Text("返回阅读", color = ink)
                }
                Spacer(Modifier.weight(1f))
                Text("听书", color = muted, fontFamily = FontFamily.Serif, fontSize = 17.sp,
                    modifier = Modifier.padding(end = 12.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ListeningCover(title, coverPath, paper, ink, accent,
                            Modifier.width(minOf(160.dp, maxWidth * 0.52f)).aspectRatio(0.7f))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(title, color = ink, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 32.sp,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (author.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(author, color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (activeBook) snapshot.chapterTitle.ifBlank { "正文" } else "从阅读位置开始",
                        color = accent, fontSize = 13.sp, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))
                    Text(if (activeBook && snapshot.text.isNotBlank()) snapshot.text else "点击播放，从阅读位置开始听书。",
                        color = muted, fontSize = 14.sp, lineHeight = 23.sp, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("本章进度", color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(progressLabel, color = ink, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(9.dp))
                    LinearProgressIndicator(progress = { fraction }, color = accent,
                        trackColor = ink.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth().height(3.dp).semantics {
                            contentDescription = "章节播放进度"
                            stateDescription = progressLabel
                        })
                    Spacer(Modifier.height(10.dp))
                    Text(when { preparing -> "正在准备语音…"; playing -> "正在播放"; activeBook -> "已暂停"; else -> "准备好开始听书" },
                        color = muted, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        ListeningSeekButton(Icons.Default.FastRewind, "快退15秒", controlsEnabled, ink) { onSeek(-15_000L) }
                        FilledIconButton(onClick = onToggle, enabled = enabled || activeBook, modifier = Modifier.size(80.dp)
                            .semantics { contentDescription = if (playing) "暂停听书" else "开始或继续听书" },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = accent, contentColor = paper,
                                disabledContainerColor = ink.copy(alpha = 0.08f), disabledContentColor = ink.copy(alpha = 0.3f))) {
                            if (preparing) CircularProgressIndicator(Modifier.size(30.dp), color = paper, strokeWidth = 2.dp)
                            else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(38.dp))
                        }
                        ListeningSeekButton(Icons.Default.FastForward, "快进15秒", controlsEnabled, ink) { onSeek(15_000L) }
                    }
                    Box {
                        TextButton(onClick = { speedMenu = true }, enabled = controlsEnabled,
                            modifier = Modifier.semantics { contentDescription = "播放速度" }) {
                            Text("${listeningSpeedLabel(snapshot.speed)} 倍速", fontSize = 13.sp)
                        }
                        DropdownMenu(expanded = speedMenu && controlsEnabled, onDismissRequest = { speedMenu = false }) {
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                                DropdownMenuItem(text = { Text(listeningSpeedLabel(speed), color = ink) },
                                    onClick = { speedMenu = false; onSpeed(speed) })
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Surface(color = ink.copy(alpha = 0.035f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("跟随原文", color = ink, fontSize = 14.sp)
                                Text("朗读时自动定位正文", color = muted, fontSize = 11.sp)
                            }
                            Switch(checked = followAudio, onCheckedChange = { onFollowToggle() }, enabled = readingActionsEnabled,
                                modifier = Modifier.semantics { contentDescription = "跟随原文" },
                                colors = SwitchDefaults.colors(checkedThumbColor = paper, checkedTrackColor = accent,
                                    checkedBorderColor = accent, uncheckedThumbColor = muted,
                                    uncheckedTrackColor = Color.Transparent, uncheckedBorderColor = ink.copy(alpha = 0.3f)))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onLocate, enabled = readingActionsEnabled,
                            border = BorderStroke(1.dp, ink.copy(alpha = 0.14f)),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "定位正在朗读的原文" }) {
                            Icon(Icons.Default.MyLocation, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("定位原文", fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                        OutlinedButton(onClick = onFromReading, enabled = enabled,
                            border = BorderStroke(1.dp, ink.copy(alpha = 0.14f)),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "从阅读位置开始听书" }) {
                            Text("从阅读处听", fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }
                    if (activeBook && snapshot.error != null) {
                        Spacer(Modifier.height(18.dp))
                        Surface(color = ink.copy(alpha = 0.04f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(snapshot.error, color = ink, fontSize = 13.sp, lineHeight = 21.sp)
                                if (snapshot.needsVoiceData) {
                                    TextButton(onClick = onVoiceSettings, enabled = activeBook,
                                        modifier = Modifier.semantics { contentDescription = "安装或设置离线语音" }) {
                                        Text("安装或设置离线语音", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onStop, enabled = controlsEnabled,
                        modifier = Modifier.semantics { contentDescription = "结束听书" }) {
                        Text("结束听书", color = if (controlsEnabled) muted else muted.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ListeningSeekButton(icon: ImageVector, description: String, enabled: Boolean, ink: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(64.dp).semantics { contentDescription = description }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(29.dp), tint = ink.copy(alpha = if (enabled) 1f else 0.25f))
            Text("15 秒", color = ink.copy(alpha = if (enabled) 0.62f else 0.25f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ListeningCover(title: String, coverPath: String?, paper: Color, ink: Color, accent: Color, modifier: Modifier) {
    val cover by produceState<ImageBitmap?>(null, coverPath) {
        value = null
        value = withContext(Dispatchers.IO) {
            coverPath?.let { path ->
                runCatching {
                    if (!File(path).isFile) return@runCatching null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 800 && sample <= Int.MAX_VALUE / 2) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 11.dp, bottomEnd = 11.dp, bottomStart = 3.dp)
    Box(modifier.shadow(16.dp, shape).clip(shape)
        .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.75f))))
        .clearAndSetSemantics { contentDescription = "《$title》封面" }) {
        val image = cover
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 15.dp, top = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Text("FREE READING", color = paper.copy(alpha = 0.7f), fontSize = 8.sp, letterSpacing = 1.4.sp)
                Text(title, color = paper, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 26.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = paper.copy(alpha = 0.7f), modifier = Modifier.size(23.dp))
            }
        }
        Box(Modifier.width(6.dp).fillMaxSize().background(ink.copy(alpha = 0.1f)))
    }
}

private fun listeningSpeedLabel(speed: Float): String =
    String.format(Locale.ROOT, "%.2f", if (speed.isFinite()) speed.coerceIn(0.5f, 2f) else 1f)
        .trimEnd('0').trimEnd('.') + "×"
