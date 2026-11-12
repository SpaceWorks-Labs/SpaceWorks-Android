package org.spaceworks.app.features

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberPage
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure

private const val ROW="""{"id":41,"status":"issued","created_at":"2026-11-02T10:00:00Z","items":[{"product_name":"Drill","requested_quantity":2,"accepted_quantity":2,"issued_quantity":2,"returned_quantity":1,"damaged_quantity":0,"missing_quantity":1,"needs_fix_quantity":0}],
    "issued_at":"2026-11-03T09:30:00Z","return_due_at":"2026-11-17T18:00:00Z","closed_at":null,"returns":[{"returned_at":"2026-11-10T12:00:00Z"}],"source":"admin_direct",
    "checked_out_at":null,"returned_at":null,"can_renew":false,"renewal_refusal":"renewals_off","renewals_left":null}"""

class BorrowingHistoryGatewayTest {
    private val scope=MemberScope(7,"workshop")
    @Test fun readsOwnHistoryWithPeriodAndPage()=withSession {session,server->
        server.enqueue(json("""{"count":3,"next":"http://x/?page=2","previous":null,"results":[$ROW]}"""))
        val page=GeneratedBorrowingHistoryGateway(session).history(scope,HistoryPeriod.CURRENT,1)
        val request=server.next()
        assertEquals("GET",request.method)
        assertEquals("/api/v1/member/makerspaces/7/borrowing-history?page=1&period=current",request.path)
        assertEquals("7",request.getHeader("X-Makerspace-Id"))
        val record=page.items.single()
        assertEquals(41,record.id);assertEquals("admin_direct",record.source);assertEquals(3,page.count);assertNotNull(page.next)
        // Without a self-checkout loan row the handout time falls back to issued_at; the latest return event is shown.
        assertEquals("2026-11-03T09:30Z",record.checkedOutAt);assertEquals("2026-11-10T12:00Z",record.returnedAt)
        assertEquals(BorrowedLine("Drill",2,2,1,0,1),record.lines.single())
    }
    @Test fun refusalKeepsServerCode()=withSession {session,server->
        server.enqueue(json("""{"detail":"Loans are switched off.","code":"feature_disabled"}""",404))
        try {GeneratedBorrowingHistoryGateway(session).history(scope,HistoryPeriod.ALL,1);fail()}
        catch(f:ApiFailure){assertEquals(404,f.status);assertEquals("Loans are switched off.",f.detail)}
        assertEquals("/api/v1/member/makerspaces/7/borrowing-history?page=1&period=all",server.next().path)
    }
}

private class FakeHistory:BorrowingHistoryGateway {
    val calls=mutableListOf<Pair<HistoryPeriod,Int>>()
    var failure:Exception?=null
    override suspend fun history(scope:MemberScope,period:HistoryPeriod,page:Int):MemberPage<BorrowingRecord> {
        calls+=period to page;failure?.let {throw it}
        val record=BorrowingRecord(page*10+scope.id,"returned","reviewed_request","2026-01-01T00:00:00Z",null,null,null,null,emptyList())
        return MemberPage(listOf(record),if(page<2)"next" else null,2)
    }
}

class BorrowingHistoryStateTest {
    @Test fun pagesAppendAndPeriodReplaces()=runTest {
        val api=FakeHistory();val holder=BorrowingHistoryStateHolder(api);holder.activate(MemberScope(7,"a"))
        holder.refresh();assertEquals(2,holder.state.value.nextPage)
        holder.more();assertEquals(listOf(17,27),holder.state.value.records.map {it.id});assertNull(holder.state.value.nextPage)
        holder.more();assertEquals(2,api.calls.size)
        holder.refresh(HistoryPeriod.HISTORY);assertEquals(listOf(17),holder.state.value.records.map {it.id})
        assertEquals(HistoryPeriod.HISTORY to 1,api.calls.last());assertTrue(holder.state.value.loaded)
    }
    @Test fun failureShowsCopyAndUnauthorizedSignsBackIn()=runTest {
        var unauthorized=0
        val api=FakeHistory().apply {failure=ApiFailure(401,"Please sign in again.",null,null)}
        val holder=BorrowingHistoryStateHolder(api,onUnauthorized={unauthorized++});holder.activate(MemberScope(7,"a"))
        holder.refresh();assertEquals("Please sign in again.",holder.state.value.error);assertEquals(1,unauthorized);assertFalse(holder.state.value.loading)
        api.failure=IllegalStateException("socket");holder.refresh();assertEquals("Couldn't connect. Try again.",holder.state.value.error)
    }
    @Test fun scopeChangeAndClearDiscardRows()=runTest {
        val holder=BorrowingHistoryStateHolder(FakeHistory());holder.activate(MemberScope(7,"a"));holder.refresh()
        holder.activate(MemberScope(8,"b"));assertTrue(holder.state.value.records.isEmpty());assertFalse(holder.state.value.loaded)
        holder.clear();assertNull(holder.state.value.scope);holder.refresh();assertTrue(holder.state.value.records.isEmpty())
    }
}
