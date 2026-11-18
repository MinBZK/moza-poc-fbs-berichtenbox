package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.democonsole.sessie.GeheugenSessieOpslag.Companion.MINUUT
import nl.rijksoverheid.moz.fbs.democonsole.sessie.GeheugenSessieOpslag.Companion.NU
import nl.rijksoverheid.moz.fbs.democonsole.sessie.GeheugenSessieOpslag.Companion.UUR
import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaBron
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class SessieBeheerTest {

    private val opslag = GeheugenSessieOpslag()

    private val personaService = mockk<PersonaService> { every { alle() } returns listOf(PIETERSEN, VAN_DIJK) }

    private val beheer = SessieBeheer(opslag, personaService, Sessieverwijzer())

    private fun sessiesVan(persona: String): List<LopendeSessie> =
        beheer.overzicht().personas.single { it.persona == persona }.sessies

    @Test
    fun `zonder sessies staat elke persona er met een lege lijst`() {
        val overzicht = beheer.overzicht()

        assertEquals(listOf("pietersen", "vandijk"), overzicht.personas.map { it.persona })
        assertEquals(listOf(emptyList<LopendeSessie>(), emptyList()), overzicht.personas.map { it.sessies })
        assertEquals(0, beheer.telLopende())
    }

    @Test
    fun `één sessie staat bij haar eigen persona met verloop en plafond`() {
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + 5 * MINUUT, plafond = NU + 12 * UUR)

        val sessie = sessiesVan("vandijk").single()

        assertEquals(Instant.ofEpochMilli(NU + 5 * MINUUT), sessie.verloopt)
        assertEquals(Instant.ofEpochMilli(NU + 12 * UUR), sessie.plafond)
        assertEquals(emptyList<LopendeSessie>(), sessiesVan("pietersen"))
    }

    @Test
    fun `meerdere sessies per persona staan op volgorde van verloop, per persona gescheiden`() {
        opslag.zet(V4_VAN_DIJK, "c".repeat(64), NU + 9 * MINUUT)
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + 1 * MINUUT)
        opslag.zet(V4_VAN_DIJK, "b".repeat(64), NU + 5 * MINUUT)
        opslag.zet(V4_PIETERSEN, "d".repeat(64), NU + 2 * MINUUT)

        assertEquals(
            listOf(NU + 1 * MINUUT, NU + 5 * MINUUT, NU + 9 * MINUUT).map(Instant::ofEpochMilli),
            sessiesVan("vandijk").map { it.verloopt },
        )
        assertEquals(1, sessiesVan("pietersen").size)
        assertEquals(4, beheer.telLopende())
    }

    @ParameterizedTest(name = "verloop nu {0} ms")
    @ValueSource(longs = [0, -1, -UUR])
    fun `een sessie die tot en met nu verliep telt niet als lopend`(verschuiving: Long) {
        // Ook precies "nu": de bibliotheek wijst een sessie met die score al af.
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + verschuiving)
        opslag.zet(V4_VAN_DIJK, "b".repeat(64), NU + 1)

        assertEquals(listOf(Instant.ofEpochMilli(NU + 1)), sessiesVan("vandijk").map { it.verloopt })
        assertEquals(1, beheer.telLopende())
    }

    @Test
    fun `een sessie zonder plafond telt niet als lopend`() {
        // De bibliotheek weigert zo'n sessie bij het volgende verzoek; tonen zou een sessie beloven
        // die niet meer werkt.
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + MINUUT, plafond = null)

        assertEquals(emptyList<LopendeSessie>(), sessiesVan("vandijk"))
        assertEquals(0, beheer.telLopende())
    }

    @Test
    fun `sessies onder een andere versie van de sessiecache tellen mee`() {
        // Tijdens een uitrol die het versieprefix ophoogt, lopen beide versies naast elkaar. Een
        // console die alleen de oude kent, meldt na de bump stil nul.
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + MINUUT)
        opslag.zet(V5_VAN_DIJK, "b".repeat(64), NU + 2 * MINUUT)

        assertEquals(2, sessiesVan("vandijk").size)
        assertEquals(2, beheer.telLopende())
    }

    @Test
    fun `een ontvanger buiten de persona's telt wel mee in het totaal`() {
        opslag.zet(administratie("v4", ANDER_KENMERK), "a".repeat(64), NU + MINUUT)

        assertEquals(listOf(0, 0), beheer.overzicht().personas.map { it.sessies.size })
        assertEquals(1, beheer.telLopende())
    }

    @Test
    fun `de verwijzing is stabiel en verraadt de sessie-hash niet`() {
        val hash = "ab".repeat(32)

        opslag.zet(V4_VAN_DIJK, hash, NU + MINUUT)

        val eerste = sessiesVan("vandijk").single().verwijzing

        assertEquals(eerste, sessiesVan("vandijk").single().verwijzing, "elke uitlezing hoort dezelfde verwijzing te geven")
        assertFalse(eerste.contains(hash.take(8)), "de verwijzing bevat een stuk van de hash")
        assertFalse(eerste.contains(V4_VAN_DIJK.kenmerk.take(8)), "de verwijzing bevat een stuk van het kenmerk")
    }

    @Test
    fun `een sessie beëindigen laat alleen die sessie verlopen`() {
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + 5 * MINUUT)
        opslag.zet(V4_VAN_DIJK, "b".repeat(64), NU + 9 * MINUUT)
        opslag.zet(V4_PIETERSEN, "c".repeat(64), NU + 7 * MINUUT)

        val eerste = sessiesVan("vandijk").first()

        assertEquals(1, beheer.beeindig(eerste.verwijzing))

        assertEquals(listOf(Instant.ofEpochMilli(NU + 9 * MINUUT)), sessiesVan("vandijk").map { it.verloopt })
        assertEquals(1, sessiesVan("pietersen").size)
        assertEquals(NU, opslag.zsets.getValue(V4_VAN_DIJK.sessies).getValue("a".repeat(64)), "de sessie hoort op nu te verlopen")
        assertEquals(NU + 9 * MINUUT, opslag.indexen.getValue(V4_VAN_DIJK.verloopindex).getValue(V4_VAN_DIJK.kenmerk))
    }

    @Test
    fun `de laatste sessie beëindigen zet de ontvanger op nu in de verloopindex`() {
        // Daar vindt de veegtaak hem: die ruimt de gegevens op en legt dat vast in het logboek.
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + 5 * MINUUT)

        assertEquals(1, beheer.beeindig(sessiesVan("vandijk").single().verwijzing))

        assertEquals(NU, opslag.indexen.getValue(V4_VAN_DIJK.verloopindex).getValue(V4_VAN_DIJK.kenmerk))
    }

    @Test
    fun `een tweede klik op dezelfde sessie beëindigt niets meer`() {
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + 5 * MINUUT)

        val verwijzing = sessiesVan("vandijk").single().verwijzing

        assertEquals(1, beheer.beeindig(verwijzing))
        assertEquals(0, beheer.beeindig(verwijzing))
    }

    @Test
    fun `een onbekende verwijzing beëindigt niets`() {
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + 5 * MINUUT)

        assertEquals(0, beheer.beeindig("A".repeat(Sessieverwijzer.LENGTE)))
        assertEquals(1, sessiesVan("vandijk").size)
    }

    @Test
    fun `een verwijzing van een vorige console beëindigt niets`() {
        // Na een herstart krijgt de console een nieuwe sleutel; een paneel dat nog een oude lijst
        // toont, raakt zo geen andere sessie.
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + 5 * MINUUT)

        val vorige = SessieBeheer(opslag, personaService, Sessieverwijzer()).overzicht()
            .personas.single { it.persona == "vandijk" }.sessies.single().verwijzing

        assertNotEquals(vorige, sessiesVan("vandijk").single().verwijzing)
        assertEquals(0, beheer.beeindig(vorige))
    }

    @ParameterizedTest(name = "{0} sessie(s)")
    @ValueSource(ints = [0, 1, 3])
    fun `alle sessies van een persona beëindigen raakt alleen die persona`(aantal: Int) {
        repeat(aantal) { opslag.zet(V4_VAN_DIJK, "$it".repeat(64), NU + (it + 1) * MINUUT) }
        opslag.zet(V4_PIETERSEN, "p".repeat(64), NU + MINUUT)

        assertEquals(aantal, beheer.beeindigVan(VAN_DIJK))

        assertEquals(emptyList<LopendeSessie>(), sessiesVan("vandijk"))
        assertEquals(1, sessiesVan("pietersen").size)
    }

    @Test
    fun `alle sessies van een persona beëindigen raakt elke versie, en een tweede klik niets`() {
        opslag.zet(V4_VAN_DIJK, "a".repeat(64), NU + MINUUT)
        opslag.zet(V4_VAN_DIJK, "b".repeat(64), NU - MINUUT)
        opslag.zet(V5_VAN_DIJK, "c".repeat(64), NU + 2 * MINUUT)

        assertEquals(2, beheer.beeindigVan(VAN_DIJK))
        assertEquals(0, beheer.beeindigVan(VAN_DIJK))

        assertEquals(NU, opslag.indexen.getValue(V4_VAN_DIJK.verloopindex).getValue(V4_VAN_DIJK.kenmerk))
        assertEquals(NU, opslag.indexen.getValue(V5_VAN_DIJK.verloopindex).getValue(V5_VAN_DIJK.kenmerk))
    }

    private companion object {

        val PIETERSEN = DemoPersona("pietersen", "J. Pietersen", "BSN", "999993653", emptyList(), PersonaBron.KETEN)

        val VAN_DIJK = DemoPersona("vandijk", "Garage Van Dijk B.V.", "KVK", "90000014", emptyList(), PersonaBron.KETEN)

        const val ANDER_KENMERK = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        fun kenmerk(persona: DemoPersona) = SessiecacheSleutels.kenmerk(Identificatienummer.fromHeader(persona.ontvanger))

        fun administratie(versie: String, kenmerk: String): Sessieadministratie =
            SessiecacheSleutels.administratie("berichtensessiecache:$versie:$kenmerk:sessies")!!

        val V4_VAN_DIJK = administratie("v4", kenmerk(VAN_DIJK))

        val V5_VAN_DIJK = administratie("v5", kenmerk(VAN_DIJK))

        val V4_PIETERSEN = administratie("v4", kenmerk(PIETERSEN))
    }
}
