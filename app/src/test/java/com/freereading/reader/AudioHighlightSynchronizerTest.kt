package com.freereading.reader

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorationChange
import org.readium.r2.navigator.changesByHref
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AudioHighlightSynchronizerTest {
    @Test fun requestsBeforeReadinessReplayOnlyTheLatestSentence() = runTest {
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        val synchronizer = AudioHighlightSynchronizer(backgroundScope, { applied += it }, { navigated += it }, { true })
        val first = target(0)
        val latest = target(2)

        synchronizer.request(first)
        synchronizer.request(latest)
        runCurrent()
        assertTrue(applied.isEmpty())
        synchronizer.onPageLoaded()
        runCurrent()

        assertTrue(applied.first().isEmpty())
        assertEquals(listOf(latest.locator), applied.filter { it.isNotEmpty() }.map { it.single().locator })
        assertEquals(listOf(latest.locator), navigated)
    }

    @Test fun pageReloadChangesTheDecorationIdWithoutRepeatingNavigation() = runTest {
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        val synchronizer = AudioHighlightSynchronizer(backgroundScope, { applied += it }, { navigated += it }, { true })
        val current = target(4)

        synchronizer.request(current)
        synchronizer.onPageLoaded()
        runCurrent()
        synchronizer.onPageLoaded()
        runCurrent()

        assertEquals(3, applied.size)
        assertTrue(applied[0].isEmpty())
        assertNotEquals(applied[1].single().id, applied[2].single().id)
        assertEquals(listOf(current.locator), navigated)
        val changes = applied[1].changesByHref(applied[2]).values.flatten()
        assertTrue(changes.any { it is DecorationChange.Removed })
        assertTrue(changes.any { it is DecorationChange.Added })
    }

    @Test fun aSlowApplyFinishesBeforeTheLatestRequestAndNeverNavigatesToTheOldSentence() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        var applying = 0
        var maxApplying = 0
        var firstDraw = true
        val synchronizer = AudioHighlightSynchronizer(backgroundScope, { decorations ->
            applying += 1
            maxApplying = maxOf(maxApplying, applying)
            try {
                if (decorations.isNotEmpty() && firstDraw) {
                    firstDraw = false
                    releaseFirst.await()
                }
                applied += decorations
            } finally {
                applying -= 1
            }
        }, { navigated += it }, { true })
        val first = target(0)
        val skipped = target(1)
        val latest = target(2)

        synchronizer.request(first)
        synchronizer.onPageLoaded()
        runCurrent()
        synchronizer.request(skipped)
        synchronizer.request(latest)
        runCurrent()
        assertEquals(1, applying)
        releaseFirst.complete(Unit)
        runCurrent()

        assertEquals(1, maxApplying)
        assertTrue(applied.first().isEmpty())
        assertEquals(listOf(first.locator, latest.locator), applied.filter { it.isNotEmpty() }.map { it.single().locator })
        assertEquals(listOf(latest.locator), navigated)
    }

    @Test fun clearingDuringAnApplyEndsWithNoHighlightAndNoLateNavigation() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        val synchronizer = AudioHighlightSynchronizer(backgroundScope, {
            if (it.isNotEmpty()) releaseFirst.await()
            applied += it
        }, { navigated += it }, { true })

        synchronizer.request(target(0))
        synchronizer.onPageLoaded()
        runCurrent()
        synchronizer.request(null)
        releaseFirst.complete(Unit)
        runCurrent()
        synchronizer.onPageLoaded()
        runCurrent()

        assertEquals(4, applied.size)
        assertTrue(applied[0].isEmpty())
        assertTrue(applied[1].isNotEmpty())
        assertTrue(applied[2].isEmpty())
        assertTrue(applied.last().isEmpty())
        assertTrue(navigated.isEmpty())
    }

    @Test fun navigationPermissionIsCheckedAfterTheApplyWhileHighlightStillUpdates() = runTest {
        val release = CompletableDeferred<Unit>()
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        var follow = true
        val synchronizer = AudioHighlightSynchronizer(backgroundScope, {
            if (it.isNotEmpty()) release.await()
            applied += it
        }, { navigated += it }, { follow })
        val current = target(0)

        synchronizer.request(current)
        synchronizer.onPageLoaded()
        runCurrent()
        follow = false
        release.complete(Unit)
        runCurrent()

        assertTrue(applied.first().isEmpty())
        assertEquals(current.locator, applied.last().single().locator)
        assertTrue(navigated.isEmpty())
    }

    @Test fun aDifferentTargetWithoutNavigationDoesNotInheritAnEarlierNavigationIntent() = runTest {
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        val synchronizer = AudioHighlightSynchronizer(backgroundScope, { applied += it }, { navigated += it }, { true })
        val latest = target(1)

        synchronizer.request(target(0))
        synchronizer.request(latest, navigate = false)
        synchronizer.onPageLoaded()
        runCurrent()
        synchronizer.onPageLoaded()
        runCurrent()

        assertTrue(applied.first().isEmpty())
        assertEquals(listOf(latest.locator, latest.locator), applied.filter { it.isNotEmpty() }.map { it.single().locator })
        assertTrue(navigated.isEmpty())
    }

    @Test fun redrawDuringAPendingApplyKeepsItsUnconsumedNavigationIntent() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        val synchronizer = AudioHighlightSynchronizer(backgroundScope, {
            if (it.isNotEmpty() && applied.size == 1) releaseFirst.await()
            applied += it
        }, { navigated += it }, { true })
        val current = target(0)

        synchronizer.request(current)
        synchronizer.onPageLoaded()
        runCurrent()
        synchronizer.onPageLoaded()
        synchronizer.request(current, navigate = false)
        releaseFirst.complete(Unit)
        runCurrent()

        assertEquals(3, applied.size)
        assertTrue(applied[0].isEmpty())
        assertNotEquals(applied[1].single().id, applied[2].single().id)
        assertEquals(listOf(current.locator), navigated)
    }

    @Test fun aSynchronousPageLoadedCallbackCannotFormANavigationLoop() = runTest {
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        lateinit var synchronizer: AudioHighlightSynchronizer
        synchronizer = AudioHighlightSynchronizer(backgroundScope, { applied += it }, {
            navigated += it
            synchronizer.onPageLoaded()
        }, { true })
        val current = target(0)

        synchronizer.request(current)
        synchronizer.onPageLoaded()
        runCurrent()

        assertEquals(3, applied.size)
        assertTrue(applied.first().isEmpty())
        assertEquals(listOf(current.locator), navigated)
        // A real later transport request to the same sentence gets a new navigation intent.
        synchronizer.request(current)
        runCurrent()
        assertEquals(2, navigated.size)
        assertEquals(5, applied.size)
    }

    @Test fun changingToAnotherResourceRemovesTheOldResourceDecoration() = runTest {
        val applied = mutableListOf<List<Decoration>>()
        val synchronizer = AudioHighlightSynchronizer(backgroundScope, { applied += it }, {}, { true })

        synchronizer.request(target(0, "first.xhtml"))
        synchronizer.onPageLoaded()
        runCurrent()
        synchronizer.request(target(1, "second.xhtml"))
        runCurrent()

        assertTrue(applied[0].isEmpty())
        val previous = applied[1].single()
        val latest = applied[2].single()
        val changes = applied[1].changesByHref(applied[2])
        assertEquals(listOf(DecorationChange.Removed(previous.id)), changes[previous.locator.href])
        assertEquals(listOf(DecorationChange.Added(latest)), changes[latest.locator.href])
    }

    @Test fun aNewSynchronizerSessionForTheSameSentenceAlwaysProducesAFreshDiff() = runTest {
        val old = mutableListOf<List<Decoration>>()
        val fresh = mutableListOf<List<Decoration>>()
        val current = target(0)
        val previous = AudioHighlightSynchronizer(backgroundScope, { old += it }, {}, { true })
        previous.request(current)
        previous.onPageLoaded()
        runCurrent()
        val next = AudioHighlightSynchronizer(backgroundScope, { fresh += it }, {}, { true })
        next.request(current)
        next.onPageLoaded()
        runCurrent()

        assertTrue(old.first().isEmpty())
        assertTrue(fresh.first().isEmpty())
        assertNotEquals(old.last().single().id, fresh.last().single().id)
        val changes = old.last().changesByHref(fresh.last()).values.flatten()
        assertTrue(changes.any { it is DecorationChange.Removed })
        assertTrue(changes.any { it is DecorationChange.Added })
    }

    @Test fun endingTheViewOwnerPreventsNavigationEvenIfItsApplyCompletesNonCancellably() = runTest {
        val release = CompletableDeferred<Unit>()
        val ownerJob = Job(backgroundScope.coroutineContext[Job])
        val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        val synchronizer = AudioHighlightSynchronizer(owner, { decorations ->
            if (decorations.isNotEmpty()) withContext(NonCancellable) { release.await() }
            applied += decorations
        }, { navigated += it }, { true })

        synchronizer.request(target(0))
        synchronizer.onPageLoaded()
        runCurrent()
        ownerJob.cancel()
        release.complete(Unit)
        runCurrent()
        synchronizer.onPageLoaded()
        runCurrent()

        assertEquals(2, applied.size)
        assertTrue(applied[0].isEmpty())
        assertTrue(applied[1].isNotEmpty())
        assertTrue(navigated.isEmpty())
        assertTrue(ownerJob.isCompleted)
    }

    @Test fun theFirstTargetIsReadAfterTheInitialClearCompletes() = runTest {
        val releaseClear = CompletableDeferred<Unit>()
        val applied = mutableListOf<List<Decoration>>()
        val navigated = mutableListOf<Locator>()
        val synchronizer = AudioHighlightSynchronizer(backgroundScope, {
            if (it.isEmpty() && applied.isEmpty()) releaseClear.await()
            applied += it
        }, { navigated += it }, { true })
        val latest = target(2)

        synchronizer.request(target(0))
        synchronizer.onPageLoaded()
        runCurrent()
        synchronizer.request(target(1))
        synchronizer.request(latest)
        releaseClear.complete(Unit)
        runCurrent()

        assertEquals(2, applied.size)
        assertTrue(applied.first().isEmpty())
        assertEquals(latest.locator, applied.last().single().locator)
        assertEquals(listOf(latest.locator), navigated)
    }

    @Test fun restartingAfterACanceledDiffClearsDomIdsMissingFromReadiumsCache() = runTest {
        val ownerJob = Job(backgroundScope.coroutineContext[Job])
        val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
        val first = target(0)
        val interrupted = target(1)
        val latest = target(2)
        var cache = emptyList<Decoration>()
        val dom = LinkedHashMap<String, Decoration>()
        val enteredInterruptedApply = CompletableDeferred<Unit>()
        val neverFinishInterrupted = CompletableDeferred<Unit>()
        val navigated = mutableListOf<Locator>()
        val applyLikeReadium: suspend (List<Decoration>) -> Unit = { next ->
            val previous = cache
            // Match Readium: cache is replaced before the suspending diff calculation.
            cache = next
            if (next.singleOrNull()?.locator == interrupted.locator) {
                enteredInterruptedApply.complete(Unit)
                neverFinishInterrupted.await()
            }
            if (next.isEmpty()) {
                // An empty target sends group.clear(), regardless of the cached IDs.
                dom.clear()
            } else {
                val nextIds = next.map { it.id }.toSet()
                previous.filter { it.id !in nextIds }.forEach { dom.remove(it.id) }
                next.forEach { dom[it.id] = it }
            }
        }
        val previous = AudioHighlightSynchronizer(owner, applyLikeReadium, { navigated += it }, { true })
        previous.request(first)
        previous.onPageLoaded()
        runCurrent()
        assertEquals(listOf(first.locator), dom.values.map { it.locator })

        previous.request(interrupted)
        runCurrent()
        assertTrue(enteredInterruptedApply.isCompleted)
        assertEquals(interrupted.locator, cache.single().locator)
        ownerJob.cancel()
        runCurrent()
        assertEquals(listOf(first.locator), dom.values.map { it.locator })

        val next = AudioHighlightSynchronizer(backgroundScope, applyLikeReadium, { navigated += it }, { true })
        next.request(latest)
        next.onPageLoaded()
        runCurrent()

        assertEquals(latest.locator, cache.single().locator)
        assertEquals(listOf(latest.locator), dom.values.map { it.locator })
        assertEquals(listOf(first.locator, latest.locator), navigated)
    }

    private fun target(index: Int, href: String = "chapter.xhtml") = AudioHighlightSynchronizer.Target(
        "original-book", index,
        requireNotNull(Locator.fromJSON(JSONObject().apply {
            put("href", href)
            put("type", "application/xhtml+xml")
            put("locations", JSONObject().put("cssSelector", "#paragraph"))
            put("text", JSONObject().put("highlight", "原创第${index}句。"))
        })),
    )
}
