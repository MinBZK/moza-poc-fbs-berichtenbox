package nl.rijksoverheid.moz.fbs.common

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

class RedisVerbindingValidatorTest {

    private val veiligAdres = "rediss://opslag.intern:6379"

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `lokale profielen mogen onversleuteld en zonder wachtwoord verbinden`(profiel: String) {
        assertDoesNotThrow {
            RedisVerbindingValidator.validate(profiel, "redis://localhost:6379")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["prod", "staging", "acceptatie"])
    fun `productie-achtige profielen weigeren een onversleutelde verbinding`(profiel: String) {
        val ex = assertThrows<IllegalStateException> {
            RedisVerbindingValidator.validate(profiel, "redis://opslag.intern:6379", password = "geheim")
        }

        assertTrue(ex.message!!.contains("rediss://"), "melding moet het juiste schema noemen: ${ex.message}")
        assertTrue(ex.message!!.contains(profiel), "melding moet het profiel noemen: ${ex.message}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["prod", "staging", "acceptatie"])
    fun `productie-achtige profielen weigeren een verbinding zonder wachtwoord`(profiel: String) {
        val ex = assertThrows<IllegalStateException> {
            RedisVerbindingValidator.validate(profiel, veiligAdres)
        }

        assertTrue(
            ex.message!!.contains(RedisVerbindingValidator.PASSWORD_KEY),
            "melding moet de wachtwoord-key noemen: ${ex.message}",
        )
    }

    @Test
    fun `een versleutelde en geauthenticeerde verbinding komt door`() {
        assertDoesNotThrow {
            RedisVerbindingValidator.validate("prod", veiligAdres, password = "geheim")
        }
    }

    /**
     * De vorm met inloggegevens in het adres wordt in het veld gebruikt en is geldige
     * authenticatie; hem afwijzen zou een werkende opstelling blokkeren om een stijlkwestie.
     */
    @Test
    fun `een wachtwoord in het adres telt als authenticatie`() {
        assertDoesNotThrow {
            RedisVerbindingValidator.validate("prod", "rediss://default:geheim@opslag.intern:6379")
        }
    }

    @ParameterizedTest
    @CsvSource(
        "rediss://opslag.intern:6379,                 alleen host",
        "rediss://@opslag.intern:6379,                lege userinfo",
        "rediss://gebruiker@opslag.intern:6379,       alleen gebruiker",
        "rediss://gebruiker:@opslag.intern:6379,      gebruiker met leeg wachtwoord",
    )
    fun `een adres zonder echt wachtwoord telt niet als authenticatie`(adres: String, geval: String) {
        assertThrows<IllegalStateException>(geval) {
            RedisVerbindingValidator.validate("prod", adres)
        }
    }

    /**
     * `quarkus.redis.hosts` mag een lijst zijn. Eén onversleuteld adres daarin is genoeg om
     * de gegevens alsnog plaintext te laten lopen, dus de check kijkt niet alleen naar de eerste.
     */
    @Test
    fun `een onversleuteld adres verderop in de lijst wordt ook geweigerd`() {
        val ex = assertThrows<IllegalStateException> {
            RedisVerbindingValidator.validate(
                "prod",
                "rediss://a.intern:6379, redis://b.intern:6379, rediss://c.intern:6379",
                password = "geheim",
            )
        }

        assertTrue(ex.message!!.contains("1 van de 3"), "melding moet aangeven hoeveel adressen falen: ${ex.message}")
    }

    @Test
    fun `een lijst met uitsluitend versleutelde adressen komt door`() {
        assertDoesNotThrow {
            RedisVerbindingValidator.validate(
                "prod",
                "rediss://a.intern:6379,rediss://b.intern:6379",
                password = "geheim",
            )
        }
    }

    @Test
    fun `een dienst zonder opslag-configuratie wordt overgeslagen`() {
        assertDoesNotThrow { RedisVerbindingValidator.validate("prod", "") }
        assertDoesNotThrow { RedisVerbindingValidator.validate("prod", "   ") }
    }

    @Test
    fun `een leeg wachtwoord telt niet als ingesteld`() {
        assertThrows<IllegalStateException> {
            RedisVerbindingValidator.validate("prod", veiligAdres, password = "   ")
        }
    }

    /**
     * De opstartmelding belandt in de log. Het adres mag inloggegevens in de userinfo dragen,
     * dus die waarde mag er nooit in terechtkomen — ook niet in het faalgeval.
     */
    @Test
    fun `de foutmelding bevat het adres en het wachtwoord niet`() {
        val ex = assertThrows<IllegalStateException> {
            RedisVerbindingValidator.validate("prod", "redis://default:zeergeheim@opslag.intern:6379")
        }

        assertFalse(ex.message!!.contains("zeergeheim"), "wachtwoord lekt in de melding: ${ex.message}")
        assertFalse(ex.message!!.contains("opslag.intern"), "adres lekt in de melding: ${ex.message}")
    }

    @Test
    fun `de bewuste uitschakeling laat de start door maar waarschuwt met het alert-token`() {
        val meldingen = vangLogMeldingen {
            assertDoesNotThrow {
                RedisVerbindingValidator.validate(
                    "prod",
                    "redis://default:zeergeheim@opslag.intern:6379",
                    unsafeAllowPlaintext = true,
                )
            }
        }

        assertEquals(1, meldingen.size, "verwacht precies één waarschuwing, was: $meldingen")
        assertTrue(meldingen[0].contains(RedisVerbindingValidator.ONBEVEILIGD_ALERT_TOKEN))
        assertFalse(meldingen[0].contains("zeergeheim"), "wachtwoord lekt in de waarschuwing: ${meldingen[0]}")
        assertFalse(meldingen[0].contains("opslag.intern"), "adres lekt in de waarschuwing: ${meldingen[0]}")
    }

    /** Zonder gebrek is er niets te melden; een waarschuwing per boot zou juist afstompen. */
    @Test
    fun `de bewuste uitschakeling waarschuwt niet bij een verbinding die wel voldoet`() {
        val meldingen = vangLogMeldingen {
            RedisVerbindingValidator.validate("prod", veiligAdres, password = "geheim", unsafeAllowPlaintext = true)
        }

        assertTrue(meldingen.isEmpty(), "geen waarschuwing verwacht, was: $meldingen")
    }

    private fun vangLogMeldingen(blok: () -> Unit): List<String> {
        val logger = Logger.getLogger(RedisVerbindingValidator::class.java.name)
        val opgevangen = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.WARNING.intValue()) opgevangen += record.message
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
