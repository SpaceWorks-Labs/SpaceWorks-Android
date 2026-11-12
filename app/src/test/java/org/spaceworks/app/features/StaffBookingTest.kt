package org.spaceworks.app.features

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberPage
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val DIGEST="a".repeat(64)
private val NEW_DIGEST="b".repeat(64)
private fun quoteJson(total:String,digest:String)="""{"charges":"tracked","quote":{"domain":"bookings","currency":"inr","mode":"hourly","lines":[],"subtotal":"$total","discount_total":"0.00","total":"$total","discount":null,"waiver":null,"notes":["Member rate"],"digest":"d"},"included_hours":null,"price_confirmation":"$digest"}"""

class StaffBookingGatewayTest {
    private val scope=MemberScope(7,"workshop")
    private val start=OffsetDateTime.parse("2026-11-14T10:00:00+05:30");private val end=start.plusHours(1)
    @Test fun membersQuoteAndReadiness()=withSession {session,server->
        val gateway=GeneratedStaffBookingGateway(session)
        server.enqueue(json("""{"count":1,"next":null,"previous":null,"results":[{"member_id":21,"display_name":"Meera"}]}"""))
        assertEquals(listOf(BookingMember(21,"Meera")),gateway.members(scope,3,1).items)
        assertEquals("/api/v1/admin/spaces/3/eligible-members/?page=1",server.next().path)
        server.enqueue(json(quoteJson("500.00",DIGEST)))
        assertEquals(BookingQuote(true,"inr","500.00",listOf("Member rate"),DIGEST),gateway.quote(scope,3,21,start,end))
        val quote=server.next();assertEquals("POST",quote.method);assertEquals("/api/v1/admin/spaces/3/bookings/quote/",quote.path);assertEquals("7",quote.getHeader("X-Makerspace-Id"))
        val body=Json.parseToJsonElement(quote.body.readUtf8()).jsonObject
        assertEquals(21,body["member_id"]!!.jsonPrimitive.int);assertEquals(start,OffsetDateTime.parse(body["starts_at"]!!.jsonPrimitive.content))
        server.enqueue(json("""{"ready":false,"checks":[{"code":"certification","state":"blocked","detail":"Needs laser induction.","certifications":["Laser"]},{"code":"future_check","state":"ok","detail":"x"}],"maintenance_link":null}"""))
        val readiness=gateway.readiness(scope,3,21,start,end)
        assertFalse(readiness.ready);assertEquals(listOf("Laser"),readiness.checks[0].certifications)
        val get=server.next();assertEquals("GET",get.method);assertTrue(get.path!!.startsWith("/api/v1/admin/spaces/3/bookings/readiness/?"))
        assertTrue(get.path!!.contains("member_id=21"))
        server.enqueue(json("""{"ready":true,"checks":[],"maintenance_link":null}"""))
        assertTrue(gateway.bookingReadiness(scope,55).ready);assertEquals("/api/v1/admin/bookings/55/readiness/",server.next().path)
    }
    @Test fun createSendsConfirmationAndReadsPriceChanged()=withSession {session,server->
        val gateway=GeneratedStaffBookingGateway(session)
        server.enqueue(json("""{"detail":"The price changed.","code":"price_changed","confirmation":${quoteJson("550.00",NEW_DIGEST)}}""",409))
        val moved=gateway.create(scope,3,21,start,end," ",DIGEST)
        assertEquals(StaffBookingResult.PriceChanged(BookingQuote(true,"inr","550.00",listOf("Member rate"),NEW_DIGEST)),moved)
        val create=server.next();assertEquals("POST",create.method);assertEquals("/api/v1/admin/spaces/3/bookings/",create.path)
        val body=Json.parseToJsonElement(create.body.readUtf8()).jsonObject
        assertEquals(DIGEST,body["price_confirmation"]!!.jsonPrimitive.content);assertEquals(21,body["member_id"]!!.jsonPrimitive.int)
        assertNull(body["note"]);assertNull(body["house"])
        server.enqueue(json("""{"detail":"Room is closed.","code":"room_outage"}""",409))
        try {gateway.create(scope,3,21,start,end,"",DIGEST);fail()}catch(f:ApiFailure){assertEquals("Room is closed.",f.detail)};server.next()
    }
}

private class FakeBooking:StaffBookingGateway {
    var creates=mutableListOf<String>();var moveOnce=true;var ready=true
    override suspend fun members(scope:MemberScope,spaceId:Int,page:Int)=MemberPage(listOf(BookingMember(21,"M")),null,1)
    override suspend fun quote(scope:MemberScope,spaceId:Int,memberId:Int,start:OffsetDateTime,end:OffsetDateTime)=BookingQuote(true,"inr","500.00",emptyList(),DIGEST)
    override suspend fun readiness(scope:MemberScope,spaceId:Int,memberId:Int,start:OffsetDateTime,end:OffsetDateTime)=BookingReadinessView(ready,emptyList())
    override suspend fun bookingReadiness(scope:MemberScope,bookingId:Int)=BookingReadinessView(true,emptyList())
    override suspend fun create(scope:MemberScope,spaceId:Int,memberId:Int,start:OffsetDateTime,end:OffsetDateTime,note:String,confirmation:String):StaffBookingResult {
        creates+=confirmation
        if(moveOnce){moveOnce=false;return StaffBookingResult.PriceChanged(BookingQuote(true,"inr","550.00",emptyList(),NEW_DIGEST))}
        return StaffBookingResult.Created(9,"confirmed")
    }
}

class StaffBookingStateTest {
    private val now=ZonedDateTime.of(2026,11,13,8,0,0,0,ZoneId.of("Asia/Kolkata"))
    @Test fun priceChangeNeedsASecondConfirmWithTheNewDigest()=runTest {
        val api=FakeBooking();val holder=StaffBookingStateHolder(api,now={now});holder.activate(MemberScope(7,"a"))
        holder.open(3);holder.confirm();assertTrue(api.creates.isEmpty())
        holder.edit {it.copy(memberId=21,dayOffset=1,startMinutes=600,durationMinutes=90)}
        assertEquals(OffsetDateTime.parse("2026-11-14T10:00+05:30") to OffsetDateTime.parse("2026-11-14T11:30+05:30"),holder.window())
        holder.preview();assertTrue(holder.state.value.canConfirm)
        holder.confirm();assertEquals(listOf(DIGEST),api.creates);assertTrue(holder.state.value.priceChanged);assertEquals("550.00",holder.state.value.quote!!.total)
        assertNull(holder.state.value.created)
        holder.confirm();assertEquals(listOf(DIGEST,NEW_DIGEST),api.creates);assertEquals(9,holder.state.value.created!!.id)
    }
    @Test fun blockedReadinessDisablesConfirmAndEditsClearThePrice()=runTest {
        val api=FakeBooking().apply {ready=false};val holder=StaffBookingStateHolder(api,now={now});holder.activate(MemberScope(7,"a"))
        holder.open(3);holder.edit {it.copy(memberId=21)};holder.preview()
        assertFalse(holder.state.value.canConfirm);holder.confirm();assertTrue(api.creates.isEmpty())
        holder.edit {it.copy(durationMinutes=5)};assertNull(holder.state.value.quote);assertEquals(30,holder.state.value.draft.durationMinutes)
        holder.checkExisting(5);assertTrue(holder.state.value.existing[5]!!.ready)
    }
}
