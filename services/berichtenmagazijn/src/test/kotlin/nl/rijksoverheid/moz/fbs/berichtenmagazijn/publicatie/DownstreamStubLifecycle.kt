package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Quarkus-resource-manager voor [PublicatieStreamE2ETest]. Start twee
 * [DownstreamHttpServer]-instanties (`aanmeld`, `notificatie`) op random
 * loopback-poorten en exposeert hun URLs als config-overrides
 * (`magazijn.publicatie.downstreams.<key>.url`).
 *
 * Lifecycle-manager is de Quarkus-canonieke manier om servers vóór de
 * applicatie-boot te starten en hun URLs in de SmallRye-config te injecteren.
 * `getConfigOverrides`/system-properties via een TestProfile lopen achter
 * Quarkus' config-initialisatie aan en kunnen `magazijn.publicatie.downstreams.*`
 * te laat opleveren — de `@Scheduled`-bean ziet dan een lege downstream-map.
 *
 * `getTestServers()` geeft testcode toegang tot dezelfde server-instanties
 * voor body-/aanroep-assertions.
 */
class DownstreamStubLifecycle : QuarkusTestResourceLifecycleManager {

    private val aanmeld = DownstreamHttpServer()
    private val notificatie = DownstreamHttpServer()

    override fun start(): Map<String, String> {
        aanmeld.start()
        notificatie.start()
        registry["aanmeld"] = aanmeld
        registry["notificatie"] = notificatie
        return mapOf(
            "magazijn.publicatie.downstreams.aanmeld.url" to aanmeld.baseUrl,
            "magazijn.publicatie.downstreams.notificatie.url" to notificatie.baseUrl,
            "magazijn.publicatie.polling.interval" to "200ms",
            "quarkus.scheduler.enabled" to "true",
        )
    }

    override fun stop() {
        registry.clear()
        aanmeld.close()
        notificatie.close()
    }

    companion object {
        /**
         * Test-globale registry zodat testcode na startup de juiste server-
         * instanties terugkrijgt voor assertions. Niet thread-safe maar test
         * runs sequentieel per JVM-fork.
         */
        private val registry = mutableMapOf<String, DownstreamHttpServer>()

        fun server(naam: String): DownstreamHttpServer =
            registry[naam] ?: error("Geen DownstreamHttpServer geregistreerd voor '$naam'")
    }
}
