package org.spaceworks.app.ui

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.AuthBundle
import org.spaceworks.app.auth.HandleAvailability
import org.spaceworks.app.auth.HandleStateHolder
import org.spaceworks.app.platform.rememberMobileText

/** Blocking "Choose your username" step for accounts whose payload says `handle_required`. */
@Composable fun HandleScreen(bundle:AuthBundle,handles:HandleStateHolder) {
    val copyText=rememberMobileText()
    val auth by bundle.holder.state.collectAsStateWithLifecycle()
    val state by handles.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(auth.user?.id) {handles.start(auth.user?.handleSuggestion)}
    // Live availability: check once typing pauses.
    LaunchedEffect(state.handle) {
        if(state.handle.isNotEmpty() && state.availability==HandleAvailability.UNCHECKED){delay(400);handles.check()}
    }
    val status=when(state.availability) {
        HandleAvailability.CHECKING -> copyText(UiStrings.MOBILE_CHECKING)
        HandleAvailability.AVAILABLE -> copyText(UiStrings.MOBILE_USERNAME_AVAILABLE)
        HandleAvailability.TAKEN -> state.suggestion?.takeIf {it!=state.handle}?.let {copyText(UiStrings.MOBILE_USERNAME_TAKEN_TRY_VALUE,it)}
            ?: copyText(UiStrings.MOBILE_USERNAME_TAKEN)
        HandleAvailability.INVALID -> copyText(UiStrings.MOBILE_USE_3_30_LETTERS_NUMBERS_DOTS_UNDERSCORES_OR_HYPHENS)
        HandleAvailability.UNCHECKED -> null
    }
    AuthLayout(copyText(UiStrings.MOBILE_CHOOSE_YOUR_USERNAME),copyText(UiStrings.MOBILE_YOUR_USERNAME_IS_PUBLIC_YOU_NEED_ONE_TO_REQUEST_BOOK_AND_REGISTER)) {
        BrandField(state.handle,handles::edit,copyText(UiStrings.MOBILE_USERNAME),Modifier.testTag("handle"),!state.busy,
            onDone={if(!state.busy)scope.launch {handles.save()}},
            errorMessage=state.error ?: status.takeIf {state.availability==HandleAvailability.INVALID || state.availability==HandleAvailability.TAKEN})
        if(state.availability==HandleAvailability.CHECKING)LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.takeIf {state.availability==HandleAvailability.AVAILABLE || state.availability==HandleAvailability.CHECKING}?.let {
            Text(it,Modifier.semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall)
        }
        if(state.availability==HandleAvailability.TAKEN)state.suggestion?.takeIf {it!=state.handle}?.let {suggestion->
            BrandTextButton(copyText(UiStrings.MOBILE_USE_VALUE,suggestion),{handles.edit(suggestion)},!state.busy,Modifier.testTag("useSuggestion"))
        }
        BrandButton(if(state.busy)copyText(UiStrings.MOBILE_SAVING) else copyText(UiStrings.MOBILE_SAVE_USERNAME),{scope.launch {handles.save()}},
            !state.busy && HandleStateHolder.valid(state.handle) && state.availability!=HandleAvailability.TAKEN,Modifier.testTag("saveHandle"))
        BrandTextButton(copyText(UiStrings.MOBILE_SIGN_OUT),{scope.launch {bundle.holder.signOut()}},!state.busy)
    }
}
