package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.util.Optional

/**
 * Het gedeelde token waarmee het beheerpad zich afschermt, en de controle dat het er buiten dev en
 * test ook echt is.
 *
 * Fail-fast en geen waarschuwing: een simulator die opstart met een onbeschermd beheerpad draait
 * daarna maanden door zonder dat iemand het merkt — tot iemand hem vindt. De WireMock-admin-API van
 * de stubs op de gedeelde omgeving stond om precies die reden open, en die fout is de moeite van het
 * niet-herhalen waard.
 *
 * Ingelezen met `@ConfigProperty` en niet met een `@ConfigMapping`: het is één sleutel, en een
 * mapping-interface voegt daar niets aan toe. De magazijnen hebben er wél een; waarom die op
 * `magazijnsimulator.magazijnen` staat en niet op het kale prefix, staat bij `MagazijnSimulatorConfig`.
 */
@ApplicationScoped
class BeheerToken(
    @param:ConfigProperty(name = "magazijnsimulator.beheer.token") private val ingesteld: Optional<String>,
    @param:ConfigProperty(name = "quarkus.profile") private val profiel: String,
) {

    private val log = Logger.getLogger(BeheerToken::class.java)

    private val token: String get() = ingesteld.orElse("").trim()

    /** Of het beheerpad open staat; alleen mogelijk onder dev en test. */
    val staatOpen: Boolean get() = token.isEmpty()

    fun bijOpstart(@Observes startup: StartupEvent) {
        if (profiel in ONBESCHERMDE_PROFIELEN) {
            if (staatOpen) {
                log.infof("Beheerpad draait zonder token onder profiel '%s'", profiel)
            }

            return
        }

        check(!staatOpen) {
            "magazijnsimulator.beheer.token is verplicht onder profiel '$profiel': zonder token kan " +
                "iedereen die het beheerpad bereikt de demo legen of magazijnen kapot zetten"
        }

        log.info("Beheerpad is met een token beveiligd")
    }

    /**
     * Of dit verzoek het beheerpad in mag: omdat het pad open staat, of omdat het token klopt.
     *
     * Eén vraag en niet twee. Met een losse `klopt` moet elke aanroeper eraan denken [staatOpen]
     * eerst te toetsen, en wie dat vergeet laat onder dev en test álles door — `klopt("")` is daar
     * immers waar. Dit type bewaakt die volgorde nu zelf.
     *
     * Vergelijkt tijdconstant. Een gewone stringvergelijking stopt bij het eerste verschillende
     * teken, en dat verschil in looptijd is genoeg om een token teken voor teken te raden. De lengte
     * lekt nog wel; dat is hier het verschil niet waard.
     */
    fun laatDoor(aangeboden: String?): Boolean =
        staatOpen || MessageDigest.isEqual(token.toByteArray(), aangeboden.orEmpty().toByteArray())

    private companion object {
        /** Alleen lokaal en in de tests mag het beheerpad zonder token draaien. */
        val ONBESCHERMDE_PROFIELEN = setOf("dev", "test")
    }
}
