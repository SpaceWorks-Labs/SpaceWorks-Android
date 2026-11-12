package org.spaceworks.app.features

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.platform.MobileText
import org.spaceworks.generated.api.AuthApi
import org.spaceworks.generated.api.ReauthenticationApi
import org.spaceworks.generated.api.SocialAuthApi
import org.spaceworks.generated.model.ClientPlatformEnum
import org.spaceworks.generated.model.DeliveryEnum
import org.spaceworks.generated.model.Reauthentication
import org.spaceworks.generated.model.SocialLink
import org.spaceworks.generated.model.SocialNonce
import org.spaceworks.generated.model.Surface356Enum

data class ReauthStatus(val verified:Boolean,val passwordAvailable:Boolean,val mfaEnabled:Boolean)

interface ProviderLinkGateway {
    suspend fun status():ReauthStatus
    /** Password (plus authenticator code when MFA is on). Returns true when the server now holds a recent proof. */
    suspend fun reauthenticate(password:String,code:String):Boolean
    /** A device-delivered nonce bound to this device grant (no attestation challenge). */
    suspend fun nonce(provider:String):String
    suspend fun link(provider:String,idToken:String,nonce:String):String
}

/** Account-global operations on the member grant; no makerspace header is needed. */
class GeneratedProviderLinkGateway(private val session:ApiSession):ProviderLinkGateway {
    private fun <T> api(type:Class<T>)=session.globalApi(type)
    override suspend fun status():ReauthStatus {
        val value=api(ReauthenticationApi::class.java).apiV1AuthReauthRetrieve().requireBody()
        return ReauthStatus(value.verified,value.passwordAvailable,value.mfaEnabled)
    }
    override suspend fun reauthenticate(password:String,code:String):Boolean = try {
        api(ReauthenticationApi::class.java).apiV1AuthReauthCreate(Reauthentication(password,code.takeIf {it.isNotBlank()})).requireBody().verified
    }catch(failure:ApiFailure) {
        // A wrong password is also 401: when the session itself is still valid, it was the credential.
        if(failure.status!=401)throw failure
        try {api(AuthApi::class.java).apiV1AuthMeRetrieve().requireBody()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){throw failure}
        false
    }
    override suspend fun nonce(provider:String)=api(SocialAuthApi::class.java)
        .apiV1AuthSocialNonceCreate(SocialNonce(provider,Surface356Enum.member,DeliveryEnum.device,ClientPlatformEnum.android)).requireBody().nonce
    override suspend fun link(provider:String,idToken:String,nonce:String)=api(SocialAuthApi::class.java)
        .apiV1AuthSocialProvidersCreate(SocialLink(provider,idToken,nonce,ClientPlatformEnum.android)).requireBody().provider
}

enum class LinkStage { CHECKING, REAUTH, READY, LINKED }
data class ProviderLinkState(val scope:MemberScope?=null,val stage:LinkStage=LinkStage.CHECKING,val status:ReauthStatus?=null,
    val busy:Boolean=false,val linked:String?=null,val error:String?=null)

/** Link Google to the signed-in person: recent reauthentication, then a fresh nonce and Google credential. */
class ProviderLinkStateHolder(private val api:ProviderLinkGateway,text:MobileText=MobileText.ENGLISH,onUnauthorized:suspend()->Unit={})
    :FeatureHolder<ProviderLinkState>({ProviderLinkState(scope=it)},text,onUnauthorized) {
    suspend fun start() {
        if(state.value.busy)return
        run({it.copy(busy=true,error=null,stage=LinkStage.CHECKING,linked=null)},{api.status()},
            {s,status->s.copy(busy=false,status=status,stage=if(status.verified)LinkStage.READY else LinkStage.REAUTH)},
            {s,message,_->s.copy(busy=false,error=message)})
    }
    suspend fun reauthenticate(password:String,code:String) {
        val status=state.value.status?:return
        if(state.value.busy || state.value.stage!=LinkStage.REAUTH)return
        if(password.isEmpty() || (status.mfaEnabled && code.isBlank())){mutable.update {it.copy(error=text(FeatureStrings.LINK_NEED_PASSWORD))};return}
        run({it.copy(busy=true,error=null)},{api.reauthenticate(password,code)},
            {s,verified->if(verified)s.copy(busy=false,stage=LinkStage.READY) else s.copy(busy=false,error=text(FeatureStrings.LINK_REAUTH_FAILED))},
            {s,message,_->s.copy(busy=false,error=message)})
    }
    /** [credential] runs the provider's own sign-in sheet with the nonce and returns its ID token. */
    suspend fun link(provider:String,credential:suspend(String)->String) {
        if(state.value.busy || state.value.stage!=LinkStage.READY)return
        run({it.copy(busy=true,error=null)},{
            val nonce=api.nonce(provider)
            // The person closed the provider sheet: nothing was sent and nothing changes.
            val token=try {credential(nonce)}catch(_:org.spaceworks.app.auth.AuthCancelled){null}
            token?.let {api.link(provider,it,nonce)}
        },{s,linked->if(linked==null)s.copy(busy=false) else s.copy(busy=false,stage=LinkStage.LINKED,linked=linked)},
            {s,message,failure->
                // The proof expired meanwhile: confirm again, then link.
                if(failure?.code=="recent_reauth_required")s.copy(busy=false,stage=LinkStage.REAUTH,error=text(FeatureStrings.LINK_REAUTH_EXPIRED))
                else s.copy(busy=false,error=message)
            })
    }
}
