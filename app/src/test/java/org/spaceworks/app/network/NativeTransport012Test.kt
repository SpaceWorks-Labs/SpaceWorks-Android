package org.spaceworks.app.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Backend 0.12.0 transport rules: app_version, 426 without rotation, remembered-device header. */
class NativeTransport012Test {
    private val update="""{"detail":"This version of the app is no longer supported. Update the app to continue.","code":"native_app_update_required","minimum_version":"2.0.0"}"""

    @Test(timeout=10000) fun refreshSendsAppVersionAndA426KeepsThePairAndSurfacesTheUpdate()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
            server.enqueue(MockResponse().setResponseCode(426).setBody(update))
            val store=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
            val session=ApiSession(server.url("/"),store,appVersion="1.4.2")
            var seen:ApiFailure?=null
            session.onUpdateRequired={seen=it}
            val response=session.auth.apiV1AuthMeRetrieve()
            assertEquals(426,response.code())
            val failure=Errors.map(response.code(),response.errorBody()?.string())
            assertTrue(failure.updateRequired);assertEquals("2.0.0",failure.minimumVersion)
            assertEquals("2.0.0",seen?.minimumVersion)
            // Nothing rotated: the stored pair is kept for after the update.
            assertEquals(TokenPair("access","refresh"),store.read())
            server.takeRequest()
            val refresh=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("refresh",refresh["refresh"]!!.jsonPrimitive.content);assertEquals("1.4.2",refresh["app_version"]!!.jsonPrimitive.content)
        }
    }
    @Test(timeout=10000) fun otherRefreshRefusalsStillEndTheSession()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Signed out.","code":"session_revoked"}"""))
            val store=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
            val session=ApiSession(server.url("/"),store)
            var updates=0;session.onUpdateRequired={updates++}
            assertEquals(401,session.auth.apiV1AuthMeRetrieve().code())
            assertNull(store.read());assertEquals(0,updates)
        }
    }
    @Test(timeout=10000) fun deviceTrustIsSentOnlyOnSignInRoutes()=runBlocking {
        MockWebServer().use {server->
            val trust=MemoryDeviceTrustStore().apply {write("signed-device-value")}
            val session=ApiSession(server.url("/"),MemoryTokenStore().apply {replace(TokenPair("a","r"))},deviceTrust=trust)
            server.enqueue(MockResponse().setBody("""{"detail":"ok"}"""))
            session.authPublic.apiV1AuthForgotPasswordCreate(
                org.spaceworks.generated.model.ForgotPasswordRequest("member@example.invalid"))
            assertNull(server.takeRequest().getHeader(DEVICE_TRUST_HEADER))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"bad","code":"login_code_invalid"}"""))
            session.mfa.apiV1AuthMfaVerifyCreate(org.spaceworks.generated.model.MFAVerify(challenge="c".repeat(24),code="123456"))
            val verify=server.takeRequest()
            assertEquals("/api/v1/auth/mfa/verify",verify.path);assertEquals("signed-device-value",verify.getHeader(DEVICE_TRUST_HEADER))
            assertNull(verify.getHeader("Authorization"))
        }
    }
}
