package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Quarkus-resource-manager voor alle end-to-end-tests van [PublicatieStream]. Start twee
 * [DownstreamHttpServer]-instanties (`aanmeld`, `notificatie`) op random
 * loopback-poorten en exposeert hun URLs als config-overrides
 * (`magazijn.publicatie.downstreams.<key>.url`).
 *
 * Eén manager voor alle stream-tests: Quarkus start per unieke combinatie van
 * resource-managers en profiel een eigen applicatie-instantie mét eigen database-container.
 * Vier tests met elk hun eigen manager kostten vier starts; het gedragsverschil tussen die
 * tests (400, 500, eerst-500-dan-202) zit nu in [DownstreamHttpServer.statusVoorAanroep],
 * dat elke test in zijn eigen opzet instelt.
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
            // Polling-interval 200ms — Quarkus clampt naar 1s, snelste optie.
            "magazijn.publicatie.polling.interval" to "200ms",
            // Lage backoff zodat een retry binnen het test-window valt.
            "magazijn.publicatie.downstreams.aanmeld.backoff.basis" to "PT0.05S",
            "magazijn.publicatie.downstreams.aanmeld.max-pogingen" to MAX_POGINGEN.toString(),
            "magazijn.publicatie.downstreams.notificatie.backoff.basis" to "PT0.05S",
            "magazijn.publicatie.downstreams.notificatie.max-pogingen" to MAX_POGINGEN.toString(),
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
         * Retry-budget per downstream, gedeeld door alle stream-tests. De uitputtings-test
         * leest deze waarde in plaats van een eigen getal te hardcoderen, zodat één budget
         * volstaat en de tests niet uiteen hoeven te lopen in configuratie.
         */
        const val MAX_POGINGEN = 3

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
