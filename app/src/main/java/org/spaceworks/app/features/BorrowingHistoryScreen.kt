package org.spaceworks.app.features

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*

/** Shared single-column page for the P1 feature screens: back, heading, help, then content. */
@Composable internal fun FeaturePage(title:String,help:String?,onBack:()->Unit,backEnabled:Boolean=true,content:@Composable ColumnScope.()->Unit) {
    val copyText=rememberMobileText()
    BackHandler(backEnabled,onBack)
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=640.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            BrandTextButton(copyText(UiStrings.MOBILE_BACK),onBack,backEnabled)
            Text(title,style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})
            help?.let {Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            content()
        }
    }
}

@Composable fun BorrowingHistoryScreen(holder:BorrowingHistoryStateHolder,onBack:()->Unit) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(holder,s.scope) {if(s.scope!=null && !s.loaded && !s.loading)holder.refresh()}
    FeaturePage(copyText(FeatureStrings.HISTORY_TITLE),copyText(FeatureStrings.HISTORY_HELP),onBack) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(HistoryPeriod.ALL to FeatureStrings.HISTORY_ALL,HistoryPeriod.CURRENT to FeatureStrings.HISTORY_CURRENT,HistoryPeriod.HISTORY to FeatureStrings.HISTORY_PAST)
                .forEach {(period,label)->FilterChip(s.period==period,{scope.launch {holder.refresh(period)}},label={Text(copyText(label))},
                    modifier=Modifier.heightIn(min=48.dp))}
        }
        BrandError(s.error)
        if(s.error!=null)BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{scope.launch {holder.refresh()}},!s.loading)
        if(s.loading && s.records.isEmpty())LinearProgressIndicator(Modifier.fillMaxWidth())
        if(s.loaded && s.records.isEmpty() && s.error==null)Text(copyText(FeatureStrings.HISTORY_EMPTY),color=MaterialTheme.colorScheme.onSurfaceVariant)
        s.records.forEach {record->
            OutlinedCard(Modifier.fillMaxWidth().testTag("history-${record.id}")) {
                Column(Modifier.padding(20.dp).semantics(mergeDescendants=true) {},verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(copyText(when(record.source) {"admin_direct"->FeatureStrings.HISTORY_SOURCE_HANDOUT;"public_self_checkout"->FeatureStrings.HISTORY_SOURCE_SELF_CHECKOUT
                        else->FeatureStrings.HISTORY_SOURCE_REQUEST}),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(memberStatus(record.status,copyText),style=MaterialTheme.typography.titleMedium)
                    record.lines.forEach {line->
                        Text(copyText(FeatureStrings.HISTORY_LINE,line.name,line.requested,line.issued,line.returned))
                        if(line.damaged>0 || line.missing>0)Text(copyText(FeatureStrings.HISTORY_LINE_ISSUES,line.damaged,line.missing),color=MaterialTheme.colorScheme.error)
                    }
                    Text(copyText(FeatureStrings.HISTORY_REQUESTED_ON,memberDate(record.createdAt)),style=MaterialTheme.typography.bodySmall)
                    record.checkedOutAt?.let {Text(copyText(FeatureStrings.HISTORY_CHECKED_OUT_ON,memberDate(it)),style=MaterialTheme.typography.bodySmall)}
                    if(record.returnedAt==null)record.dueAt?.let {Text(copyText(FeatureStrings.HISTORY_DUE_ON,memberDate(it)),style=MaterialTheme.typography.bodySmall)}
                    record.returnedAt?.let {Text(copyText(FeatureStrings.HISTORY_RETURNED_ON,memberDate(it)),style=MaterialTheme.typography.bodySmall)}
                    if(record.returnedAt==null)record.closedAt?.let {Text(copyText(FeatureStrings.HISTORY_CLOSED_ON,memberDate(it)),style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
        if(s.nextPage!=null)BrandButton(copyText(FeatureStrings.HISTORY_LOAD_MORE),{scope.launch {holder.more()}},!s.loading)
        if(s.loading && s.records.isNotEmpty())LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
