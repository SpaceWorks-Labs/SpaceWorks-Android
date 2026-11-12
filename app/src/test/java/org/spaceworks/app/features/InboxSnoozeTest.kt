package org.spaceworks.app.features

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class InboxSnoozeTest {
    private val scope=MemberScope(7,"workshop")
    private val now=ZonedDateTime.of(2026,11,13,22,30,0,0,ZoneId.of("Asia/Kolkata"))
    @Test fun snoozePostsUntil()=withSession {session,server->
        server.enqueue(json("""{"id":12,"feature":"loans","event":"due_soon","title":"Due","summary":"s","link":"","read_at":null,"snoozed_until":"2026-11-13T18:00:00Z","archived_on":null,"created_at":"2026-11-13T10:00:00Z"}"""))
        val until=OffsetDateTime.parse("2026-11-13T23:30:00+05:30")
        GeneratedInboxSnoozeGateway(session).snooze(scope,12,until)
        val request=server.next();assertEquals("POST",request.method);assertEquals("/api/v1/notifications/inbox/7/12/snooze",request.path)
        assertEquals("7",request.getHeader("X-Makerspace-Id"))
        assertEquals(until,OffsetDateTime.parse(Json.parseToJsonElement(request.body.readUtf8()).jsonObject["until"]!!.jsonPrimitive.content))
    }
    @Test fun choicesStayWithinThirtyDays() {
        assertEquals(now.plusHours(1).toOffsetDateTime(),SnoozeChoice.ONE_HOUR.until(now))
        assertEquals(OffsetDateTime.parse("2026-11-14T09:00+05:30"),SnoozeChoice.TOMORROW_MORNING.until(now))
        SnoozeChoice.entries.forEach {assertTrue(it.until(now).isBefore(now.plusDays(30).toOffsetDateTime()))}
    }
    @Test fun holderRecordsResultAndNeverRetries()=runTest {
        var calls=0
        val api=object:InboxSnoozeGateway {
            var failure:Exception?=null
            override suspend fun snooze(scope:MemberScope,id:Int,until:OffsetDateTime):String? {calls++;failure?.let {throw it};return until.toString()}
        }
        val holder=InboxSnoozeStateHolder(api,now={now});holder.activate(scope)
        assertTrue(holder.snooze(12,SnoozeChoice.ONE_HOUR));assertEquals(setOf(12),holder.state.value.snoozed.keys)
        api.failure=ApiFailure(404,"Snooze is off here.",null,null)
        assertFalse(holder.snooze(13,SnoozeChoice.NEXT_WEEK));assertEquals("Snooze is off here.",holder.state.value.error);assertEquals(2,calls)
        assertNull(holder.state.value.busy)
    }
    @Test fun topicChannelsFallBackToDefault() {
        assertEquals(7,NotificationChannels.topics.size)
        assertEquals("sw_money",NotificationChannels.channelFor("sw_money","spaceworks_updates"))
        assertEquals("spaceworks_updates",NotificationChannels.channelFor("sw_unknown","spaceworks_updates"))
        assertEquals("spaceworks_updates",NotificationChannels.channelFor(null,"spaceworks_updates"))
    }
}
