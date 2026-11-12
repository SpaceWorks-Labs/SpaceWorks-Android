package org.spaceworks.app.features

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure

class CalendarFeedGatewayTest {
    private val scope=MemberScope(7,"workshop")
    @Test fun personalFeedsUseKindPath()=withSession {session,server->
        val gateway=GeneratedCalendarFeedGateway(session)
        server.enqueue(json("""{"enabled":true,"token_hint":"a1b2","created_at":"2026-11-01T00:00:00Z","rotated_at":null}"""))
        assertEquals(FeedStatus(true,"a1b2"),gateway.status(scope,FeedKind.SHIFTS))
        val read=server.next();assertEquals("GET",read.method);assertEquals("/api/v1/member/makerspaces/7/calendar-feeds/shifts/",read.path);assertEquals("7",read.getHeader("X-Makerspace-Id"))
        server.enqueue(json("""{"feed_url":"https://spaces.example/feeds/abc.ics","token_hint":"c3d4"}"""))
        assertEquals("https://spaces.example/feeds/abc.ics" to "c3d4",gateway.issue(scope,FeedKind.COMBINED))
        val issue=server.next();assertEquals("POST",issue.method);assertEquals("/api/v1/member/makerspaces/7/calendar-feeds/combined/",issue.path)
        server.enqueue(MockResponse().setResponseCode(204))
        gateway.revoke(scope,FeedKind.SHIFTS)
        val revoke=server.next();assertEquals("DELETE",revoke.method);assertEquals("/api/v1/member/makerspaces/7/calendar-feeds/shifts/",revoke.path)
    }
    @Test fun eventFeedConfirmsBearerRisk()=withSession {session,server->
        val gateway=GeneratedCalendarFeedGateway(session)
        server.enqueue(json("""{"enabled":false,"token_hint":null,"created_at":null,"rotated_at":null}"""))
        assertEquals(FeedStatus(false,null),gateway.status(scope,FeedKind.EVENTS))
        assertEquals("/api/v1/member/makerspaces/7/event-calendar-feed/",server.next().path)
        server.enqueue(json("""{"feed_url":"https://spaces.example/e.ics","token_hint":"e5f6","created_at":"2026-11-01T00:00:00Z"}"""))
        gateway.issue(scope,FeedKind.EVENTS)
        val issue=server.next();assertEquals("POST",issue.method);assertEquals("/api/v1/member/makerspaces/7/event-calendar-feed/",issue.path)
        assertEquals(buildJsonObject {put("confirm_bearer_risk",true)},Json.parseToJsonElement(issue.body.readUtf8()))
        server.enqueue(MockResponse().setResponseCode(204));gateway.revoke(scope,FeedKind.EVENTS)
        assertEquals("DELETE",server.next().method)
        server.enqueue(json("""{"detail":"Not found.","code":"not_found"}""",404))
        try {gateway.revoke(scope,FeedKind.EVENTS);fail()}catch(f:ApiFailure){assertEquals(404,f.status)};server.next()
    }
    @Test fun webcalKeepsHostAndPath() {
        assertEquals("webcal://spaces.example/f/a.ics?t=1",webcalUrl("https://spaces.example/f/a.ics?t=1"))
        assertEquals("webcal://x/y",webcalUrl("webcal://x/y"));assertNull(webcalUrl("javascript:alert(1)"));assertNull(webcalUrl("::"))
    }
}

private class FakeFeeds:CalendarFeedGateway {
    val calls=mutableListOf<String>()
    override suspend fun status(scope:MemberScope,kind:FeedKind):FeedStatus {
        if(kind==FeedKind.SHIFTS)throw ApiFailure(404,"Not found.",null,null)
        return FeedStatus(kind==FeedKind.COMBINED,if(kind==FeedKind.COMBINED)"h1" else null)
    }
    override suspend fun issue(scope:MemberScope,kind:FeedKind):Pair<String,String> {calls+="issue $kind";return "https://s/secret.ics" to "n2"}
    override suspend fun revoke(scope:MemberScope,kind:FeedKind){calls+="revoke $kind"}
}

class CalendarFeedsStateTest {
    @Test fun unavailableFeedsAreHiddenAndIssueNeedsConfirmation()=runTest {
        val api=FakeFeeds();val holder=CalendarFeedsStateHolder(api);holder.activate(MemberScope(7,"a"))
        holder.refresh()
        assertTrue(holder.state.value.rows[FeedKind.SHIFTS]!!.unavailable);assertEquals(FeedStatus(true,"h1"),holder.state.value.rows[FeedKind.COMBINED]!!.status)
        assertNull(holder.state.value.error);assertFalse(holder.state.value.loading)
        holder.issue();assertTrue(api.calls.isEmpty())
        holder.confirm(FeedKind.EVENTS);holder.issue()
        assertEquals(listOf("issue EVENTS"),api.calls);assertEquals(IssuedFeed(FeedKind.EVENTS,"https://s/secret.ics"),holder.state.value.issued)
        assertEquals(FeedStatus(true,"n2"),holder.state.value.rows[FeedKind.EVENTS]!!.status);assertNull(holder.state.value.confirming)
        // The secret never appears in logs or state dumps.
        assertFalse(holder.state.value.toString().contains("secret"))
        holder.forgetIssued();assertNull(holder.state.value.issued)
    }
    @Test fun revokeAndScopeChangeDropTheSecret()=runTest {
        val api=FakeFeeds();val holder=CalendarFeedsStateHolder(api);holder.activate(MemberScope(7,"a"));holder.refresh()
        holder.confirm(FeedKind.COMBINED);holder.issue();holder.revoke(FeedKind.COMBINED)
        assertNull(holder.state.value.issued);assertEquals(FeedStatus(false,null),holder.state.value.rows[FeedKind.COMBINED]!!.status)
        holder.confirm(FeedKind.EVENTS);holder.issue();assertNotNull(holder.state.value.issued)
        holder.activate(MemberScope(8,"b"));assertNull(holder.state.value.issued);assertTrue(holder.state.value.rows.isEmpty())
    }
}
