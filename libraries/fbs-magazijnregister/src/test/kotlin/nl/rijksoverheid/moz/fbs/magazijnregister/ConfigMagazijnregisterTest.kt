package nl.rijksoverheid.moz.fbs.magazijnregister

import io.quarkus.runtime.StartupEvent
import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.Optional

class ConfigMagazijnregisterTest {

    private val oinA = "00000001003214345000"
    private val oinB = "00000001823288444000"

    @Test
    fun `geldige config levert inschrijvingen via alle en voorOin`() {
        val register = register(
            "test",
            oinA to inschrijving("http://localhost:8081", "Belastingdienst"),
            oinB to inschrijving("http://localhost:8082", null),
        )

        assertEquals(2, register.alle().size)

        val inschrijvingA = register.voorOin(Oin(oinA))!!

        assertEquals(Oin(oinA), inschrijvingA.oin)
        assertEquals(URI.create("http://localhost:8081"), inschrijvingA.url)
        assertEquals("Belastingdienst", inschrijvingA.naam)
    }

    @Test
    fun `naam is optioneel en wordt null zonder waarde`() {
        val register = register("test", oinA to inschrijving("http://localhost:8081", null))

        assertNull(register.voorOin(Oin(oinA))!!.naam)
    }

    @Test
    fun `onbekende OIN levert null via voorOin`() {
        val register = register("test", oinA to inschrijving("http://localhost:8081", null))

        assertNull(register.voorOin(Oin(oinB)))
    }

    @Test
    fun `leeg register faalt fail-fast bij init`() {
        val ex = assertThrows<IllegalArgumentException> { register("test") }

        assertTrue(ex.message!!.contains("Geen magazijnen geconfigureerd"))
    }

    @Test
    fun `ongeldige OIN-key faalt fail-fast met de key in de melding`() {
        val ex = assertThrows<IllegalStateException> {
            register("test", "magazijn-a" to inschrijving("http://localhost:8081", null))
        }

        assertTrue(ex.message!!.contains("magazijn-a"), "foutmelding moet de ongeldige key tonen")
        assertTrue(ex.message!!.contains("OIN"), "foutmelding moet duiden dat de key een OIN moet zijn")
    }

    @Test
    fun `OIN-key geheel uit nullen faalt fail-fast`() {
        assertThrows<IllegalStateException> {
            register("test", "00000000000000000000" to inschrijving("http://localhost:8081", null))
        }
    }

    @Test
    fun `ongeldige URI faalt fail-fast met config-key in de melding`() {
        val ex = assertThrows<IllegalStateException> {
            register("test", oinA to inschrijving("http://exa mple.com", null))
        }

        assertTrue(ex.message!!.contains("magazijnen.\"$oinA\".url"))
    }

    @Test
    fun `niet-http-scheme faalt fail-fast`() {
        val ex = assertThrows<IllegalStateException> {
            register("test", oinA to inschrijving("ftp://example.com", null))
        }

        assertTrue(ex.message!!.contains("http(s)"))
    }

    @Test
    fun `hostloze URL faalt fail-fast`() {
        // URI.create accepteert `http:///pad` (scheme zonder host); zonder deze check
        // zou de boot slagen en pas het eerste verkeer falen.
        val ex = assertThrows<IllegalStateException> {
            register("test", oinA to inschrijving("http:///geen-host", null))
        }

        assertTrue(ex.message!!.contains("host"), "foutmelding moet de ontbrekende host benoemen")
    }

    @Test
    fun `bijOpstart logt na geslaagde init zonder fout`() {
        val register = register("test", oinA to inschrijving("http://localhost:8081", null))

        assertDoesNotThrow { (register as ConfigMagazijnregister).bijOpstart(StartupEvent()) }
    }

    @Test
    fun `prod-profiel weigert http-URL`() {
        val ex = assertThrows<IllegalArgumentException> {
            register("prod", oinA to inschrijving("http://magazijn.intern:8081", null))
        }

        assertTrue(ex.message!!.contains("BIO 13.2.1"), "foutmelding moet naar BIO 13.2.1 verwijzen")
    }

    @Test
    fun `prod-profiel accepteert https-URL`() {
        assertDoesNotThrow { register("prod", oinA to inschrijving("https://magazijn.intern:8443", "Magazijn")) }
    }

    @Test
    fun `grantHash is null zonder configwaarde`() {
        val register = register("test", oinA to inschrijving("http://localhost:8081", null))

        assertNull(register.voorOin(Oin(oinA))!!.grantHash)
    }

    @Test
    fun `grantHash wordt doorgegeven vanuit config`() {
        val register = register("test", oinA to inschrijving("http://localhost:8081", null, grantHash = "abc123"))

        assertEquals("abc123", register.voorOin(Oin(oinA))!!.grantHash)
    }

    @Test
    fun `meerdere magazijnen waarvan er een grantHash heeft en een niet`() {
        val register = register(
            "test",
            oinA to inschrijving("http://localhost:8081", null, grantHash = "abc123"),
            oinB to inschrijving("http://localhost:8082", null),
        )

        assertEquals("abc123", register.voorOin(Oin(oinA))!!.grantHash)
        assertNull(register.voorOin(Oin(oinB))!!.grantHash)
    }

    @Test
    fun `grantHash wordt getrimd`() {
        val register = register("test", oinA to inschrijving("http://localhost:8081", null, grantHash = "  abc123  "))

        assertEquals("abc123", register.voorOin(Oin(oinA))!!.grantHash)
    }

    @Test
    fun `lege grantHash faalt fail-fast met de config-key in de melding`() {
        val ex = assertThrows<IllegalStateException> {
            register("test", oinA to inschrijving("http://localhost:8081", null, grantHash = ""))
        }

        assertTrue(ex.message!!.contains("magazijnen.\"$oinA\".grantHash"))
    }

    @Test
    fun `whitespace-only grantHash faalt fail-fast met de config-key in de melding`() {
        val ex = assertThrows<IllegalStateException> {
            register("test", oinA to inschrijving("http://localhost:8081", null, grantHash = "   "))
        }

        assertTrue(ex.message!!.contains("magazijnen.\"$oinA\".grantHash"))
    }

    /**
     * Boot-regressietest voor `magazijnen."<OIN>".grantHash=${MAGAZIJN_A_GRANT_HASH:}`
     * (de vorm in productie-config) met de env-var ongezet: SmallRye expandeert dat naar
     * een lege string die op `Optional.empty()` bindt, dus [ConfigMagazijnregister] mag
     * hier niet fail-fast falen — het handgebouwde mock-object in [inschrijving] hierboven
     * omzeilt SmallRye's expressie-expansie en kan dit gedrag niet aantonen; deze test
     * gaat via een echte `SmallRyeConfigBuilder`-binding om de volledige keten te dekken.
     */
    @Test
    fun `onbeantwoorde grantHash-expressie met lege default boot niet fail-fast`() {
        val config = SmallRyeConfigBuilder()
            .addDefaultInterceptors() // activeert SmallRye's `${...}`-expressie-expansie
            .withMapping(MagazijnregisterConfig::class.java)
            .withSources(
                PropertiesConfigSource(
                    mapOf(
                        "magazijnen.\"$oinA\".url" to "http://localhost:8081",
                        "magazijnen.\"$oinA\".grantHash" to "\${MAGAZIJN_A_GRANT_HASH:}",
                    ),
                    "test",
                    100,
                ),
            )
            .build()
            .getConfigMapping(MagazijnregisterConfig::class.java)

        val register = assertDoesNotThrow { ConfigMagazijnregister(config, "test").apply { init() } }

        assertNull(register.voorOin(Oin(oinA))!!.grantHash)
    }

    private fun register(profiel: String, vararg entries: Pair<String, MagazijnregisterConfig.Inschrijving>): Magazijnregister {
        val config = object : MagazijnregisterConfig {
            override fun inschrijvingen(): Map<String, MagazijnregisterConfig.Inschrijving> = mapOf(*entries)
        }

        return ConfigMagazijnregister(config, profiel).apply { init() }
    }

    private fun inschrijving(url: String, naam: String?, grantHash: String? = null): MagazijnregisterConfig.Inschrijving =
        object : MagazijnregisterConfig.Inschrijving {
            override fun url(): String = url
            override fun naam(): Optional<String> = Optional.ofNullable(naam)
            override fun grantHash(): Optional<String> = Optional.ofNullable(grantHash)
        }
}
