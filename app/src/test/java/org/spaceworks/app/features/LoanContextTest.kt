package org.spaceworks.app.features

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.Errors
import java.util.UUID

private const val GEN="00000000-0000-4000-8000-000000000001"

class LoanContextGatewayTest {
    private val scope=MemberScope(7,"workshop")
    @Test fun requestEventContextReadAndCasWrites()=withSession {session,server->
        val gateway=GeneratedLoanContextGateway(session)
        server.enqueue(json("""{"active":true,"generation":"$GEN","revision":3,"can_write":true,"event_id":12,"may_view_participant":false,"participant_registration_id":40}"""))
        val read=gateway.read(scope,LoanRef(5,false),ContextKind.EVENT)
        assertEquals(LoanContext(ContextKind.EVENT,true,UUID.fromString(GEN),3,true,12,null),read)
        val get=server.next();assertEquals("GET",get.method);assertEquals("/api/v1/admin/makerspace/7/requests/5/event-context",get.path);assertEquals("7",get.getHeader("X-Makerspace-Id"))
        server.enqueue(json("""{"active":true,"generation":"$GEN","revision":4,"can_write":true,"event_id":13,"may_view_participant":true,"participant_registration_id":null}"""))
        assertEquals(4,gateway.link(scope,LoanRef(5,false),read,13).revision)
        val put=server.next();assertEquals("PUT",put.method);assertEquals("/api/v1/admin/makerspace/7/requests/5/event-context",put.path)
        // The schema requires participant_registration_id even when null, but this client omits nulls (backend gap recorded).
        assertEquals(buildJsonObject {put("expected_generation",GEN);put("expected_revision",3);put("event_id",13)},Json.parseToJsonElement(put.body.readUtf8()))
        server.enqueue(json("""{"active":false,"generation":"$GEN","revision":5,"can_write":true}"""))
        gateway.unlink(scope,LoanRef(5,false),read.copy(revision=4))
        val delete=server.next();assertEquals("DELETE",delete.method)
        assertEquals(buildJsonObject {put("expected_generation",GEN);put("expected_revision",4)},Json.parseToJsonElement(delete.body.readUtf8()))
    }
    @Test fun directLoanBookingContext()=withSession {session,server->
        val gateway=GeneratedLoanContextGateway(session)
        server.enqueue(json("""{"active":false,"generation":"$GEN","revision":0,"can_write":true,"booking_id":null}"""))
        val read=gateway.read(scope,LoanRef(9,true),ContextKind.BOOKING)
        assertEquals("/api/v1/admin/makerspace/7/direct-loans/9/booking-context",server.next().path)
        server.enqueue(json("""{"active":true,"generation":"$GEN","revision":1,"can_write":true,"booking_id":77}"""))
        assertEquals(77,gateway.link(scope,LoanRef(9,true),read,77).targetId)
        val put=server.next();assertEquals("PUT",put.method);assertEquals("/api/v1/admin/makerspace/7/direct-loans/9/booking-context",put.path)
        assertEquals(buildJsonObject {put("expected_generation",GEN);put("expected_revision",0);put("booking_id",77)},Json.parseToJsonElement(put.body.readUtf8()))
        server.enqueue(json("""{"detail":"Changed.","code":"conflict"}""",409))
        try {gateway.unlink(scope,LoanRef(9,true),read);fail()}catch(f:ApiFailure){assertEquals(409,f.status)}
        assertEquals("DELETE",server.next().method)
    }
}

private class FakeContexts:LoanContextGateway {
    var writes=0;var reads=0;var conflict=false;var bookingOff=true
    override suspend fun read(scope:MemberScope,loan:LoanRef,kind:ContextKind):LoanContext {
        reads++
        if(kind==ContextKind.BOOKING && bookingOff)throw ApiFailure(404,"Not found.",null,null)
        return LoanContext(kind,false,UUID(0,1),reads,true,null,null)
    }
    override suspend fun link(scope:MemberScope,loan:LoanRef,current:LoanContext,targetId:Int,participantRegistrationId:Int?):LoanContext {
        writes++;if(conflict)throw Errors.map(409,"""{"detail":"Changed.","code":"conflict"}""")
        return current.copy(active=true,targetId=targetId,revision=current.revision+1)
    }
    override suspend fun unlink(scope:MemberScope,loan:LoanRef,current:LoanContext)=current.copy(active=false,targetId=null)
}

class LoanContextStateTest {
    @Test fun hiddenModulesAndConflictIsNeverReplayed()=runTest {
        val api=FakeContexts();val holder=LoanContextStateHolder(api);holder.activate(MemberScope(7,"a"))
        holder.open(LoanRef(5,false))
        assertEquals(setOf(ContextKind.EVENT),holder.state.value.contexts.keys)
        holder.link(ContextKind.BOOKING,3);assertEquals(0,api.writes)
        holder.link(ContextKind.EVENT,12);assertEquals(12,holder.state.value.contexts[ContextKind.EVENT]!!.targetId)
        api.conflict=true;holder.unlink(ContextKind.EVENT);holder.link(ContextKind.EVENT,13)
        assertTrue(holder.state.value.stale);assertEquals(FeatureStrings.CONTEXT_STALE.english,holder.state.value.error)
        holder.link(ContextKind.EVENT,14);assertEquals(2,api.writes)
        // Reviewing re-reads; only then may the person write again.
        api.conflict=false;holder.open(LoanRef(5,false));assertFalse(holder.state.value.stale)
        holder.link(ContextKind.EVENT,14);assertEquals(3,api.writes)
    }
    @Test fun readOnlyContextHasNoWrites()=runTest {
        val api=object:LoanContextGateway {
            var writes=0
            override suspend fun read(scope:MemberScope,loan:LoanRef,kind:ContextKind)=LoanContext(kind,true,UUID(0,1),1,false,4,null)
            override suspend fun link(scope:MemberScope,loan:LoanRef,current:LoanContext,targetId:Int,participantRegistrationId:Int?):LoanContext {writes++;return current}
            override suspend fun unlink(scope:MemberScope,loan:LoanRef,current:LoanContext):LoanContext {writes++;return current}
        }
        val holder=LoanContextStateHolder(api);holder.activate(MemberScope(7,"a"));holder.open(LoanRef(1,true))
        holder.unlink(ContextKind.EVENT);holder.link(ContextKind.BOOKING,2);assertEquals(0,api.writes)
    }
}
