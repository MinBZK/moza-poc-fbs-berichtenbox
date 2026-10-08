package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.quarkus.runtime.LaunchMode
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
        UitgegevenStelseldocument(register, Sleutelbron(config, klok, LaunchMode.NORMAL), config, klok)

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

    // Een afnemer weigert een exemplaar met een oudere iat dan het laatste dat hij accepteerde.
    // Dit is de gewone klokcorrectie van enkele seconden.
    @Test
    fun `loopt de klok een stukje terug, dan blijft het bestaande exemplaar staan tot de klok het inhaalt`() {
        val uitgegeven = uitgegeven().apply { bijOpstart(startup) }
        val eerste = uitgegeven.geldend()!!

        klok.moment = start.minusSeconds(30)
        uitgegeven.ververs()

        assertEquals(eerste, uitgegeven.geldend())

        klok.moment = start.plus(Duration.ofMinutes(5))
        uitgegeven.ververs()

        assertTrue(uitgegeven.geldend()!!.uitgegevenOp.isAfter(eerste.uitgegevenOp))
    }

    // Het omgekeerde geval: de klok stond bij een uitgifte vooruit. Dat exemplaar accepteert geen
    // afnemer, dus het telt niet als geldend en het mag de volgende uitgifte niet tegenhouden.
    @Test
    fun `stond de klok vooruit, dan telt dat exemplaar niet en wordt het bij de volgende uitgifte vervangen`() {
        klok.moment = start.plus(Duration.ofDays(3))
        val uitgegeven = uitgegeven().apply { bijOpstart(startup) }
        val uitDeToekomst = uitgegeven.geldend()!!

        klok.moment = start
        assertNull(uitgegeven.geldend())

        uitgegeven.ververs()

        val hersteld = uitgegeven.geldend()!!

        assertTrue(hersteld.uitgegevenOp.isBefore(uitDeToekomst.uitgegevenOp))
        assertEquals(start.epochSecond, hersteld.uitgegevenOp.epochSecond)
    }

    @Test
    fun `de grens ligt op de speling van een afnemer, zestig seconden`() {
        klok.moment = start.plusSeconds(60)
        val binnen = uitgegeven().apply { bijOpstart(startup) }
        val eerste = binnen.geldend()!!

        klok.moment = start
        binnen.ververs()
        assertEquals(eerste, binnen.geldend())

        klok.moment = start.plusSeconds(62)
        val buiten = uitgegeven().apply { bijOpstart(startup) }

        klok.moment = start
        assertNull(buiten.geldend())
    }

    @Test
    fun `elke uitgifte toetst of de keten bijna verloopt, niet alleen de eerste`() {
        val bron = spyk(Sleutelbron(config(), klok, LaunchMode.NORMAL))
        val uitgegeven = UitgegevenStelseldocument(register, bron, config(), klok).apply { bijOpstart(startup) }

        klok.moment = start.plus(Duration.ofHours(1))
        uitgegeven.ververs()

        verify(exactly = 2) { bron.waarschuwBijNaderendVerloop(any()) }
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

    // Een afnemer weigert een document dat langer dan 24 uur geldt; zo'n instelling levert dus
    // een dienst die gezond oogt en niets bruikbaars uitgeeft.
    @Test
    fun `een geldigheid boven de 24 uur blokkeert de start, precies 24 uur niet`() {
        val teLang = config(geldigheid = Duration.ofHours(24).plusSeconds(1))

        val fout = assertThrows(IllegalArgumentException::class.java) { uitgegeven(teLang).bijOpstart(startup) }

        assertTrue(fout.message.orEmpty().contains("mag niet langer zijn dan"), fout.message)
        uitgegeven(config(geldigheid = Duration.ofHours(24))).bijOpstart(startup)
    }

    @ParameterizedTest
    @CsvSource(
        "'', ''",
        "https://a.example, ''",
        "http://a.example, http://a.example",
        "https://a.example|http://b.example|HTTPS://c.example|http://d.example, http://b.example|http://d.example",
    )
    fun `alleen adressen zonder https tellen als onversleuteld`(adressen: String, verwacht: String) {
        fun lijst(tekst: String) = tekst.split("|").filter(String::isNotEmpty)

        val document = Stelseldocument(
            lijst(adressen).mapIndexed { index, adres -> Organisatie("0000000000000010000$index", "Organisatie $index", adres) },
        )

        assertEquals(lijst(verwacht), UitgegevenStelseldocument.onversleuteld(document))
    }
}
