package org.spaceworks.app.features

import kotlinx.coroutines.flow.update
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberPage
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.platform.MobileText
import org.spaceworks.generated.api.PublicInventoryApi
import org.spaceworks.generated.model.ContextualSelf
import org.spaceworks.generated.model.KitSelection
import org.spaceworks.generated.model.RequestItemInput
import java.util.UUID

data class KitLine(val productId:Int,val name:String,val perPerson:Int,val returnable:Boolean)
data class EquipmentKit(val id:Int,val name:String,val version:Int,val lines:List<KitLine>)
/** What the equipment is for: one of the member's own registrations and/or bookings. */
data class RequestContext(val registrationId:Long?,val bookingToken:String?,val label:String) {
    init {require(registrationId!=null || bookingToken!=null)}
}
data class ContextualDraft(val context:RequestContext,val requestedFor:String,val kit:EquipmentKit?,val items:Map<Int,Int>)
data class ContextualOutcome(val consumablePlan:Boolean,val status:String?,val replayed:Boolean)

interface ContextualRequestGateway {
    suspend fun kits(scope:MemberScope,page:Int):MemberPage<EquipmentKit>
    suspend fun kit(scope:MemberScope,id:Int):EquipmentKit
    suspend fun submit(scope:MemberScope,operationId:UUID,draft:ContextualDraft):ContextualOutcome
}

class GeneratedContextualRequestGateway(private val session:ApiSession):ContextualRequestGateway {
    private fun api(scope:MemberScope)=session.scopedApi(PublicInventoryApi::class.java,scope.id)
    private fun kit(value:org.spaceworks.generated.model.PublicKit)=EquipmentKit(value.id,value.name,value.version,
        value.lines.map {KitLine(it.productId,it.displayName,it.quantityPerPerson,it.kind.value!="consumable")})
    override suspend fun kits(scope:MemberScope,page:Int):MemberPage<EquipmentKit> {
        val body=api(scope).apiV1PublicEquipmentKitsList(scope.slug,page).requireBody()
        return MemberPage(body.results.map(::kit),body.next?.toString(),body.count)
    }
    override suspend fun kit(scope:MemberScope,id:Int)=kit(api(scope).apiV1PublicEquipmentKitRetrieve(id,scope.slug).requireBody())
    override suspend fun submit(scope:MemberScope,operationId:UUID,draft:ContextualDraft):ContextualOutcome {
        // Exactly one of a kit or items; never the `website` bot-trap field.
        val body=ContextualSelf(operationId=operationId,requestedFor=draft.requestedFor.trim(),
            items=if(draft.kit==null)draft.items.filterValues {it>0}.map {(id,quantity)->RequestItemInput(id,quantity)} else null,
            eventRegistrationId=draft.context.registrationId,kit=draft.kit?.let {KitSelection(it.id.toLong(),it.version.toLong())},
            bookingToken=draft.context.bookingToken?.let(UUID::fromString))
        val value=api(scope).apiV1PublicRequestsContextualCreate(scope.slug,body).requireBody()
        return ContextualOutcome(value.kind.value=="consumable_plan",value.status,value.replayed)
    }
}

data class ContextualState(val scope:MemberScope?=null,val kits:List<EquipmentKit> = emptyList(),val kitsLoaded:Boolean=false,
    val loading:Boolean=false,val context:RequestContext?=null,val kit:EquipmentKit?=null,val items:Map<Int,Int> = emptyMap(),
    val requestedFor:String="",val submitting:Boolean=false,val outcome:ContextualOutcome?=null,val error:String?=null,
    /** Kept across retries of the same draft so the server can recognise a replay; cleared when the draft changes. */
    val operationId:UUID?=null) {
    val ready get()=context!=null && (kit!=null || items.values.any {it>0}) && !submitting
}

class ContextualRequestStateHolder(private val api:ContextualRequestGateway,text:MobileText=MobileText.ENGLISH,
    onUnauthorized:suspend()->Unit={},private val newOperation:()->UUID=UUID::randomUUID)
    :FeatureHolder<ContextualState>({ContextualState(scope=it)},text,onUnauthorized) {
    suspend fun refreshKits() {
        if(state.value.loading)return
        run({it.copy(loading=true,error=null)},{api.kits(it,1)},{s,page->s.copy(kits=page.items,kitsLoaded=true,loading=false)},
            {s,message,_->s.copy(loading=false,error=message)})
    }
    private fun edit(change:(ContextualState)->ContextualState){
        if(state.value.submitting)return
        mutable.update {change(it).copy(operationId=null,outcome=null,error=null)}
    }
    fun chooseContext(value:RequestContext)=edit {it.copy(context=value)}
    fun describe(value:String)=edit {it.copy(requestedFor=value.take(500))}
    fun setQuantity(productId:Int,quantity:Int)=edit {it.copy(kit=null,items=(it.items+(productId to quantity.coerceIn(0,99))).filterValues {q->q>0})}
    fun clearKit()=edit {it.copy(kit=null)}
    /** Picks a kit at its current published version; the kit replaces any item list. */
    suspend fun chooseKit(id:Int) {
        if(state.value.submitting)return
        run({it.copy(loading=true,error=null)},{api.kit(it,id)},{s,kit->s.copy(kit=kit,items=emptyMap(),operationId=null,outcome=null,loading=false)},
            {s,message,_->s.copy(loading=false,error=message)})
    }
    /** Submits the draft. A retry of an unchanged draft reuses its operation ID; nothing is retried automatically. */
    suspend fun submit():Boolean {
        val current=state.value
        if(!current.ready)return false
        val draft=ContextualDraft(requireNotNull(current.context),current.requestedFor,current.kit,current.items)
        val operation=current.operationId?:newOperation()
        return run({it.copy(submitting=true,error=null,operationId=operation)},{api.submit(it,operation,draft)},
            {s,outcome->s.copy(submitting=false,outcome=outcome,operationId=null,kit=null,items=emptyMap(),requestedFor="")},
            {s,message,failure->
                // operation_conflict: this ID was used for different content; the next submit starts a new operation.
                val copy=when(failure?.code){"lock_busy"->text(FeatureStrings.KITS_LOCK_BUSY);"operation_conflict"->text(FeatureStrings.KITS_OPERATION_CONFLICT);else->message}
                s.copy(submitting=false,error=copy,operationId=if(failure?.code=="operation_conflict")null else s.operationId)
            })!=null
    }
}
