package org.spaceworks.app.features

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberPage
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.Errors
import java.util.UUID

private const val KIT="""{"id":3,"name":"Soldering kit","version":2,"lines":[{"product_id":11,"display_name":"Iron","quantity_per_person":1,"kind":"returnable"},{"product_id":12,"display_name":"Solder","quantity_per_person":2,"kind":"consumable"}]}"""

class ContextualRequestGatewayTest {
    private val scope=MemberScope(7,"workshop")
    private val operation=UUID.fromString("00000000-0000-4000-8000-000000000001")
    @Test fun listsAndReadsPublishedKits()=withSession {session,server->
        val gateway=GeneratedContextualRequestGateway(session)
        server.enqueue(json("""{"count":1,"next":null,"previous":null,"results":[$KIT]}"""))
        val kit=gateway.kits(scope,1).items.single()
        assertEquals(EquipmentKit(3,"Soldering kit",2,listOf(KitLine(11,"Iron",1,true),KitLine(12,"Solder",2,false))),kit)
        val list=server.next();assertEquals("GET",list.method);assertEquals("/api/v1/public/workshop/equipment-kits/?page=1",list.path);assertEquals("7",list.getHeader("X-Makerspace-Id"))
        server.enqueue(json(KIT));assertEquals(2,gateway.kit(scope,3).version)
        assertEquals("/api/v1/public/workshop/equipment-kits/3/",server.next().path)
    }
    @Test fun submitsKitForRegistrationWithOperationId()=withSession {session,server->
        server.enqueue(json("""{"kind":"request","public_token":"00000000-0000-4000-8000-0000000000aa","status":"pending_approval","replayed":false}""",201))
        val kit=EquipmentKit(3,"Soldering kit",2,emptyList())
        val outcome=GeneratedContextualRequestGateway(session).submit(scope,operation,ContextualDraft(RequestContext(55,null,"Event"),"  For the workshop ",kit,mapOf(9 to 1)))
        assertEquals(ContextualOutcome(false,"pending_approval",false),outcome)
        val request=server.next();assertEquals("POST",request.method);assertEquals("/api/v1/public/workshop/requests/contextual",request.path)
        val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(operation.toString(),body["operation_id"]!!.jsonPrimitive.content)
        assertEquals("For the workshop",body["requested_for"]!!.jsonPrimitive.content)
        assertEquals(55,body["event_registration_id"]!!.jsonPrimitive.int)
        assertEquals(buildJsonObject {put("id",3);put("version",2)},body["kit"])
        // Exactly one of kit or items, and never the bot-trap field.
        assertNull(body["items"]);assertNull(body["website"]);assertTrue(body["booking_token"]==null || body["booking_token"] is JsonNull)
    }
    @Test fun submitsItemsForBookingAndMapsConflicts()=withSession {session,server->
        val token="00000000-0000-4000-8000-0000000000bb"
        server.enqueue(json("""{"kind":"consumable_plan","plan_id":4,"replayed":true}"""))
        val outcome=GeneratedContextualRequestGateway(session).submit(scope,operation,ContextualDraft(RequestContext(null,token,"Booking"),"",null,mapOf(9 to 2,10 to 0)))
        assertTrue(outcome.consumablePlan);assertTrue(outcome.replayed)
        val body=Json.parseToJsonElement(server.next().body.readUtf8()).jsonObject
        assertEquals(token,body["booking_token"]!!.jsonPrimitive.content)
        assertEquals(buildJsonArray {addJsonObject {put("product_id",9);put("quantity",2)}},body["items"]);assertNull(body["kit"])
        server.enqueue(json("""{"detail":"Busy.","code":"lock_busy"}""",409))
        try {GeneratedContextualRequestGateway(session).submit(scope,operation,ContextualDraft(RequestContext(null,token,"B"),"",null,mapOf(9 to 1)));fail()}
        catch(f:ApiFailure){assertEquals("lock_busy",f.code);assertEquals(409,f.status)};server.next()
    }
}

private class FakeContextual:ContextualRequestGateway {
    val operations=mutableListOf<UUID>()
    var failure:Exception?=null
    override suspend fun kits(scope:MemberScope,page:Int)=MemberPage(listOf(EquipmentKit(3,"Kit",1,emptyList())),null,1)
    override suspend fun kit(scope:MemberScope,id:Int)=EquipmentKit(id,"Kit",2,emptyList())
    override suspend fun submit(scope:MemberScope,operationId:UUID,draft:ContextualDraft):ContextualOutcome {
        operations+=operationId;failure?.let {throw it};return ContextualOutcome(false,"pending_approval",false)
    }
}

class ContextualRequestStateTest {
    private var next=0
    private fun holder(api:FakeContextual)=ContextualRequestStateHolder(api,newOperation={UUID(0,(++next).toLong())}).apply {activate(MemberScope(7,"a"))}
    @Test fun retryReusesOperationUntilDraftChanges()=runTest {
        val api=FakeContextual();val holder=holder(api)
        assertFalse(holder.submit())
        holder.chooseContext(RequestContext(1,null,"E"));holder.setQuantity(9,2);assertTrue(holder.state.value.ready)
        api.failure=Errors.map(409,"""{"detail":"Busy","code":"lock_busy"}""")
        assertFalse(holder.submit());assertEquals("The space is busy with another request. Try again in a moment.",holder.state.value.error)
        assertFalse(holder.submit());assertEquals(api.operations[0],api.operations[1])
        // The member changed the request: a new operation.
        holder.setQuantity(9,3);assertNull(holder.state.value.operationId)
        api.failure=null;assertTrue(holder.submit());assertNotEquals(api.operations[0],api.operations[2])
        assertNotNull(holder.state.value.outcome);assertTrue(holder.state.value.items.isEmpty());assertNull(holder.state.value.operationId)
    }
    @Test fun operationConflictStartsNewOperationAndEligibilityShowsCopy()=runTest {
        val api=FakeContextual();val holder=holder(api)
        holder.chooseContext(RequestContext(null,"00000000-0000-4000-8000-0000000000bb","B"));holder.chooseKit(3)
        assertEquals(2,holder.state.value.kit!!.version)
        api.failure=Errors.map(409,"""{"detail":"Conflict","code":"operation_conflict"}""")
        assertFalse(holder.submit());assertNull(holder.state.value.operationId)
        api.failure=Errors.map(403,"""{"detail":"x","code":["presence_required"]}""")
        assertFalse(holder.submit());assertNotEquals(api.operations[0],api.operations[1])
        assertEquals(FeatureStrings.ELIGIBILITY_PRESENCE_REQUIRED.english,holder.state.value.error)
    }
    @Test fun kitReplacesItemsAndQuantitiesAreBounded()=runTest {
        val holder=holder(FakeContextual())
        holder.setQuantity(9,120);assertEquals(99,holder.state.value.items[9])
        holder.chooseKit(3);assertTrue(holder.state.value.items.isEmpty())
        holder.setQuantity(9,1);assertNull(holder.state.value.kit)
        holder.setQuantity(9,0);assertTrue(holder.state.value.items.isEmpty())
        holder.refreshKits();assertTrue(holder.state.value.kitsLoaded)
    }
}
