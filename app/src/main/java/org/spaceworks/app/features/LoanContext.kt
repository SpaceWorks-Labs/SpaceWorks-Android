package org.spaceworks.app.features

import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.platform.MobileText
import org.spaceworks.generated.api.AdminRequestsApi
import org.spaceworks.generated.model.BookingContextPut
import org.spaceworks.generated.model.BookingLoanContextCAS
import org.spaceworks.generated.model.EventContextPut
import org.spaceworks.generated.model.EventLoanContextCAS
import java.util.UUID

/** A reviewed request or a direct handout. */
data class LoanRef(val id:Int,val direct:Boolean)
enum class ContextKind { EVENT, BOOKING }
/** What a loan is for. [generation]/[revision] are the optimistic-concurrency pair every write must echo. */
data class LoanContext(val kind:ContextKind,val active:Boolean,val generation:UUID,val revision:Int,val canWrite:Boolean,
    val targetId:Int?,val participantRegistrationId:Int?)

interface LoanContextGateway {
    suspend fun read(scope:MemberScope,loan:LoanRef,kind:ContextKind):LoanContext
    /** Links an event (with an optional participant registration) or a booking, from the last-read [current]. */
    suspend fun link(scope:MemberScope,loan:LoanRef,current:LoanContext,targetId:Int,participantRegistrationId:Int?=null):LoanContext
    suspend fun unlink(scope:MemberScope,loan:LoanRef,current:LoanContext):LoanContext
}

/** Staff step-up session only. A 409 is returned to the caller; nothing here replays a write. */
class GeneratedLoanContextGateway(private val staffSession:ApiSession):LoanContextGateway {
    private fun api(scope:MemberScope)=staffSession.scopedApi(AdminRequestsApi::class.java,scope.id)
    private fun event(value:org.spaceworks.generated.model.EventContextResponse)=LoanContext(ContextKind.EVENT,value.active,value.generation,value.revision,
        value.canWrite,value.eventId,value.participantRegistrationId.takeIf {value.mayViewParticipant==true})
    private fun booking(value:org.spaceworks.generated.model.BookingContextResponse)=LoanContext(ContextKind.BOOKING,value.active,value.generation,value.revision,
        value.canWrite,value.bookingId,null)
    override suspend fun read(scope:MemberScope,loan:LoanRef,kind:ContextKind):LoanContext {
        val api=api(scope)
        return when {
            kind==ContextKind.EVENT && loan.direct->event(api.apiV1AdminMakerspaceDirectLoansEventContextRetrieve(loanId=loan.id,makerspaceId=scope.id).requireBody())
            kind==ContextKind.EVENT->event(api.apiV1AdminMakerspaceRequestsEventContextRetrieve(makerspaceId=scope.id,requestId=loan.id).requireBody())
            loan.direct->booking(api.apiV1AdminMakerspaceDirectLoansBookingContextRetrieve(loanId=loan.id,makerspaceId=scope.id).requireBody())
            else->booking(api.apiV1AdminMakerspaceRequestsBookingContextRetrieve(makerspaceId=scope.id,requestId=loan.id).requireBody())
        }
    }
    override suspend fun link(scope:MemberScope,loan:LoanRef,current:LoanContext,targetId:Int,participantRegistrationId:Int?):LoanContext {
        val api=api(scope)
        return if(current.kind==ContextKind.EVENT) {
            val body=EventContextPut(current.generation,current.revision,targetId,participantRegistrationId)
            event(if(loan.direct)api.apiV1AdminMakerspaceDirectLoansEventContextUpdate(loanId=loan.id,makerspaceId=scope.id,eventContextPut=body).requireBody()
                else api.apiV1AdminMakerspaceRequestsEventContextUpdate(makerspaceId=scope.id,requestId=loan.id,eventContextPut=body).requireBody())
        } else {
            val body=BookingContextPut(current.generation,current.revision,targetId)
            booking(if(loan.direct)api.apiV1AdminMakerspaceDirectLoansBookingContextUpdate(loanId=loan.id,makerspaceId=scope.id,bookingContextPut=body).requireBody()
                else api.apiV1AdminMakerspaceRequestsBookingContextUpdate(makerspaceId=scope.id,requestId=loan.id,bookingContextPut=body).requireBody())
        }
    }
    override suspend fun unlink(scope:MemberScope,loan:LoanRef,current:LoanContext):LoanContext {
        val api=api(scope)
        return if(current.kind==ContextKind.EVENT) {
            val body=EventLoanContextCAS(current.generation,current.revision)
            event(if(loan.direct)api.apiV1AdminMakerspaceDirectLoansEventContextDestroy(loanId=loan.id,makerspaceId=scope.id,eventLoanContextCAS=body).requireBody()
                else api.apiV1AdminMakerspaceRequestsEventContextDestroy(makerspaceId=scope.id,requestId=loan.id,eventLoanContextCAS=body).requireBody())
        } else {
            val body=BookingLoanContextCAS(current.generation,current.revision)
            booking(if(loan.direct)api.apiV1AdminMakerspaceDirectLoansBookingContextDestroy(loanId=loan.id,makerspaceId=scope.id,bookingLoanContextCAS=body).requireBody()
                else api.apiV1AdminMakerspaceRequestsBookingContextDestroy(makerspaceId=scope.id,requestId=loan.id,bookingLoanContextCAS=body).requireBody())
        }
    }
}

/** A context that answered 403/404 (module off, or no authority) is not shown at all. */
data class LoanContextState(val scope:MemberScope?=null,val loan:LoanRef?=null,val contexts:Map<ContextKind,LoanContext> = emptyMap(),
    val loading:Boolean=false,val busy:ContextKind?=null,val stale:Boolean=false,val error:String?=null)

class LoanContextStateHolder(private val api:LoanContextGateway,text:MobileText=MobileText.ENGLISH,onUnauthorized:suspend()->Unit={})
    :FeatureHolder<LoanContextState>({LoanContextState(scope=it)},text,onUnauthorized) {
    /** Reads both contexts of [loan]; called when its detail opens and to "review the latest state". */
    suspend fun open(loan:LoanRef) {
        if(state.value.loading || state.value.busy!=null)return
        run({LoanContextState(scope=it.scope,loan=loan,loading=true)},{s->ContextKind.entries.mapNotNull {kind->
            try {api.read(s,loan,kind)}catch(failure:org.spaceworks.app.network.ApiFailure){if(failure.status==403 || failure.status==404)null else throw failure}
        }},{s,values->if(s.loan!=loan)s else s.copy(contexts=values.associateBy {it.kind},loading=false)},{s,message,_->s.copy(loading=false,error=message)})
    }
    suspend fun link(kind:ContextKind,targetId:Int,participantRegistrationId:Int?=null)=write(kind) {scope,loan,current->api.link(scope,loan,current,targetId,participantRegistrationId)}
    suspend fun unlink(kind:ContextKind)=write(kind) {scope,loan,current->api.unlink(scope,loan,current)}
    private suspend fun write(kind:ContextKind,block:suspend(MemberScope,LoanRef,LoanContext)->LoanContext) {
        val current=state.value
        val loan=current.loan?:return
        val context=current.contexts[kind]?.takeIf {it.canWrite}?:return
        if(current.busy!=null || current.loading || current.stale)return
        run({it.copy(busy=kind,error=null)},{block(it,loan,context)},{s,value->s.copy(busy=null,contexts=s.contexts+(kind to value))},{s,message,failure->
            // 409: someone else changed it (or it was restored). Never replay: ask for a review of the latest state.
            if(failure?.status==409)s.copy(busy=null,stale=true,error=text(FeatureStrings.CONTEXT_STALE)) else s.copy(busy=null,error=message)
        })
    }
}
