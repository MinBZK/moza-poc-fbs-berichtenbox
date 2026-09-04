package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.stream.Stream

class AfzendernamenTest {

    private fun afzendernamenMet(inschrijvingen: List<Pair<Oin, String?>>): Afzendernamen {
        val entries = inschrijvingen.map { (oin, naam) ->
            Magazijninschrijving(oin, URI.create("http://localhost:8081"), naam = naam)
        }

        val register = object : Magazijnregister {
            override fun alle(): Collection<Magazijninschrijving> = entries
            override fun voorOin(oin: Oin): Magazijninschrijving? = entries.firstOrNull { it.oin == oin }
        }

        return Afzendernamen(register)
    }

    /**
     * Meerdere inschrijvingen borgen dat de lookup per organisatie discrimineert in plaats van
     * de enige/eerste naam terug te geven. De lege cardinaliteit hoort hier niet: die zou nul
     * assertions doen en dus altijd slagen; het lege register wordt hieronder bewezen.
     */
    @ParameterizedTest(name = "register={0}")
    @MethodSource("gevuldeRegisterCardinaliteiten")
    fun `geeft per organisatie haar eigen naam`(inschrijvingen: List<Pair<Oin, String?>>) {
        val afzendernamen = afzendernamenMet(inschrijvingen)

        inschrijvingen.forEach { (oin, naam) ->
            assertEquals(naam, afzendernamen.naamVoor(oin.waarde))
        }
    }

    @ParameterizedTest(name = "register={0}")
    @MethodSource("gevuldeRegisterCardinaliteiten")
    fun `niet-ingeschreven organisatie geeft geen naam`(inschrijvingen: List<Pair<Oin, String?>>) {
        val afzendernamen = afzendernamenMet(inschrijvingen)

        assertNull(afzendernamen.naamVoor("99999999999999999999"))
    }

    @Test
    fun `leeg register verzint geen naam`() {
        val afzendernamen = afzendernamenMet(emptyList())

        assertNull(afzendernamen.naamVoor(BELASTINGDIENST.waarde))
    }

    @Test
    fun `ingeschreven organisatie zonder naam in het register geeft geen naam`() {
        // Naamloos naast een genoemde buur: dit moet null geven en niet stilzwijgend de
        // naam van de buur of het nummer zelf opleveren.
        val afzendernamen = afzendernamenMet(
            listOf(
                NAAMLOOS to null,
                BELASTINGDIENST to "Belastingdienst",
            ),
        )

        assertNull(afzendernamen.naamVoor(NAAMLOOS.waarde))
        assertEquals("Belastingdienst", afzendernamen.naamVoor(BELASTINGDIENST.waarde))
    }

    /** Een niet-OIN-vormig magazijnId levert geen naam op in plaats van de lijst te laten falen. */
    @ParameterizedTest(name = "magazijnId=''{0}''")
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
    fun `magazijnId dat geen geldige OIN is geeft geen naam`(magazijnId: String) {
        val afzendernamen = afzendernamenMet(listOf(BELASTINGDIENST to "Belastingdienst"))

        assertNull(afzendernamen.naamVoor(magazijnId))
    }

    companion object {

        private val NAAMLOOS = Oin("00000001003214345000")
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
                    NAAMLOOS to null,
                ),
            ),
        )
    }
}
