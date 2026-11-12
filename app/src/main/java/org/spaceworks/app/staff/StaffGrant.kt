package org.spaceworks.app.staff

import org.spaceworks.app.telemetry.AppTelemetry
import org.spaceworks.app.telemetry.SignInMethod
import org.spaceworks.app.telemetry.TelemetryEvent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.spaceworks.app.auth.AttestationProvider
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.TokenPair
import org.spaceworks.app.network.TokenStore
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.failure
import org.spaceworks.generated.api.AuthApi
import org.spaceworks.generated.api.DeviceAuthApi
import org.spaceworks.generated.model.*

/**
 * Staff step-up (PRD §7 item 6): from the person's member device session, ask for a NEW grant with
 * `authority: staff` fixed to one makerspace, always behind the authenticator. The member grant
 * stays for member screens; the staff grant has its own token pair, refresh and sign-out.
 */
interface StaffGrantGateway {
    /** `POST /auth/device/staff-grant` with the member bearer and a fresh attestation. */
    suspend fun stepUp(makerspaceSlug:String):MFAChallengeResponse
    suspend fun enroll(challenge:String):MFAEnrollment
    /** Finishes the step-up challenge without Authorization; the result is the staff grant. */
    suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted
    /** `GET /auth/me` on the staff grant. */
    suspend fun me():AuthUserPayload
    /** `POST /auth/device/logout` with the staff bearer. */
    suspend fun signOut()
}

class GeneratedStaffGrantGateway(private val member:ApiSession,private val staff:ApiSession,private val appId:String,
    private val environment:EnvironmentEnum,private val attestation:AttestationProvider):StaffGrantGateway {
    override suspend fun stepUp(makerspaceSlug:String):MFAChallengeResponse {
        val challenge=member.devicePublic.apiV1AuthDeviceAttestationChallengeCreate(DeviceIdentity(DevicePlatformEnum.android,appId,environment)).requireBody()
        val proof=attestation.attest(challenge.challenge)
        return member.globalApi(DeviceAuthApi::class.java).apiV1AuthDeviceStaffGrantCreate(
            DeviceStaffGrant(makerspace=makerspaceSlug,challenge=challenge.challenge,attestation=proof,appVersion=member.appVersion)).requireBody()
    }
    override suspend fun enroll(challenge:String)=member.mfa.apiV1AuthMfaEnrollCreate(MFAChallenge(challenge)).requireBody()
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted {
        val input=MFAVerify(challenge=challenge,code=code)
        return (if(enrollment)member.mfa.apiV1AuthMfaConfirmCreate(input) else member.mfa.apiV1AuthMfaVerifyCreate(input)).requireBody()
            .also {result->result.deviceTrust?.takeIf {it.isNotBlank()}?.let(member.deviceTrust::write)}
    }
    override suspend fun me()=staff.globalApi(AuthApi::class.java).apiV1AuthMeRetrieve().requireBody()
    override suspend fun signOut() {staff.globalApi(DeviceAuthApi::class.java).apiV1AuthDeviceLogoutCreate().requireBody()}
}

data class StaffGrantState(
    /** The makerspace the current staff grant is fixed to, once `/auth/me` confirmed it. */
    val makerspaceId:Int?=null,
    val actions:List<String> = emptyList(),
    /** The makerspace a step-up is in progress for. */
    val pending:MemberScope?=null,
    val mfa:MFAChallengeResponse?=null,val enrollment:MFAEnrollment?=null,val recoveryCodes:List<String> = emptyList(),
    val busy:Boolean=false,val error:String?=null,val notice:String?=null,
) {
    val active get()=makerspaceId!=null
    fun covers(id:Int)=makerspaceId==id
}

/**
 * Owns the staff grant of one server. It never borrows the member grant's tokens: [tokens] is a
 * separate store, refreshed by its own session, and cleared whenever the member session ends.
 */
class StaffGrantStateHolder(private val api:StaffGrantGateway,private val tokens:TokenStore,
    private val text:MobileText=MobileText.ENGLISH) {
    private val mutable=MutableStateFlow(StaffGrantState())
    val state=mutable.asStateFlow()
    private val operation=Mutex()
    /** The staff store installation the confirmed state belongs to. */
    @Volatile var acceptedEpoch:Long?=null
        private set

    fun covers(id:Int)=state.value.covers(id) && acceptedEpoch==tokens.snapshot().epoch && tokens.read()!=null

    /** Restores a stored staff grant (app relaunch) and re-validates it with the server. */
    suspend fun restore()=run {
        if(tokens.read()==null){acceptedEpoch=null;mutable.value=StaffGrantState();return@run}
        confirm(null)
    }
    /** Re-reads the staff identity; a revoked grant (lost role) answers 401 and is dropped. */
    suspend fun foreground() {if(state.value.active && !state.value.busy)run {confirm(null)}}

    /** Opens the staff sign-in step for [scope]; nothing is sent until [stepUp]. */
    fun begin(scope:MemberScope) {if(!state.value.busy)mutable.update {it.copy(pending=scope,mfa=null,enrollment=null,error=null,notice=null)}}
    suspend fun stepUp(scope:MemberScope)=run(report=true) {
        mutable.value=state.value.copy(pending=scope,mfa=null,enrollment=null,recoveryCodes=emptyList(),notice=null)
        AppTelemetry.track(TelemetryEvent.signInStarted(SignInMethod.STAFF))
        val challenge=api.stepUp(scope.slug)
        if(!challenge.mfaRequired && !challenge.enrollmentRequired)throw MobileCopyException(CoreStrings.CORE_RESTART_SIGN_IN_AND_TRY_AGAIN)
        mutable.update {it.copy(mfa=challenge)}
        AppTelemetry.track(TelemetryEvent.mfaChallengeShown(if(challenge.enrollmentRequired)"enrollment" else "verification"))
    }
    suspend fun enroll()=run {
        val challenge=state.value.mfa?:throw MobileCopyException(CoreStrings.CORE_RESTART_SIGN_IN_AND_TRY_AGAIN)
        val setup=api.enroll(challenge.challenge)
        mutable.update {it.copy(enrollment=setup)}
    }
    suspend fun verify(code:String)=run(report=true) {
        val challenge=state.value.mfa?:throw MobileCopyException(CoreStrings.CORE_RESTART_SIGN_IN_AND_TRY_AGAIN)
        val scope=state.value.pending?:throw MobileCopyException(CoreStrings.CORE_RESTART_SIGN_IN_AND_TRY_AGAIN)
        if(code.trim().length !in 6..64)throw MobileCopyException(CoreStrings.CORE_ENTER_YOUR_AUTHENTICATOR_OR_RECOVERY_CODE)
        val result=api.verify(challenge.challenge,code.trim(),challenge.enrollmentRequired)
        val refresh=result.refresh?.takeIf {it.isNotBlank()}
        if(result.access.isBlank() || refresh==null)throw MobileCopyException(CoreStrings.CORE_THIS_SERVER_DID_NOT_RETURN_A_DEVICE_SESSION_CONTACT_YOUR_MAK)
        withContext(NonCancellable+Dispatchers.IO) {tokens.replace(TokenPair(result.access,refresh))}
        confirm(scope)
        mutable.update {it.copy(mfa=null,enrollment=null,pending=null,recoveryCodes=result.recoveryCodes.orEmpty())}
        AppTelemetry.track(TelemetryEvent.signInSucceeded(SignInMethod.STAFF))
    }
    fun acknowledgeRecoveryCodes(){mutable.update {it.copy(recoveryCodes=emptyList())}}
    fun cancel() {if(!state.value.busy)mutable.update {it.copy(pending=null,mfa=null,enrollment=null,error=null)}}
    fun dismissNotice(){mutable.update {it.copy(notice=null,error=null)}}

    /** Ends the staff grant on the server, then locally. The member grant is untouched. */
    suspend fun signOut(notice:Boolean=true)=withContext(NonCancellable) {
        if(tokens.read()!=null)try {api.signOut()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
        clearLocal()
        if(notice)mutable.update {it.copy(notice=text(StaffStrings.STAFF_SIGNED_OUT_OF_STAFF_TOOLS))}
    }
    /** The member session ended (sign-out, revocation, account switch): drop the staff grant locally. */
    fun clearLocal() {
        acceptedEpoch=null
        if(tokens.read()!=null)tokens.replace(null)
        mutable.value=StaffGrantState()
    }

    private suspend fun confirm(expected:MemberScope?) {
        val epoch=tokens.snapshot().epoch
        val user=try {api.me()} catch(f:ApiFailure) {
            if(f.status==401 || f.status==403){clearLocal();throw f}
            throw f
        }
        val roles=user.staffRoles
        val fixed=roles.singleOrNull()?.takeIf {user.sessionAuthority==SessionAuthorityEnum.native_staff}
        if(fixed==null || (expected!=null && fixed.makerspace.id!=expected.id) || tokens.snapshot().epoch!=epoch) {
            // Not a native staff grant for the asked makerspace: never keep it.
            try {api.signOut()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
            clearLocal()
            throw MobileCopyException(StaffStrings.STAFF_STAFF_TOOLS_ARE_UNAVAILABLE_FOR_THIS_DEVICE_SESSION)
        }
        acceptedEpoch=epoch
        mutable.update {it.copy(makerspaceId=fixed.makerspace.id,actions=fixed.actions)}
    }
    /** [report]: a server refusal of the step-up is a `sign_in_failed{method: staff}` (local validation is not). */
    private suspend fun run(report:Boolean=false,block:suspend()->Unit) {
        if(!operation.tryLock())return
        try {
            mutable.update {it.copy(busy=true,error=null)}
            block()
        } catch(cancelled:CancellationException){throw cancelled}
        catch(f:Exception) {
            if(report && f !is MobileCopyException)AppTelemetry.track(TelemetryEvent.signInFailed(SignInMethod.STAFF,if(f is ApiFailure)f.code?:"${f.status/100}xx" else "network"))
            mutable.update {it.copy(error=when(f){is ApiFailure->text.apiFailure(f);is MobileCopyException->text.failure(f);else->text(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN)})}
        } finally {mutable.update {it.copy(busy=false)};operation.unlock()}
    }
}
