package org.spaceworks.app.features

import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.platform.MobileText
import org.spaceworks.generated.api.MembershipsApi

/** The space's current waiver text; [present] is false when the space has none. */
data class Waiver(val present:Boolean,val body:String,val version:String)

interface WaiverGateway {
    suspend fun waiver(scope:MemberScope):Waiver
    /** Returns the accepted version, or null when the space has no active waiver any more. */
    suspend fun accept(scope:MemberScope):String?
}

class GeneratedWaiverGateway(private val session:ApiSession):WaiverGateway {
    private fun api(scope:MemberScope)=session.scopedApi(MembershipsApi::class.java,scope.id)
    override suspend fun waiver(scope:MemberScope):Waiver {
        val value=api(scope).apiV1MemberMakerspacesWaiverRetrieve(scope.id).requireBody()
        return Waiver(value.hasWaiver,value.body.orEmpty(),value.version.orEmpty())
    }
    override suspend fun accept(scope:MemberScope):String? {
        val value=api(scope).apiV1MemberMakerspacesWaiverAcceptCreate(scope.id).requireBody()
        return value.version?.takeIf {value.accepted}
    }
}

data class WaiverState(val scope:MemberScope?=null,val waiver:Waiver?=null,val loading:Boolean=false,
    val accepting:Boolean=false,val acceptedVersion:String?=null,val changed:Boolean=false,val gone:Boolean=false,val error:String?=null)

class WaiverStateHolder(private val api:WaiverGateway,text:MobileText=MobileText.ENGLISH,onUnauthorized:suspend()->Unit={})
    :FeatureHolder<WaiverState>({WaiverState(scope=it)},text,onUnauthorized) {
    suspend fun refresh() {
        if(state.value.loading)return
        run({it.copy(loading=true,error=null)},{api.waiver(it)},{s,value->s.copy(waiver=value,loading=false)},{s,message,_->s.copy(loading=false,error=message)})
    }
    /** Accepts only after the member has read a loaded waiver; the server records the current version. */
    suspend fun accept():Boolean {
        val current=state.value
        if(current.accepting || current.waiver?.present!=true)return false
        var accepted=false
        run({it.copy(accepting=true,error=null)},{api.accept(it)},{s,version->
            accepted=version!=null
            s.copy(accepting=false,acceptedVersion=version,gone=version==null,changed=version!=null && version!=s.waiver?.version)
        },{s,message,_->s.copy(accepting=false,error=message)})
        // The server accepts whichever version is current: if it moved meanwhile, show the member that text.
        if(state.value.changed)refresh()
        return accepted
    }
}
