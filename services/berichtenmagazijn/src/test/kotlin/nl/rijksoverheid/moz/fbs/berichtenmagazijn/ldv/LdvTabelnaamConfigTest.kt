package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfig
import io.smallrye.config.SmallRyeConfigBuilder
import nl.rijksoverheid.moz.fbs.common.LdvTabelnaamValidator
import org.eclipse.microprofile.config.spi.ConfigSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URL

/**
 * Borgt de configregel waarmee elk magazijn zijn logboek in het schema van de eigen
 * organisatie schrijft. Meerdere magazijnen delen in productie één database en één
 * DB-user; alleen die prefix houdt hun logboeken uit elkaar.
 *
 * Waarom apart van [LdvSchemaGekwalificeerdTest]: die zet de tabelnaam rechtstreeks in een
 * TestProfile en toont daarmee aan dat de wrapper een gekwalificeerde naam áán kan. Hij
 * raakt `application.properties` niet, dus als de `%prod`-regel wegvalt blijft hij groen
 * terwijl beide magazijnen weer in één tabel schrijven. Deze test leest de echte
 * properties.
 *
 * Geen `@QuarkusTest`: die draait per definitie in profiel `test`, waardoor de
 * `%prod`-regel nooit geëvalueerd wordt. Een expliciet gebouwde [SmallRyeConfig] met
 * profiel `prod` kan dat wel.
 */
class LdvTabelnaamConfigTest {

    private fun configMet(profiel: String, dbSchema: String?): SmallRyeConfig {
        val overrides = buildMap { dbSchema?.let { put("DB_SCHEMA", it) } }

        return SmallRyeConfigBuilder()
            .withSources(PropertiesConfigSource(serviceProperties(), APPLICATION_PROPERTIES_ORDINAL))
            .withSources(InMemoryConfigSource(overrides))
            .withProfile(profiel)
            .addDefaultInterceptors()
            .build()
    }

    private fun prodConfigMet(dbSchema: String?): SmallRyeConfig = configMet("prod", dbSchema)

    private fun tabelnaamUit(config: SmallRyeConfig): String =
        config.getValue(LdvTabelnaamValidator.TABEL_KEY, String::class.java)

    @ParameterizedTest
    @ValueSource(strings = ["magazijna", "magazijnb"])
    fun `in prod draagt de tabelnaam het schema van de organisatie`(schema: String) {
        val tabelnaam = tabelnaamUit(prodConfigMet(schema))

        assertEquals(
            "$schema.logboek_dataverwerkingen",
            tabelnaam,
            "zonder schema-prefix schrijven alle magazijnen in dezelfde logboektabel",
        )

        assertDoesNotThrow("de guard moet de eigen productiewaarde accepteren") {
            LdvTabelnaamValidator.validate("prod", "postgresql", tabelnaam)
        }
    }

    /**
     * Twee schema's leveren twee verschillende tabellen op. Met één waarde zou de test ook
     * slagen als de prefix genegeerd werd en er een vaste naam uitkwam.
     */
    @Test
    fun `verschillende schema's leveren verschillende tabellen op`() {
        assertEquals(
            listOf("magazijna.logboek_dataverwerkingen", "magazijnb.logboek_dataverwerkingen"),
            listOf("magazijna", "magazijnb").map { tabelnaamUit(prodConfigMet(it)) },
        )
    }

    /**
     * Buiten prod blijft de naam ongekwalificeerd: dev en test draaien tegen een eigen
     * database waar niets te scheiden valt, en kennen geen `DB_SCHEMA`.
     */
    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test houden de ongekwalificeerde tabelnaam`(profiel: String) {
        assertEquals("logboek_dataverwerkingen", tabelnaamUit(configMet(profiel, dbSchema = null)))
    }

    private class InMemoryConfigSource(private val props: Map<String, String>) : ConfigSource {
        override fun getProperties(): Map<String, String> = props
        override fun getPropertyNames(): Set<String> = props.keys
        override fun getValue(propertyName: String): String? = props[propertyName]
        override fun getName(): String = "test-in-memory"
        // Hoogste prio zodat DB_SCHEMA de expressie in application.properties vult.
        override fun getOrdinal(): Int = 1000
    }

    private companion object {
        const val APPLICATION_PROPERTIES_ORDINAL = 250

        /**
         * Het test-classpath draagt twee bestanden met deze naam: die van de service en die
         * van de testresources. Alleen de eerste bevat de profielregels die hier onderzocht
         * worden, en welke van de twee `getResource` teruggeeft hangt af van de volgorde die
         * Surefire aanhoudt. Daarom selecteren we op inhoud in plaats van op positie.
         */
        fun serviceProperties(): URL =
            LdvTabelnaamConfigTest::class.java.classLoader.getResources("application.properties")
                .toList()
                .firstOrNull { "%prod.${LdvTabelnaamValidator.TABEL_KEY}" in it.readText() }
                ?: error(
                    "geen application.properties met %prod.${LdvTabelnaamValidator.TABEL_KEY} op het " +
                        "classpath — is de profielregel hernoemd of verwijderd?",
                )
    }
}
