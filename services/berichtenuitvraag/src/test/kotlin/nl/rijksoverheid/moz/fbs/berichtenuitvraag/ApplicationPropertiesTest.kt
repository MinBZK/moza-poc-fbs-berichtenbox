package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import nl.rijksoverheid.moz.fbs.common.fsc.ProfielFscOutwayHeadersFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Pint dat `application.properties` de FSC-grant-hash-configsleutel van
 * [ProfielFscOutwayHeadersFilter] daadwerkelijk bevat, in de expressie-vorm die de
 * env-var optioneel maakt. Zonder deze regel schakelt de filter stilzwijgend uit —
 * geen `Fsc-Grant-Hash`-header, en de outway antwoordt "service not found" — zonder
 * dat een bestaande test dat opmerkt. Bewust géén `@QuarkusTest`: dit leest het
 * bestand rechtstreeks van disk, zodat de test ook zonder Docker draait.
 */
class ApplicationPropertiesTest {

    @Test
    fun `profiel-service grant-hash-configsleutel staat in application-properties met env-var-expansie`() {
        val properties = Properties().apply {
            File("src/main/resources/application.properties").inputStream().use { load(it) }
        }

        assertEquals(
            "\${PROFIEL_SERVICE_GRANT_HASH:}",
            properties.getProperty(ProfielFscOutwayHeadersFilter.CONFIG_KEY),
        )
    }

    /**
     * De `%dev`-magazijn-URL's moeten hun env-var respecteren. Stond er een kale `localhost`-waarde,
     * dan overrulet het dev-profiel de omgeving en praat de uitvraag met het magazijn naast de deur
     * terwijl `MAGAZIJN_A_URL` naar een FSC-outway wijst. Dat is aan het gedrag niet te zien: het
     * ophalen slaagt, alleen loopt het verkeer buiten de keten om en blijft het transactielogboek
     * leeg.
     */
    @Test
    fun `dev-magazijn-URLs laten de omgeving voorgaan op de localhost-default`() {
        val properties = Properties().apply {
            File("src/main/resources/application.properties").inputStream().use { load(it) }
        }

        assertEquals(
            "\${MAGAZIJN_A_URL:http://localhost:8090}",
            properties.getProperty("%dev.magazijnen.\"00000000000000100000\".url"),
        )
        assertEquals(
            "\${MAGAZIJN_B_URL:http://localhost:8091}",
            properties.getProperty("%dev.magazijnen.\"00000001823288444000\".url"),
        )
    }
}
