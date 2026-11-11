package dev.yourstream.ui.core

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageRoutingTest {
    private val origin = MockWebServer()
    private val proxy = MockWebServer()
    private var clock = 0L

    @Before fun start() { origin.start(); proxy.start() }
    @After fun stop() { origin.close(); proxy.close() }

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .addInterceptor(ImageRouting({ clock }) { url -> listOf(proxy.url("/p?url=" + url.encodedPath).toString()) })
        .build()

    private fun image() = MockResponse.Builder().code(200).setHeader("Content-Type", "image/jpeg").body("JPEG").build()
    private fun get(c: OkHttpClient, path: String) = c.newCall(Request.Builder().url(origin.url(path)).build()).execute()

    @Test
    fun reachableImagesComeDirectly() {
        origin.enqueue(image())
        get(client(), "/t/p/w342/a.jpg").use { assertEquals(200, it.code); assertEquals("JPEG", it.body.string()) }
        assertEquals(0, proxy.requestCount)
    }

    @Test
    fun blockPagesAndDeadHostsGoThroughTheProxyAndAreRemembered() {
        val c = client()
        // An ISP block page: 200, but HTML instead of an image.
        origin.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body("<html>blocked</html>").build())
        proxy.enqueue(image())
        get(c, "/t/p/w342/a.jpg").use { assertEquals(200, it.code); assertEquals("JPEG", it.body.string()) }
        assertEquals("/p?url=/t/p/w342/a.jpg", proxy.takeRequest().target)

        // The host is remembered: the next poster goes straight to the proxy.
        proxy.enqueue(image())
        get(c, "/t/p/w342/b.jpg").use { assertEquals(200, it.code) }
        assertEquals(1, origin.requestCount)

        // After 30 minutes the host is tried again.
        clock += 31 * 60_000L
        origin.enqueue(image())
        get(c, "/t/p/w342/c.jpg").use { assertEquals(200, it.code) }
        assertEquals(2, origin.requestCount)
    }

    @Test
    fun aHostThatCannotBeReachedIsProxied() {
        val dead = "http://127.0.0.1:1/t/p/w342/a.jpg" // nothing listens on port 1
        proxy.enqueue(image())
        client().newCall(Request.Builder().url(dead).build()).execute().use { assertEquals(200, it.code) }
        assertEquals(1, proxy.requestCount)
    }

    @Test
    fun missingImagesAreNotProxied() {
        origin.enqueue(MockResponse.Builder().code(404).build())
        get(client(), "/t/p/w342/none.jpg").use { assertEquals(404, it.code) }
        assertEquals(0, proxy.requestCount)
    }

    @Test
    fun publicProxies() {
        val routes = ImageRouting.proxies("https://image.tmdb.org/t/p/w342/abc.jpg".toHttpUrl())
        assertEquals("https://wsrv.nl/?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw342%2Fabc.jpg", routes[0])
        assertEquals("https://i0.wp.com/image.tmdb.org/t/p/w342/abc.jpg?ssl=1", routes[1])
    }

    @Test
    fun artworkSourcesInOrder() {
        val art = "https://image.tmdb.org/a.jpg\ntgthumb://thumb/1/2"
        assertEquals(listOf("https://image.tmdb.org/a.jpg", "tgthumb://poster/1/2"), artworkSources(art, portraitSlot = true), "poster slots ask for poster-shaped thumbnails")
        assertEquals(listOf("https://image.tmdb.org/a.jpg", "tgthumb://thumb/1/2"), artworkSources(art, portraitSlot = false))
        assertEquals(listOf("https://x.example/b.jpg"), artworkSources("http://x.example/b.jpg\n\nnull", portraitSlot = true), "http upgraded, blanks dropped")
        assertTrue(artworkSources(null, true).isEmpty())
        assertTrue(TelegramThumbs.isPosterShaped(213, 320))
        assertTrue(!TelegramThumbs.isPosterShaped(320, 320), "square channel logos are not posters")
        assertTrue(!TelegramThumbs.isPosterShaped(320, 180), "video frames are not posters")
    }
}
