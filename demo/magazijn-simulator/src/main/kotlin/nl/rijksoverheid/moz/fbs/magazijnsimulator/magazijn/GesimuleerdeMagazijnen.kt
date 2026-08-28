package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import io.quarkus.runtime.StartupEvent
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger

/**
 * De set magazijnen die deze simulator voorstelt, ingelezen bij het starten.
 *
 * Fail-fast op de configuratie: een OIN-key die geen OIN is, of een lege set, hoort de boot te
 * blokkeren. De uitvraag krijgt zijn register uit hetzelfde generator-artefact, dus een fout hier
 * levert anders pas bij het eerste verkeer een 404 op — midden in een demo, bij één van de honderd
 * magazijnen, en dan is de oorzaak ver weg.
 */
@ApplicationScoped
class GesimuleerdeMagazijnen(private val config: MagazijnSimulatorConfig) {

    private val log = Logger.getLogger(GesimuleerdeMagazijnen::class.java)
    private lateinit var magazijnen: Map<String, GesimuleerdMagazijn>

    @PostConstruct
    fun init() {
        val entries = config.magazijnen()

        require(entries.isNotEmpty()) {
            "Geen magazijnen geconfigureerd (magazijnsimulator.magazijnen.\"<OIN>\".naam)"
        }

        magazijnen = entries.entries.associate { (key, entry) ->
            check(OIN_PATROON.matches(key)) {
                "Ongeldige OIN-key in magazijn-simulator-config: '$key' moet precies 20 cijfers zijn " +
                    "(magazijnsimulator.magazijnen.\"$key\".naam)"
            }

            check(key.any { it != '0' }) {
                "Ongeldige OIN-key in magazijn-simulator-config: '$key' kan niet geheel uit nullen bestaan"
            }

            val naam = entry.naam().trim()

            check(naam.isNotBlank()) {
                "magazijnsimulator.magazijnen.\"$key\".naam mag niet leeg of alleen whitespace zijn"
            }

            key to GesimuleerdMagazijn(oin = key, naam = naam)
        }
    }

    /**
     * Het observeren van [StartupEvent] dwingt bean-instantiatie — en daarmee de [init]-validatie —
     * af tijdens boot, ook als er nog geen verkeer is geweest.
     */
    fun bijOpstart(@Observes startup: StartupEvent) {
        log.infof("Magazijn-simulator stelt %d magazijn(en) voor", magazijnen.size)
    }

    fun voorOin(oin: String): GesimuleerdMagazijn? = magazijnen[oin]

    private companion object {
        private val OIN_PATROON = Regex("^[0-9]{20}$")
    }
}
