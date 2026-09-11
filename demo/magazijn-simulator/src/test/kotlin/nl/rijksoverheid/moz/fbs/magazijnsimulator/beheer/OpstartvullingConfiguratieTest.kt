package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Optional
import java.util.OptionalInt

/**
 * De toets op de opstartvulling-configuratie.
 *
 * Deze waardes komen op de gedeelde omgeving uit een met de hand vervangen bestand, en dat is
 * precies waar een typefout ontstaat. De meldingen moeten dus de configuratiesleutel noemen: wie ze
 * leest heeft een properties-bestand bewerkt en geen verzoek verstuurd, en heeft aan een verwijzing
 * naar een JSON-veld niets.
 */
class OpstartvullingConfiguratieTest {

    @Test
    fun `zonder ontvangers is er geen vulling`() {
        assertNull(OpstartvullingConfiguratie.valideer(config(ontvangers = null)))
    }

    /**
     * Een lege lijst en lege regels betekenen hetzelfde als "niet ingevuld". Een bestand met
     * `ontvangers=` erin hoort niet anders af te lopen dan een bestand zonder die regel.
     */
    @ParameterizedTest
    @ValueSource(strings = ["", " ", ","])
    fun `een lege opgave telt als geen vulling`(ruw: String) {
        assertNull(OpstartvullingConfiguratie.valideer(config(ontvangers = ruw.split(","))))
    }

    @Test
    fun `een ontvanger levert een verzoek met de standaardaantallen`() {
        val verzoek = OpstartvullingConfiguratie.valideer(config(ontvangers = listOf("KVK:90000001")))

        assertEquals(listOf("KVK:90000001"), verzoek?.ontvangers)
        assertEquals(SeedVerzoek.STANDAARD_AANTAL, verzoek?.berichtenPerMagazijn)
        assertEquals(SeedVerzoek.STANDAARD_BIJLAGE_ELKE, verzoek?.bijlageElke)
    }

    @Test
    fun `meerdere ontvangers blijven allemaal staan`() {
        val verzoek = OpstartvullingConfiguratie.valideer(
            config(ontvangers = listOf("BSN:999993653", "KVK:90000001", "RSIN:823288444")),
        )

        assertEquals(3, verzoek?.ontvangers?.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["KVK:9000001", "BSN:999993652", "kvk:90000001", "90000001", "KVK:"])
    fun `een onbruikbare ontvanger noemt zijn plek in de configuratie`(ontvanger: String) {
        val fout = assertThrows(IllegalStateException::class.java) {
            OpstartvullingConfiguratie.valideer(config(ontvangers = listOf(ontvanger)))
        }

        assertTrue(
            fout.message!!.startsWith("magazijnsimulator.opstartvulling.ontvangers[0]"),
            "de melding hoort met de configuratiesleutel te beginnen, maar was: ${fout.message}",
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, SeedVerzoek.MAX_AANTAL + 1])
    fun `een onbruikbaar aantal noemt zijn eigen sleutel`(aantal: Int) {
        val fout = assertThrows(IllegalStateException::class.java) {
            OpstartvullingConfiguratie.valideer(config(ontvangers = listOf("KVK:90000001"), aantal = aantal))
        }

        assertTrue(
            fout.message!!.startsWith("magazijnsimulator.opstartvulling.berichten-per-magazijn"),
            "de melding hoort de sleutel te noemen, maar was: ${fout.message}",
        )
    }

    @Test
    fun `een negatieve bijlageverhouding noemt zijn eigen sleutel`() {
        val fout = assertThrows(IllegalStateException::class.java) {
            OpstartvullingConfiguratie.valideer(config(ontvangers = listOf("KVK:90000001"), bijlageElke = -1))
        }

        assertTrue(
            fout.message!!.startsWith("magazijnsimulator.opstartvulling.bijlage-elke"),
            "de melding hoort de sleutel te noemen, maar was: ${fout.message}",
        )
    }

    private fun config(ontvangers: List<String>?, aantal: Int? = null, bijlageElke: Int? = null) =
        object : OpstartvullingConfig {
            override fun ontvangers(): Optional<List<String>> = Optional.ofNullable(ontvangers)

            override fun berichtenPerMagazijn(): OptionalInt =
                aantal?.let { OptionalInt.of(it) } ?: OptionalInt.empty()

            override fun bijlageElke(): OptionalInt =
                bijlageElke?.let { OptionalInt.of(it) } ?: OptionalInt.empty()
        }
}
