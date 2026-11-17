package com.freereading.reader

import androidx.annotation.MainThread
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator

/**
 * 串行更新朗读句高亮并在允许时跳到原文页，合并快速到达的中间状态。
 * 每个已启动的导航视图使用独立实例，所有调用均在该视图的 UI 调度器执行。
 */
@MainThread
internal class AudioHighlightSynchronizer(
    scope: CoroutineScope,
    private val apply: suspend (List<Decoration>) -> Unit,
    private val navigate: (Locator) -> Unit,
    private val canNavigate: (Target) -> Boolean,
) {
    /** 高亮目标同时携带书籍 ID 与句子索引，供调用方校验它仍属于当前播放位置。 */
    data class Target(val bookId: String, val sentenceIndex: Int, val locator: Locator)

    private val session = nextSession.incrementAndGet()
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private var latestTarget: Target? = null
    private var revision = 0L
    private var pageEpoch = 0L
    private var ready = false
    private var pendingNavigation = false

    init {
        scope.launch {
            var clearPreviousSession = true
            for (ignored in updates) {
                if (!ready) continue
                if (clearPreviousSession) {
                    // 旧视图任务取消后可能留下 DOM 标记，而 Readium 缓存已切换 ID；
                    // 必须先清空整个高亮组，才能移除缓存之外的残留标记。
                    apply(emptyList())
                    currentCoroutineContext().ensureActive()
                    clearPreviousSession = false
                    // 清理期间到达的更新已合并进 latestTarget，无需重复处理通知。
                    updates.tryReceive()
                }
                val target = latestTarget
                val requestRevision = revision
                val decorations = target?.let {
                    listOf(Decoration(
                        id = "speech-$session-${it.bookId}-${it.sentenceIndex}-$pageEpoch",
                        locator = it.locator,
                        style = Decoration.Style.Highlight(tint = 0xFFE4CB88.toInt()),
                    ))
                }.orEmpty()

                // Readium 在挂起计算差异前就更新缓存目标，因此每次 apply 都必须执行完毕。
                // 不能通过取消旧 apply 追赶新请求，否则缓存与实际发送的 DOM 脚本会不一致。
                apply(decorations)
                currentCoroutineContext().ensureActive()
                if (requestRevision != revision || target == null || !pendingNavigation || !canNavigate(target)) continue

                // 先消费本次跳页意图：go() 可能同步触发页面加载回调，
                // navigate() 中产生的新请求也必须保留它自己的跳页意图。
                pendingNavigation = false
                navigate(target.locator)
            }
        }.invokeOnCompletion { updates.close() }
    }

    /** 更新最新目标；相同目标的纯重绘不会丢失尚未执行的跳页请求，null 用于清除高亮。 */
    fun request(target: Target?, navigate: Boolean = true) {
        pendingNavigation = target != null && (navigate || (target == latestTarget && pendingNavigation))
        latestTarget = target
        revision += 1
        if (ready) updates.trySend(Unit)
    }

    /** 页面加载或重载后刷新渲染代次以重绘高亮，已有跳页意图保持不变。 */
    fun onPageLoaded() {
        ready = true
        pageEpoch += 1
        revision += 1
        updates.trySend(Unit)
    }

    private companion object {
        val nextSession = AtomicLong()
    }
}
