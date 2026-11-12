package org.spaceworks.app.features

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.Errors

class ProviderLinkGatewayTest {
    @Test fun reauthNonceAndLinkUseTheDeviceGrant()=withSession {session,server->
        val gateway=GeneratedProviderLinkGateway(session)
        server.enqueue(json("""{"verified":false,"password_available":true,"mfa_enabled":true,"passkey_available":false,"providers":["google"]}"""))
        assertEquals(ReauthStatus(false,true,true),gateway.status())
        val status=server.next();assertEquals("GET",status.method);assertEquals("/api/v1/auth/reauth",status.path);assertEquals("Bearer access",status.getHeader("Authorization"))
        server.enqueue(json("""{"verified":true,"expires_in":300}"""))
        assertTrue(gateway.reauthenticate("pw","123456"))
        val reauth=server.next();assertEquals("/api/v1/auth/reauth",reauth.path)
        assertEquals(buildJsonObject {put("password","pw");put("code","123456")},Json.parseToJsonElement(reauth.body.readUtf8()))
        server.enqueue(json("""{"nonce":"n-1","expires_in":300}"""))
        assertEquals("n-1",gateway.nonce("google"))
        val nonce=server.next();assertEquals("/api/v1/auth/social/nonce",nonce.path);assertEquals("Bearer access",nonce.getHeader("Authorization"))
        val nonceBody=Json.parseToJsonElement(nonce.body.readUtf8()).jsonObject
        assertEquals("device",nonceBody["delivery"]!!.jsonPrimitive.content);assertEquals("android",nonceBody["client_platform"]!!.jsonPrimitive.content)
        assertNull(nonceBody["challenge"])
        server.enqueue(json("""{"provider":"google","created_at":"2026-11-01T00:00:00Z","updated_at":"2026-11-01T00:00:00Z"}"""))
        assertEquals("google",gateway.link("google","id-token","n-1"))
        val link=server.next();assertEquals("POST",link.method);assertEquals("/api/v1/auth/social/providers",link.path)
        assertEquals(buildJsonObject {put("provider","google");put("id_token","id-token");put("nonce","n-1");put("client_platform","android")},
            Json.parseToJsonElement(link.body.readUtf8()).jsonObject.filterKeys {it!="apple_name"}.let(::JsonObject))
    }
}

private class FakeLink(var verified:Boolean=false):ProviderLinkGateway {
    var nonces=0;var links=mutableListOf<Triple<String,String,String>>();var linkFailure:Exception?=null
    override suspend fun status()=ReauthStatus(verified,true,false)
    override suspend fun reauthenticate(password:String,code:String):Boolean {verified=password=="ok";return verified}
    override suspend fun nonce(provider:String):String {nonces++;return "n$nonces"}
    override suspend fun link(provider:String,idToken:String,nonce:String):String {linkFailure?.let {throw it};links+=Triple(provider,idToken,nonce);return provider}
}

class ProviderLinkStateTest {
    @Test fun reauthThenLinkWithFreshNonce()=runTest {
        val api=FakeLink();val holder=ProviderLinkStateHolder(api);holder.activate(MemberScope(7,"a"))
        holder.start();assertEquals(LinkStage.REAUTH,holder.state.value.stage)
        holder.link("google") {"never"};assertEquals(0,api.nonces)
        holder.reauthenticate("","");assertEquals(FeatureStrings.LINK_NEED_PASSWORD.english,holder.state.value.error)
        holder.reauthenticate("bad","");assertEquals(FeatureStrings.LINK_REAUTH_FAILED.english,holder.state.value.error)
        holder.reauthenticate("ok","");assertEquals(LinkStage.READY,holder.state.value.stage)
        holder.link("google") {nonce->"token-for-$nonce"}
        assertEquals(listOf(Triple("google","token-for-n1","n1")),api.links);assertEquals(LinkStage.LINKED,holder.state.value.stage)
    }
    @Test fun cancelledSheetChangesNothingAndExpiredProofAsksAgain()=runTest {
        val api=FakeLink(verified=true);val holder=ProviderLinkStateHolder(api);holder.activate(MemberScope(7,"a"));holder.start()
        holder.link("google") {throw org.spaceworks.app.auth.AuthCancelled()}
        assertEquals(LinkStage.READY,holder.state.value.stage);assertNull(holder.state.value.error);assertTrue(api.links.isEmpty())
        api.linkFailure=Errors.map(403,"""{"detail":"Confirm again.","code":"recent_reauth_required"}""")
        holder.link("google") {"t"}
        assertEquals(LinkStage.REAUTH,holder.state.value.stage);assertEquals(FeatureStrings.LINK_REAUTH_EXPIRED.english,holder.state.value.error)
    }
}
