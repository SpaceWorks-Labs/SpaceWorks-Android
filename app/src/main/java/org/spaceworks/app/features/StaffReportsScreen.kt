package org.spaceworks.app.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*

@Composable fun StaffReportsScreen(holder:StaffReportsStateHolder,onBack:()->Unit) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(holder,s.scope) {if(s.scope!=null && s.catalog==null && !s.loading)holder.refresh()}
    val open=s.open
    FeaturePage(open?.title?:copyText(FeatureStrings.REPORTS_TITLE),if(open==null)copyText(FeatureStrings.REPORTS_HELP) else open.summary.takeIf {it.isNotBlank()},
        {if(open!=null)holder.closeReport() else onBack()}) {
        BrandError(s.error)
        if(s.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(open==null) {
            if(s.error!=null && s.catalog==null)BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{scope.launch {holder.refresh()}},!s.loading)
            val catalog=s.catalog.orEmpty()
            if(s.catalog!=null && catalog.isEmpty())Text(copyText(FeatureStrings.REPORTS_EMPTY),color=MaterialTheme.colorScheme.onSurfaceVariant)
            catalog.groupBy {it.group}.forEach {(group,entries)->
                MemberSection(group.ifBlank {copyText(FeatureStrings.REPORTS_TITLE)}) {
                    entries.forEach {entry->
                        OutlinedCard(onClick={scope.launch {holder.open(entry)}},enabled=entry.available && !s.loading,
                            modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("report-${entry.key}")) {
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(entry.title,style=MaterialTheme.typography.titleMedium)
                                if(!entry.available)Text(entry.unavailableReason?.takeIf {it.isNotBlank()}?.let {copyText(FeatureStrings.REPORTS_UNAVAILABLE,it)}
                                    ?:copyText(FeatureStrings.REPORTS_UNAVAILABLE_PLAIN),color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } else s.view?.let {view->
            if(view.points.isEmpty() && view.rows.isEmpty())Text(copyText(FeatureStrings.REPORTS_NO_ROWS),color=MaterialTheme.colorScheme.onSurfaceVariant)
            view.points.forEach {(label,value)->
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).semantics(mergeDescendants=true) {},verticalAlignment=Alignment.CenterVertically) {
                    Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                    Text(if(open.unit.isBlank())value else "$value ${open.unit}",style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.SemiBold,fontFeatureSettings="tnum"),
                        color=MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            view.rows.forEach {row->MemberPanel {row.forEach {cell->Text(copyText(FeatureStrings.REPORTS_CELL,cell.field.replace('_',' '),cell.value))}}}
            val shown=maxOf(view.points.size,view.rows.size)
            if(view.totalRows>shown)Text(copyText(FeatureStrings.REPORTS_ROWS_SHOWN,shown,view.totalRows),color=MaterialTheme.colorScheme.onSurfaceVariant)
            BrandTextButton(copyText(FeatureStrings.REPORTS_BACK_TO_LIST),holder::closeReport)
        }
    }
}

/** Staff landing entry; shown only inside staff tools, which already require the staff grant. */
@Composable fun StaffReportsEntry(onOpen:()->Unit) {
    val copyText=rememberMobileText()
    BrandTextButton(copyText(FeatureStrings.REPORTS_OPEN),onOpen,modifier=Modifier.testTag("staff-reports-entry"))
}
