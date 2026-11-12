package org.spaceworks.app.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.*
import org.spaceworks.app.testsupport.Payloads
import org.spaceworks.generated.model.*

/** PRD v4 / backend 0.12.0 sign-in branches: risk email code, social MFA, 426, usernames. */
class AuthFlow012Test {
    private val json=Json {ignoreUnknownKeys=true}
    private fun challenge(purpose:String="login",factors:String="""["totp","recovery"]""")=json.decodeFromString<MFAChallengeResponse>(
        """{"mfa_required":true,"enrollment_required":false,"challenge":"challenge-000000000000","expires_in":600,"purpose":"$purpose","factors":$factors}""")
    private fun completed()=json.decodeFromString<MFACompleted>(
        """{"access":"access","refresh":"refresh","surface":"member","user":${Payloads.user()},"device_grant":{"id":"00000000-0000-0000-0000-000000000001"}}""")
    private val grant=JsonObject(mapOf("id" to JsonPrimitive("00000000-0000-0000-0000-000000000001")))

    private inner class Fake:AuthGateway {
        var login:DeviceLoginResult=DeviceLoginResult.MFAChallengeResponseValue(challenge("risk","""["email_code"]"""))
        var social:SocialLoginResult=SocialLoginResult.MFAChallengeResponseValue(challenge())
        var who=json.decodeFromString<AuthUserPayload>(Payloads.user())
        var meFailure:Exception?=null
        val verified=mutableListOf<Pair<String,FactorEnum?>>();var resends=0;var resendSent=true
        override val nativeSocialReady=true
        override suspend fun config()=DemoWire.config(false,100).copy(native=DemoWire.native(NativeMinVersion(null,null),
            NativeProviders(true,false,NativeGoogle("android-audience","ios-audience"),null,emptyList())))
        override suspend fun login(username:String,password:String)=login
        override suspend fun enroll(challenge:String)=throw AssertionError("unused")
        override suspend fun verify(challenge:String,code:String,enrollment:Boolean)=verify(challenge,code,enrollment,null)
        override suspend fun verify(challenge:String,code:String,enrollment:Boolean,factor:FactorEnum?):MFACompleted {verified+=code to factor;return completed()}
        override suspend fun resendLoginCode(challenge:String):LoginCodeResent {resends++;return json.decodeFromString("""{"sent":$resendSent,"expires_in":600,"resends_left":${2-resends}}""")}
        override suspend fun me():AuthUserPayload {meFailure?.let {throw it};return who}
        override suspend fun logout() {}
        override suspend fun google(clientId:String,credential:suspend(String,String)->String)=social
    }

    @Test fun unusualSignInAsksForTheEmailedCodeAndAnswersWithEmailCode()=runTest {
        val api=Fake();val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.signIn("member","password")
        assertTrue(holder.state.value.riskChallenge);assertNull(holder.state.value.user)
        holder.resendLoginCode()
        assertEquals(1,api.resends);assertEquals(1,holder.state.value.loginCodeResendsLeft);assertNotNull(holder.state.value.notice)
        holder.verify("abcdef");assertTrue(api.verified.isEmpty());assertNotNull(holder.state.value.error)
        holder.verify("123456")
        assertEquals(listOf("123456" to FactorEnum.email_code),api.verified)
        assertNotNull(holder.state.value.user);assertNull(holder.state.value.mfa);assertEquals(TokenPair("access","refresh"),store.read())
    }
    @Test fun exhaustedResendsShowAnError()=runTest {
        val api=Fake().apply {resendSent=false};val holder=AuthStateHolder(api,MemoryTokenStore())
        holder.initialize();holder.signIn("member","password");holder.resendLoginCode()
        assertNotNull(holder.state.value.error);assertTrue(holder.state.value.riskChallenge)
    }
    @Test fun authenticatorChallengeKeepsTheServerDefaultFactor()=runTest {
        val api=Fake().apply {login=DeviceLoginResult.MFAChallengeResponseValue(challenge())};val holder=AuthStateHolder(api,MemoryTokenStore())
        holder.initialize();holder.signIn("member","password");assertFalse(holder.state.value.riskChallenge)
        holder.verify("654321");assertEquals(listOf("654321" to null),api.verified)
    }
    @Test fun socialSignInCanContinueIntoMfaOrInstallTokens()=runTest {
        val api=Fake();val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.googleSignIn {_,_->"id-token"}
        assertNotNull(holder.state.value.mfa);assertNull(store.read())
        holder.verify("123456");assertNotNull(holder.state.value.user)
        holder.signOut()
        api.social=SocialLoginResult.SocialLoginResponseValue(SocialLoginResponse(access="s-access",outcome="signed_in",user=emptyMap(),refresh="s-refresh",deviceGrant=grant))
        holder.googleSignIn {_,_->"id-token"}
        assertEquals(TokenPair("s-access","s-refresh"),store.read());assertNotNull(holder.state.value.user)
    }
    @Test fun update426KeepsTheSessionAndShowsTheUpdateGate()=runTest {
        val api=Fake();val store=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
        val holder=AuthStateHolder(api,store)
        api.meFailure=ApiFailure(426,"Update the app.",UPDATE_REQUIRED_CODE,null,minimumVersion="2.0.0")
        holder.initialize()
        assertEquals(AuthGate.UPDATE_REQUIRED,holder.state.value.gate);assertNotNull(store.read())
        holder.dismissGate();assertEquals(AuthGate.UPDATE_REQUIRED,holder.state.value.gate)
        api.meFailure=null;holder.initialize()
        assertNull(holder.state.value.gate);assertNotNull(holder.state.value.user)
        holder.requireUpdate();assertEquals(AuthGate.UPDATE_REQUIRED,holder.state.value.gate)
    }
    @Test fun signInRefusedWith426ShowsTheUpdateGate()=runTest {
        val api=object:AuthGateway by Fake() {
            override suspend fun login(username:String,password:String):DeviceLoginResult=throw ApiFailure(426,"Update the app.",UPDATE_REQUIRED_CODE,null)
        }
        val holder=AuthStateHolder(api,MemoryTokenStore());holder.initialize();holder.signIn("member","password")
        assertEquals(AuthGate.UPDATE_REQUIRED,holder.state.value.gate)
    }
    @Test fun handleRequiredComesFromThePayload()=runTest {
        val api=Fake();api.who=json.decodeFromString(Payloads.user(""""handle":null,"handle_required":true,"handle_suggestion":"meera.n""""))
        val holder=AuthStateHolder(api,MemoryTokenStore().apply {replace(TokenPair("a","r"))});holder.initialize()
        assertTrue(holder.state.value.handleRequired);assertEquals("meera.n",holder.state.value.user?.handleSuggestion)
    }
    @Test fun staffLinksNeedAStaffGrantForThatMakerspace()=runTest {
        var covered=false
        val holder=AuthStateHolder(Fake(),MemoryTokenStore().apply {replace(TokenPair("a","r"))},staffGrantFor={covered && it==7})
        holder.initialize()
        assertEquals(7,holder.authorizeSlug("fab-lab",staff=false));assertNull(holder.authorizeSlug("fab-lab",staff=true))
        covered=true;assertEquals(7,holder.authorizeSlug("fab-lab",staff=true))
    }
}
