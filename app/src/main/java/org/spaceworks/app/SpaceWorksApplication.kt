package org.spaceworks.app

import android.app.Application
import org.spaceworks.app.telemetry.TelemetrySetup

/** App entry point: installs the consent-gated telemetry facade before any screen or crash can occur. */
class SpaceWorksApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        TelemetrySetup.install(this)
    }
}
