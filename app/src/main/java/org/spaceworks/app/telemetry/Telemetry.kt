package org.spaceworks.app.telemetry

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Consent purposes (docs/TELEMETRY.md). All are off until the person agrees. */
enum class TelemetryPurpose(val key:String) { ANALYTICS("analytics"), REPLAY("replay"), DIAGNOSTICS("diagnostics") }

data class TelemetryConsent(val analytics:Boolean=false,val replay:Boolean=false,val diagnostics:Boolean=false) {
    fun allows(purpose:TelemetryPurpose)=when(purpose){TelemetryPurpose.ANALYTICS->analytics;TelemetryPurpose.REPLAY->replay;TelemetryPurpose.DIAGNOSTICS->diagnostics}
    fun with(purpose:TelemetryPurpose,value:Boolean)=when(purpose){TelemetryPurpose.ANALYTICS->copy(analytics=value);TelemetryPurpose.REPLAY->copy(replay=value);TelemetryPurpose.DIAGNOSTICS->copy(diagnostics=value)}
    companion object { fun all(purposes:Set<TelemetryPurpose>)=purposes.fold(TelemetryConsent()){c,p->c.with(p,true)} }
}

/** Pseudonymous identity: never an email, name or server id. */
data class TelemetryUser(val id:String,val deployment:String,val roleKind:String) {
    companion object {
        fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
        /** Origin as `scheme://host[:port]`, lowercase, no trailing slash (same as iOS). */
        fun origin(raw:String)=raw.trim().lowercase().trimEnd('/')
        /** id = lowercase hex SHA-256 of "<origin>|<user id>"; deployment = first 12 hex of SHA-256(origin). */
        fun of(origin:String,userId:String,staff:Boolean):TelemetryUser {
            val normalized=origin(origin)
            return TelemetryUser(sha256("$normalized|$userId"),sha256(normalized).take(12),if(staff)"staff" else "member")
        }
    }
}

/** The app-level facade. Feature code depends only on this (through [AppTelemetry]). */
interface Telemetry {
    fun track(event:TelemetryEvent)
    fun screen(screen:TelemetryScreen)
    fun screenLeft(screen:TelemetryScreen) {}
    fun identify(user:TelemetryUser)
    fun reset()
    fun setConsent(consent:TelemetryConsent)
}

object NoopTelemetry:Telemetry {
    override fun track(event:TelemetryEvent)=Unit
    override fun screen(screen:TelemetryScreen)=Unit
    override fun identify(user:TelemetryUser)=Unit
    override fun reset()=Unit
    override fun setConsent(consent:TelemetryConsent)=Unit
}

/**
 * Process-wide entry point. The Application installs the real controller; tests install a recording fake.
 * While a DEBUG demo session is active ([demoActive]) nothing is forwarded: demo data never reaches a provider.
 */
object AppTelemetry:Telemetry {
    @Volatile var delegate:Telemetry=NoopTelemetry
    @Volatile var demoActive=false
    override fun track(event:TelemetryEvent){if(!demoActive)delegate.track(event)}
    override fun screen(screen:TelemetryScreen){if(!demoActive)delegate.screen(screen)}
    override fun screenLeft(screen:TelemetryScreen){if(!demoActive)delegate.screenLeft(screen)}
    override fun identify(user:TelemetryUser){if(!demoActive)delegate.identify(user)}
    override fun reset()=delegate.reset()
    override fun setConsent(consent:TelemetryConsent)=delegate.setConsent(consent)
}

/** Build-time public configuration (generated BuildConfig fields; see docs/TELEMETRY.md). */
data class TelemetryConfig(
    val sentryDsn:String?=null,val sentryEnvironment:String?=null,val sentryTracesSampleRate:Double=0.1,
    val posthogApiKey:String?=null,val posthogHost:String="https://eu.i.posthog.com",val clarityProjectId:String?=null,
    val firebaseConfigured:Boolean=false,
    /** Release builds, or a debug build with the explicit telemetry debug override. */
    val sendingAllowed:Boolean=false,
    val platform:String="android",val appVersion:String="",val buildType:String="",val release:String=""
) {
    val configuredPurposes:Set<TelemetryPurpose> get()=buildSet {
        if(firebaseConfigured || !posthogApiKey.isNullOrBlank())add(TelemetryPurpose.ANALYTICS)
        if(!clarityProjectId.isNullOrBlank())add(TelemetryPurpose.REPLAY)
        if(!sentryDsn.isNullOrBlank())add(TelemetryPurpose.DIAGNOSTICS)
    }
}

/** Per-install consent in plain preferences with a schema version. */
interface ConsentStorage {
    fun read():StoredConsent?
    fun write(value:StoredConsent)
}
data class StoredConsent(val consent:TelemetryConsent,val asked:Set<TelemetryPurpose>,val schema:Int=SCHEMA) {
    companion object { const val SCHEMA=1 }
}

/** One provider SDK behind the facade. [start] runs only after consent; [stop] withdraws at once. */
interface TelemetryProvider {
    val purpose:TelemetryPurpose
    fun start()
    fun stop()
    fun track(event:TelemetryEvent,properties:Map<String,Any>) {}
    fun screen(screen:TelemetryScreen) {}
    /** A sign-in, payment, QR or camera screen is (or is no longer) visible: replay must pause. */
    fun sensitive(visible:Boolean) {}
    fun identify(user:TelemetryUser,superProperties:Map<String,Any>) {}
    fun reset() {}
}

/**
 * Routes events to the providers whose purpose the person allowed. A provider is never started before
 * consent or when sending is not allowed (debug without override); withdrawal stops it immediately.
 */
class TelemetryController(private val config:TelemetryConfig,private val storage:ConsentStorage,
    private val providers:List<TelemetryProvider>):Telemetry {
    private val configured=config.configuredPurposes
    private val stored=storage.read()?.takeIf {it.schema==StoredConsent.SCHEMA}
    private val consentMutable=MutableStateFlow(stored?.consent?:TelemetryConsent())
    private var asked=stored?.asked.orEmpty()
    private val promptMutable=MutableStateFlow(configured-asked)
    private val active=mutableSetOf<TelemetryProvider>()
    private var user:TelemetryUser?=null
    private val sensitive=mutableListOf<TelemetryScreen>()
    val consent:StateFlow<TelemetryConsent> = consentMutable.asStateFlow()
    /** Configured purposes the person has not been asked about yet (first run or newly configured provider). */
    val pendingPrompt:StateFlow<Set<TelemetryPurpose>> = promptMutable.asStateFlow()
    val configuredPurposes:Set<TelemetryPurpose> get()=configured

    init {apply()}

    private fun superProperties()=buildMap<String,Any> {
        put("platform",config.platform);put("app_version",config.appVersion);put("build_type",config.buildType)
        user?.let {put("deployment",it.deployment);put("role_kind",it.roleKind)}
    }

    @Synchronized private fun apply() {
        val consent=consentMutable.value
        providers.forEach {provider->
            val allowed=config.sendingAllowed && provider.purpose in configured && consent.allows(provider.purpose)
            if(allowed && provider !in active) {
                provider.start();active+=provider
                if(sensitive.isNotEmpty())provider.sensitive(true)
                user?.let {provider.identify(it,superProperties())}
            } else if(!allowed && provider in active) {provider.stop();active-=provider}
        }
    }

    @Synchronized override fun setConsent(consent:TelemetryConsent) {
        val effective=TelemetryConsent(consent.analytics && TelemetryPurpose.ANALYTICS in configured,
            consent.replay && TelemetryPurpose.REPLAY in configured,consent.diagnostics && TelemetryPurpose.DIAGNOSTICS in configured)
        asked=asked+configured
        consentMutable.value=effective;promptMutable.value=emptySet()
        storage.write(StoredConsent(effective,asked))
        apply()
        track(TelemetryEvent.consentUpdated(effective))
    }

    @Synchronized override fun track(event:TelemetryEvent) {
        if(active.isEmpty())return
        val properties=superProperties()+event.properties
        active.forEach {it.track(event,properties)}
    }

    @Synchronized override fun screen(screen:TelemetryScreen) {
        if(screen.sensitive) {sensitive+=screen;if(sensitive.size==1)active.forEach {it.sensitive(true)}}
        active.forEach {it.screen(screen)}
    }

    @Synchronized override fun screenLeft(screen:TelemetryScreen) {
        if(sensitive.remove(screen) && sensitive.isEmpty())active.forEach {it.sensitive(false)}
    }

    /** True while any sensitive screen is visible: replay providers must not record. */
    val sensitiveVisible:Boolean @Synchronized get()=sensitive.isNotEmpty()

    @Synchronized override fun identify(user:TelemetryUser) {
        this.user=user
        active.forEach {it.identify(user,superProperties())}
    }

    @Synchronized override fun reset() {
        user=null
        active.forEach {it.reset()}
    }
}
