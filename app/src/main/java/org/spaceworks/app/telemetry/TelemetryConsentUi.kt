package org.spaceworks.app.telemetry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.spaceworks.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.BrandButton
import org.spaceworks.app.ui.BrandTextButton

private fun TelemetryPurpose.title()=when(this){TelemetryPurpose.ANALYTICS->TelemetryStrings.TELEMETRY_USAGE_ANALYTICS;TelemetryPurpose.REPLAY->TelemetryStrings.TELEMETRY_SESSION_REPLAY;TelemetryPurpose.DIAGNOSTICS->TelemetryStrings.TELEMETRY_CRASH_REPORTS}
private fun TelemetryPurpose.help()=when(this){TelemetryPurpose.ANALYTICS->TelemetryStrings.TELEMETRY_USAGE_ANALYTICS_HELP;TelemetryPurpose.REPLAY->TelemetryStrings.TELEMETRY_SESSION_REPLAY_HELP;TelemetryPurpose.DIAGNOSTICS->TelemetryStrings.TELEMETRY_CRASH_REPORTS_HELP}

/** One full-width, 48dp+ switch row; the whole row toggles and TalkBack reads title, help and state together. */
@Composable private fun PurposeRow(purpose:TelemetryPurpose,checked:Boolean,onChange:(Boolean)->Unit) {
    val copyText=rememberMobileText()
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(checked,role=Role.Switch,onValueChange=onChange).padding(vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(copyText(purpose.title()),style=MaterialTheme.typography.titleMedium)
            Text(copyText(purpose.help()),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked,null)
    }
}

/**
 * First-run "Help improve <app name>" sheet: one switch per configured, not-yet-asked purpose, then
 * Allow all, Save choices or Not now (same copy and behaviour as iOS). Shown once per purpose set and never
 * blocks sign-in: dismissing it counts as Not now for the asked purposes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TelemetryConsentSheet(controller:TelemetryController) {
    val pending by controller.pendingPrompt.collectAsStateWithLifecycle()
    if(pending.isEmpty())return
    val copyText=rememberMobileText()
    val purposes=TelemetryPurpose.entries.filter {it in pending}
    var choice by remember {mutableStateOf(controller.consent.value)}
    fun decide(granted:Boolean)=controller.setConsent(purposes.fold(controller.consent.value){c,p->c.with(p,granted)})
    ModalBottomSheet(onDismissRequest={decide(false)},scrimColor=MaterialTheme.colorScheme.scrim.copy(alpha=.5f)) {
        DisposableEffect(Unit) {AppTelemetry.screen(TelemetryScreen.CONSENT_PROMPT);onDispose {AppTelemetry.screenLeft(TelemetryScreen.CONSENT_PROMPT)}}
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=24.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(copyText(TelemetryStrings.TELEMETRY_HELP_IMPROVE_TITLE,stringResource(R.string.app_name)),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics {heading()})
            Text(copyText(TelemetryStrings.TELEMETRY_HELP_IMPROVE_BODY),style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            purposes.forEach {purpose->PurposeRow(purpose,choice.allows(purpose)) {choice=choice.with(purpose,it)}}
            BrandButton(copyText(TelemetryStrings.TELEMETRY_ALLOW_ALL),{decide(true)},modifier=Modifier.testTag("telemetry-allow-all"))
            BrandTextButton(copyText(TelemetryStrings.TELEMETRY_SAVE_CHOICES),{controller.setConsent(purposes.fold(controller.consent.value){c,p->c.with(p,choice.allows(p))})},modifier=Modifier.fillMaxWidth().testTag("telemetry-save"))
            BrandTextButton(copyText(TelemetryStrings.TELEMETRY_NOT_NOW),{decide(false)},modifier=Modifier.fillMaxWidth().testTag("telemetry-not-now"))
            Text(copyText(TelemetryStrings.TELEMETRY_CHANGE_LATER),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Profile → "Privacy and analytics": the same switches at any time; changes apply immediately. */
@Composable fun PrivacyAnalyticsScreen(controller:TelemetryController,onBack:()->Unit) {
    val copyText=rememberMobileText()
    val consent by controller.consent.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {AppTelemetry.screen(TelemetryScreen.PRIVACY_ANALYTICS);onDispose {AppTelemetry.screenLeft(TelemetryScreen.PRIVACY_ANALYTICS)}}
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            BrandTextButton(copyText(TelemetryStrings.TELEMETRY_BACK),onBack)
            Text(copyText(TelemetryStrings.TELEMETRY_PRIVACY_TITLE),style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})
            TelemetryPurpose.entries.filter {it in controller.configuredPurposes}.forEach {purpose->
                PurposeRow(purpose,consent.allows(purpose)) {controller.setConsent(consent.with(purpose,it))}
            }
            Text(copyText(TelemetryStrings.TELEMETRY_WITHDRAW_NOTE),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("telemetry-withdrawal-note"))
        }
    }
}

/** Reports a route-template screen view; sensitive screens pause session replay until they close. */
@Composable fun TrackScreen(screen:TelemetryScreen) {
    DisposableEffect(screen) {AppTelemetry.screen(screen);onDispose {AppTelemetry.screenLeft(screen)}}
}
