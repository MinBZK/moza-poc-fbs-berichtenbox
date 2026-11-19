package nl.rijksoverheid.moz.fbs.common.exception

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Path
import jakarta.validation.constraints.Pattern
import jakarta.validation.metadata.ConstraintDescriptor
import nl.rijksoverheid.moz.fbs.kern.testhulp.Logvanger
import nl.rijksoverheid.moz.fbs.kern.testhulp.herkenbareDelen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ConstraintViolationExceptionMapperTest {

    private val mapper = ConstraintViolationExceptionMapper()

    @Test
    fun `single violation geeft 400 Problem met paramName en message`() {
        val exception = ConstraintViolationException(setOf(violation("onderwerp", "mag niet leeg zijn")))

        val response = mapper.toResponse(exception)
        val problem = response.entity as Problem

        assertEquals(400, response.status)
        assertEquals("Bad Request", problem.title)
        assertEquals("onderwerp: mag niet leeg zijn", problem.detail)
    }

    @Test
    fun `meerdere violations worden met puntkomma gejoined`() {
        val exception = ConstraintViolationException(
            setOf(
                violation("afzender", "formaat onjuist"),
                violation("inhoud", "te lang"),
            ),
        )

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        assertTrue(detail.contains("afzender: formaat onjuist"), "afzender detail ontbreekt: $detail")
        assertTrue(detail.contains("inhoud: te lang"), "inhoud detail ontbreekt: $detail")
        assertTrue(detail.contains("; "), "scheidingsteken `; ` ontbreekt: $detail")
    }

    @Test
    fun `violation zonder propertyPath valt terug op toString`() {
        val mock = mockk<ConstraintViolation<Any>>()
        val mockPath = mockk<Path>()
        every { mockPath.toString() } returns ""
        every { mockPath.iterator() } answers { mutableListOf<Path.Node>().iterator() }
        every { mock.propertyPath } returns mockPath
        every { mock.message } returns "verplicht"
        every { mock.constraintDescriptor } returns null

        val exception = ConstraintViolationException(setOf(mock))
        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        // Test bewijst dat het format altijd werkt (geen NPE) bij afwezige path.
        assertTrue(detail.contains("verplicht"), "message moet zichtbaar blijven: $detail")
    }

    @Test
    fun `CRLF in violation-message wordt gestript (CWE-117)`() {
        // CWE-117: log-injection via CRLF in violation-message. Pinned dat sanitize-wrap blijft.
        val exception = ConstraintViolationException(
            setOf(violation("veld", "ongeldig\r\nLevel: ERROR\nfake-line")),
        )

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        org.junit.jupiter.api.Assertions.assertFalse(
            detail.contains("\r") || detail.contains("\n"),
            "CRLF mag niet in detail (log-injection-vector) — gevonden: $detail",
        )
    }

    @Test
    fun `lange detail wordt op 500 chars begrensd (DoS-mitigatie)`() {
        // DoS-amplification: unbounded detail-grootte. Pinned 500-char-grens.
        val longMsg = "a".repeat(2000)
        val exception = ConstraintViolationException(setOf(violation("veld", longMsg)))

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        assertTrue(
            detail.length <= 500,
            "detail moet begrensd zijn op 500 — lengte: ${detail.length}",
        )
    }

    @Test
    fun `file-pad in violation-message wordt geredact`() {
        // Borgt sanitizeClientDetail FILE_PATH_PATTERN-strip op @Pattern-message
        // die per ongeluk paden interpoleert.
        val exception = ConstraintViolationException(
            setOf(violation("config", "fout in /etc/passwd configuratie")),
        )

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        org.junit.jupiter.api.Assertions.assertFalse(
            detail.contains("/etc/passwd"),
            "file-pad mag niet in detail — gevonden: $detail",
        )
    }

    @Test
    fun `meer dan 50 violations worden begrensd (memory-pressure mitigatie)`() {
        // Round 13 L1: take(50) begrenst tussenstring-allocatie. Pin tegen refactor
        // die de grens weghaalt en N=10000 violations toelaat.
        val violations = (1..200).map { violation("veld$it", "fout$it") }.toSet()
        val exception = ConstraintViolationException(violations)

        val response = mapper.toResponse(exception)
        val detail = (response.entity as Problem).detail ?: ""

        // Niet alle 200 violations mogen in de detail staan
        val gevondenAantal = detail.split(";").size
        assertTrue(
            gevondenAantal <= ConstraintViolationExceptionMapper.MAX_VIOLATIONS_IN_DETAIL,
            "max ${ConstraintViolationExceptionMapper.MAX_VIOLATIONS_IN_DETAIL} violations in detail — gevonden: $gevondenAantal",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["BSN:99999365", "BSN:99999365x", "BSN:999993654"])
    fun `de log noemt pad en regel van een schending, maar nooit de aangeboden waarde`(waarde: String) {
        val schending = violation(
            knopen = listOf(knoop("getBerichten"), knoop("xOntvanger")),
            message = "waarde '$waarde' past niet",
            ongeldigeWaarde = waarde,
        )

        val problem = Logvanger().use { logvanger ->
            val problem = mapper.toResponse(ConstraintViolationException(setOf(schending))).entity as Problem
            val log = logvanger.tekst()

            herkenbareDelen(waarde.removePrefix("BSN:")).forEach { deel ->
                assertFalse(log.contains(deel), "'$deel' hoort niet in de log: $log")
            }

            assertTrue(log.contains("getBerichten.xOntvanger (Pattern)"), "pad en regel horen in de log: $log")
            assertTrue(log.contains(problem.instance.toString().removePrefix("urn:uuid:")), "het id hoort in de log: $log")
            problem
        }

        assertEquals(400, problem.status)
    }

    @Test
    fun `van een element in een collectie gaat de positie mee, een map-sleutel niet`() {
        val schending = violation(
            knopen = listOf(
                knoop("aanleveren"),
                knoop("bijlagen"),
                knoop("naam", inCollectie = true, index = 2),
                knoop("kenmerken"),
                knoop("waarde", inCollectie = true, index = null),
            ),
            message = "ongeldig",
            ongeldigeWaarde = "999993653",
        )

        Logvanger().use { logvanger ->
            mapper.toResponse(ConstraintViolationException(setOf(schending)))

            val log = logvanger.tekst()
            assertTrue(log.contains("aanleveren.bijlagen[2].naam.kenmerken.?.waarde (Pattern)"), log)
            assertFalse(log.contains("99999"), log)
        }
    }

    private fun violation(propertyName: String, message: String): ConstraintViolation<Any> =
        violation(listOf(knoop(propertyName)), message, ongeldigeWaarde = null)

    /**
     * Een schending zoals Hibernate Validator haar aanlevert: de aangeboden waarde in `invalidValue`,
     * in een eigen melding met `${validatedValue}` en daarmee in `toString`, en een map-sleutel in
     * het pad. Precies wat er níét in de log mag komen.
     */
    private fun violation(knopen: List<Path.Node>, message: String, ongeldigeWaarde: Any?): ConstraintViolation<Any> {
        val violation = mockk<ConstraintViolation<Any>>()
        val path = mockk<Path>()
        val descriptor = mockk<ConstraintDescriptor<*>>()
        val pad = alsHibernateValidator(knopen)
        every { path.iterator() } answers { knopen.toMutableList().iterator() }
        every { path.toString() } returns pad
        every { descriptor.annotation } returns PATTERN
        every { violation.propertyPath } returns path
        every { violation.message } returns message
        every { violation.invalidValue } returns ongeldigeWaarde
        every { violation.constraintDescriptor } returns descriptor
        every { violation.toString() } returns
            "ConstraintViolationImpl{interpolatedMessage='$message', propertyPath=$pad, rootBeanClass=class Object, " +
            "messageTemplate='{jakarta.validation.constraints.Pattern.message}'}"
        return violation
    }

    /** Zoals `PathImpl.toString`: een element in een collectie met zijn positie of sleutel tussen haken. */
    private fun alsHibernateValidator(knopen: List<Path.Node>): String = buildString {
        knopen.forEach { knoop ->
            if (knoop.isInIterable) append('[').append(knoop.index ?: knoop.key ?: "").append(']')

            knoop.name?.let { naam -> append(if (isEmpty()) naam else ".$naam") }
        }
    }

    private fun knoop(naam: String?, inCollectie: Boolean = false, index: Int? = null): Path.Node {
        val node = mockk<Path.Node>()
        every { node.name } returns naam
        every { node.isInIterable } returns inCollectie
        every { node.index } returns index
        every { node.key } returns if (inCollectie && index == null) "999993653" else null
        return node
    }

    private class MetPatroon(@field:Pattern(regexp = "^BSN:[0-9]{9}$") val veld: String)

    private companion object {
        val PATTERN: Annotation = MetPatroon::class.java.getDeclaredField("veld").annotations.single()
    }
}
