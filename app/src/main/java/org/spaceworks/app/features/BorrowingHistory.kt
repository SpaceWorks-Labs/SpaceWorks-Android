package org.spaceworks.app.features

import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberPage
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.platform.MobileText
import org.spaceworks.generated.api.MemberBorrowingApi

enum class HistoryPeriod(val wire:String) { ALL("all"), CURRENT("current"), HISTORY("history") }
data class BorrowedLine(val name:String,val requested:Int,val issued:Int,val returned:Int,val damaged:Int,val missing:Int)
/** One of the caller's own requests, handouts or self-checkouts. Dates are the server's ISO strings. */
data class BorrowingRecord(val id:Int,val status:String,val source:String,val createdAt:String,val checkedOutAt:String?,
    val dueAt:String?,val returnedAt:String?,val closedAt:String?,val lines:List<BorrowedLine>)

interface BorrowingHistoryGateway {
    suspend fun history(scope:MemberScope,period:HistoryPeriod,page:Int):MemberPage<BorrowingRecord>
}

/** `GET /member/makerspaces/{id}/borrowing-history`: needs no membership, only the caller's own account. */
class GeneratedBorrowingHistoryGateway(private val session:ApiSession):BorrowingHistoryGateway {
    override suspend fun history(scope:MemberScope,period:HistoryPeriod,page:Int):MemberPage<BorrowingRecord> {
        val wire=MemberBorrowingApi.PeriodApiV1MemberMakerspacesBorrowingHistoryList.entries.first {it.value==period.wire}
        val body=session.scopedApi(MemberBorrowingApi::class.java,scope.id)
            .apiV1MemberMakerspacesBorrowingHistoryList(scope.id,page,wire).requireBody()
        return MemberPage(body.results.map {row->
            BorrowingRecord(row.id,row.status,row.source,row.createdAt.toString(),(row.checkedOutAt?:row.issuedAt)?.toString(),
                row.returnDueAt?.toString(),(row.returnedAt?:row.returns.lastOrNull()?.returnedAt)?.toString(),row.closedAt?.toString(),
                row.items.map {BorrowedLine(it.productName,it.requestedQuantity,it.issuedQuantity,it.returnedQuantity,it.damagedQuantity,it.missingQuantity)})
        },body.next?.toString(),body.count)
    }
}

data class BorrowingHistoryState(val scope:MemberScope?=null,val period:HistoryPeriod=HistoryPeriod.ALL,
    val records:List<BorrowingRecord> = emptyList(),val nextPage:Int?=null,val loaded:Boolean=false,
    val loading:Boolean=false,val error:String?=null)

class BorrowingHistoryStateHolder(private val api:BorrowingHistoryGateway,text:MobileText=MobileText.ENGLISH,onUnauthorized:suspend()->Unit={})
    :FeatureHolder<BorrowingHistoryState>({BorrowingHistoryState(scope=it)},text,onUnauthorized) {
    suspend fun refresh(period:HistoryPeriod=state.value.period)=load(period,1)
    suspend fun more() {state.value.nextPage?.takeIf {!state.value.loading}?.let {load(state.value.period,it)}}
    private suspend fun load(period:HistoryPeriod,page:Int) {
        if(state.value.loading && page>1)return
        run({it.copy(period=period,loading=true,error=null,records=if(page==1 && period!=it.period)emptyList() else it.records)},
            {api.history(it,period,page)},
            {s,value->if(s.period!=period)s else s.copy(records=if(page==1)value.items else s.records+value.items,nextPage=value.next?.let {page+1},loaded=true,loading=false)},
            {s,message,_->s.copy(loading=false,error=message)})
    }
}
