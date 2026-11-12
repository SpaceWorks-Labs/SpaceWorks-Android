package org.spaceworks.app.features

import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.platform.MobileText
import org.spaceworks.generated.api.AnalyticsApi
import org.spaceworks.generated.api.ReportsApi

/** One catalog entry as the server describes it; availability is the server's, never derived from roles. */
data class ReportEntry(val key:String,val title:String,val group:String,val available:Boolean,val unavailableReason:String?,
    val fields:List<String>,val xField:String?,val valueField:String?,val unit:String,val currencyField:String?,val summary:String)
data class ReportCell(val field:String,val value:String)
/** A report reduced for reading on a phone: labelled values when the catalog declares a chart, else rows of cells. */
data class ReportView(val points:List<Pair<String,String>>,val rows:List<List<ReportCell>>,val totalRows:Int)

interface StaffReportsGateway {
    suspend fun catalog(scope:MemberScope):List<ReportEntry>
    suspend fun report(scope:MemberScope,entry:ReportEntry):ReportView
}

/** Staff step-up session only; the server narrows the catalog to what this person may read. */
class GeneratedStaffReportsGateway(private val staffSession:ApiSession):StaffReportsGateway {
    override suspend fun catalog(scope:MemberScope):List<ReportEntry> =
        staffSession.scopedApi(ReportsApi::class.java,scope.id).apiV1AdminMakerspaceReportsCatalogRetrieve(scope.id).requireBody().results.map {
            val spec=it.chartSpec
            ReportEntry(it.key,it.title,it.groupLabel?:it.group.orEmpty(),it.available!=false,it.unavailableReason,it.fields,
                spec?.x?.`field`,spec?.propertyValues?.firstOrNull()?.`field`,spec?.propertyValues?.firstOrNull()?.unit.orEmpty(),spec?.currencyField,spec?.altTemplate.orEmpty())
        }
    override suspend fun report(scope:MemberScope,entry:ReportEntry):ReportView {
        val body=staffSession.scopedApi(AnalyticsApi::class.java,scope.id)
            .apiV1AdminMakerspaceAnalyticsRetrieve(makerspaceId=scope.id,reportKey=entry.key).requireBody()
        return project(entry,body)
    }
    companion object {
        const val MAX_ROWS=50
        private fun text(value:JsonElement?):String? = when(value) {
            null,JsonNull->null
            is JsonPrimitive->value.contentOrNull
            else->value.toString()
        }
        /** Reads `typed_rows` (or a flat summary object). Amounts stay per currency and as the server sent them. */
        fun project(entry:ReportEntry,body:JsonElement):ReportView {
            val obj=body as? JsonObject?:return ReportView(emptyList(),emptyList(),0)
            val typed=(obj["typed_rows"] as? JsonArray)?.mapNotNull {it as? JsonObject}
            if(typed==null) {
                // A summary report is one flat object of counters.
                val cells=obj.entries.mapNotNull {(key,value)->(value as? JsonPrimitive)?.let {ReportCell(key,it.content)}}
                return ReportView(emptyList(),if(cells.isEmpty())emptyList() else listOf(cells),if(cells.isEmpty())0 else 1)
            }
            val x=entry.xField;val measure=entry.valueField
            if(x!=null && measure!=null) {
                val points=typed.mapNotNull {row->
                    val label=text(row[x])?:return@mapNotNull null
                    val value=text(row[measure])?:return@mapNotNull null
                    val currency=entry.currencyField?.let {text(row[it])}
                    (if(currency.isNullOrBlank())label else "$label (${currency.uppercase()})") to value
                }
                if(points.isNotEmpty())return ReportView(points.take(MAX_ROWS),emptyList(),points.size)
            }
            val order=entry.fields.ifEmpty {typed.firstOrNull()?.keys?.toList().orEmpty()}
            val rows=typed.take(MAX_ROWS).map {row->order.mapNotNull {field->text(row[field])?.let {ReportCell(field,it)}}}
            return ReportView(emptyList(),rows,typed.size)
        }
    }
}

data class StaffReportsState(val scope:MemberScope?=null,val catalog:List<ReportEntry>?=null,val loading:Boolean=false,
    val open:ReportEntry?=null,val view:ReportView?=null,val error:String?=null)

class StaffReportsStateHolder(private val api:StaffReportsGateway,text:MobileText=MobileText.ENGLISH,onUnauthorized:suspend()->Unit={})
    :FeatureHolder<StaffReportsState>({StaffReportsState(scope=it)},text,onUnauthorized) {
    suspend fun refresh() {
        if(state.value.loading)return
        run({it.copy(loading=true,error=null)},{api.catalog(it)},{s,value->s.copy(catalog=value,loading=false)},{s,message,_->s.copy(loading=false,error=message)})
    }
    /** Opens only reports the catalog marks available. */
    suspend fun open(entry:ReportEntry) {
        if(!entry.available || state.value.loading)return
        run({it.copy(open=entry,view=null,loading=true,error=null)},{api.report(it,entry)},
            {s,value->if(s.open?.key==entry.key)s.copy(view=value,loading=false) else s},{s,message,_->s.copy(loading=false,error=message)})
    }
    fun closeReport()=mutable.update {it.copy(open=null,view=null,error=null)}
}
