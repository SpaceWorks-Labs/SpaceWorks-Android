package org.spaceworks.app.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*

/** Home notice while the member's membership still needs the current waiver. */
@Composable fun WaiverPendingBanner(onReview:()->Unit) {
    val copyText=rememberMobileText()
    Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp).testTag("waiver-pending"),color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(copyText(FeatureStrings.WAIVER_PENDING),color=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            BrandTextButton(copyText(FeatureStrings.WAIVER_REVIEW),onReview)
        }
    }
}

@Composable fun WaiverScreen(holder:WaiverStateHolder,onBack:()->Unit,onAccepted:()->Unit) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(holder,s.scope) {if(s.scope!=null)holder.refresh()}
    FeaturePage(copyText(FeatureStrings.WAIVER_TITLE),copyText(FeatureStrings.WAIVER_HELP),onBack,!s.accepting) {
        BrandError(s.error)
        if(s.error!=null && s.waiver==null)BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{scope.launch {holder.refresh()}},!s.loading)
        if(s.loading && s.waiver==null)LinearProgressIndicator(Modifier.fillMaxWidth())
        s.acceptedVersion?.let {Text(copyText(FeatureStrings.WAIVER_ACCEPTED,it),style=MaterialTheme.typography.titleMedium,
            color=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        if(s.changed)Text(copyText(FeatureStrings.WAIVER_CHANGED))
        val waiver=s.waiver
        when {
            waiver==null->{}
            !waiver.present || s.gone->Text(copyText(FeatureStrings.WAIVER_NONE),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else->{
                if(waiver.version.isNotBlank())Text(copyText(FeatureStrings.WAIVER_VERSION,waiver.version),style=MaterialTheme.typography.labelLarge)
                OutlinedCard(Modifier.fillMaxWidth()) {Text(waiver.body,Modifier.padding(20.dp).testTag("waiver-body"),style=MaterialTheme.typography.bodyLarge)}
                if(s.acceptedVersion!=waiver.version)BrandButton(copyText(if(s.accepting)FeatureStrings.WAIVER_ACCEPTING else FeatureStrings.WAIVER_ACCEPT),
                    {scope.launch {if(holder.accept())onAccepted()}},!s.accepting && !s.loading,Modifier.testTag("waiver-accept"))
            }
        }
    }
}
