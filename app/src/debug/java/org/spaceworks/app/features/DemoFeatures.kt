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

/** Debug-only kits and contextual requests: a submit is answered locally, a repeated operation as a replay. */
class DemoContextualRequestGateway:ContextualRequestGateway {
    private val kits=listOf(EquipmentKit(3,"Soldering kit",2,listOf(KitLine(11,"Soldering iron",1,true),KitLine(12,"Solder wire",1,false))),
        EquipmentKit(4,"Workshop camera kit",1,listOf(KitLine(21,"Camera",1,true),KitLine(22,"Tripod",1,true))))
    private val seen=mutableSetOf<java.util.UUID>()
    override suspend fun kits(scope:MemberScope,page:Int):MemberPage<EquipmentKit> {delay(120);return MemberPage(kits,null,kits.size)}
    override suspend fun kit(scope:MemberScope,id:Int):EquipmentKit {delay(80);return kits.first {it.id==id}}
    override suspend fun submit(scope:MemberScope,operationId:java.util.UUID,draft:ContextualDraft):ContextualOutcome {
        delay(250)
        val consumableOnly=draft.kit?.lines?.all {!it.returnable}==true
        return ContextualOutcome(consumableOnly,"pending_approval",!seen.add(operationId))
    }
}

/** Debug-only feeds: the "link" points at an invalid host, so no real calendar ever subscribes. */
class DemoCalendarFeedGateway:CalendarFeedGateway {
    private val enabled=mutableMapOf(FeedKind.EVENTS to false,FeedKind.COMBINED to true)
    override suspend fun status(scope:MemberScope,kind:FeedKind):FeedStatus {
        delay(80)
        val on=enabled[kind]?:throw org.spaceworks.app.network.ApiFailure(404,"Not found.",null,null)
        return FeedStatus(on,if(on)"a1b2" else null)
    }
    override suspend fun issue(scope:MemberScope,kind:FeedKind):Pair<String,String> {delay(200);enabled[kind]=true;return "https://demo.invalid/feeds/sample.ics" to "c3d4"}
    override suspend fun revoke(scope:MemberScope,kind:FeedKind){delay(150);enabled[kind]=false}
}

/** Debug-only report catalog with one chart and one table. */
class DemoStaffReportsGateway:StaffReportsGateway {
    private val loans=ReportEntry("most_lent","Most lent items","Loans",true,null,listOf("product","count"),"product","count","loans",null,"Most lent items")
    private val money=ReportEntry("revenue_over_time","Revenue over time","Payments",false,"Payments reports need the finance role.",emptyList(),null,null,"",null,"")
    override suspend fun catalog(scope:MemberScope):List<ReportEntry> {delay(120);return listOf(loans,money)}
    override suspend fun report(scope:MemberScope,entry:ReportEntry):ReportView {
        delay(150)
        return ReportView(listOf("Cordless drill" to "18","Soldering station" to "11","Multimeter" to "7"),emptyList(),3)
    }
}

/** Debug-only linking: the sample password is "demo"; no provider or server is contacted. */
class DemoProviderLinkGateway:ProviderLinkGateway {
    private var verified=false
    override suspend fun status():ReauthStatus {delay(80);return ReauthStatus(verified,true,false)}
    override suspend fun reauthenticate(password:String,code:String):Boolean {delay(150);verified=password=="demo";return verified}
    override suspend fun nonce(provider:String)="demo-nonce"
    override suspend fun link(provider:String,idToken:String,nonce:String):String {delay(150);return provider}
}

/** Debug-only snooze: answered locally. */
class DemoInboxSnoozeGateway:InboxSnoozeGateway {
    override suspend fun snooze(scope:MemberScope,id:Int,until:java.time.OffsetDateTime):String? {delay(150);return until.toString()}
}

/** Debug-only loan contexts: kept in memory, each write bumps the revision like the server. */
class DemoLoanContextGateway:LoanContextGateway {
    private val rows=mutableMapOf<Pair<LoanRef,ContextKind>,LoanContext>()
    override suspend fun read(scope:MemberScope,loan:LoanRef,kind:ContextKind):LoanContext {
        delay(80);return rows.getOrPut(loan to kind) {LoanContext(kind,false,java.util.UUID(0,1),0,true,null,null)}
    }
    override suspend fun link(scope:MemberScope,loan:LoanRef,current:LoanContext,targetId:Int,participantRegistrationId:Int?):LoanContext {
        delay(150);return current.copy(active=true,revision=current.revision+1,targetId=targetId).also {rows[loan to current.kind]=it}
    }
    override suspend fun unlink(scope:MemberScope,loan:LoanRef,current:LoanContext):LoanContext {
        delay(150);return current.copy(active=false,revision=current.revision+1,targetId=null).also {rows[loan to current.kind]=it}
    }
}

/** Debug-only staff booking: the first confirm answers a moved price once, like a real 409 price_changed. */
class DemoStaffBookingGateway:StaffBookingGateway {
    private var moved=false
    override suspend fun members(scope:MemberScope,spaceId:Int,page:Int):MemberPage<BookingMember> {delay(100);return MemberPage(listOf(BookingMember(21,"Meera Demo"),BookingMember(22,"Arjun Demo")),null,2)}
    private fun quote()=BookingQuote(true,"inr",if(moved)"550.00" else "500.00",emptyList(),if(moved)"b".repeat(64) else "a".repeat(64))
    override suspend fun quote(scope:MemberScope,spaceId:Int,memberId:Int,start:java.time.OffsetDateTime,end:java.time.OffsetDateTime):BookingQuote {delay(120);return quote()}
    override suspend fun readiness(scope:MemberScope,spaceId:Int,memberId:Int,start:java.time.OffsetDateTime,end:java.time.OffsetDateTime)=
        BookingReadinessView(true,listOf(ReadinessItem("room_outage","ok","No outage in this window.",emptyList())))
    override suspend fun bookingReadiness(scope:MemberScope,bookingId:Int)=BookingReadinessView(false,listOf(ReadinessItem("machine_repair","blocked","The laser cutter is under repair.",emptyList())))
    override suspend fun create(scope:MemberScope,spaceId:Int,memberId:Int,start:java.time.OffsetDateTime,end:java.time.OffsetDateTime,note:String,confirmation:String):StaffBookingResult {
        delay(200)
        if(!moved){moved=true;return StaffBookingResult.PriceChanged(quote())}
        return StaffBookingResult.Created(99,"confirmed")
    }
}

fun demoMemberFeatures(text:MobileText)=MemberFeatures(BorrowingHistoryStateHolder(DemoBorrowingHistoryGateway(),text),WaiverStateHolder(DemoWaiverGateway(),text),ContextualRequestStateHolder(DemoContextualRequestGateway(),text),CalendarFeedsStateHolder(DemoCalendarFeedGateway(),text),StaffReportsStateHolder(DemoStaffReportsGateway(),text),ProviderLinkStateHolder(DemoProviderLinkGateway(),text),InboxSnoozeStateHolder(DemoInboxSnoozeGateway(),text),LoanContextStateHolder(DemoLoanContextGateway(),text),StaffBookingStateHolder(DemoStaffBookingGateway(),text))
