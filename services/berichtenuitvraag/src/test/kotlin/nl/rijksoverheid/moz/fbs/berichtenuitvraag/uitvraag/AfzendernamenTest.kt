package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtSamenvatting
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

class AfzendernamenTest {

    private fun afzendernamenMet(inschrijvingen: List<Pair<Oin, String>>): Afzendernamen {
        val entries = inschrijvingen.map { (oin, naam) ->
            Magazijninschrijving(oin, URI.create("http://localhost:8081"), naam = naam)
        }

        val register = object : Magazijnregister {
            override fun alle(): Collection<Magazijninschrijving> = entries
            override fun voorOin(oin: Oin): Magazijninschrijving? = entries.firstOrNull { it.oin == oin }
        }

        return Afzendernamen(register)
    }

    /** Een bericht zoals het uit de sessiecache komt: een magazijnId plus de naam van toen. */
    private fun samenvatting(magazijnId: String, meegeschrevenNaam: String) = BerichtSamenvatting(
        berichtId = UUID.randomUUID(),
        afzender = "00000001003214345000",
        afzenderNaam = meegeschrevenNaam,
        ontvanger = Bsn("999990019"),
        onderwerp = "Onderwerp",
        publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
        magazijnId = magazijnId,
        aantalBijlagen = 0,
    )

    /**
     * Meerdere inschrijvingen borgen dat de lookup per organisatie discrimineert in plaats van de
     * enige/eerste naam terug te geven.
     */
    @ParameterizedTest(name = "register={0}")
    @MethodSource("gevuldeRegisterCardinaliteiten")
    fun `geeft per organisatie haar eigen naam uit het register`(inschrijvingen: List<Pair<Oin, String>>) {
        val afzendernamen = afzendernamenMet(inschrijvingen)

        inschrijvingen.forEach { (oin, naam) ->
            assertEquals(naam, afzendernamen.naamVoor(samenvatting(oin.waarde, "Oude naam")))
        }
    }

    @Test
    fun `het register wint van de meegeschreven naam, zodat een hernoeming meteen doorwerkt`() {
        val afzendernamen = afzendernamenMet(listOf(BELASTINGDIENST to "Belastingdienst"))

        assertEquals(
            "Belastingdienst",
            afzendernamen.naamVoor(samenvatting(BELASTINGDIENST.waarde, "Naam van vóór de hernoeming")),
        )
    }

    @Test
    fun `een organisatie die uit het register verdween houdt haar meegeschreven naam`() {
        // Config-drift tijdens een lopende sessie: het bericht staat nog in de cache, de
        // inschrijving niet meer. De naam die bij het schrijven meeging is dan het vangnet.
        val afzendernamen = afzendernamenMet(listOf(BELASTINGDIENST to "Belastingdienst"))

        assertEquals("RVO", afzendernamen.naamVoor(samenvatting(RVO.waarde, "RVO")))
    }

    @Test
    fun `een leeg register laat elk bericht op zijn meegeschreven naam terugvallen`() {
        val afzendernamen = afzendernamenMet(emptyList())

        assertEquals(
            "Belastingdienst",
            afzendernamen.naamVoor(samenvatting(BELASTINGDIENST.waarde, "Belastingdienst")),
        )
    }

    /**
     * Een magazijnId uit de sessiecache hoort een OIN te zijn; een waarde uit een oudere
     * registerstaat mag de lijst niet laten falen en verliest hooguit de verse register-naam.
     */
    @ParameterizedTest(name = "magazijnId=\'\'{0}\'\'")
    @ValueSource(
        strings = [
            "",
            "magazijn-a",
            "0000000100321434500",
            "000000010032143450000",
            "0000000o003214345000",
            "00000000000000000000",
        ],
    )
    fun `magazijnId dat geen geldige OIN is valt terug op de meegeschreven naam`(magazijnId: String) {
        val afzendernamen = afzendernamenMet(listOf(BELASTINGDIENST to "Belastingdienst"))

        assertEquals("Magazijn A", afzendernamen.naamVoor(samenvatting(magazijnId, "Magazijn A")))
    }

    companion object {

        private val KVK = Oin("00000001003214345000")
        private val BELASTINGDIENST = Oin("00000001823288444000")
        private val RVO = Oin("00000000000000100000")

        @JvmStatic
        fun gevuldeRegisterCardinaliteiten(): Stream<Arguments> = Stream.of(
            Arguments.of(listOf(BELASTINGDIENST to "Belastingdienst")),
            Arguments.of(listOf(BELASTINGDIENST to "Belastingdienst", RVO to "RVO")),
            Arguments.of(
                listOf(
                    BELASTINGDIENST to "Belastingdienst",
                    RVO to "RVO",
                    KVK to "Kamer van Koophandel",
                ),
            ),
        )
    }
}
