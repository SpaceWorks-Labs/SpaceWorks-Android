package org.spaceworks.app.telemetry

/** Recording fake for the facade: install with [RecordingTelemetry.install] and inspect what feature code sent. */
class RecordingTelemetry:Telemetry {
    val events=mutableListOf<TelemetryEvent>()
    val screens=mutableListOf<TelemetryScreen>()
    val users=mutableListOf<TelemetryUser?>()
    val consents=mutableListOf<TelemetryConsent>()
    val names get()=events.map {it.name.wire}
    override fun track(event:TelemetryEvent){events+=event}
    override fun screen(screen:TelemetryScreen){screens+=screen}
    override fun identify(user:TelemetryUser){users+=user}
    override fun reset(){users+=null}
    override fun setConsent(consent:TelemetryConsent){consents+=consent}
    companion object {
        fun install()=RecordingTelemetry().also {AppTelemetry.demoActive=false;AppTelemetry.delegate=it}
        fun uninstall(){AppTelemetry.delegate=NoopTelemetry;AppTelemetry.demoActive=false}
    }
}

/** Provider fake recording lifecycle calls, standing in for an SDK adapter. */
class FakeProvider(override val purpose:TelemetryPurpose):TelemetryProvider {
    val calls=mutableListOf<String>()
    override fun start(){calls+="start"}
    override fun stop(){calls+="stop"}
    override fun track(event:TelemetryEvent,properties:Map<String,Any>){calls+="track:${event.name.wire}"}
    override fun screen(screen:TelemetryScreen){calls+="screen:${screen.route}"}
    override fun sensitive(visible:Boolean){calls+="sensitive:$visible"}
    override fun identify(user:TelemetryUser,superProperties:Map<String,Any>){calls+="identify:${user.id}"}
    override fun reset(){calls+="reset"}
}

class MemoryConsentStorage(var value:StoredConsent?=null):ConsentStorage {
    override fun read()=value
    override fun write(value:StoredConsent){this.value=value}
}
