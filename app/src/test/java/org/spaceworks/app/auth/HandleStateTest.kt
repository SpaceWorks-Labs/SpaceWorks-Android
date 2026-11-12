package org.spaceworks.app.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.generated.model.*

class HandleStateTest {
    private class Fake:AuthGateway {
        var available=true;val checks=mutableListOf<String?>();val claims=mutableListOf<String>();var claimFailure:Exception?=null
        override suspend fun config()=throw AssertionError("unused")
        override suspend fun login(username:String,password:String)=throw AssertionError("unused")
        override suspend fun enroll(challenge:String)=throw AssertionError("unused")
        override suspend fun verify(challenge:String,code:String,enrollment:Boolean)=throw AssertionError("unused")
        override suspend fun me()=throw AssertionError("unused")
        override suspend fun logout() {}
        override suspend fun checkHandle(handle:String?,displayName:String?):HandleCheckResponse {
            checks+=handle
            return Json.decodeFromString(if(available)"""{"handle":"$handle","available":true,"reason":"","suggestion":null}"""
                else """{"handle":"$handle","available":false,"reason":"taken","suggestion":"meera.n2"}""")
        }
        override suspend fun claimHandle(handle:String):HandleRead {claimFailure?.let {throw it};claims+=handle
            return org.spaceworks.app.testsupport.Payloads.wire.decodeFromString("""{"handle":"$handle","claimed_at":"2026-11-12T10:00:00Z","changed_at":"2026-11-12T10:00:00Z"}""")}
    }
    @Test fun suggestionPrefillsAndAvailabilityIsChecked()=runTest {
        val api=Fake();val holder=HandleStateHolder(api)
        holder.start("meera.n");assertEquals("meera.n",holder.state.value.handle);assertEquals(HandleAvailability.AVAILABLE,holder.state.value.availability)
        holder.edit("Meera.N");assertEquals("meera.n",holder.state.value.handle)
        api.available=false;holder.check()
        assertEquals(HandleAvailability.TAKEN,holder.state.value.availability);assertEquals("meera.n2",holder.state.value.suggestion)
        holder.edit("x");holder.check();assertEquals(HandleAvailability.INVALID,holder.state.value.availability);assertEquals(listOf<String?>("meera.n"),api.checks)
    }
    @Test fun saveClaimsThenRefreshesIdentity()=runTest {
        var refreshed=0;val api=Fake();val holder=HandleStateHolder(api,{refreshed++})
        holder.edit("-bad-");assertFalse(holder.save());assertTrue(api.claims.isEmpty());assertNotNull(holder.state.value.error)
        holder.edit("meera.n");assertTrue(holder.save());assertEquals(listOf("meera.n"),api.claims);assertEquals(1,refreshed)
        api.claimFailure=ApiFailure(400,"This name is reserved.",null,null)
        assertFalse(holder.save());assertEquals("This name is reserved.",holder.state.value.error)
    }
}
