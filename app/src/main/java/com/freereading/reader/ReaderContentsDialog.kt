package com.freereading.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collect

/** 每次打开按当时的阅读位置定位并标记当前章节；用户浏览目录后不再自动抢回位置。 */
@Composable
internal fun ReaderContentsDialog(
    entries: List<ContentsEntry>,
    currentIndex: Int?,
    paper: Color,
    ink: Color,
    accent: Color,
    onDismiss: () -> Unit,
    onSelect: (ContentsEntry) -> Unit,
) {
    val initialIndex = remember {
        currentIndex?.takeIf { entries.isNotEmpty() }?.coerceIn(entries.indices)
    }
    var openingIndex by remember { mutableStateOf(initialIndex) }
    var userBrowsed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex ?: 0)
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) {
        if (dragging) userBrowsed = true
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (openingIndex == null && (index != 0 || offset != 0)) userBrowsed = true
            }
    }
    LaunchedEffect(currentIndex, entries.size) {
        // 锚点解析可能晚于弹窗打开；仅补定位一次，且不能覆盖用户已经进行的浏览。
        if (openingIndex == null && currentIndex != null && entries.isNotEmpty()) {
            val resolvedIndex = currentIndex.coerceIn(entries.indices)
            val untouched = !userBrowsed && !listState.isScrollInProgress &&
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            openingIndex = resolvedIndex
            if (untouched) listState.scrollToItem(resolvedIndex)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = paper,
        modifier = Modifier.testTag("reader-contents-dialog"),
        title = { Text("目录", color = ink, fontFamily = FontFamily.Serif) },
        text = {
            LazyColumn(state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("reader-contents-list")) {
                // EPUB 目录中的资源和锚点可能重复，使用展平索引保证列表 key 唯一。
                itemsIndexed(entries, key = { index, _ -> index }) { index, entry ->
                    val isCurrent = index == openingIndex
                    TextButton(onClick = { onSelect(entry) }, modifier = Modifier.fillMaxWidth()
                        .padding(start = (entry.depth.coerceIn(0, 6) * 14).dp)
                        .background(if (isCurrent) accent.copy(alpha = 0.10f) else Color.Transparent,
                            RoundedCornerShape(8.dp))
                        .testTag("reader-contents-item-$index")
                        .semantics {
                            selected = isCurrent
                            if (isCurrent) stateDescription = "当前章节"
                        }) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(entry.link.title?.takeIf(String::isNotBlank) ?: entry.fallbackTitle,
                                color = if (isCurrent) accent else ink, fontSize = 14.sp, lineHeight = 22.sp)
                            if (isCurrent) Text("当前章节", color = accent, fontSize = 11.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("reader-contents-close")) {
                Text("关闭", color = accent)
            }
        },
    )
}
