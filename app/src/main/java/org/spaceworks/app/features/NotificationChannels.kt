package org.spaceworks.app.features

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.spaceworks.app.platform.MobileCopyKey

/** Payload v3 topics (PRD "Channels (Android)"): people mute a topic per channel. */
object NotificationChannels {
    val topics:List<Pair<String,MobileCopyKey>> = listOf(
        "sw_loans" to FeatureStrings.CHANNEL_LOANS,"sw_bookings" to FeatureStrings.CHANNEL_BOOKINGS,"sw_events" to FeatureStrings.CHANNEL_EVENTS,
        "sw_machines" to FeatureStrings.CHANNEL_MACHINES,"sw_membership" to FeatureStrings.CHANNEL_MEMBERSHIP,"sw_money" to FeatureStrings.CHANNEL_MONEY,
        "sw_space" to FeatureStrings.CHANNEL_SPACE)
    private val ids=topics.map {it.first}.toSet()
    /** The topic channel for a push, or [fallback] for an unknown, missing or older (v1/v2) payload. */
    fun channelFor(channel:String?,fallback:String):String = channel?.takeIf {it in ids}?:fallback
    /** Idempotent: re-creating a channel only refreshes its name; the person's own settings stay. */
    fun create(context:Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(topics.map {(id,name)->NotificationChannel(id,context.getString(name.resourceId),NotificationManager.IMPORTANCE_DEFAULT)})
    }
}
