package org.spaceworks.app.features

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure

class WaiverGatewayTest {
    private val scope=MemberScope(7,"workshop")
    @Test fun readsWaiverAndAcceptsWithoutBody()=withSession {session,server->
        val gateway=GeneratedWaiverGateway(session)
        server.enqueue(json("""{"has_waiver":true,"body":"Wear goggles.","version":"3"}"""))
        assertEquals(Waiver(true,"Wear goggles.","3"),gateway.waiver(scope))
        val read=server.next();assertEquals("GET",read.method);assertEquals("/api/v1/member/makerspaces/7/waiver",read.path);assertEquals("7",read.getHeader("X-Makerspace-Id"))
        server.enqueue(json("""{"has_waiver":false}"""))
        assertEquals(Waiver(false,"",""),gateway.waiver(scope));server.next()
        server.enqueue(json("""{"accepted":true,"version":"3"}"""))
        assertEquals("3",gateway.accept(scope))
        val accept=server.next();assertEquals("POST",accept.method);assertEquals("/api/v1/member/makerspaces/7/waiver/accept",accept.path);assertEquals(0L,accept.bodySize)
        server.enqueue(json("""{"accepted":false,"version":null}"""))
        assertNull(gateway.accept(scope));server.next()
    }
    @Test fun nonMemberRefusalSurfaces()=withSession {session,server->
        server.enqueue(json("""{"detail":"An active membership is required."}""",400))
        try {GeneratedWaiverGateway(session).accept(scope);fail()}catch(f:ApiFailure){assertEquals("An active membership is required.",f.detail)}
        server.next()
    }
}

private class FakeWaiver(var current:Waiver=Waiver(true,"Text","1"),var acceptVersion:String?="1"):WaiverGateway {
    var reads=0;var accepts=0
    override suspend fun waiver(scope:MemberScope):Waiver {reads++;return current}
    override suspend fun accept(scope:MemberScope):String? {accepts++;return acceptVersion}
}

class WaiverStateTest {
    @Test fun acceptNeedsLoadedWaiverAndRecordsVersion()=runTest {
        val api=FakeWaiver();val holder=WaiverStateHolder(api);holder.activate(MemberScope(7,"a"))
        assertFalse(holder.accept());assertEquals(0,api.accepts)
        holder.refresh();assertTrue(holder.accept())
        assertEquals("1",holder.state.value.acceptedVersion);assertFalse(holder.state.value.changed);assertEquals(1,api.reads)
    }
    @Test fun changedVersionReloadsTextAndNoWaiverIsNotAccepted()=runTest {
        val api=FakeWaiver(acceptVersion="2");val holder=WaiverStateHolder(api);holder.activate(MemberScope(7,"a"));holder.refresh()
        api.current=Waiver(true,"New text","2")
        assertTrue(holder.accept());assertTrue(holder.state.value.changed);assertEquals("New text",holder.state.value.waiver!!.body)
        val none=WaiverStateHolder(FakeWaiver(acceptVersion=null));none.activate(MemberScope(7,"a"));none.refresh()
        assertFalse(none.accept());assertTrue(none.state.value.gone)
        val empty=WaiverStateHolder(FakeWaiver(Waiver(false,"","")));empty.activate(MemberScope(7,"a"));empty.refresh();assertFalse(empty.accept())
    }
    @Test fun failureKeepsWaiverAndShowsError()=runTest {
        val api=object:WaiverGateway {
            override suspend fun waiver(scope:MemberScope)=Waiver(true,"T","1")
            override suspend fun accept(scope:MemberScope):String?=throw ApiFailure(403,"Your account is restricted at this space.",null,null)
        }
        val holder=WaiverStateHolder(api);holder.activate(MemberScope(7,"a"));holder.refresh()
        assertFalse(holder.accept());assertEquals("Your account is restricted at this space.",holder.state.value.error)
        assertNotNull(holder.state.value.waiver);assertFalse(holder.state.value.accepting);assertNull(holder.state.value.acceptedVersion)
    }
}
