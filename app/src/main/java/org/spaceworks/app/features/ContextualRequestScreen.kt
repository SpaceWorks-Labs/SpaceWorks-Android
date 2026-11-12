package org.spaceworks.app.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.member.InventoryItem
import org.spaceworks.app.member.MemberOverview
import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*

/** The member's own active registrations and upcoming bookings, the only contexts a member may request for. */
fun requestContexts(overview:MemberOverview?,text:MobileText):List<RequestContext> {
    overview?:return emptyList()
    val registrations=overview.registrations.filter {it.status in setOf("registered","confirmed","approved","attended")}.mapNotNull {r->
        r.id.toLongOrNull()?.let {RequestContext(it,null,text(FeatureStrings.KITS_EVENT_CONTEXT,r.title,memberDate(r.startsAt)))}}
    val bookings=overview.bookings.items.filter {it.status!="cancelled" && it.status!="rejected"}.mapNotNull {b->
        runCatching {java.util.UUID.fromString(b.id)}.getOrNull()?.let {RequestContext(null,b.id,text(FeatureStrings.KITS_BOOKING_CONTEXT,b.spaceName,memberDate(b.startsAt,b.timezone)))}}
    return registrations+bookings
}

@Composable fun ContextualRequestScreen(holder:ContextualRequestStateHolder,contexts:List<RequestContext>,products:List<InventoryItem>,onBack:()->Unit,onSent:()->Unit) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(holder,s.scope) {if(s.scope!=null && !s.kitsLoaded && !s.loading)holder.refreshKits()}
    LaunchedEffect(contexts) {if(s.context==null || s.context !in contexts)contexts.singleOrNull()?.let(holder::chooseContext)}
    FeaturePage(copyText(FeatureStrings.KITS_TITLE),copyText(FeatureStrings.KITS_HELP),onBack,!s.submitting) {
        s.outcome?.let {outcome->Text(copyText(when {outcome.consumablePlan->FeatureStrings.KITS_PLAN_SENT;outcome.replayed->FeatureStrings.KITS_SENT_REPLAY;else->FeatureStrings.KITS_SENT}),
            style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        MemberSection(copyText(FeatureStrings.KITS_FOR)) {
            if(contexts.isEmpty())Text(copyText(FeatureStrings.KITS_NO_CONTEXT),color=MaterialTheme.colorScheme.onSurfaceVariant)
            contexts.forEach {context->
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(s.context==context,!s.submitting,Role.RadioButton) {holder.chooseContext(context)},
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RadioButton(s.context==context,null);Text(context.label,Modifier.weight(1f))
                }
            }
        }
        MemberSection(copyText(FeatureStrings.KITS_KITS)) {
            if(s.loading && !s.kitsLoaded)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(s.kitsLoaded && s.kits.isEmpty())Text(copyText(FeatureStrings.KITS_NO_KITS),color=MaterialTheme.colorScheme.onSurfaceVariant)
            s.kit?.let {kit->MemberPanel {
                Text(copyText(FeatureStrings.KITS_CHOSEN_KIT,kit.name),style=MaterialTheme.typography.titleMedium)
                kit.lines.forEach {Text(copyText(if(it.returnable)FeatureStrings.KITS_LINE else FeatureStrings.KITS_LINE_CONSUMABLE,it.name,it.perPerson))}
                BrandTextButton(copyText(FeatureStrings.KITS_REMOVE_KIT),holder::clearKit,!s.submitting)
            }}
            s.kits.filter {it.id!=s.kit?.id}.forEach {kit->MemberPanel {
                Text(kit.name,style=MaterialTheme.typography.titleMedium)
                kit.lines.forEach {Text(copyText(if(it.returnable)FeatureStrings.KITS_LINE else FeatureStrings.KITS_LINE_CONSUMABLE,it.name,it.perPerson))}
                BrandTextButton(copyText(FeatureStrings.KITS_CHOOSE_KIT,kit.name),{scope.launch {holder.chooseKit(kit.id)}},!s.submitting && !s.loading,Modifier.testTag("kit-${kit.id}"))
            }}
        }
        if(s.kit==null)MemberSection(copyText(FeatureStrings.KITS_ITEMS)) {
            val choosable=products.filter {it.id.toIntOrNull()!=null}
            if(choosable.isEmpty())Text(copyText(FeatureStrings.KITS_NO_ITEMS),color=MaterialTheme.colorScheme.onSurfaceVariant)
            choosable.forEach {item->
                val id=item.id.toInt()
                val quantity=s.items[id]?:0
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(copyText(FeatureStrings.KITS_QUANTITY,item.name,quantity),Modifier.weight(1f).semantics {liveRegion=LiveRegionMode.Polite})
                    TextButton({holder.setQuantity(id,quantity-1)},Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics {contentDescription=copyText(FeatureStrings.KITS_LESS,item.name)},
                        enabled=quantity>0 && !s.submitting) {Text("−")}
                    TextButton({holder.setQuantity(id,quantity+1)},Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics {contentDescription=copyText(FeatureStrings.KITS_MORE,item.name)},
                        enabled=quantity<99 && !s.submitting && (s.items.size<20 || quantity>0)) {Text("+")}
                }
            }
        }
        BrandField(s.requestedFor,holder::describe,copyText(FeatureStrings.KITS_NOTE),enabled=!s.submitting)
        BrandError(s.error)
        BrandButton(copyText(if(s.submitting)FeatureStrings.KITS_SENDING else FeatureStrings.KITS_SUBMIT),
            {scope.launch {if(holder.submit())onSent()}},s.ready,Modifier.testTag("contextual-submit"))
    }
}
