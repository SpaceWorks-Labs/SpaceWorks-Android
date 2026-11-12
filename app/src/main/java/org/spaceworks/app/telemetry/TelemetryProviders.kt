package org.spaceworks.app.telemetry

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.microsoft.clarity.Clarity
import com.microsoft.clarity.ClarityConfig
import com.posthog.PostHog
import com.posthog.PersonProfiles
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User

/** Plain per-install preferences with a schema version (consent is not per account). */
class PreferencesConsentStorage(private val prefs:SharedPreferences):ConsentStorage {
    override fun read():StoredConsent? {
        if(!prefs.contains("schema"))return null
        val consent=TelemetryConsent(prefs.getBoolean("analytics",false),prefs.getBoolean("replay",false),prefs.getBoolean("diagnostics",false))
        val asked=prefs.getStringSet("asked",emptySet()).orEmpty().mapNotNull {key->TelemetryPurpose.entries.firstOrNull {it.key==key}}.toSet()
        return StoredConsent(consent,asked,prefs.getInt("schema",0))
    }
    override fun write(value:StoredConsent) {
        prefs.edit().putInt("schema",value.schema).putBoolean("analytics",value.consent.analytics)
            .putBoolean("replay",value.consent.replay).putBoolean("diagnostics",value.consent.diagnostics)
            .putStringSet("asked",value.asked.map {it.key}.toSet()).apply()
    }
}

/** Real SDK adapters, created only for configured providers. None is touched before [TelemetryProvider.start]. */
object TelemetryProviders {
    fun create(application:Application,config:TelemetryConfig):List<TelemetryProvider> = buildList {
        if(config.firebaseConfigured)add(FirebaseProvider(application))
        config.posthogApiKey?.takeIf {it.isNotBlank()}?.let {add(PostHogProvider(application,it,config.posthogHost))}
        config.clarityProjectId?.takeIf {it.isNotBlank()}?.let {add(ClarityProvider(application,it))}
        config.sentryDsn?.takeIf {it.isNotBlank()}?.let {add(SentryProvider(application,config,it))}
    }
}

private fun Map<String,Any>.toBundle()=Bundle().also {bundle->
    forEach {(key,value)->when(value){is Boolean->bundle.putString(key,value.toString());is Int->bundle.putLong(key,value.toLong());
        is Long->bundle.putLong(key,value);is Number->bundle.putDouble(key,value.toDouble());else->bundle.putString(key,value.toString().take(100))}}
}

/** Firebase Analytics without ad ids: collection is off in the manifest and enabled only here. */
private class FirebaseProvider(private val context:Context):TelemetryProvider {
    override val purpose=TelemetryPurpose.ANALYTICS
    private val analytics get()=FirebaseAnalytics.getInstance(context)
    override fun start() {
        analytics.setConsent(mapOf(FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to FirebaseAnalytics.ConsentStatus.GRANTED,
            FirebaseAnalytics.ConsentType.AD_STORAGE to FirebaseAnalytics.ConsentStatus.DENIED,
            FirebaseAnalytics.ConsentType.AD_USER_DATA to FirebaseAnalytics.ConsentStatus.DENIED,
            FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to FirebaseAnalytics.ConsentStatus.DENIED))
        analytics.setAnalyticsCollectionEnabled(true)
    }
    override fun stop() {analytics.setAnalyticsCollectionEnabled(false);analytics.resetAnalyticsData()}
    override fun track(event:TelemetryEvent,properties:Map<String,Any>) {
        // Firebase reserves screen_view for its own screen event, sent from screen().
        if(event.name!=TelemetryEventName.SCREEN_VIEW)analytics.logEvent(event.name.wire,properties.toBundle())
    }
    override fun screen(screen:TelemetryScreen)=analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW,Bundle().apply {
        putString(FirebaseAnalytics.Param.SCREEN_NAME,screen.route);putString(FirebaseAnalytics.Param.SCREEN_CLASS,screen.route)})
    override fun identify(user:TelemetryUser,superProperties:Map<String,Any>) {
        analytics.setUserId(user.id)
        analytics.setUserProperty("role_kind",user.roleKind);analytics.setUserProperty("deployment",user.deployment)
    }
    override fun reset()=analytics.setUserId(null)
}

/** PostHog product analytics: no autocapture, no session replay, no feature flags; lifecycle events on. */
private class PostHogProvider(private val application:Application,private val apiKey:String,private val host:String):TelemetryProvider {
    override val purpose=TelemetryPurpose.ANALYTICS
    private var setup=false
    override fun start() {
        if(setup){PostHog.optIn();return}
        PostHogAndroid.setup(application,PostHogAndroidConfig(apiKey,host).apply {
            captureApplicationLifecycleEvents=true;captureDeepLinks=false;captureScreenViews=false
            sessionReplay=false;preloadFeatureFlags=false;sendFeatureFlagEvent=false
            personProfiles=PersonProfiles.IDENTIFIED_ONLY
        })
        setup=true
    }
    override fun stop() {PostHog.optOut();PostHog.reset()}
    override fun track(event:TelemetryEvent,properties:Map<String,Any>)=PostHog.capture(event.name.wire,properties=properties)
    override fun screen(screen:TelemetryScreen)=PostHog.screen(screen.route)
    override fun identify(user:TelemetryUser,superProperties:Map<String,Any>) {
        PostHog.identify(user.id,userProperties=mapOf("role_kind" to user.roleKind,"platform" to (superProperties["platform"]?:"android")))
    }
    override fun reset()=PostHog.reset()
}

/**
 * Microsoft Clarity session replay. Masking mode is a Clarity project setting and must be Strict
 * (docs/TELEMETRY.md); the app additionally pauses recording on every sensitive screen.
 */
private class ClarityProvider(private val context:Context,private val projectId:String):TelemetryProvider {
    override val purpose=TelemetryPurpose.REPLAY
    private var initialized=false
    private var sensitive=false
    private var started=false
    override fun start() {
        started=true
        if(!initialized){initialized=Clarity.initialize(context,ClarityConfig(projectId))==true;if(sensitive)Clarity.pause()}
        else if(!sensitive)Clarity.resume()
    }
    override fun stop() {started=false;if(initialized)Clarity.pause()}
    override fun sensitive(visible:Boolean) {
        sensitive=visible
        if(!initialized || !started)return
        if(visible)Clarity.pause() else Clarity.resume()
    }
    override fun screen(screen:TelemetryScreen) {if(initialized)Clarity.setCurrentScreenName(screen.route)}
    override fun identify(user:TelemetryUser,superProperties:Map<String,Any>) {if(initialized)Clarity.setCustomUserId(user.id)}
}

/** Sentry crash, ANR and performance reporting with the [TelemetryScrubber] applied to every event and breadcrumb. */
private class SentryProvider(private val context:Context,private val config:TelemetryConfig,private val dsn:String):TelemetryProvider {
    override val purpose=TelemetryPurpose.DIAGNOSTICS
    override fun start()=SentryAndroid.init(context) {options->SentryScrubbing.configure(options,config,dsn)}
    override fun stop()=Sentry.close()
    override fun identify(user:TelemetryUser,superProperties:Map<String,Any>)=Sentry.setUser(User().apply {id=user.id})
    override fun reset()=Sentry.setUser(null)
}

/** Sentry options shared by the adapter and unit tests. */
object SentryScrubbing {
    fun configure(options:SentryOptions,config:TelemetryConfig,dsn:String) {
        options.dsn=dsn
        options.environment=config.sentryEnvironment?:config.buildType
        options.release=config.release
        options.tracesSampleRate=config.sentryTracesSampleRate
        options.isSendDefaultPii=false
        options.isAttachStacktrace=true
        if(options is io.sentry.android.core.SentryAndroidOptions) {
            options.isAttachScreenshot=false;options.isAttachViewHierarchy=false
            options.isAnrEnabled=true;options.isEnableUserInteractionBreadcrumbs=false
        }
        options.beforeSend=SentryOptions.BeforeSendCallback {event,_->scrub(event)}
        options.beforeBreadcrumb=SentryOptions.BeforeBreadcrumbCallback {breadcrumb,_->scrub(breadcrumb)}
    }

    fun scrub(event:SentryEvent):SentryEvent {
        event.request?.let {request->
            request.url=request.url?.let(TelemetryScrubber::url)
            request.queryString=null;request.fragment=null;request.data=null;request.cookies=null
            request.headers=TelemetryScrubber.headers(request.headers)
        }
        event.user=event.user?.id?.let {id->User().apply {this.id=id}}
        event.breadcrumbs?.forEach {scrub(it)}
        event.serverName=null
        return event
    }

    fun scrub(breadcrumb:Breadcrumb):Breadcrumb {
        (breadcrumb.getData("url") as? String)?.let {breadcrumb.setData("url",TelemetryScrubber.url(it))}
        listOf("http.query","http.fragment","request_body","response_body").forEach {breadcrumb.removeData(it)}
        TelemetryScrubber.SENSITIVE_HEADERS.forEach {breadcrumb.removeData(it)}
        return breadcrumb
    }
}
