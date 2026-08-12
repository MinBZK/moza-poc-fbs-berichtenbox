package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import nl.rijksoverheid.moz.fbs.common.fsc.ProfielFscOutwayHeadersFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
     * Pint dat de %dev-regels die de demo-stack aanstuurt hun env-var-expansie houden. Een
     * %dev-sleutel schaduwt de gelijknamige basissleutel, dus staat hier een kale waarde, dan
     * bereikt de env-var uit `compose.yaml` de container niet en zoekt de service zijn
     * afhankelijkheden op localhost — binnen een container zichzelf. Dat is eerder gebeurd bij
     * het samenvoegen van twee takken die hetzelfde blok herschreven, zonder dat iets faalde.
     */
    @ParameterizedTest(name = "{0} leest {1}")
    @CsvSource(
        "%dev.quarkus.redis.hosts, REDIS_HOSTS",
        "%dev.quarkus.rest-client.profiel-service.url, PROFIEL_SERVICE_URL",
        "%dev.magazijnen.\"00000000000000100000\".url, MAGAZIJN_A_URL",
        "%dev.magazijnen.\"00000001823288444000\".url, MAGAZIJN_B_URL",
        "%dev.logboekdataverwerking.postgresql.url, LDV_POSTGRES_URL",
        "%dev.logboekdataverwerking.postgresql.username, LDV_POSTGRES_USERNAME",
        "%dev.logboekdataverwerking.postgresql.password, LDV_POSTGRES_PASSWORD",
    )
    fun `dev-regels van de demo houden hun env-var-expansie`(sleutel: String, envVar: String) {
        val properties = Properties().apply {
            File("src/main/resources/application.properties").inputStream().use { load(it) }
        }

        val waarde = properties.getProperty(sleutel)

        assertTrue(
            waarde != null && waarde.startsWith("\${$envVar:"),
            "$sleutel moet beginnen met \${$envVar:<default>}, maar was: $waarde",
        )
    }
}
