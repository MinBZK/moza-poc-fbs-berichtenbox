package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class UitgegevenStelseldocumentTest {

    /** Een klok die de test vooruit zet. */
    private class Zetklok(var moment: Instant) : Clock() {
        override fun instant() = moment
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    private val start = Instant.now()
    private val klok = Zetklok(start)
    private val einde = Testketens.geldig.certificaat.notAfter.toInstant()

    private val inschrijvingen = mutableListOf(
        Magazijninschrijving(Oin("00000000000000100000"), URI.create("https://a.example"), "RVO"),
    )
    private val register = object : Magazijnregister {
        override fun alle() = inschrijvingen.toList()
        override fun voorOin(oin: Oin) = inschrijvingen.firstOrNull { it.oin == oin }
    }

    private fun config(
        uitgever: String = "00000000000000001000",
        geldigheid: Duration = Duration.ofHours(24),
        verversen: Duration = Duration.ofHours(1),
    ) = object : StelseldocumentConfig {
        override fun uitgeverOin() = uitgever
        override fun omgeving() = "test"
        override fun geldigheid() = geldigheid
        override fun verversen() = verversen
        override fun keystore() = object : StelseldocumentConfig.Keystore {
            override fun pad() = Optional.of(Testketens.geldig.pad.toString())
            override fun wachtwoord() = Optional.of(String(Testketens.geldig.wachtwoord))
            override fun alias() = Wegwerpketen.ALIAS
        }
    }

    private fun uitgegeven(config: StelseldocumentConfig = config()) =
        UitgegevenStelseldocument(register, Sleutelbron(config, klok), config, klok)

    private val startup = mockk<StartupEvent>()

    @Test
    fun `voor de start is er geen document`() {
        assertNull(uitgegeven().geldend())
    }

    @Test
    fun `bij de start staat er een exemplaar dat 24 uur geldt`() {
        val uitgegeven = uitgegeven().apply { bijOpstart(startup) }

        assertEquals(1, uitgegeven.geldend()?.aantalOrganisaties)

        klok.moment = start.plus(Duration.ofHours(23))
        assertNotNull(uitgegeven.geldend())

        klok.moment = start.plus(Duration.ofHours(24))
        assertNull(uitgegeven.geldend())
    }

    @Test
    fun `verversen vervangt het exemplaar en neemt een registerwijziging mee`() {
        val uitgegeven = uitgegeven().apply { bijOpstart(startup) }
        val eerste = uitgegeven.geldend()!!

        inschrijvingen += Magazijninschrijving(Oin("00000001823288444000"), URI.create("https://b.example"), "Belastingdienst")
        klok.moment = start.plus(Duration.ofHours(1))
        uitgegeven.ververs()

        val tweede = uitgegeven.geldend()!!

        assertEquals(2, tweede.aantalOrganisaties)
        assertTrue(tweede.uitgegevenOp.isAfter(eerste.uitgegevenOp))
        assertTrue(tweede.versie != eerste.versie)
    }

    @Test
    fun `mislukt het verversen, dan blijft het vorige exemplaar tot het zelf verloopt`() {
        klok.moment = einde.minus(Duration.ofHours(2))
        val uitgegeven = uitgegeven().apply { bijOpstart(startup) }
        val laatste = uitgegeven.geldend()!!

        klok.moment = einde.minus(Duration.ofHours(1))
        uitgegeven.ververs()
        assertTrue(uitgegeven.geldend()!!.uitgegevenOp.isAfter(laatste.uitgegevenOp))
        val voorHetEinde = uitgegeven.geldend()!!

        // Het certificaat is verlopen: een nieuwe uitgifte kan niet, en dat mag de planner niet breken.
        klok.moment = einde.plusSeconds(1)
        uitgegeven.ververs()

        assertNull(uitgegeven.geldend())

        klok.moment = einde.minusSeconds(1)
        assertEquals(voorHetEinde, uitgegeven.geldend())
    }

    @Test
    fun `een verlopen certificaat bij de start blokkeert de start`() {
        klok.moment = einde.plusSeconds(1)

        assertThrows(OngeldigeOndertekensleutelException::class.java) { uitgegeven().bijOpstart(startup) }
    }

    @Test
    fun `een uitgever die geen OIN is blokkeert de start`() {
        assertThrows(DomainValidationException::class.java) { uitgegeven(config(uitgever = "logius")).bijOpstart(startup) }
    }

    @Test
    fun `een geldigheid die niet langer is dan het ververs-interval blokkeert de start`() {
        val gelijk = config(geldigheid = Duration.ofHours(1), verversen = Duration.ofHours(1))
        val korter = config(geldigheid = Duration.ofMinutes(30), verversen = Duration.ofHours(1))

        assertThrows(IllegalArgumentException::class.java) { uitgegeven(gelijk).bijOpstart(startup) }
        assertThrows(IllegalArgumentException::class.java) { uitgegeven(korter).bijOpstart(startup) }
    }
}
