package dev.yourstream.core.diagnostics

import dev.yourstream.core.common.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private val clock = Clock { now }

    @Test
    fun redactsSecrets() {
        val r = Redactor.redact(
            "GET https://api.example.org/3/movie?api_key=abcdef123456&q=leo phone +491701234567 " +
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secret {\"api_hash\":\"0123456789abcdef0123456789abcdef\"} login code 12345 " +
                "hash 0123456789abcdef0123456789abcdef",
        )
        listOf("abcdef123456", "1701234567", "eyJhbGciOiJIUzI1NiJ9", "0123456789abcdef0123456789abcdef", "12345 ").forEach {
            assertFalse(it in r, "\"$it\" leaked in: $r")
        }
        assertTrue("q=leo" in r, "ordinary parameters stay")
        assertTrue("+49" in r && "67" in r, "phone prefix/suffix kept for recognition")
        assertEquals("Leo.2023.1080p.mkv 700000000 bytes, S01E05", Redactor.redact("Leo.2023.1080p.mkv 700000000 bytes, S01E05"), "ordinary text untouched")
    }

    @Test
    fun fileLogRotatesAndRedacts() {
        val log = RotatingFileLog(tmp.root, "t", maxBytes = 100, maxFiles = 3)
        repeat(30) { log.append("line %02d token=supersecret".format(it)) }
        log.flush()
        assertTrue(log.files().size <= 3)
        assertTrue(log.sizeBytes() <= 3 * 100)
        val text = log.readAll()
        assertFalse("supersecret" in text)
        assertEquals("line 29 token=***", log.tail(1).single(), "newest last")
        assertTrue("line 00" !in text, "oldest rotated away")
        log.clear(); assertTrue(log.files().isEmpty())
    }

    @Test
    fun fileLogNeverThrows() {
        val notADir = tmp.newFile("blocker")
        val log = RotatingFileLog(File(notADir, "sub"), "x")
        log.append("hello"); log.flush()
        assertTrue(log.failures > 0)
        assertEquals("", log.readAll())
    }

    @Test
    fun journalCountsPersistsAndReloads() {
        val file = RotatingFileLog(tmp.root, "events")
        val j = EventJournal(clock, file)
        j.info(DiagnosticCategory.STARTUP, "started")
        j.warn(DiagnosticCategory.NETWORK, "offline")
        j.error(DiagnosticCategory.METADATA, "tmdb format changed", IllegalStateException("bad json", RuntimeException("root")))
        assertEquals(mapOf(DiagnosticCategory.NETWORK to 1, DiagnosticCategory.METADATA to 1), j.problemCounts())
        assertEquals(1, j.errorCount())
        assertEquals("tmdb format changed", j.recent(1).single().message)
        assertTrue(j.recent(1).single().error!!.contains("cause RuntimeException: root"))
        assertEquals(2, j.recent(minSeverity = Severity.WARNING).size)
        file.flush()
        // After a restart the previous events are readable.
        val again = EventJournal(clock, RotatingFileLog(tmp.root, "events"))
        assertEquals(listOf("tmdb format changed", "offline", "started"), again.recent().map { it.message })
        assertTrue(again.problemCounts().isEmpty(), "counts are per run")
    }

    @Test
    fun crashStoreAndCrashLoopDetection() {
        val store = CrashStore(File(tmp.root, "crashes"), clock, keep = 3)
        repeat(5) { now += 1000; store.write("main", IllegalStateException("boom $it password=hunter2"), mapOf("version" to "1.0"), listOf("log a")) }
        val list = store.list()
        assertEquals(3, list.size, "only the newest are kept")
        assertEquals("IllegalStateException: boom 4 password=***", list.first().summary)
        val text = store.read(list.first())
        assertTrue("--- stack trace ---" in text && "version: 1.0" in text && "hunter2" !in text)

        val guard = StartupGuard(File(tmp.root, "guard"), clock, threshold = 3)
        // Three crashes in the last 10 minutes → safe mode.
        assertTrue(guard.begin(list).safeMode)
        store.clear()
        guard.markStable()
        assertFalse(guard.begin(emptyList()).safeMode)
        // Starts that die before becoming stable (native crash, kill) are counted too.
        assertEquals(1, guard.begin(emptyList()).earlyDeaths)
        assertEquals(2, guard.begin(emptyList()).earlyDeaths)
        assertTrue(guard.begin(emptyList()).safeMode)
        guard.markStable()
        assertFalse(guard.begin(emptyList()).safeMode)
        guard.markStable()
        guard.forceSafeMode(true)
        assertTrue(guard.begin(emptyList()).let { it.safeMode && it.forcedSafeMode })
        guard.forceSafeMode(false); guard.markStable()
        assertFalse(guard.begin(emptyList()).safeMode)
    }

    @Test
    fun runnerSurvivesBrokenChecks() = runTest {
        val journal = EventJournal(clock)
        fun check(n: String, block: suspend () -> CheckOutcome) = object : HealthCheck {
            override val name = n
            override val category = DiagnosticCategory.OTHER
            override suspend fun check() = block()
        }
        val results = DiagnosticsRunner(
            listOf(
                check("fine") { ok("all good") },
                check("throws") { error("kaput") },
                check("hangs") { delay(60_000); ok("never") },
                check("warn") { warning("slow", "detail token=abc") },
            ),
            journal, timeoutMs = 1_000,
        ).run()
        val byName = results.associateBy { it.name }
        assertEquals(CheckStatus.OK, byName.getValue("fine").status)
        assertEquals(CheckStatus.FAILED, byName.getValue("throws").status)
        assertTrue("kaput" in byName.getValue("throws").summary)
        assertEquals("no answer within 1 s", byName.getValue("hangs").summary)
        assertEquals(listOf("detail token=***"), byName.getValue("warn").details)
        assertEquals(setOf("throws", "hangs"), results.take(2).map { it.name }.toSet(), "failures first")
        assertEquals(CheckStatus.FAILED, results.first().status)
        assertEquals(2, journal.recent(minSeverity = Severity.WARNING).size)

        val report = DiagnosticReport.format(mapOf("version" to "1.0"), results, journal.recent(), emptyList(), listOf("api_key=xyz"))
        assertTrue("[FAILED] OTHER/throws" in report && "api_key=***" in report)
    }
}

class PlaybackSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun savedSessionSurvivesUntilClearedAndExpires() {
        val file = File(tmp.root, "s/session.properties")
        val store = PlaybackSessionStore(file, maxAgeMs = 1_000)
        assertEquals(null, store.interrupted(0))
        val s = PlaybackSession(7, 70, 700, "Ben 10 S01E03", 600_000, 1_320_000, 100)
        store.save(s)
        // A new store instance = the next process.
        assertEquals(s, PlaybackSessionStore(file, maxAgeMs = 1_000).interrupted(500))
        assertEquals(null, store.interrupted(5_000), "too old to offer")
        store.save(s.copy(episodeId = null, mediaId = null, positionMs = 1))
        assertEquals(null, store.read()!!.episodeId)
        store.clear()
        assertEquals(null, store.read())
        file.writeText("garbage\u0000\u0001")
        assertEquals(null, store.read(), "damaged file = no session, no crash")
    }
}
