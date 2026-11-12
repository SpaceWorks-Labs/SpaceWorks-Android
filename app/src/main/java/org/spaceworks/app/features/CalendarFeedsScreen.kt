package org.spaceworks.app.features

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*

/** Hands the secret feed to the OS calendar: `webcal://` first, the https URL as fallback. Nothing is logged or kept. */
private fun openFeed(context:android.content.Context,url:String):Boolean {
    for(candidate in listOfNotNull(webcalUrl(url),url.takeIf {it.startsWith("https://")})) {
        try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(candidate)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true}
        catch(_:ActivityNotFoundException){}
    }
    return false
}

@Composable fun CalendarFeedsScreen(holder:CalendarFeedsStateHolder,onBack:()->Unit) {
    val copyText=rememberMobileText()
    val context=LocalContext.current
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var noCalendarApp by remember {mutableStateOf(false)}
    LaunchedEffect(holder,s.scope) {if(s.scope!=null)holder.refresh()}
    DisposableEffect(holder) {onDispose {holder.forgetIssued();holder.confirm(null)}}
    s.confirming?.let {
        AlertDialog(onDismissRequest={holder.confirm(null)},title={Text(copyText(FeatureStrings.FEEDS_WARNING_TITLE))},
            text={Text(copyText(FeatureStrings.FEEDS_WARNING_BODY))},
            confirmButton={BrandTextButton(copyText(FeatureStrings.FEEDS_WARNING_ACCEPT),{scope.launch {holder.issue()}})},
            dismissButton={BrandTextButton(copyText(UiStrings.MOBILE_CANCEL),{holder.confirm(null)})})
    }
    FeaturePage(copyText(FeatureStrings.FEEDS_TITLE),copyText(FeatureStrings.FEEDS_HELP),onBack,s.busy==null) {
        BrandError(s.error)
        s.issued?.let {issued->
            Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(copyText(FeatureStrings.FEEDS_READY),color=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                    BrandButton(copyText(FeatureStrings.FEEDS_ADD_TO_CALENDAR),{
                        noCalendarApp=!openFeed(context,issued.url)
                        if(!noCalendarApp)holder.forgetIssued()
                    },modifier=Modifier.testTag("feed-open"))
                    BrandTextButton(copyText(FeatureStrings.FEEDS_DONE),{holder.forgetIssued()})
                }
            }
        }
        if(noCalendarApp)BrandError(copyText(FeatureStrings.FEEDS_NO_CALENDAR_APP))
        if(s.loading && s.rows.isEmpty())LinearProgressIndicator(Modifier.fillMaxWidth())
        val offered=FeedKind.entries.filter {s.rows[it]?.status!=null}
        if(!s.loading && offered.isEmpty() && s.error==null)Text(copyText(FeatureStrings.FEEDS_NONE),color=MaterialTheme.colorScheme.onSurfaceVariant)
        offered.forEach {kind->
            val status=requireNotNull(s.rows[kind]?.status)
            MemberPanel {
                Text(copyText(when(kind){FeedKind.EVENTS->FeatureStrings.FEEDS_EVENTS;FeedKind.SHIFTS->FeatureStrings.FEEDS_SHIFTS;FeedKind.COMBINED->FeatureStrings.FEEDS_COMBINED}),
                    style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics {heading()})
                Text(if(status.enabled)copyText(FeatureStrings.FEEDS_ON,status.hint.orEmpty()) else copyText(FeatureStrings.FEEDS_OFF))
                if(s.busy==kind)LinearProgressIndicator(Modifier.fillMaxWidth())
                BrandButton(copyText(if(status.enabled)FeatureStrings.FEEDS_NEW_LINK else FeatureStrings.FEEDS_SUBSCRIBE),{noCalendarApp=false;holder.confirm(kind)},
                    s.busy==null,Modifier.testTag("feed-issue-${kind.name.lowercase()}"))
                if(status.enabled)BrandTextButton(copyText(FeatureStrings.FEEDS_TURN_OFF),{scope.launch {holder.revoke(kind)}},s.busy==null)
            }
        }
    }
}
