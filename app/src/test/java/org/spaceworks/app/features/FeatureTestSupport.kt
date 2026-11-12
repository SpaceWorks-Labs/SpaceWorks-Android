package org.spaceworks.app.features

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.MemoryTokenStore
import org.spaceworks.app.network.TokenPair
import java.util.concurrent.TimeUnit

internal fun json(body:String,code:Int=200)=MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
internal fun MockWebServer.next():RecordedRequest=requireNotNull(takeRequest(5,TimeUnit.SECONDS))
/** A signed-in generated session against a stub server. */
internal fun withSession(block:suspend(ApiSession,MockWebServer)->Unit)=runBlocking {
    MockWebServer().use {server->
        val tokens=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
        block(ApiSession(server.url("/"),tokens),server)
    }
}
