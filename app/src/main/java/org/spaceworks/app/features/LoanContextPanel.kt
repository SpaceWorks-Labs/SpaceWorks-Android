package org.spaceworks.app.features

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*

/**
 * Staff panel on a request or direct-handout detail. [events]/[bookings] are the choices the staff tools already
 * loaded (id to label, events for labels only); the editor hides when the server says `can_write` is false.
 */
@Composable fun LoanContextPanel(holder:LoanContextStateHolder,loan:LoanRef,events:List<Pair<Int,String>>,bookings:List<Pair<Int,String>>) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(holder,s.scope,loan) {if(s.scope!=null && s.loan!=loan)holder.open(loan)}
    if(s.loan!=loan)return
    if(!s.loading && s.contexts.isEmpty() && s.error==null)return
    MemberSection(copyText(FeatureStrings.CONTEXT_TITLE)) {
        Text(copyText(FeatureStrings.CONTEXT_HELP),color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(s.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        BrandError(s.error)
        if(s.stale)BrandButton(copyText(FeatureStrings.CONTEXT_REVIEW),{scope.launch {holder.open(loan)}},!s.loading,Modifier.testTag("context-review"))
        listOf(ContextKind.EVENT to events,ContextKind.BOOKING to bookings).forEach {(kind,choices)->
            val context=s.contexts[kind]?:return@forEach
            MemberPanel {
                Text(copyText(if(kind==ContextKind.EVENT)FeatureStrings.CONTEXT_EVENT else FeatureStrings.CONTEXT_BOOKING),style=MaterialTheme.typography.titleMedium,
                    modifier=Modifier.semantics {heading()})
                val target=context.targetId?.takeIf {context.active}
                val label=target?.let {id->choices.firstOrNull {it.first==id}?.second}
                Text(when {
                    target==null->copyText(FeatureStrings.CONTEXT_NOT_LINKED)
                    label!=null->copyText(if(kind==ContextKind.EVENT)FeatureStrings.CONTEXT_LINKED_EVENT else FeatureStrings.CONTEXT_LINKED_BOOKING,label)
                    else->copyText(if(kind==ContextKind.EVENT)FeatureStrings.CONTEXT_LINKED_EVENT_ID else FeatureStrings.CONTEXT_LINKED_BOOKING_ID,target)
                })
                context.participantRegistrationId?.let {Text(copyText(FeatureStrings.CONTEXT_PARTICIPANT,it),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(!context.canWrite)Text(copyText(FeatureStrings.CONTEXT_READ_ONLY),color=MaterialTheme.colorScheme.onSurfaceVariant)
                else if(!s.stale) {
                    val editable=s.busy==null && !s.loading
                    if(target!=null)BrandTextButton(copyText(FeatureStrings.CONTEXT_REMOVE),{scope.launch {holder.unlink(kind)}},editable,Modifier.testTag("context-remove-${kind.name.lowercase()}"))
                    // Linking an event needs `participant_registration_id` sent as an explicit null, which this client's
                    // transport omits (backend gap recorded): events can be unlinked here, booking links fully edited.
                    if(kind==ContextKind.BOOKING && choices.isEmpty())Text(copyText(FeatureStrings.CONTEXT_NO_CHOICES),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(kind==ContextKind.BOOKING)choices.filter {it.first!=target}.take(10).forEach {(id,name)->
                        BrandTextButton(copyText(FeatureStrings.CONTEXT_LINK_TO,name),{scope.launch {holder.link(kind,id)}},editable,Modifier.testTag("context-link-${kind.name.lowercase()}-$id"))
                    }
                }
            }
        }
    }
}
