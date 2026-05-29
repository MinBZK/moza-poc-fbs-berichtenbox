package nl.rijksoverheid.moz.fbs.common.profiel

import nl.rijksoverheid.moz.fbs.common.exception.Problem
import nl.rijksoverheid.moz.fbs.common.exception.ProblemMediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToestemmingGeweigerdExceptionMapperTest {

    private val mapper = ToestemmingGeweigerdExceptionMapper()

    @Test
    fun `geenProfiel levert 403 met problem+json`() {
        val response = mapper.toResponse(ToestemmingGeweigerdException.geenProfiel())

        assertEquals(403, response.status)
        assertEquals(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE, response.mediaType)
    }

    @Test
    fun `geenActieveVoorkeur levert 403 met problem+json`() {
        val response = mapper.toResponse(ToestemmingGeweigerdException.geenActieveVoorkeur())

        assertEquals(403, response.status)
        assertEquals(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE, response.mediaType)
    }

    @Test
    fun `geenProfiel-detail noemt ontbrekend profiel`() {
        val problem = mapper.toResponse(ToestemmingGeweigerdException.geenProfiel()).entity as Problem

        assertEquals(403, problem.status)
        assertEquals("Forbidden", problem.title)
        assertNotNull(problem.detail)
        assertTrue(
            problem.detail!!.contains("geen profiel"),
            "detail moet ontbrekend profiel noemen: ${problem.detail}",
        )
    }

    @Test
    fun `geenActieveVoorkeur-detail noemt ontbrekende voorkeur`() {
        val problem = mapper.toResponse(ToestemmingGeweigerdException.geenActieveVoorkeur()).entity as Problem

        assertEquals(403, problem.status)
        assertEquals("Forbidden", problem.title)
        assertNotNull(problem.detail)
        assertTrue(
            problem.detail!!.contains("geen actieve berichtenbox-voorkeur"),
            "detail moet ontbrekende voorkeur noemen: ${problem.detail}",
        )
    }

    @Test
    fun `beide redenen leveren een verschillende detail-tekst (geen verwisseling)`() {
        val geenProfiel = (mapper.toResponse(ToestemmingGeweigerdException.geenProfiel()).entity as Problem).detail
        val geenVoorkeur = (mapper.toResponse(ToestemmingGeweigerdException.geenActieveVoorkeur()).entity as Problem).detail

        assertNotNull(geenProfiel)
        assertNotNull(geenVoorkeur)
        assertFalse(geenProfiel == geenVoorkeur, "detail per reden mag niet identiek zijn")
    }

    @Test
    fun `log-regel draagt reden-context zonder ontvanger-waarde te lekken`() {
        // De afzender-/ontvanger-waarde mag niet in de log of response-body belanden (AVG);
        // alleen de reden-enum geeft incident-context. Pin de infof-regel + PII-afwezigheid vast.
        // De reden gaat via een %s-parameter; de geformatteerde waarde wordt op output ingevuld
        // (niet in het opgevangen record onder de default-LogManager), dus we asserten op het
        // template-skelet (`reden=`) i.p.v. de geformatteerde enum-naam.
        val targetLogger = java.util.logging.Logger.getLogger(ToestemmingGeweigerdExceptionMapper::class.java.name)
        val captured = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { captured.add(record) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val previousLevel = targetLogger.level

        targetLogger.addHandler(handler)
        targetLogger.level = java.util.logging.Level.ALL

        try {
            mapper.toResponse(ToestemmingGeweigerdException.geenProfiel())

            val logRegel = captured.firstOrNull { it.message?.contains("Toestemming geweigerd") == true }

            assertNotNull(logRegel, "Mapper moet een 'Toestemming geweigerd'-log produceren")
            assertTrue(
                logRegel!!.message!!.contains("reden="),
                "Log-regel moet reden-context dragen: ${logRegel.message}",
            )
            assertFalse(
                logRegel.message!!.any { it.isDigit() },
                "Log-template mag geen identificatie-cijfers bevatten: ${logRegel.message}",
            )
        } finally {
            targetLogger.removeHandler(handler)
            targetLogger.level = previousLevel
        }
    }
}
