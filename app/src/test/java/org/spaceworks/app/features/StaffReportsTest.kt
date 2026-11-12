package org.spaceworks.app.features

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure

private const val SPEC="""{"series":["count"],"x":{"field":"product","kind":"category"},"values":[{"field":"count","unit":"loans"}],"currency_field":null,"partition":null,"negatives":"clip","truncation":null,"alt_template":"Most lent"}"""
private fun item(key:String,available:Boolean?,spec:String="null",reason:String="null")="""{"key":"$key","title":"T $key","fields":["product","count"],"exportable":true,"summary":false,"required_modules":[],"available":$available,"unavailable_reason":$reason,"grains":[],"chart_hint":"bar","chart_spec":$spec,"identity_bearing":false,"aggregate_supported":false,"allowed_formats":["csv"],"schedulable":false,"previewable":true,"condition_metrics":[],"group":"loans","group_label":"Loans","group_order":1}"""

class StaffReportsGatewayTest {
    private val scope=MemberScope(7,"workshop")
    @Test fun catalogAndReportUseStaffSession()=withSession {session,server->
        val gateway=GeneratedStaffReportsGateway(session)
        server.enqueue(json("""{"results":[${item("most_lent",true,SPEC)},${item("ledger",false,reason="\"Needs finance.\"")}]}"""))
        val catalog=gateway.catalog(scope)
        assertEquals(listOf(true,false),catalog.map {it.available});assertEquals("Needs finance.",catalog[1].unavailableReason)
        assertEquals("product",catalog[0].xField);assertEquals("count",catalog[0].valueField);assertEquals("Loans",catalog[0].group)
        val read=server.next();assertEquals("/api/v1/admin/makerspace/7/reports/catalog",read.path);assertEquals("7",read.getHeader("X-Makerspace-Id"))
        server.enqueue(json("""{"rows":[["Drill",18]],"typed_rows":[{"product":"Drill","count":18},{"product":"Saw","count":"4"}]}"""))
        val view=gateway.report(scope,catalog[0])
        assertEquals(listOf("Drill" to "18","Saw" to "4"),view.points)
        assertEquals("/api/v1/admin/makerspace/7/analytics/most_lent",server.next().path)
    }
    @Test fun refusalSurfaces()=withSession {session,server->
        server.enqueue(json("""{"detail":"Staff access required.","code":"staff_access_required"}""",403))
        try {GeneratedStaffReportsGateway(session).catalog(scope);fail()}catch(f:ApiFailure){assertEquals(403,f.status)};server.next()
    }
    @Test fun projectionKeepsCurrenciesApartAndFallsBackToTables() {
        val money=ReportEntry("rev","Revenue","Payments",true,null,listOf("period","currency","total"),"period","total","",
            "currency","")
        val rows=GeneratedStaffReportsGateway.project(money,Json.parseToJsonElement("""{"rows":[],"typed_rows":[{"period":"2026-10","currency":"inr","total":"10.00"},{"period":"2026-10","currency":"usd","total":"2.00"}]}"""))
        assertEquals(listOf("2026-10 (INR)" to "10.00","2026-10 (USD)" to "2.00"),rows.points)
        val table=money.copy(xField=null,valueField=null)
        val detail=GeneratedStaffReportsGateway.project(table,Json.parseToJsonElement("""{"rows":[],"typed_rows":[{"period":"p","currency":"inr","total":"1","extra":"x"}]}"""))
        assertEquals(listOf(listOf(ReportCell("period","p"),ReportCell("currency","inr"),ReportCell("total","1"))),detail.rows)
        val summary=GeneratedStaffReportsGateway.project(table,Json.parseToJsonElement("""{"products":4,"assets":9}"""))
        assertEquals(listOf(ReportCell("products","4"),ReportCell("assets","9")),summary.rows.single())
        val many=GeneratedStaffReportsGateway.project(table,Json.parseToJsonElement("""{"typed_rows":[${(1..60).joinToString(",") {"{\"period\":\"$it\"}"}}]}"""))
        assertEquals(50,many.rows.size);assertEquals(60,many.totalRows)
    }
}

class StaffReportsStateTest {
    private val entry=ReportEntry("a","A","G",true,null,emptyList(),null,null,"",null,"")
    private class Fake(val entries:List<ReportEntry>):StaffReportsGateway {
        var reads=0
        override suspend fun catalog(scope:MemberScope)=entries
        override suspend fun report(scope:MemberScope,entry:ReportEntry):ReportView {reads++;return ReportView(listOf("x" to "1"),emptyList(),1)}
    }
    @Test fun opensOnlyAvailableReports()=runTest {
        val api=Fake(listOf(entry,entry.copy(key="b",available=false)));val holder=StaffReportsStateHolder(api);holder.activate(MemberScope(7,"a"))
        holder.refresh();assertEquals(2,holder.state.value.catalog!!.size)
        holder.open(holder.state.value.catalog!![1]);assertEquals(0,api.reads);assertNull(holder.state.value.open)
        holder.open(entry);assertEquals(1,api.reads);assertEquals("a",holder.state.value.open!!.key);assertNotNull(holder.state.value.view)
        holder.closeReport();assertNull(holder.state.value.open);assertNull(holder.state.value.view)
        holder.clear();assertNull(holder.state.value.catalog)
    }
}
