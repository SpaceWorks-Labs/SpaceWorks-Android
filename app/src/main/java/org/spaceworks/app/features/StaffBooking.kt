package org.spaceworks.app.features

import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import org.openapitools.client.infrastructure.BigDecimalAdapter
import org.openapitools.client.infrastructure.OffsetDateTimeAdapter
import org.openapitools.client.infrastructure.UUIDAdapter
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberPage
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.Errors
import org.spaceworks.app.platform.MobileText
import org.spaceworks.generated.api.AdminBookingsApi
import org.spaceworks.generated.model.StaffBookingQuote
import org.spaceworks.generated.model.StaffBookingQuoteInput
import org.spaceworks.generated.model.StaffSingleBookingInput
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class BookingMember(val id:Int,val name:String)
/** [confirmation] is the server digest the create must echo; amounts stay the server's decimal strings. */
data class BookingQuote(val charged:Boolean,val currency:String,val total:String,val notes:List<String>,val confirmation:String)
data class ReadinessItem(val code:String,val state:String,val detail:String,val certifications:List<String>)
data class BookingReadinessView(val ready:Boolean,val checks:List<ReadinessItem>)
sealed interface StaffBookingResult {
    data class Created(val id:Int,val status:String):StaffBookingResult
    /** 409 `price_changed`: nothing was created or charged; show this price and ask again. */
    data class PriceChanged(val quote:BookingQuote):StaffBookingResult
}

interface StaffBookingGateway {
    suspend fun members(scope:MemberScope,spaceId:Int,page:Int):MemberPage<BookingMember>
    suspend fun quote(scope:MemberScope,spaceId:Int,memberId:Int,start:OffsetDateTime,end:OffsetDateTime):BookingQuote
    suspend fun readiness(scope:MemberScope,spaceId:Int,memberId:Int,start:OffsetDateTime,end:OffsetDateTime):BookingReadinessView
    suspend fun bookingReadiness(scope:MemberScope,bookingId:Int):BookingReadinessView
    suspend fun create(scope:MemberScope,spaceId:Int,memberId:Int,start:OffsetDateTime,end:OffsetDateTime,note:String,confirmation:String):StaffBookingResult
}

/** Staff step-up session only. */
class GeneratedStaffBookingGateway(private val staffSession:ApiSession):StaffBookingGateway {
    private fun api(scope:MemberScope)=staffSession.scopedApi(AdminBookingsApi::class.java,scope.id)
    private val json=Json {ignoreUnknownKeys=true;serializersModule=SerializersModule {
        contextual(java.util.UUID::class,UUIDAdapter);contextual(OffsetDateTime::class,OffsetDateTimeAdapter);contextual(java.math.BigDecimal::class,BigDecimalAdapter)
    }}
    private fun quote(value:StaffBookingQuote)=BookingQuote(value.charges.value=="tracked",value.quote?.currency.orEmpty(),value.quote?.total?.toPlainString().orEmpty(),
        value.quote?.notes.orEmpty(),value.priceConfirmation)
    private fun readiness(value:org.spaceworks.generated.model.BookingReadiness)=BookingReadinessView(value.ready,
        value.checks.map {ReadinessItem(it.code.value,it.state.value,it.detail,it.certifications.orEmpty())})
    override suspend fun members(scope:MemberScope,spaceId:Int,page:Int):MemberPage<BookingMember> {
        val body=api(scope).apiV1AdminSpacesEligibleMembersRetrieve(id=spaceId,page=page).requireBody()
        return MemberPage(body.results.map {BookingMember(it.memberId,it.displayName)},body.next,body.count)
    }
    override suspend fun quote(scope:MemberScope,spaceId:Int,memberId:Int,start:OffsetDateTime,end:OffsetDateTime)=
        quote(api(scope).apiV1AdminSpacesBookingsQuoteCreate(spaceId,StaffBookingQuoteInput(memberId.toLong(),start,end)).requireBody())
    override suspend fun readiness(scope:MemberScope,spaceId:Int,memberId:Int,start:OffsetDateTime,end:OffsetDateTime)=
        readiness(api(scope).apiV1AdminSpacesBookingsReadinessRetrieve(id=spaceId,endsAt=end,startsAt=start,memberId=memberId.toLong()).requireBody())
    override suspend fun bookingReadiness(scope:MemberScope,bookingId:Int)=readiness(api(scope).apiV1AdminBookingsReadinessRetrieve(bookingId).requireBody())
    override suspend fun create(scope:MemberScope,spaceId:Int,memberId:Int,start:OffsetDateTime,end:OffsetDateTime,note:String,confirmation:String):StaffBookingResult {
        val response=api(scope).apiV1AdminSpacesBookingsCreate(spaceId,StaffSingleBookingInput(startsAt=start,endsAt=end,memberId=memberId.toLong(),
            note=note.trim().takeIf {it.isNotEmpty()},priceConfirmation=confirmation))
        if(response.isSuccessful) {
            val booking=response.body()?:throw IllegalStateException("Empty API response")
            return StaffBookingResult.Created(booking.id,booking.status.value)
        }
        val raw=response.errorBody()?.string()
        if(response.code()==409) {
            val body=runCatching {Json.parseToJsonElement(requireNotNull(raw)).jsonObject}.getOrNull()
            if((body?.get("code") as? JsonPrimitive)?.contentOrNull=="price_changed")
                (body["confirmation"] as? JsonObject)?.let {runCatching {json.decodeFromJsonElement(StaffBookingQuote.serializer(),it)}.getOrNull()}
                    ?.let {return StaffBookingResult.PriceChanged(quote(it))}
        }
        throw Errors.map(response.code(),raw,response.headers()["Retry-After"])
    }
}

/** A new staff booking: member, day, start and length; times are this device's local time. */
data class StaffBookingDraft(val memberId:Int?=null,val dayOffset:Int=0,val startMinutes:Int=10*60,val durationMinutes:Int=60,val note:String="")
data class StaffBookingState(val scope:MemberScope?=null,val spaceId:Int?=null,val members:List<BookingMember> = emptyList(),
    val draft:StaffBookingDraft=StaffBookingDraft(),val quote:BookingQuote?=null,val readiness:BookingReadinessView?=null,
    val priceChanged:Boolean=false,val created:StaffBookingResult.Created?=null,val existing:Map<Int,BookingReadinessView> = emptyMap(),
    val busy:Boolean=false,val error:String?=null) {
    /** Readiness is advisory, but a blocked check disables confirm; the create rechecks everything. */
    val canConfirm get()=quote!=null && readiness?.ready==true && !busy && draft.memberId!=null
}

class StaffBookingStateHolder(private val api:StaffBookingGateway,text:MobileText=MobileText.ENGLISH,onUnauthorized:suspend()->Unit={},
    private val now:()->ZonedDateTime={ZonedDateTime.now(ZoneId.systemDefault())})
    :FeatureHolder<StaffBookingState>({StaffBookingState(scope=it)},text,onUnauthorized) {
    fun window(draft:StaffBookingDraft=state.value.draft):Pair<OffsetDateTime,OffsetDateTime> {
        val start=now().toLocalDate().plusDays(draft.dayOffset.toLong()).atStartOfDay(now().zone).plusMinutes(draft.startMinutes.toLong())
        return start.toOffsetDateTime() to start.plusMinutes(draft.durationMinutes.toLong()).toOffsetDateTime()
    }
    suspend fun open(spaceId:Int) {
        if(state.value.spaceId==spaceId && state.value.members.isNotEmpty())return
        run({StaffBookingState(scope=it.scope,spaceId=spaceId,busy=true)},{api.members(it,spaceId,1)},
            {s,page->if(s.spaceId!=spaceId)s else s.copy(members=page.items,busy=false)},{s,message,_->s.copy(busy=false,error=message)})
    }
    /** Any change invalidates the shown price and checks. */
    fun edit(change:(StaffBookingDraft)->StaffBookingDraft) {
        if(state.value.busy)return
        mutable.update {it.copy(draft=change(it.draft).let {d->d.copy(dayOffset=d.dayOffset.coerceIn(0,60),startMinutes=d.startMinutes.coerceIn(0,24*60-30),
            durationMinutes=d.durationMinutes.coerceIn(30,12*60),note=d.note.take(5000))},quote=null,readiness=null,priceChanged=false,created=null,error=null)}
    }
    suspend fun preview() {
        val current=state.value
        val spaceId=current.spaceId?:return
        val member=current.draft.memberId?:return
        if(current.busy)return
        val (start,end)=window()
        run({it.copy(busy=true,error=null,quote=null,readiness=null,priceChanged=false)},{scope->api.quote(scope,spaceId,member,start,end) to api.readiness(scope,spaceId,member,start,end)},
            {s,(quote,readiness)->s.copy(busy=false,quote=quote,readiness=readiness)},{s,message,_->s.copy(busy=false,error=message)})
    }
    /** Creates with the shown price only; a moved price replaces the quote and waits for a new confirmation. */
    suspend fun confirm() {
        val current=state.value
        if(!current.canConfirm)return
        val spaceId=current.spaceId?:return
        val (start,end)=window()
        run({it.copy(busy=true,error=null)},{api.create(it,spaceId,requireNotNull(current.draft.memberId),start,end,current.draft.note,requireNotNull(current.quote).confirmation)},
            {s,result->when(result) {
                is StaffBookingResult.Created->s.copy(busy=false,created=result,quote=null,readiness=null,priceChanged=false,draft=StaffBookingDraft())
                is StaffBookingResult.PriceChanged->s.copy(busy=false,quote=result.quote,priceChanged=true)
            }},{s,message,_->s.copy(busy=false,error=message)})
    }
    suspend fun checkExisting(bookingId:Int) {
        if(state.value.busy)return
        run({it.copy(busy=true,error=null)},{api.bookingReadiness(it,bookingId)},{s,value->s.copy(busy=false,existing=s.existing+(bookingId to value))},
            {s,message,_->s.copy(busy=false,error=message)})
    }
}
