package dev.yourstream.desktop

import com.sun.net.httpserver.HttpServer
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.desktop.update.DesktopUpdates
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Windows updater against a stand-in for GitHub's API and release downloads (127.0.0.1). */
class DesktopUpdatesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val msi = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
    private var sums = ""
    private var tag = "v0.13.3"

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun serve(path: String, body: () -> ByteArray) = server.createContext(path) { ex ->
        val b = body()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    init {
        sums = MessageDigest.getInstance("SHA-256").digest(msi).joinToString("") { "%02x".format(it) } + " *YourStream-v0.13.3-windows-x64.msi\n"
        serve("/repos/me/app/releases/latest") {
            """{"tag_name":"$tag","body":"Notes","assets":[
              {"name":"YourStream-v0.13.3-universal.apk","browser_download_url":"${base()}/apk","size":10},
              {"name":"YourStream-v0.13.3-windows-x64.msi","browser_download_url":"${base()}/msi","size":${msi.size}},
              {"name":"SHA256SUMS-windows.txt","browser_download_url":"${base()}/sums","size":100}]}""".toByteArray()
        }
        serve("/msi") { msi }
        serve("/sums") { sums.toByteArray() }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun updates(installed: String, installed2: MutableList<File> = mutableListOf()) =
        DesktopUpdates(installed, tmp.newFolder(), NoOpLogger, installer = { installed2 += it }, apiBase = base(), repo = "me/app")

    @Test
    fun findsDownloadsChecksAndInstallsANewerRelease(): Unit = runBlocking {
        val ran = mutableListOf<File>()
        val u = updates("0.13.2", ran)
        val available = assertIs<DesktopUpdates.State.Available>(u.check())
        assertEquals("0.13.3", available.release.version)
        val ready = assertIs<DesktopUpdates.State.Ready>(u.download(available.release))
        assertTrue(ready.file.readBytes().contentEquals(msi))
        u.install(ready)
        assertEquals(listOf(ready.file), ran)
    }

    @Test
    fun sameOrOlderIsUpToDate(): Unit = runBlocking {
        assertIs<DesktopUpdates.State.UpToDate>(updates("0.13.3").check())
        assertIs<DesktopUpdates.State.UpToDate>(updates("0.14.0").check())
    }

    @Test
    fun aDamagedDownloadIsDeletedAndNothingInstalls(): Unit = runBlocking {
        sums = "0".repeat(64) + " *YourStream-v0.13.3-windows-x64.msi\n"
        val ran = mutableListOf<File>()
        val u = updates("0.13.2", ran)
        val available = assertIs<DesktopUpdates.State.Available>(u.check())
        val failed = assertIs<DesktopUpdates.State.Failed>(u.download(available.release))
        assertTrue("damaged" in failed.message)
        assertFalse(File(tmp.root.listFiles()!!.first(), "YourStream-v0.13.3-windows-x64.msi").exists())
        assertTrue(ran.isEmpty())
    }

    @Test
    fun versionsAndChecksumFiles() {
        assertTrue(DesktopUpdates.newer("0.13.10", "0.13.9"))
        assertTrue(DesktopUpdates.newer("v1.0.0", "0.99.9"))
        assertFalse(DesktopUpdates.newer("0.13.2", "0.13.2"))
        val parsed = DesktopUpdates.parseChecksums("${"a".repeat(64)} *YourStream-v1-windows-x64.msi\n${"B".repeat(64)}  other.apk\n")
        assertEquals(mapOf("YourStream-v1-windows-x64.msi" to "a".repeat(64), "other.apk" to "b".repeat(64)), parsed)
    }
}
