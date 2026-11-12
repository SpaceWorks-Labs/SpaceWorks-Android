package org.spaceworks.app.staff

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.MemoryTokenStore
import org.spaceworks.app.network.TokenPair
import org.spaceworks.app.testsupport.Payloads
import org.spaceworks.generated.model.*

class StaffGrantStateTest {
    private val json=Json {ignoreUnknownKeys=true}
    private val scope=MemberScope(7,"fab-lab")
    private fun challenge(enrollment:Boolean=false)=json.decodeFromString<MFAChallengeResponse>(
        """{"mfa_required":${!enrollment},"enrollment_required":$enrollment,"challenge":"staff-challenge-000000000","expires_in":300,"purpose":"login","factors":["totp","recovery"]}""")
    private fun completed(access:String="staff-access")=json.decodeFromString<MFACompleted>(
        """{"access":"$access","refresh":"staff-refresh","surface":"member","user":${Payloads.user()},"device_grant":{"id":"00000000-0000-0000-0000-000000000009"},"device_trust":"trust"}""")
    private fun staffUser(makerspace:Int=7,authority:String="native_staff")=json.decodeFromString<AuthUserPayload>(Payloads.user(
        """"session_authority":"$authority","staff_roles":[{"makerspace":{"id":$makerspace,"slug":"fab-lab","name":"Fab Lab"},"roles":[{"id":2,"slug":"handover","name":"Handover"}],"actions":["issue_request","return_request"]}]"""))

    private class Fake:StaffGrantGateway {
        var challenge:MFAChallengeResponse?=null;var completed:MFACompleted?=null;var me:AuthUserPayload?=null
        var meFailure:Exception?=null;var stepUps=mutableListOf<String>();var verifies=0;var signOuts=0;var enrolls=0
        override suspend fun stepUp(makerspaceSlug:String)=challenge!!.also {stepUps+=makerspaceSlug}
        override suspend fun enroll(challenge:String):MFAEnrollment {enrolls++;return Json.decodeFromString("""{"secret":"k","provisioning_uri":"otpauth://x","qr_data_url":"data:image/png;base64,AA=="}""")}
        override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted {verifies++;return completed!!}
        override suspend fun me():AuthUserPayload {meFailure?.let {throw it};return me!!}
        override suspend fun signOut() {signOuts++}
    }

    @Test fun stepUpVerifiesThenStoresASeparateStaffGrantForTheMakerspace()=runTest {
        val api=Fake().apply {challenge=challenge();completed=completed();me=staffUser()}
        val store=MemoryTokenStore();val holder=StaffGrantStateHolder(api,store)
        holder.begin(scope);holder.stepUp(scope)
        assertEquals(listOf("fab-lab"),api.stepUps);assertNotNull(holder.state.value.mfa);assertFalse(holder.covers(7))
        holder.verify("12345");assertEquals(0,api.verifies);assertNotNull(holder.state.value.error)
        holder.verify("123456")
        assertEquals(TokenPair("staff-access","staff-refresh"),store.read())
        assertTrue(holder.covers(7));assertFalse(holder.covers(8))
        assertEquals(listOf("issue_request","return_request"),holder.state.value.actions)
        assertNull(holder.state.value.mfa);assertNull(holder.state.value.pending)
    }
    @Test fun enrolmentIsRequestedBeforeTheCode()=runTest {
        val api=Fake().apply {challenge=challenge(enrollment=true);completed=completed();me=staffUser()}
        val holder=StaffGrantStateHolder(api,MemoryTokenStore())
        holder.stepUp(scope);holder.enroll()
        assertEquals(1,api.enrolls);assertNotNull(holder.state.value.enrollment)
        holder.verify("123456");assertTrue(holder.covers(7))
    }
    @Test fun aGrantForAnotherMakerspaceOrAMemberAuthorityIsNeverKept()=runTest {
        for(user in listOf(staffUser(makerspace=8),staffUser(authority="member"))) {
            val api=Fake().apply {challenge=challenge();completed=completed();me=user}
            val store=MemoryTokenStore();val holder=StaffGrantStateHolder(api,store)
            holder.stepUp(scope);holder.verify("123456")
            assertNull(store.read());assertFalse(holder.covers(7));assertEquals(1,api.signOuts)
            assertNotNull(holder.state.value.error)
        }
    }
    @Test fun aLostStaffRoleDropsTheGrantOnForeground()=runTest {
        val api=Fake().apply {challenge=challenge();completed=completed();me=staffUser()}
        val store=MemoryTokenStore();val holder=StaffGrantStateHolder(api,store)
        holder.stepUp(scope);holder.verify("123456");assertTrue(holder.covers(7))
        api.meFailure=ApiFailure(401,"Signed out.","session_revoked",null)
        holder.foreground()
        assertFalse(holder.covers(7));assertNull(store.read())
    }
    @Test fun signOutEndsOnlyTheStaffGrantAndClearLocalNeverCallsTheServer()=runTest {
        val api=Fake().apply {challenge=challenge();completed=completed();me=staffUser()}
        val store=MemoryTokenStore();val holder=StaffGrantStateHolder(api,store)
        holder.stepUp(scope);holder.verify("123456")
        holder.signOut()
        assertEquals(1,api.signOuts);assertNull(store.read());assertFalse(holder.covers(7));assertNotNull(holder.state.value.notice)
        holder.stepUp(scope);holder.verify("123456");holder.clearLocal()
        assertEquals(1,api.signOuts);assertNull(store.read())
    }
    @Test fun restoreRevalidatesAStoredGrant()=runTest {
        val api=Fake().apply {me=staffUser()}
        val store=MemoryTokenStore().apply {replace(TokenPair("a","r"))};val holder=StaffGrantStateHolder(api,store)
        holder.restore();assertTrue(holder.covers(7))
        val empty=StaffGrantStateHolder(api,MemoryTokenStore());empty.restore();assertFalse(empty.covers(7))
    }
}
