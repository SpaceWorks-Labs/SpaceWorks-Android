package org.spaceworks.app.features

import kotlinx.coroutines.delay
import org.spaceworks.app.member.MemberPage
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.platform.MobileText

/** Debug-only sample borrowing history. No API is ever contacted. */
class DemoBorrowingHistoryGateway:BorrowingHistoryGateway {
    private val rows=listOf(
        BorrowingRecord(41,"issued","reviewed_request","2026-11-02T10:00:00Z","2026-11-03T09:30:00Z","2026-11-17T18:00:00Z",null,null,
            listOf(BorrowedLine("Cordless drill",1,1,0,0,0))),
        BorrowingRecord(37,"returned","admin_direct","2026-10-12T15:00:00Z","2026-10-12T15:05:00Z","2026-10-19T18:00:00Z","2026-10-18T11:20:00Z","2026-10-18T11:20:00Z",
            listOf(BorrowedLine("Soldering station",1,1,1,0,0),BorrowedLine("Multimeter",2,2,1,1,0))),
        BorrowingRecord(29,"returned","public_self_checkout","2026-09-05T17:00:00Z","2026-09-05T17:00:00Z",null,"2026-09-06T12:00:00Z","2026-09-06T12:00:00Z",
            listOf(BorrowedLine("Camera tripod",1,1,1,0,0))))
    override suspend fun history(scope:MemberScope,period:HistoryPeriod,page:Int):MemberPage<BorrowingRecord> {
        delay(150)
        val items=when(period){HistoryPeriod.ALL->rows;HistoryPeriod.CURRENT->rows.filter {it.returnedAt==null};HistoryPeriod.HISTORY->rows.filter {it.returnedAt!=null}}
        return MemberPage(items,null,items.size)
    }
}

/** Debug-only waiver: accepting flips a local flag; nothing is recorded anywhere. */
class DemoWaiverGateway:WaiverGateway {
    override suspend fun waiver(scope:MemberScope):Waiver {
        delay(120)
        return Waiver(true,"Sample waiver for this preview.\n\nI will follow the induction for every tool, wear the protective equipment it needs and report damage straight away.","2026.2")
    }
    override suspend fun accept(scope:MemberScope):String? {delay(200);return "2026.2"}
}

fun demoMemberFeatures(text:MobileText)=MemberFeatures(BorrowingHistoryStateHolder(DemoBorrowingHistoryGateway(),text),WaiverStateHolder(DemoWaiverGateway(),text))
