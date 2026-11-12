package org.spaceworks.app.telemetry

import android.app.Application
import org.spaceworks.app.BuildConfig

/** Generated build configuration (BuildConfig fields from brand.json `telemetry`, Gradle properties or env). */
fun buildTelemetryConfig()=TelemetryConfig(
    sentryDsn=BuildConfig.TELEMETRY_SENTRY_DSN.ifBlank {null},
    sentryEnvironment=BuildConfig.TELEMETRY_SENTRY_ENVIRONMENT.ifBlank {null},
    sentryTracesSampleRate=BuildConfig.TELEMETRY_SENTRY_TRACES_SAMPLE_RATE,
    posthogApiKey=BuildConfig.TELEMETRY_POSTHOG_API_KEY.ifBlank {null},
    posthogHost=BuildConfig.TELEMETRY_POSTHOG_HOST,
    clarityProjectId=BuildConfig.TELEMETRY_CLARITY_PROJECT_ID.ifBlank {null},
    firebaseConfigured=BuildConfig.TELEMETRY_FIREBASE_CONFIGURED,
    // Debug builds (and therefore the DEBUG demo) never send unless the explicit override is set.
    sendingAllowed=!BuildConfig.DEBUG || BuildConfig.TELEMETRY_DEBUG,
    appVersion=BuildConfig.VERSION_NAME,buildType=BuildConfig.BUILD_TYPE,
    release="${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")

/** Installs the consent-gated controller as the process facade. Called from the Application. */
object TelemetrySetup {
    @Volatile var controller:TelemetryController?=null
        private set
    fun install(application:Application):TelemetryController {
        val config=buildTelemetryConfig()
        val storage=PreferencesConsentStorage(application.getSharedPreferences("telemetry-consent",0))
        return TelemetryController(config,storage,TelemetryProviders.create(application,config)).also {
            controller=it
            AppTelemetry.delegate=it
            it.track(TelemetryEvent.appOpen)
        }
    }
}
