package nl.rijksoverheid.moz.fbs.berichtenmagazijn

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
        "%dev.quarkus.datasource.jdbc.url, DB_JDBC_URL",
        "%dev.quarkus.datasource.username, DB_USERNAME",
        "%dev.quarkus.datasource.password, DB_PASSWORD",
        "%dev.magazijn.publicatie.organisatie.oin, MAGAZIJN_OIN",
        "%dev.magazijn.publicatie.downstreams.aanmeld.url, AANMELD_URL",
        "%dev.magazijn.publicatie.downstreams.notificatie.url, NOTIFICATIE_URL",
        "%dev.magazijn.publicatie.downstreams.notificatie.grant-hash, NOTIFICATIE_GRANT_HASH",
        "%dev.quarkus.rest-client.profiel-service.url, PROFIEL_SERVICE_URL",
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

    /**
     * Hetzelfde voor %prod — het profiel dat op ZAD draait, en waar de gevolgen van een kale
     * waarde het grootst zijn. Anders dan bij %dev telt hier niet dat er een default staat: de
     * URL-sleutels hebben er bewust géén, zodat een ontbrekende env-var de boot laat falen in
     * plaats van stilletjes op een localhost-default te landen.
     */
    @ParameterizedTest(name = "{0} leest {1}")
    @CsvSource(
        "%prod.magazijn.publicatie.downstreams.aanmeld.url, AANMELD_URL",
        "%prod.magazijn.publicatie.downstreams.notificatie.url, NOTIFICATIE_URL",
        "%prod.magazijn.publicatie.downstreams.notificatie.grant-hash, NOTIFICATIE_GRANT_HASH",
        "magazijn.publicatie.outway.host, OUTWAY_HOST",
    )
    fun `prod-regels lezen hun waarde uit een env-var`(sleutel: String, envVar: String) {
        val properties = Properties().apply {
            File("src/main/resources/application.properties").inputStream().use { load(it) }
        }

        val waarde = properties.getProperty(sleutel)

        assertTrue(
            waarde != null && (waarde == "\${$envVar}" || waarde.startsWith("\${$envVar:")),
            "$sleutel moet \${$envVar} of \${$envVar:<default>} zijn, maar was: $waarde",
        )
    }

    /**
     * De grant-hash en de outway-host horen bij elkaar: zonder host levert een gezette hash een
     * configuratiefout in plaats van verkeer. Beide moeten dus in hetzelfde profiel te zetten
     * zijn, en de host staat daarom op de basissleutel — die geldt ook voor %prod.
     */
    @Test
    fun `de outway-host geldt in elk profiel waarin een grant-hash gezet kan worden`() {
        val properties = Properties().apply {
            File("src/main/resources/application.properties").inputStream().use { load(it) }
        }

        assertTrue(
            properties.getProperty("magazijn.publicatie.outway.host") != null,
            "de basissleutel magazijn.publicatie.outway.host ontbreekt",
        )
        assertTrue(
            properties.keys.none { it.toString().endsWith(".magazijn.publicatie.outway.host") },
            "een profielspecifieke outway.host schaduwt de basissleutel; zet 'm op één plek",
        )
    }
}
