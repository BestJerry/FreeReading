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

/** One instance per started navigator view; call its methods on that view's UI dispatcher. */
@MainThread
internal class AudioHighlightSynchronizer(
    scope: CoroutineScope,
    private val apply: suspend (List<Decoration>) -> Unit,
    private val navigate: (Locator) -> Unit,
    private val canNavigate: (Target) -> Boolean,
) {
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
                    // A canceled previous owner can leave its DOM behind while Readium's cache
                    // already names a different ID. Only group.clear() removes those orphan IDs.
                    apply(emptyList())
                    currentCoroutineContext().ensureActive()
                    clearPreviousSession = false
                    // Updates received during the clear are already represented by latestTarget.
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

                // Readium updates its cached target before suspending to calculate the diff.
                // Finish each apply, then process the latest request; never cancel an apply to
                // supersede it, or the cache can claim an update whose script was never sent.
                apply(decorations)
                currentCoroutineContext().ensureActive()
                if (requestRevision != revision || target == null || !pendingNavigation || !canNavigate(target)) continue

                // Consume before calling out: go() may synchronously produce a page-loaded event.
                // A new request issued by navigate() must also retain its own navigation intent.
                pendingNavigation = false
                navigate(target.locator)
            }
        }.invokeOnCompletion { updates.close() }
    }

    fun request(target: Target?, navigate: Boolean = true) {
        pendingNavigation = target != null && (navigate || (target == latestTarget && pendingNavigation))
        latestTarget = target
        revision += 1
        if (ready) updates.trySend(Unit)
    }

    /** Loading/reloading only requests a fresh drawing; it does not invent a navigation intent. */
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
