package com.freereading.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 绘制叠加于正文的阅读菜单和薄页脚；系统栏留白及正文手势由调用方负责。 */
@Composable
internal fun ReaderChrome(
    title: String,
    chapter: String,
    readingProgress: Float?,
    controlsVisible: Boolean,
    hasAudio: Boolean,
    followingAudio: Boolean,
    isPlaying: Boolean,
    isPreparing: Boolean,
    enabled: Boolean,
    paper: Color,
    ink: Color,
    accent: Color,
    onBack: () -> Unit,
    onContents: () -> Unit,
    onAppearance: () -> Unit,
    onListen: () -> Unit,
    onFollowToggle: () -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = ink.copy(alpha = 0.62f)
    val listeningEnabled = enabled || hasAudio
    val viewConfiguration = LocalViewConfiguration.current
    val footerViewConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            // 薄页脚的最小触摸热区不能向上扩展，否则会遮挡正文手势。
            override val minimumTouchTargetSize = DpSize(viewConfiguration.minimumTouchTargetSize.width, 0.dp)
        }
    }
    val percent = readingProgress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.let { (it * 100f).toInt() }
    val audioState = when {
        isPreparing -> "正在准备语音"
        isPlaying -> "正在播放"
        else -> "已暂停"
    }
    val followState = when {
        !followingAudio -> "已暂停跟随，$audioState"
        isPreparing -> "已开启，正在准备语音"
        isPlaying -> "播放时自动翻页"
        else -> "已开启，听书已暂停"
    }

    // 外层不能添加背景、clickable 或 pointerInput；中央透明区域必须让底层 Readium
    // 接收滑动、菜单轻触和文字选择，只有实际控件区域拦截触摸。
    Box(modifier.fillMaxSize().testTag("reader_chrome")) {
        if (controlsVisible) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(52.dp)
                    .background(paper.copy(alpha = 0.97f)).padding(horizontal = 8.dp)
                    .testTag("reader_chrome_toolbar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp).testTag("reader_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书架", tint = ink, modifier = Modifier.size(21.dp))
                }
                Text(
                    title.ifBlank { "阅读" },
                    modifier = Modifier.weight(1f).padding(start = 5.dp, end = 16.dp),
                    color = ink,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (controlsVisible) {
                Column(Modifier.fillMaxWidth().background(paper).testTag("reader_chrome_menu")) {
                    HorizontalDivider(color = ink.copy(alpha = 0.09f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        ReaderChromeMenuItem(
                            label = "目录",
                            description = "打开目录",
                            tag = "reader_contents",
                            icon = Icons.AutoMirrored.Filled.List,
                            enabled = enabled,
                            color = ink,
                            onClick = onContents,
                            modifier = Modifier.weight(1f),
                        )
                        ReaderChromeMenuItem(
                            label = "听书",
                            description = "打开听书播放器",
                            tag = "reader_listen",
                            icon = Icons.Default.Headphones,
                            enabled = listeningEnabled,
                            color = if (hasAudio) accent else ink,
                            preparing = isPreparing && hasAudio,
                            state = audioState.takeIf { hasAudio },
                            onClick = onListen,
                            modifier = Modifier.weight(1f),
                        )
                        ReaderChromeMenuItem(
                            label = "设置",
                            description = "打开阅读设置",
                            tag = "reader_appearance",
                            icon = Icons.Default.Settings,
                            enabled = enabled,
                            color = ink,
                            onClick = onAppearance,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalViewConfiguration provides footerViewConfiguration) {
                Row(
                    Modifier.fillMaxWidth().height(28.dp).background(paper.copy(alpha = 0.96f))
                        .padding(horizontal = 18.dp).testTag("reader_chrome_footer"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).fillMaxHeight().testTag("reader_controls_toggle")
                            .clickable(enabled = enabled, role = Role.Button, onClick = onToggleControls)
                            .semantics(mergeDescendants = true) {
                                contentDescription = if (controlsVisible) "收起阅读工具" else "显示阅读工具"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            chapter.ifBlank { "阅读中" },
                            modifier = Modifier.weight(1f),
                            color = muted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (percent != null) {
                            Spacer(Modifier.width(10.dp))
                            Text("全书 $percent%", color = muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1)
                        }
                    }
                    if (hasAudio) {
                        Spacer(Modifier.width(10.dp))
                        Row(
                            Modifier.fillMaxHeight().widthIn(max = 104.dp).testTag("reader_audio_follow")
                                .clickable(enabled = enabled, role = Role.Button, onClick = onFollowToggle)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = if (followingAudio) "暂停自动翻页" else "跟随朗读自动翻页"
                                    stateDescription = followState
                                }.padding(horizontal = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (followingAudio) "自动翻页" else "继续跟随",
                                color = if (enabled) accent else muted,
                                fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                                softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (hasAudio && !controlsVisible) {
                        Spacer(Modifier.width(14.dp))
                        Row(
                            Modifier.fillMaxHeight().testTag("reader_listen_shortcut")
                                .clickable(enabled = listeningEnabled, role = Role.Button, onClick = onListen)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "打开听书播放器"
                                    stateDescription = audioState
                                }.padding(horizontal = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isPreparing) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), color = accent, strokeWidth = 1.5.dp)
                            } else {
                                Icon(Icons.Default.Headphones, null, tint = accent, modifier = Modifier.size(14.dp))
                            }
                            Text("听书", color = accent, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 统一阅读菜单入口的点击、加载状态和无障碍描述。 */
@Composable
private fun ReaderChromeMenuItem(
    label: String,
    description: String,
    tag: String,
    icon: ImageVector,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preparing: Boolean = false,
    state: String? = null,
) {
    val tint = if (enabled) color else color.copy(alpha = 0.35f)
    Column(
        modifier.height(64.dp).testTag(tag).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (state != null) stateDescription = state
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (preparing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = tint, strokeWidth = 1.5.dp)
        } else {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
