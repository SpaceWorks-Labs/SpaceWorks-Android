package org.spaceworks.app.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.AuthMessage
import org.spaceworks.app.platform.GoogleIdentity
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*

@Composable fun ProviderLinkScreen(holder:ProviderLinkStateHolder,googleClientId:String,onBack:()->Unit) {
    val copyText=rememberMobileText()
    val context=LocalContext.current
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    // Secrets stay in this composition only; they are cleared when it leaves.
    var password by remember {mutableStateOf("")}
    var code by remember {mutableStateOf("")}
    LaunchedEffect(holder,s.scope) {if(s.scope!=null)holder.start()}
    FeaturePage(copyText(FeatureStrings.LINK_TITLE),copyText(FeatureStrings.LINK_HELP),onBack,!s.busy) {
        BrandError(s.error)
        if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        when(s.stage) {
            LinkStage.CHECKING->if(s.error!=null)BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{scope.launch {holder.start()}},!s.busy)
            LinkStage.REAUTH->if(s.status?.passwordAvailable==false)Text(copyText(FeatureStrings.LINK_NO_PASSWORD)) else {
                BrandField(password,{password=it},copyText(FeatureStrings.LINK_PASSWORD),enabled=!s.busy,secret=true)
                if(s.status?.mfaEnabled==true)BrandField(code,{code=it.filter(Char::isDigit).take(6)},copyText(FeatureStrings.LINK_CODE),enabled=!s.busy,role=FieldRole.ONE_TIME_CODE)
                BrandButton(copyText(FeatureStrings.LINK_CONFIRM),{scope.launch {holder.reauthenticate(password,code);if(holder.state.value.stage==LinkStage.READY){password="";code=""}}},!s.busy,Modifier.testTag("link-reauth"))
            }
            LinkStage.READY->BrandButton(copyText(FeatureStrings.LINK_CONTINUE_GOOGLE),{scope.launch {
                holder.link("google") {nonce->GoogleIdentity.credential(context.activity()?:throw AuthMessage(copyText(FeatureStrings.LINK_UNAVAILABLE)),googleClientId,nonce)}
            }},!s.busy,Modifier.testTag("link-google"))
            LinkStage.LINKED->Text(copyText(FeatureStrings.LINK_LINKED),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onPrimaryContainer,
                modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
        }
    }
}
