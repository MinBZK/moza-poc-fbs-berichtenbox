package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.smallrye.config.ConfigValidationException
import io.smallrye.config.SmallRyeConfig
import io.smallrye.config.SmallRyeConfigBuilder
import org.eclipse.microprofile.config.spi.ConfigSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Borgt dat de `@NotBlank`-invariant op [PublicatieConfig.verwerkingsregisterPubliceren]
 * en [PublicatieConfig.verwerkingsregisterAanleveren] daadwerkelijk afdwingt
 * dat een lege waarde de service niet kan starten. Voorkomt dat een latere
 * refactor de annotaties verwijdert zonder dat een test faalt.
 *
 * Vermijd `@QuarkusTest` met een leeg-property profile: SmallRye gooit dan
 * vóór JUnit-fixtures (te vroeg om mooi af te vangen). I.p.v. dat bouwen we
 * een [SmallRyeConfig] expliciet — `build()` is eager en doet de validatie
 * synchroon (gooit [ConfigValidationException]).
 *
 * **Niet getest hier**: het `@URL(regexp="^https?://.*")`-pad. Bean Validation
 * vereist een gewired Hibernate Validator (door Quarkus geleverd, niet door
 * raw SmallRye). De `@URL`-check wordt indirect afgedekt door
 * `PublicatieConfig`-startup in `@QuarkusTest`-suites.
 */
class PublicatieConfigValidationTest {

    private fun bouwConfigMet(props: Map<String, String>): SmallRyeConfig =
        SmallRyeConfigBuilder()
            .withMapping(PublicatieConfig::class.java)
            .addDefaultInterceptors()
            .withSources(InMemoryConfigSource(props))
            .build()

    /**
     * Buiten Quarkus runtime mist de Quarkus-Duration-converter (die `60s`/`24h`
     * accepteert); raw SmallRye gebruikt `java.time.Duration.parse()` die alleen
     * ISO-8601 (`PT60S`/`PT24H`) snapt. We overrulen daarom de defaults uit
     * `application.properties` met ISO-8601 in elke test-config zodat de
     * Duration-binding niet faalt voordat we onze eigenlijke validatie checken.
     */
    private fun bouwConfigMetMinimaal(extra: Map<String, String>): SmallRyeConfig {
        val basis = mutableMapOf(
            "magazijn.publicatie.organisatie.oin" to "00000001003214345000",
            "magazijn.publicatie.downstreams.aanmeld.url" to "https://aanmeld.example.nl/events",
            "magazijn.publicatie.polling.interval" to "PT60S",
            "magazijn.publicatie.opschonen.interval" to "PT24H",
            "magazijn.publicatie.backoff.basis" to "PT1S",
            "magazijn.publicatie.backoff.cap" to "PT1H",
        )
        basis.putAll(extra)
        return bouwConfigMet(basis)
    }

    @Test
    fun `lege verwerkingsregisterPubliceren faalt validatie`() {
        val ex = assertThrows(ConfigValidationException::class.java) {
            // Build is eager: validatie + Converter-conversie gebeurt hier, niet bij
            // de mapping-aanroep. Empty String wordt door SmallRye's BuiltInConverter
            // als null geclassificeerd, wat NoSuchElementException oplevert (= validatiefout).
            bouwConfigMetMinimaal(mapOf("magazijn.publicatie.verwerkingsregister-publiceren" to ""))
        }
        assertTrue(
            ex.message?.contains("verwerkingsregister-publiceren") == true,
            "verwacht foutreferentie naar verwerkingsregister-publiceren, kreeg: ${ex.message}",
        )
    }

    @Test
    fun `lege verwerkingsregisterAanleveren faalt validatie`() {
        val ex = assertThrows(ConfigValidationException::class.java) {
            bouwConfigMetMinimaal(mapOf("magazijn.publicatie.verwerkingsregister-aanleveren" to ""))
        }
        assertTrue(
            ex.message?.contains("verwerkingsregister-aanleveren") == true,
            "verwacht foutreferentie naar verwerkingsregister-aanleveren, kreeg: ${ex.message}",
        )
    }

    @Test
    fun `geldige https-URI passeert validatie`() {
        val config = bouwConfigMetMinimaal(
            mapOf(
                "magazijn.publicatie.verwerkingsregister-publiceren" to
                    "https://register.rijksoverheid.nl/verwerkingen/publiceren-1",
                "magazijn.publicatie.verwerkingsregister-aanleveren" to
                    "https://register.rijksoverheid.nl/verwerkingen/aanleveren-1",
            ),
        )
        val mapping = config.getConfigMapping(PublicatieConfig::class.java)
        assertEquals(
            "https://register.rijksoverheid.nl/verwerkingen/publiceren-1",
            mapping.verwerkingsregisterPubliceren(),
        )
        assertEquals(
            "https://register.rijksoverheid.nl/verwerkingen/aanleveren-1",
            mapping.verwerkingsregisterAanleveren(),
        )
    }

    /** Eenvoudige in-memory ConfigSource zodat we niet afhankelijk zijn van system-properties of yaml. */
    private class InMemoryConfigSource(private val props: Map<String, String>) : ConfigSource {
        override fun getProperties(): Map<String, String> = props
        override fun getPropertyNames(): Set<String> = props.keys
        override fun getValue(propertyName: String): String? = props[propertyName]
        override fun getName(): String = "test-in-memory"
        // Hoogste prio zodat onze waarden andere ConfigSources op de classpath winnen.
        override fun getOrdinal(): Int = 1000
    }
}
