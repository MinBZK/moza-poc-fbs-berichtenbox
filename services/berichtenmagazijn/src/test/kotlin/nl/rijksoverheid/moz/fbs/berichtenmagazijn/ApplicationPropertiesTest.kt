package nl.rijksoverheid.moz.fbs.berichtenmagazijn

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
}
