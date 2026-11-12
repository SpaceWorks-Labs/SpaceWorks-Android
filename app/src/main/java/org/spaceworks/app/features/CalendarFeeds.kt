package org.spaceworks.app.features

import kotlinx.coroutines.flow.update
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.platform.MobileText
import org.spaceworks.generated.api.MemberEventsApi
import org.spaceworks.generated.model.MemberCalendarFeedIssue

enum class FeedKind { EVENTS, SHIFTS, COMBINED }
/** Feed status without the secret: [hint] is the server's short token hint. */
data class FeedStatus(val enabled:Boolean,val hint:String?)

interface CalendarFeedGateway {
    suspend fun status(scope:MemberScope,kind:FeedKind):FeedStatus
    /** Issues or rotates the feed and returns its secret URL; the caller shows it once and never stores it. */
    suspend fun issue(scope:MemberScope,kind:FeedKind):Pair<String,String>
    suspend fun revoke(scope:MemberScope,kind:FeedKind)
}

class GeneratedCalendarFeedGateway(private val session:ApiSession):CalendarFeedGateway {
    private fun api(scope:MemberScope)=session.scopedApi(MemberEventsApi::class.java,scope.id)
    private fun wire(kind:FeedKind)=if(kind==FeedKind.SHIFTS)"shifts" else "combined"
    override suspend fun status(scope:MemberScope,kind:FeedKind):FeedStatus = if(kind==FeedKind.EVENTS) {
        val value=api(scope).apiV1MemberMakerspacesEventCalendarFeedRetrieve(makerspaceId=scope.id).requireBody()
        FeedStatus(value.enabled,value.tokenHint)
    } else {
        val value=api(scope).apiV1MemberMakerspacesCalendarFeedsRetrieve(kind=wire(kind),makerspaceId=scope.id).requireBody()
        FeedStatus(value.enabled,value.tokenHint)
    }
    override suspend fun issue(scope:MemberScope,kind:FeedKind):Pair<String,String> = if(kind==FeedKind.EVENTS) {
        // The member has accepted the "anyone with this link" warning before this call.
        val value=api(scope).apiV1MemberMakerspacesEventCalendarFeedCreate(makerspaceId=scope.id,memberCalendarFeedIssue=MemberCalendarFeedIssue(true)).requireBody()
        value.feedUrl.toString() to value.tokenHint
    } else {
        val value=api(scope).apiV1MemberMakerspacesCalendarFeedsCreate(kind=wire(kind),makerspaceId=scope.id).requireBody()
        value.feedUrl.toString() to value.tokenHint
    }
    override suspend fun revoke(scope:MemberScope,kind:FeedKind) {
        val response=if(kind==FeedKind.EVENTS)api(scope).apiV1MemberMakerspacesEventCalendarFeedDestroy(makerspaceId=scope.id)
            else api(scope).apiV1MemberMakerspacesCalendarFeedsDestroy(kind=wire(kind),makerspaceId=scope.id)
        response.requireSuccess()
    }
}

/** A feed that answered 403/404 is not offered here (module off or not applicable to this member). */
data class FeedRow(val status:FeedStatus?=null,val unavailable:Boolean=false)
data class CalendarFeedsState(val scope:MemberScope?=null,val rows:Map<FeedKind,FeedRow> = emptyMap(),val loading:Boolean=false,
    val busy:FeedKind?=null,val confirming:FeedKind?=null,val issued:IssuedFeed?=null,val error:String?=null)
/** The secret URL lives only in memory, only until it is handed to the calendar app or the screen closes. */
data class IssuedFeed(val kind:FeedKind,val url:String) {override fun toString()="IssuedFeed($kind)"}

class CalendarFeedsStateHolder(private val api:CalendarFeedGateway,text:MobileText=MobileText.ENGLISH,onUnauthorized:suspend()->Unit={})
    :FeatureHolder<CalendarFeedsState>({CalendarFeedsState(scope=it)},text,onUnauthorized) {
    suspend fun refresh() {
        if(state.value.loading)return
        mutable.update {it.copy(loading=true,error=null)}
        for(kind in FeedKind.entries) {
            run({it},{api.status(it,kind)},{s,status->s.copy(rows=s.rows+(kind to FeedRow(status)))},{s,message,failure->
                if(failure?.status==403 || failure?.status==404)s.copy(rows=s.rows+(kind to FeedRow(unavailable=true))) else s.copy(error=message)})
        }
        mutable.update {it.copy(loading=false)}
    }
    /** Issuing needs the bearer-risk warning first: [confirm] shows it, [issue] runs only for the confirmed kind. */
    fun confirm(kind:FeedKind?){if(state.value.busy==null)mutable.update {it.copy(confirming=kind,error=null)}}
    suspend fun issue() {
        val kind=state.value.confirming?:return
        if(state.value.busy!=null)return
        run({it.copy(busy=kind,confirming=null,issued=null,error=null)},{api.issue(it,kind)},
            {s,(url,hint)->s.copy(busy=null,issued=IssuedFeed(kind,url),rows=s.rows+(kind to FeedRow(FeedStatus(true,hint))))},
            {s,message,_->s.copy(busy=null,error=message)})
    }
    suspend fun revoke(kind:FeedKind) {
        if(state.value.busy!=null)return
        run({it.copy(busy=kind,error=null,issued=it.issued?.takeIf {issued->issued.kind!=kind})},{api.revoke(it,kind)},
            {s,_->s.copy(busy=null,rows=s.rows+(kind to FeedRow(FeedStatus(false,null))))},{s,message,_->s.copy(busy=null,error=message)})
    }
    /** Drops the secret URL once it was handed to the OS, or when the screen closes. */
    fun forgetIssued()=mutable.update {it.copy(issued=null)}
}

/** `webcal://` asks the OS calendar to subscribe; an https feed keeps its host and path. */
fun webcalUrl(feedUrl:String):String? {
    val uri=runCatching {java.net.URI(feedUrl)}.getOrNull()?:return null
    return when(uri.scheme?.lowercase()) {"https","http"->"webcal"+feedUrl.substring(uri.scheme.length);"webcal"->feedUrl;else->null}
}
