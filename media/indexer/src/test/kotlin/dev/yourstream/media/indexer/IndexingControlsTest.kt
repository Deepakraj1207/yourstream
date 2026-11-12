package dev.yourstream.media.indexer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult.Outcome
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.api.model.IndexingPolicy
import dev.yourstream.media.api.model.MediaQuery
import dev.yourstream.media.indexer.engine.IndexProgressTracker
import dev.yourstream.media.indexer.work.IndexScheduler
import dev.yourstream.telegram.api.TelegramChatRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Pause, resume, cancel, explicit states and the Wi-Fi/battery policy, on the real database,
 * engine and WorkManager (test mode: work is enqueued with its real constraints, not run).
 */
@RunWith(RobolectricTestRunner::class)
class IndexingControlsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val env = IndexerTestEnv()
    private val chat = 100L
    private lateinit var scheduler: IndexScheduler
    private lateinit var indexer: DefaultMediaIndexer

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, androidx.work.Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        context.getSharedPreferences("indexing_policy", Context.MODE_PRIVATE).edit().clear().commit()
        scheduler = IndexScheduler(context)
        // Enabling chats needs Telegram; these tests enable them in the database directly.
        val noTelegram = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TelegramChatRepository::class.java)) { _, m, _ ->
            error("Telegram not used here: ${m.name}")
        } as TelegramChatRepository
        indexer = DefaultMediaIndexer(env.db, noTelegram, scheduler, env.tracker, env.clock, NoOpLogger)
    }

    @After fun tearDown() = env.close()

    private suspend fun status() = env.repository.observeChat(chat).first()!!
    private suspend fun count() = env.repository.count(MediaQuery(chatId = chat)).first()
    private fun work(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(IndexScheduler.uniqueName(chat)).get()
    private fun queued() = work().filter { !it.state.isFinished }

    private fun seed() = (1L..9L).forEach { env.telegram.add(mediaMessage(chat, it * 10, "Movie.$it.2020.1080p.mkv")) }

    @Test
    fun pausingAnIdleChatHoldsItUntilResumed() = runTest {
        env.enable(chat); seed()
        indexer.pause(chat)
        assertEquals(IndexState.PAUSED, status().state)
        assertTrue(status().paused)

        // Periodic syncs and "sync now" leave a paused chat alone.
        indexer.schedule(chat)
        indexer.scheduleAll()
        assertTrue(queued().isEmpty(), "nothing queued while paused")
        assertEquals(IndexState.PAUSED, status().state)

        // A run that was already queued ends before reading anything.
        val run = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(Outcome.CANCELLED, run.outcome)
        assertTrue(env.telegram.calls.isEmpty(), "no Telegram request while paused")

        indexer.resume(chat)
        assertFalse(status().paused)
        assertEquals(IndexState.QUEUED, status().state)
        assertEquals(1, queued().size, "resume queues the chat again")
    }

    @Test
    fun pausingARunningScanKeepsItsProgressAndResumesWithoutRescanning() = runTest {
        env.enable(chat); seed()
        env.telegram.suspendOnCall = 3
        val job = async(start = CoroutineStart.UNDISPATCHED) { env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes) }
        while (env.telegram.calls.size < 3) yield()
        assertEquals(IndexState.SCANNING, status().state, "the running state is explicit")
        indexer.pause(chat)
        job.cancel() // WorkManager stops the worker when its unique work is cancelled
        runCatching { job.await() }

        assertEquals(IndexState.PAUSED, status().state)
        val saved = count()
        assertTrue(saved > 0, "pages read before the pause are kept")

        env.telegram.suspendOnCall = null
        indexer.resume(chat)
        val resumed = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(Outcome.COMPLETED, resumed.outcome)
        assertEquals(9, count())
        assertEquals(9 - saved, resumed.mediaAdded, "nothing indexed twice")
        assertEquals(IndexState.COMPLETED, status().state)
    }

    @Test
    fun cancellingStopsOnlyThisRun() = runTest {
        env.enable(chat); seed()
        indexer.schedule(chat)
        assertEquals(IndexState.QUEUED, status().state)
        indexer.cancel(chat)
        assertEquals(IndexState.CANCELLED, status().state)
        assertTrue(queued().isEmpty())
        // Not paused: the next sync picks it up again.
        indexer.scheduleAll()
        assertEquals(IndexState.QUEUED, status().state)
        assertEquals(1, queued().size)
    }

    @Test
    fun aNetworkFailureIsRecoveringNotFailed() = runTest {
        env.enable(chat); seed()
        repeat(env.config.maxAttempts) { env.telegram.errors.addLast(AppError.Network("Connection reset")) }
        val run = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(Outcome.RETRY, run.outcome)
        val s = status()
        assertEquals(IndexState.RECOVERING, s.state, "WorkManager retries it; not shown as failed yet")
        assertTrue(s.lastError.orEmpty().contains("Connection reset"))
    }

    @Test
    fun wifiOnlyAndBatteryPolicyBecomeTheWorkConditions() = runTest {
        env.enable(chat)
        assertEquals(IndexingPolicy(wifiOnly = false, batteryAware = true), indexer.policy.value, "defaults")
        indexer.schedule(chat)
        queued().single().constraints.let {
            assertEquals(NetworkType.CONNECTED, it.requiredNetworkType)
            assertTrue(it.requiresBatteryNotLow())
        }

        indexer.setPolicy(IndexingPolicy(wifiOnly = true, batteryAware = false))
        // The waiting work was replaced under the new conditions.
        queued().single().constraints.let {
            assertEquals(NetworkType.UNMETERED, it.requiredNetworkType)
            assertFalse(it.requiresBatteryNotLow())
        }
        // Kept for workers in a fresh process.
        assertEquals(IndexingPolicy(wifiOnly = true, batteryAware = false), IndexScheduler(context).policy.value)
    }

    @Test
    fun statesLeftByAnOlderVersionOrAKilledProcessAreReadSensibly() = runTest {
        env.enable(chat)
        val dao = env.db.indexedChatDao()
        // Before v13: RUNNING = killed mid-run; PAUSED (no flag) = time budget used, continuation queued.
        dao.setState(chat, "RUNNING", null, 0)
        assertEquals(IndexState.RECOVERING, status().state)
        dao.setState(chat, "PAUSED", null, 0)
        assertEquals(IndexState.QUEUED, status().state)

        // What the start-up recovery writes.
        dao.setState(chat, IndexState.SCANNING.name, null, 0)
        dao.resetUnpausedStates(listOf("RUNNING", IndexState.SCANNING.name), IndexState.RECOVERING.name)
        assertEquals(IndexState.RECOVERING, status().state)
        // A user's pause survives the recovery.
        dao.setPaused(chat, true, 0)
        dao.setState(chat, IndexState.PAUSED.name, null, 0)
        dao.resetUnpausedStates(listOf(IndexState.PAUSED.name), IndexState.QUEUED.name)
        assertEquals(IndexState.PAUSED, status().state)
    }
}
