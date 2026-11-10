package nl.rijksoverheid.moz.fbs.democonsole.sessie

import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SessiecacheSleutelsTest {

    @Test
    fun `het kenmerk is de sha256 over de canonieke ontvanger`() {
        // Vaste uitkomsten, buiten Kotlin berekend (`printf 'KVK:90000014' | sha256sum`): een fout in
        // de afleiding zelf valt zo op, en niet alleen een verschil met een tweede afleiding hier.
        assertEquals(
            "6a9056ae50ec26106e793b6cb9382d9ad2bcef88ae735fdc802c8206fd50dd3e",
            SessiecacheSleutels.kenmerk(Identificatienummer.fromHeader("KVK:90000014")),
        )
        assertEquals(
            "cd6cda1bf9a9744de31f6857f4052553b7cbc262850e9757faa2eb0561258a21",
            SessiecacheSleutels.kenmerk(Identificatienummer.fromHeader("BSN:999993653")),
        )
    }

    @ParameterizedTest(name = "versie {0}")
    @ValueSource(strings = ["v4", "v5", "v12"])
    fun `een sessie-ZSET wordt onder elke versie herkend`(versie: String) {
        val administratie = SessiecacheSleutels.administratie("berichtensessiecache:$versie:$KENMERK:sessies")

        assertEquals(KENMERK, administratie?.kenmerk)
        assertEquals("berichtensessiecache:$versie:$KENMERK:sessies", administratie?.sessies)
        assertEquals("berichtensessiecache:$versie:$KENMERK:sessies:plafond", administratie?.plafonds)
        assertEquals("berichtensessiecache:$versie:verloop", administratie?.verloopindex)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "berichtensessiecache:v4:$KENMERK:sessies:plafond",
            "berichtensessiecache:v4:$KENMERK:status",
            "berichtensessiecache:v4:verloop",
            "berichtensessiecache:v4:$KENMERK:verwijderd:00000000000000100000:sessies",
            "berichtensessiecache:$KENMERK:sessies",
            "berichtensessiecache:v4:${KORT_KENMERK}:sessies",
            "bericht:v4:00000000000000100000:sessies",
        ],
    )
    fun `andere sleutels zijn geen sessie-ZSET`(sleutel: String) {
        assertNull(SessiecacheSleutels.administratie(sleutel))
    }

    @Test
    fun `het patroon per ontvanger laat de versie open`() {
        assertEquals("berichtensessiecache:*:$KENMERK:sessies", SessiecacheSleutels.sessiesVan(KENMERK))
        assertEquals("berichtensessiecache:*:sessies", SessiecacheSleutels.ALLE_SESSIES)
    }

    private companion object {

        const val KENMERK = "6a9056ae50ec26106e793b6cb9382d9ad2bcef88ae735fdc802c8206fd50dd3e"

        const val KORT_KENMERK = "6a9056ae"
    }
}
