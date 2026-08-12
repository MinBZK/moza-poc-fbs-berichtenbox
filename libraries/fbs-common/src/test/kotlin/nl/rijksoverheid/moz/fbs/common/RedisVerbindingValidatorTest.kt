package nl.rijksoverheid.moz.fbs.common

import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

class RedisVerbindingValidatorTest {

    private val veiligAdres = "rediss://opslag.intern:6379"

    /** Een verbinding die aan alle eisen voldoet; per test alleen het onderzochte aspect afwijkend. */
    private fun valideerVeilig(
        profile: String = "prod",
        hosts: String = veiligAdres,
        password: String = "geheim",
        trustAll: Boolean = false,
        hostnameVerificatie: String = "HTTPS",
        tlsIngeschakeld: Boolean = false,
        unsafeAllowPlaintext: Boolean = false,
    ) = RedisVerbindingValidator.validate(
        profile = profile,
        hosts = hosts,
        password = password,
        trustAll = trustAll,
        hostnameVerificatie = hostnameVerificatie,
        tlsIngeschakeld = tlsIngeschakeld,
        unsafeAllowPlaintext = unsafeAllowPlaintext,
    )

    // --- profielafbakening ---

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `lokale profielen mogen onbeschermd verbinden`(profiel: String) {
        assertDoesNotThrow {
            RedisVerbindingValidator.validate(profiel, "redis://localhost:6379")
        }
    }

    /**
     * De vrijstelling is een allowlist op de exacte profielnaam. Een samengesteld of anders
     * geschreven profiel valt er dus buiten en krijgt de volle eis — fail-closed, en dat hoort
     * vast te liggen: het alternatief zou een `dev`-substring zijn die productie vrijstelt.
     */
    @ParameterizedTest
    @ValueSource(strings = ["dev,demo", "test,integratie", "DEV", "Test", "prod", "staging", "acceptatie"])
    fun `elk ander profiel krijgt de volle eis`(profiel: String) {
        assertThrows<IllegalStateException> {
            RedisVerbindingValidator.validate(profiel, "redis://opslag.intern:6379", password = "geheim")
        }
    }

    // --- versleuteling ---

    @Test
    fun `een verbinding die aan alle eisen voldoet komt door`() {
        assertDoesNotThrow { valideerVeilig() }
    }

    @Test
    fun `een onversleuteld adres wordt geweigerd`() {
        val ex = assertThrows<IllegalStateException> { valideerVeilig(hosts = "redis://opslag.intern:6379") }

        assertTrue(ex.message!!.contains("rediss://"), "melding moet het juiste schema noemen: ${ex.message}")
        assertTrue(ex.message!!.contains("prod"), "melding moet het profiel noemen: ${ex.message}")
    }

    /**
     * Hoofdlettergevoelig, gelijk aan wat de Redis-client accepteert. `REDISS://` doorlaten zou
     * hier een garantie suggereren die pas verderop door de striktere parser geleverd wordt.
     */
    @ParameterizedTest
    @ValueSource(strings = ["REDISS://opslag.intern:6379", "Rediss://opslag.intern:6379", "REDIS://opslag.intern:6379"])
    fun `een schema in hoofdletters telt niet als versleuteld`(adres: String) {
        assertThrows<IllegalStateException> { valideerVeilig(hosts = adres) }
    }

    /**
     * Een unix-socket kent geen netwerkblootstelling, maar zo wordt deze dienst niet uitgerold.
     * De weigering is bewust: liever een expliciete afwijzing die om een besluit vraagt dan een
     * uitzondering die ongemerkt ook op een netwerkadres van toepassing raakt.
     */
    @Test
    fun `een unix-socket-adres wordt geweigerd zolang die vorm niet ondersteund is`() {
        assertThrows<IllegalStateException> { valideerVeilig(hosts = "redis-socket:///var/run/redis.sock") }
    }

    @Test
    fun `een adres zonder schema wordt geweigerd`() {
        assertThrows<IllegalStateException> { valideerVeilig(hosts = "opslag.intern:6379") }
    }

    // --- peer-verificatie ---

    @Test
    fun `trust-all wordt geweigerd, ook bij een versleuteld adres`() {
        val ex = assertThrows<IllegalStateException> { valideerVeilig(trustAll = true) }

        assertTrue(ex.message!!.contains("trust-all"), "melding moet trust-all noemen: ${ex.message}")
    }

    /**
     * Quarkus zet hostnaam-verificatie standaard op NONE. Zonder deze check zou `rediss://`
     * versleutelen zonder te controleren of het certificaat bij de opslag hoort.
     */
    @ParameterizedTest
    @ValueSource(strings = ["NONE", "none", "None"])
    fun `uitgeschakelde hostnaam-verificatie wordt geweigerd`(waarde: String) {
        val ex = assertThrows<IllegalStateException> { valideerVeilig(hostnameVerificatie = waarde) }

        assertTrue(
            ex.message!!.contains("hostname-verification-algorithm"),
            "melding moet de verificatie-instelling noemen: ${ex.message}",
        )
    }

    // --- authenticatie ---

    @Test
    fun `een verbinding zonder wachtwoord wordt geweigerd`() {
        val ex = assertThrows<IllegalStateException> { valideerVeilig(password = "") }

        assertTrue(ex.message!!.contains("password"), "melding moet de wachtwoord-key noemen: ${ex.message}")
    }

    @Test
    fun `een leeg wachtwoord telt niet als ingesteld`() {
        assertThrows<IllegalStateException> { valideerVeilig(password = "   ") }
    }

    /**
     * Inloggegevens in het adres zijn technisch geldig, maar dan is het geen secret-veld meer:
     * het staat in de deployment-omgeving, is zichtbaar in pod-beschrijvingen, en de
     * Redis-client logt de volledige URI wanneer hij hem niet kan herbouwen. Ook mét een los
     * wachtwoord blijft dat een gebrek.
     */
    @ParameterizedTest
    @CsvSource(
        "rediss://default:geheim@opslag.intern:6379,  gebruiker en wachtwoord",
        "rediss://:geheim@opslag.intern:6379,         alleen wachtwoord",
    )
    fun `inloggegevens in het adres worden geweigerd`(adres: String, geval: String) {
        val ex = assertThrows<IllegalStateException>(geval) { valideerVeilig(hosts = adres) }

        assertTrue(ex.message!!.contains("inloggegevens in de URL"), "melding moet de oorzaak noemen: ${ex.message}")
    }

    /**
     * Een `@` in een query-parameter is een ondersteunde vorm en mag niet als inloggegevens
     * gelezen worden — anders zou de guard denken dat er geauthenticeerd wordt terwijl dat niet zo is.
     */
    @Test
    fun `een apenstaartje in een query-parameter telt niet als inloggegevens`() {
        assertDoesNotThrow { valideerVeilig(hosts = "rediss://opslag.intern:6379/0?client=a@b") }

        val ex = assertThrows<IllegalStateException> {
            valideerVeilig(hosts = "rediss://opslag.intern:6379/0?client=a@b", password = "")
        }

        assertTrue(ex.message!!.contains("password"), "zonder los wachtwoord hoort dit ongeauthenticeerd te zijn")
    }

    @ParameterizedTest
    @CsvSource(
        "rediss://@opslag.intern:6379,             lege userinfo",
        "rediss://gebruiker@opslag.intern:6379,    alleen gebruiker",
        "rediss://gebruiker:@opslag.intern:6379,   gebruiker met leeg wachtwoord",
    )
    fun `een adres zonder echt wachtwoord telt niet als authenticatie`(adres: String, geval: String) {
        assertThrows<IllegalStateException>(geval) { valideerVeilig(hosts = adres, password = "") }
    }

    // --- lijsten van adressen ---

    /**
     * `quarkus.redis.hosts` mag een lijst zijn. Zowel de versleuteling als de authenticatie
     * wordt per adres beoordeeld: één onbeschermd adres in de lijst is genoeg om de gegevens
     * alsnog onbeschermd te laten lopen.
     */
    @ParameterizedTest
    @CsvSource(
        "rediss://a:6379|redis://b:6379|rediss://c:6379,   1 van de 3",
        "rediss://a:6379|redis://b:6379|redis://c:6379,    2 van de 3",
        "redis://a:6379|redis://b:6379,                    2 van de 2",
        "redis://a:6379|rediss://b:6379,                   1 van de 2",
    )
    fun `elk adres in de lijst wordt geteld`(hostsMetPipes: String, verwachteTelling: String) {
        val ex = assertThrows<IllegalStateException> {
            valideerVeilig(hosts = hostsMetPipes.replace("|", ","))
        }

        assertTrue(ex.message!!.contains(verwachteTelling), "verwacht '$verwachteTelling' in: ${ex.message}")
    }

    @Test
    fun `een lijst met uitsluitend versleutelde adressen komt door`() {
        assertDoesNotThrow { valideerVeilig(hosts = "rediss://a.intern:6379,rediss://b.intern:6379") }
    }

    /** Eén adres met inloggegevens dekt de andere niet; die worden dan ongeauthenticeerd benaderd. */
    @Test
    fun `een wachtwoord bij een adres dekt de overige adressen niet`() {
        val ex = assertThrows<IllegalStateException> {
            valideerVeilig(hosts = "rediss://default:geheim@a.intern:6379,rediss://b.intern:6379", password = "")
        }

        assertTrue(ex.message!!.contains("password"), "melding moet de wachtwoord-key noemen: ${ex.message}")
    }

    @Test
    fun `spaties en lege elementen in de lijst tellen niet als adres`() {
        val ex = assertThrows<IllegalStateException> {
            valideerVeilig(hosts = " rediss://a:6379 ,, redis://b:6379 ,")
        }

        assertTrue(ex.message!!.contains("1 van de 2"), "lege elementen mogen niet meetellen: ${ex.message}")
    }

    /**
     * Een waarde die alleen uit scheidingstekens bestaat is niet blank maar levert nul adressen
     * op. Zonder aparte afslag zou elke per-adres-check leeg-waar zijn en een onbeoordeelde
     * configuratie als "voldoet" door de guard komen.
     */
    @ParameterizedTest
    @ValueSource(strings = [",", " , , ", ",,"])
    fun `een adreslijst zonder adressen wordt geweigerd`(hosts: String) {
        val ex = assertThrows<IllegalStateException> { valideerVeilig(hosts = hosts) }

        assertTrue(ex.message!!.contains("geen enkel adres"), "melding moet de lege lijst noemen: ${ex.message}")
    }

    @Test
    fun `een dienst zonder opslag-configuratie wordt overgeslagen`() {
        assertDoesNotThrow { valideerVeilig(hosts = "") }
        assertDoesNotThrow { valideerVeilig(hosts = "   ") }
    }

    // --- meldingen ---

    @Test
    fun `meerdere gebreken staan samen in een melding`() {
        val ex = assertThrows<IllegalStateException> {
            valideerVeilig(hosts = "redis://opslag.intern:6379", password = "", hostnameVerificatie = "NONE")
        }

        assertTrue(ex.message!!.contains("rediss://"), "versleuteling ontbreekt in: ${ex.message}")
        assertTrue(ex.message!!.contains("password"), "authenticatie ontbreekt in: ${ex.message}")
        assertTrue(ex.message!!.contains(";"), "gebreken horen gescheiden te zijn: ${ex.message}")
    }

    /**
     * De opstartmelding belandt in de log. Het adres mag inloggegevens in de userinfo dragen,
     * dus die waarde mag er nooit in terechtkomen — ook niet in het faalgeval.
     */
    @Test
    fun `de foutmelding bevat het adres en het wachtwoord niet`() {
        val ex = assertThrows<IllegalStateException> {
            valideerVeilig(hosts = "redis://default:zeergeheim@opslag.intern:6379", password = "")
        }

        assertFalse(ex.message!!.contains("zeergeheim"), "wachtwoord lekt in de melding: ${ex.message}")
        assertFalse(ex.message!!.contains("opslag.intern"), "adres lekt in de melding: ${ex.message}")
    }

    // --- bewuste uitschakeling ---

    @Test
    fun `de bewuste uitschakeling laat de transport-eis vallen maar waarschuwt`() {
        val meldingen = vangWaarschuwingen {
            assertDoesNotThrow {
                valideerVeilig(hosts = "redis://opslag.intern:6379", unsafeAllowPlaintext = true)
            }
        }

        assertEquals(1, meldingen.size, "verwacht precies één waarschuwing, was: $meldingen")
        assertTrue(meldingen[0].contains(RedisVerbindingValidator.ONBEVEILIGD_ALERT_TOKEN))
        assertTrue(meldingen[0].contains("prod"), "waarschuwing moet het profiel noemen: ${meldingen[0]}")
        assertFalse(meldingen[0].contains("opslag.intern"), "adres lekt in de waarschuwing: ${meldingen[0]}")
    }

    /**
     * De klep dekt alleen het transport. Mesh-mTLS vervangt de versleuteling, maar niet de
     * authenticatie: binnen een mesh zonder wachtwoord kan elke pod die het netwerk bereikt
     * alle berichten lezen.
     */
    @Test
    fun `de bewuste uitschakeling laat de wachtwoord-eis staan`() {
        val ex = assertThrows<IllegalStateException> {
            valideerVeilig(hosts = "redis://opslag.intern:6379", password = "", unsafeAllowPlaintext = true)
        }

        assertTrue(ex.message!!.contains("password"), "melding moet de wachtwoord-key noemen: ${ex.message}")
    }

    /** Zonder gebrek is er niets te melden; een waarschuwing per boot zou juist afstompen. */
    @Test
    fun `de bewuste uitschakeling waarschuwt niet bij een verbinding die wel voldoet`() {
        val meldingen = vangWaarschuwingen { valideerVeilig(unsafeAllowPlaintext = true) }

        assertTrue(meldingen.isEmpty(), "geen waarschuwing verwacht, was: $meldingen")
    }

    /** Ops koppelt een alertregel aan deze letterlijke waarde; wijzigen laat die regel stil uitvallen. */
    @Test
    fun `het alert-token heeft de afgesproken waarde`() {
        assertEquals("REDIS_UNPROTECTED", RedisVerbindingValidator.ONBEVEILIGD_ALERT_TOKEN)
        assertEquals("fbs.redis.unsafe-allow-plaintext", RedisVerbindingValidator.UNSAFE_KEY)
    }

    // --- bedrading op de configuratie ---

    /**
     * De statische controle zegt niets over de vraag of hij op de júíste config-keys wordt
     * losgelaten. Deze tests voeren de echte config-uitlezing uit, inclusief de ontdekking van
     * named clients — een tweede client naar dezelfde opslag zou de eis anders omzeilen.
     */
    @Test
    fun `de default client wordt uit de configuratie gelezen en beoordeeld`() {
        val config = configMet(
            "quarkus.redis.hosts" to "redis://opslag.intern:6379",
            "quarkus.redis.password" to "geheim",
        )

        val ex = assertThrows<IllegalStateException> {
            RedisVerbindingValidator.valideerAlleClients("prod", config)
        }

        assertTrue(ex.message!!.contains("quarkus.redis.hosts"), "melding moet de hosts-key noemen: ${ex.message}")
    }

    @Test
    fun `een named client wordt ook beoordeeld`() {
        val config = configMet(
            "quarkus.redis.hosts" to "rediss://a.intern:6379",
            "quarkus.redis.password" to "geheim",
            "quarkus.redis.tls.hostname-verification-algorithm" to "HTTPS",
            "quarkus.redis.beheer.hosts" to "redis://b.intern:6379",
            "quarkus.redis.beheer.password" to "geheim",
        )

        val ex = assertThrows<IllegalStateException> {
            RedisVerbindingValidator.valideerAlleClients("prod", config)
        }

        assertTrue(
            ex.message!!.contains("quarkus.redis.beheer.hosts"),
            "melding moet de named client noemen: ${ex.message}",
        )
    }

    @Test
    fun `een volledig veilige configuratie komt door de bedrading heen`() {
        val config = configMet(
            "quarkus.redis.hosts" to "rediss://opslag.intern:6379",
            "quarkus.redis.password" to "geheim",
            "quarkus.redis.tls.hostname-verification-algorithm" to "HTTPS",
            "quarkus.redis.tls.trust-all" to "false",
        )

        assertDoesNotThrow { RedisVerbindingValidator.valideerAlleClients("prod", config) }
    }

    @Test
    fun `een dienst zonder redis-configuratie doorloopt de bedrading zonder fout`() {
        assertDoesNotThrow {
            RedisVerbindingValidator.valideerAlleClients("prod", configMet("quarkus.http.port" to "8080"))
        }
    }

    private fun configMet(vararg paren: Pair<String, String>) = SmallRyeConfigBuilder()
        .withSources(PropertiesConfigSource(paren.toMap(), "test", 100))
        .build()

    /**
     * Formatteert het record zoals een log-appender dat doet. Zonder die stap zou een overstap
     * naar een `%s`-stijl logaanroep de PII-assertions hierboven stilzwijgend laten slagen op
     * een patroon in plaats van op de ingevulde tekst.
     */
    /**
     * De client zet trust-all zodra één van beide knoppen aanstaat. Alleen de client-eigen knop
     * lezen zou `quarkus.tls.trust-all=true` ongemerkt elk certificaat laten accepteren.
     */
    @Test
    fun `de globale trust-all-knop telt mee`() {
        val config = configMet(
            "quarkus.redis.hosts" to "rediss://opslag.intern:6379",
            "quarkus.redis.password" to "geheim",
            "quarkus.redis.tls.hostname-verification-algorithm" to "HTTPS",
            "quarkus.tls.trust-all" to "true",
        )

        val ex = assertThrows<IllegalStateException> { RedisVerbindingValidator.valideerAlleClients("prod", config) }

        assertTrue(ex.message!!.contains("trust-all"), "melding: ${ex.message}")
    }

    /**
     * Een benoemde TLS-configuratie vervangt het hele client-eigen tls-blok. De legacy-keys
     * beoordelen zou een garantie geven over instellingen die de client niet gebruikt.
     */
    @Test
    fun `een benoemde tls-configuratie wordt beoordeeld in plaats van de client-eigen keys`() {
        val config = configMet(
            "quarkus.redis.hosts" to "rediss://opslag.intern:6379",
            "quarkus.redis.password" to "geheim",
            "quarkus.redis.tls-configuration-name" to "opslag",
            // Zou de check hiernaar kijken, dan leek alles in orde.
            "quarkus.redis.tls.hostname-verification-algorithm" to "HTTPS",
            "quarkus.tls.opslag.trust-all" to "true",
        )

        val ex = assertThrows<IllegalStateException> { RedisVerbindingValidator.valideerAlleClients("prod", config) }

        assertTrue(ex.message!!.contains("trust-all"), "melding: ${ex.message}")
    }

    /** Een env-var als QUARKUS_REDIS__CACHE_A__HOSTS levert een clientnaam mét punt op. */
    @Test
    fun `een named client met een punt in de naam wordt ontdekt`() {
        val config = configMet(
            "quarkus.redis.hosts" to "rediss://a.intern:6379",
            "quarkus.redis.password" to "geheim",
            "quarkus.redis.tls.hostname-verification-algorithm" to "HTTPS",
            "quarkus.redis.\"cache.a\".hosts" to "redis://b.intern:6379",
            "quarkus.redis.\"cache.a\".password" to "geheim",
        )

        val ex = assertThrows<IllegalStateException> { RedisVerbindingValidator.valideerAlleClients("prod", config) }

        assertTrue(ex.message!!.contains("cache.a"), "melding moet de named client noemen: ${ex.message}")
    }

    /**
     * Een programmatische hosts-provider levert het adres buiten de configuratie om. Die waarde
     * is hier niet te beoordelen, dus stil doorlaten zou een garantie suggereren die er niet is.
     */
    @Test
    fun `een hosts-provider wordt geweigerd omdat hij niet te beoordelen is`() {
        val config = configMet("quarkus.redis.hosts-provider-name" to "eigen-provider")

        val ex = assertThrows<IllegalStateException> { RedisVerbindingValidator.valideerAlleClients("prod", config) }

        assertTrue(ex.message!!.contains("hosts-provider-name"), "melding: ${ex.message}")
    }

    /** TLS kan ook via de losse schakelaar aanstaan; dan zegt het schema niets meer. */
    @Test
    fun `tls via de losse schakelaar maakt het schema niet doorslaggevend`() {
        assertDoesNotThrow { valideerVeilig(hosts = "redis://opslag.intern:6379", tlsIngeschakeld = true) }
    }

    /** Staat de klep open, dan hoort de melding niet alsnog om TLS te vragen. */
    @Test
    fun `met de klep open noemt de melding alleen het ontbrekende wachtwoord`() {
        val ex = assertThrows<IllegalStateException> {
            valideerVeilig(hosts = "redis://opslag.intern:6379", password = "", unsafeAllowPlaintext = true)
        }

        assertTrue(ex.message!!.contains("password"), "melding: ${ex.message}")
        assertFalse(ex.message!!.contains("rediss://"), "TLS is al vrijgegeven, niet opnieuw eisen: ${ex.message}")
    }

    private fun vangWaarschuwingen(blok: () -> Unit): List<String> {
        val logger = Logger.getLogger(RedisVerbindingValidator::class.java.name)
        val formatter = SimpleFormatter()
        val opgevangen = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level == Level.WARNING) opgevangen += formatter.formatMessage(record)
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        logger.addHandler(handler)

        try {
            blok()
        } finally {
            logger.removeHandler(handler)
        }

        return opgevangen
    }
}
