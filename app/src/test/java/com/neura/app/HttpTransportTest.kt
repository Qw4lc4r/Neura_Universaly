package com.neura.app

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpTransportTest {
    @Test fun joinsMultilineEventsAndIgnoresComments() {
        val events = SseReader(Buffer().writeUtf8(": ping\r\nevent: message\r\ndata: {\r\ndata: \"text\":\"hello\"}\r\n\r\ndata: [DONE]\n\n"))
        assertEquals("{\n\"text\":\"hello\"}", events.next())
        assertEquals("[DONE]", events.next())
        assertNull(events.next())
    }

    @Test fun handlesEofAndEmptyData() {
        val events = SseReader(Buffer().writeUtf8("data:\n\ndata: last"))
        assertEquals("", events.next())
        assertEquals("last", events.next())
        assertNull(events.next())
    }

    @Test fun cancellingAnUnresponsiveServerClosesTheCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/chat/completions")).build())
            val task = launch(Dispatchers.IO) { call.consume { it.body!!.string() } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(3_000) { task.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    @Test fun cancellationRemainsActiveWhileReadingBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: first\n\ndata: second\n\n").throttleBody(1, 1, TimeUnit.SECONDS))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/stream")).build())
            val headers = CompletableDeferred<Unit>()
            val task = launch(Dispatchers.IO) { call.consume { headers.complete(Unit); it.body!!.string() } }
            withTimeout(5_000) { headers.await() }
            withTimeout(3_000) { task.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    @Test fun consumesARealSseResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"))
            withContext(Dispatchers.IO) {
                OkHttpClient().newCall(Request.Builder().url(server.url("/stream")).build()).consume {
                    val events = SseReader(it.body!!.source())
                    assertTrue(events.next()!!.contains("ok"))
                    assertEquals("[DONE]", events.next())
                }
            }
        }
    }
}
