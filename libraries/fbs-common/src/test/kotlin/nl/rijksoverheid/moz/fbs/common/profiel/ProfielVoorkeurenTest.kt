package nl.rijksoverheid.moz.fbs.common.profiel

import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfielVoorkeurenTest {

    private val afzenderA = Oin("00000001003214345000")
    private val afzenderB = Oin("00000001823288444000")

    @Test
    fun `lege voorkeuren leveren lege OIN-sequentie`() {
        val partij = PartijResponse(voorkeuren = emptyList())

        assertTrue(ProfielVoorkeuren.optedInAfzenderOinStrings(partij).toList().isEmpty())
        assertFalse(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
    }

    @Test
    fun `voorkeurType ongelijk aan OntvangViaBerichtenbox wordt genegeerd`() {
        val partij = PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "WebsiteTaal",
                    waarde = "nl",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", afzenderA.waarde))),
                ),
            ),
        )

        assertTrue(ProfielVoorkeuren.optedInAfzenderOinStrings(partij).toList().isEmpty())
        assertFalse(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
    }

    @Test
    fun `waarde 'true' geldt als opt-in`() {
        val partij = partijMetOpt("true", afzenderA.waarde)
        assertTrue(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
    }

    @Test
    fun `waarde 'ja' geldt als opt-in`() {
        val partij = partijMetOpt("ja", afzenderA.waarde)
        assertTrue(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
    }

    @Test
    fun `waarde 'TRUE' (hoofdletters) geldt als opt-in (case-insensitive)`() {
        val partij = partijMetOpt("TRUE", afzenderA.waarde)
        assertTrue(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
    }

    @Test
    fun `waarde 'false' is opt-out`() {
        val partij = partijMetOpt("false", afzenderA.waarde)
        assertFalse(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
    }

    @Test
    fun `waarde 'yes' is geen opt-in (alleen 'true' en 'ja' tellen)`() {
        val partij = partijMetOpt("yes", afzenderA.waarde)
        assertFalse(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
    }

    @Test
    fun `waarde null is opt-out`() {
        val partij = partijMetOpt(null, afzenderA.waarde)
        assertFalse(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
    }

    @Test
    fun `scope zonder partij (alleen dienst) wordt genegeerd`() {
        val partij = PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = null, dienst = DienstResponse(id = 1, beschrijving = "X")),
                    ),
                ),
            ),
        )

        assertTrue(ProfielVoorkeuren.optedInAfzenderOinStrings(partij).toList().isEmpty())
        assertFalse(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
    }

    @Test
    fun `scope met identificatieType KVK wordt genegeerd (alleen OIN telt)`() {
        val partij = PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("KVK", "12345678"))),
                ),
            ),
        )

        assertTrue(ProfielVoorkeuren.optedInAfzenderOinStrings(partij).toList().isEmpty())
    }

    @Test
    fun `partij met 2 opt-in OINs levert beide`() {
        val partij = PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", afzenderA.waarde)),
                        ScopeResponse(partij = IdentificatieResponse("OIN", afzenderB.waarde)),
                    ),
                ),
            ),
        )

        val oins = ProfielVoorkeuren.optedInAfzenderOinStrings(partij).toList()
        assertEquals(listOf(afzenderA.waarde, afzenderB.waarde), oins)
        assertTrue(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
        assertTrue(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderB))
    }

    @Test
    fun `partij met opt-in voor afzender A is niet opt-in voor afzender B`() {
        val partij = partijMetOpt("true", afzenderA.waarde)

        assertTrue(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
        assertFalse(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderB))
    }

    @Test
    fun `meerdere voorkeuren — alleen OntvangViaBerichtenbox telt`() {
        val partij = PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "WebsiteTaal",
                    waarde = "nl",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", afzenderA.waarde))),
                ),
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", afzenderB.waarde))),
                ),
            ),
        )

        val oins = ProfielVoorkeuren.optedInAfzenderOinStrings(partij).toList()
        assertEquals(listOf(afzenderB.waarde), oins)
        assertFalse(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderA))
        assertTrue(ProfielVoorkeuren.isOptedInVoorAfzender(partij, afzenderB))
    }

    private fun partijMetOpt(waarde: String?, oin: String) = PartijResponse(
        voorkeuren = listOf(
            VoorkeurResponse(
                voorkeurType = "OntvangViaBerichtenbox",
                waarde = waarde,
                scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", oin))),
            ),
        ),
    )
}
