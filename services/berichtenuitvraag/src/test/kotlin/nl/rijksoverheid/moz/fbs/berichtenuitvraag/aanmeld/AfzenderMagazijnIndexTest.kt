package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.util.stream.Stream

class AfzenderMagazijnIndexTest {

    private val oinOnbekend = Oin("99999999999999999999")

    private fun indexMet(ingeschreven: List<Oin>): AfzenderMagazijnIndex {
        val entries = ingeschreven.map { oin ->
            Magazijninschrijving(oin, URI.create("http://localhost:8081"), naam = null)
        }

        val register = object : Magazijnregister {
            override fun alle(): Collection<Magazijninschrijving> = entries
            override fun voorOin(oin: Oin): Magazijninschrijving? = entries.firstOrNull { it.oin == oin }
        }

        return AfzenderMagazijnIndex(register)
    }

    /**
     * Elke ingeschreven afzender mapt naar zijn eigen magazijn (= de OIN zelf). De
     * cardinaliteit varieert van leeg t/m meerdere: bij meerdere inschrijvingen borgt
     * dit dat de lookup per afzender discrimineert i.p.v. de enige/eerste terug te geven;
     * bij leeg dat een register zonder inschrijvingen geen magazijn verzint.
     */
    @ParameterizedTest(name = "register={0}")
    @MethodSource("ingeschrevenCardinaliteiten")
    fun `mapt elke ingeschreven afzender-OIN naar zichzelf`(ingeschreven: List<Oin>) {
        val index = indexMet(ingeschreven)

        ingeschreven.forEach { oin ->
            assertEquals(oin.waarde, index.magazijnVoor(oin))
        }
    }

    @ParameterizedTest(name = "register={0}")
    @MethodSource("ingeschrevenCardinaliteiten")
    fun `onbekende afzender geeft null`(ingeschreven: List<Oin>) {
        val index = indexMet(ingeschreven)

        assertNull(index.magazijnVoor(oinOnbekend))
    }

    companion object {

        @JvmStatic
        fun ingeschrevenCardinaliteiten(): Stream<Arguments> = Stream.of(
            Arguments.of(emptyList<Oin>()),
            Arguments.of(listOf(Oin("00000001003214345000"))),
            Arguments.of(listOf(Oin("00000001003214345000"), Oin("00000001823288444000"))),
            Arguments.of(
                listOf(
                    Oin("00000001003214345000"),
                    Oin("00000001823288444000"),
                    Oin("00000003273416502000"),
                ),
            ),
        )
    }
}
