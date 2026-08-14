package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import nl.rijksoverheid.moz.fbs.common.fsc.ProfielFscOutwayHeadersFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
     * Elk geconfigureerd magazijn moet een `grantHash`-sleutel hebben, ook zonder contract.
     *
     * Ontbreekt hij, dan levert de contract-bootstrap wel een `MAGAZIJN_<X>_GRANT_HASH` maar bindt
     * niets die waarde: dat magazijn wordt zónder `Fsc-Grant-Hash` aangeroepen — dus buiten de
     * FSC-keten om — terwijl zowel de bootstrap als het ophalen groen melden. Een lege env-var is
     * de veilige uitkomst; een ontbrekende sleutel is onzichtbaar.
     */
    @Test
    fun `elk magazijn met een url heeft ook een grantHash-sleutel`() {
        val properties = Properties().apply {
            File("src/main/resources/application.properties").inputStream().use { load(it) }
        }

        val urlSleutel = Regex("""^magazijnen\."(\d+)"\.url$""")
        val oins = properties.stringPropertyNames()
            .mapNotNull { urlSleutel.find(it)?.groupValues?.get(1) }
            .toSortedSet()

        assertTrue(oins.isNotEmpty(), "geen enkel magazijn in application.properties — deze test meet niets")

        val zonderGrantHash = oins.filter { properties.getProperty("""magazijnen."$it".grantHash""") == null }

        assertEquals(
            emptyList<String>(),
            zonderGrantHash,
            "magazijn(en) zonder grantHash-sleutel: die worden buiten de FSC-keten om aangeroepen",
        )
    }

    /**
     * Een `%dev`-override mag de env-var van de basissleutel niet doodslaan.
     *
     * Leest de basissleutel `${VAR}` en zet `%dev` er een kale waarde tegenover, dan wint die kale
     * waarde onder `QUARKUS_PROFILE=dev` — en dat is aan het gedrag niet te zien: de service start,
     * het verkeer gaat alleen naar het verkeerde adres. Zo liepen de magazijn-URL's langs de
     * FSC-outway heen en Redis/Profiel langs Toxiproxy. Deze test dekt de hele klasse: elke
     * `%dev`-sleutel die een env-var-gestuurde basissleutel overschrijft, moet diezelfde var noemen.
     */
    @Test
    fun `dev-overrides slaan de env-var van hun basissleutel niet dood`() {
        val properties = Properties().apply {
            File("src/main/resources/application.properties").inputStream().use { load(it) }
        }

        val envVar = Regex("""\$\{([A-Z0-9_]+)[:}]""")
        val overtreders = properties.stringPropertyNames()
            .filter { it.startsWith("%dev.") }
            .mapNotNull { devSleutel ->
                val basis = devSleutel.removePrefix("%dev.")
                val basisVar = envVar.find(properties.getProperty(basis) ?: "")?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val devWaarde = properties.getProperty(devSleutel) ?: ""

                if (devWaarde.contains("\${$basisVar")) null else "$devSleutel (basis leest \$$basisVar)"
            }
            .sorted()

        assertEquals(
            emptyList<String>(),
            overtreders,
            "deze %dev-overrides negeren de env-var van hun basissleutel; het verkeer gaat dan stil naar het verkeerde adres",
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
