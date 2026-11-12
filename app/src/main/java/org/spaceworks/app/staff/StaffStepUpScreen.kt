package org.spaceworks.app.staff

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*

/**
 * Staff sign-in: a step-up from the member session to a staff grant for one makerspace, always
 * behind the authenticator (or its enrolment when the server asks for it).
 */
@Composable fun StaffStepUpScreen(holder:StaffGrantStateHolder,makerspace:String,onDone:()->Unit) {
    val text=rememberMobileText()
    val state by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var code by remember {mutableStateOf("")}
    var acknowledged by remember {mutableStateOf(false)}
    ProtectSecrets(state.mfa!=null || state.recoveryCodes.isNotEmpty())
    val mfa=state.mfa
    when {
        state.recoveryCodes.isNotEmpty() -> AuthLayout(text(UiStrings.MOBILE_SAVE_YOUR_RECOVERY_CODES),text(UiStrings.MOBILE_KEEP_THESE_SOMEWHERE_SAFE_YOU_CAN_USE_EACH_CODE_ONCE_IF_YOU_LOSE)) {
            SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {state.recoveryCodes.forEach {Text(it)}}}
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(acknowledged,{acknowledged=it})
                Text(text(UiStrings.MOBILE_I_VE_SAVED_MY_RECOVERY_CODES))
            }
            BrandButton(text(UiStrings.MOBILE_CONTINUE),{holder.acknowledgeRecoveryCodes();onDone()},acknowledged)
        }
        mfa==null -> AuthLayout(text(StaffStrings.STAFF_STAFF_SIGN_IN),text(StaffStrings.STAFF_CONFIRM_IT_S_YOU_WITH_YOUR_AUTHENTICATOR_TO_OPEN_STAFF_TOOLS_FOR_VALUE,makerspace)) {
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            BrandError(state.error)
            BrandButton(text(UiStrings.MOBILE_CONTINUE),{state.pending?.let {scope.launch {holder.stepUp(it)}}},!state.busy && state.pending!=null,Modifier.testTag("staffStepUp"))
            BrandTextButton(text(UiStrings.MOBILE_CANCEL),{holder.cancel();onDone()},!state.busy)
        }
        else -> AuthLayout(if(mfa.enrollmentRequired)text(UiStrings.MOBILE_ADD_EXTRA_SECURITY) else text(UiStrings.MOBILE_CHECK_YOUR_AUTHENTICATOR),
            if(mfa.enrollmentRequired)text(UiStrings.MOBILE_ADD_THIS_ACCOUNT_TO_YOUR_AUTHENTICATOR_APP_THEN_ENTER_ITS_SIX_DI)
            else text(UiStrings.MOBILE_ENTER_THE_SIX_DIGIT_CODE_FROM_YOUR_AUTHENTICATOR_APP)) {
            if(mfa.enrollmentRequired && state.enrollment==null)BrandButton(text(UiStrings.MOBILE_SHOW_SETUP_CODE),{scope.launch {holder.enroll()}},!state.busy)
            state.enrollment?.let {setup->
                val bitmap=remember(setup.qrDataUrl) {try {val bytes=Base64.decode(setup.qrDataUrl.substringAfter(','),Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)}catch(_:Exception){null}}
                bitmap?.let {Image(it.asImageBitmap(),text(UiStrings.MOBILE_SCAN_TO_ADD_SPACE_WORKS_TO_YOUR_AUTHENTICATOR),Modifier.sizeIn(maxWidth=224.dp,maxHeight=224.dp))}
                SelectionContainer {Text(text(UiStrings.MOBILE_SETUP_KEY_VALUE,setup.secret))}
            }
            if(!mfa.enrollmentRequired || state.enrollment!=null) {
                BrandField(code,{code=it.filter {c->c in '0'..'9'}.take(6)},text(UiStrings.MOBILE_AUTHENTICATION_CODE),Modifier.testTag("staffMfaCode"),!state.busy,role=FieldRole.CODE)
                BrandButton(if(state.busy)text(UiStrings.MOBILE_VERIFYING) else text(UiStrings.MOBILE_VERIFY),{
                    scope.launch {holder.verify(code);code="";if(holder.state.value.active && holder.state.value.recoveryCodes.isEmpty())onDone()}
                },!state.busy && code.length==6,Modifier.testTag("staffVerify"))
            }
            BrandError(state.error)
            BrandTextButton(text(UiStrings.MOBILE_CANCEL),{holder.cancel();onDone()},!state.busy)
        }
    }
}
