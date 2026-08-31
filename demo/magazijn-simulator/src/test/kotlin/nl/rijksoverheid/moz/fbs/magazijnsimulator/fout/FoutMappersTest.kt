package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Path
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Problem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De drie mappers die ervoor zorgen dat er nooit iets anders dan `problem+json` naar buiten komt.
 *
 * Twee ervan grijpen op elkaar in: [UncaughtExceptionMapper] staat op `Exception` en zou zonder de
 * specifiekere [ProblemExceptionMapper] óók elke bewuste 404 opvangen en als 500 doorgeven. Dat is
 * geen theoretisch geval — het is de standaard-mapperkeuze van JAX-RS — en het is onzichtbaar tot
 * een client een 500 krijgt waar een 404 hoorde te staan.
 */
class FoutMappersTest {

    @Test
    fun `een zelfgebouwde problem-response gaat ongewijzigd door`() {
        val response = problemResponse(status = 404, title = "Not Found", detail = "Geen bericht")

        val uitkomst = ProblemExceptionMapper().toResponse(WebApplicationException(response))

        assertSame(response, uitkomst)
    }

    @Test
    fun `een exception van elders krijgt alsnog een problem-body met dezelfde status`() {
        val uitkomst = ProblemExceptionMapper().toResponse(NotFoundException("Bericht bestaat niet"))

        assertEquals(404, uitkomst.status)
        assertEquals(PROBLEM_JSON, uitkomst.mediaType.toString())

        val problem = uitkomst.entity as Problem

        assertEquals("Not Found", problem.title)
        assertEquals(404, problem.status)
        // Bij een 4xx is de melding juist nuttig: dat is wat de aanroeper nodig heeft om te zien wat
        // er mis is.
        assertEquals("Bericht bestaat niet", problem.detail)
    }

    /**
     * Een melding die eruitziet als interne toestand hoort een client nooit te bereiken. Aan een
     * exception van elders is niet te zien waar zijn melding vandaan komt, dus dat wordt op vorm
     * beoordeeld.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "java.lang.NullPointerException\n\tat nl.rijksoverheid.Foo.bar(Foo.kt:42)",
            "Fout in BerichtService.kt:118",
            "kapot in Repository.java:9",
        ],
    )
    fun `een melding die naar interne toestand ruikt blijft weg`(melding: String) {
        val problem = ProblemExceptionMapper().toResponse(NotFoundException(melding)).entity as Problem

        assertNull(problem.detail)
    }

    @Test
    fun `een 5xx geeft de melding niet door, ook niet als hij onschuldig lijkt`() {
        val exception = WebApplicationException("kan de database niet bereiken op host db-01", 503)

        val problem = ProblemExceptionMapper().toResponse(exception).entity as Problem

        assertEquals(503, problem.status)
        assertNull(problem.detail)
    }

    @Test
    fun `een onbekende status levert een neutrale titel op in plaats van een lege`() {
        val exception = WebApplicationException(Response.status(499).build())

        val problem = ProblemExceptionMapper().toResponse(exception).entity as Problem

        assertEquals("Error", problem.title)
        assertEquals(499, problem.status)
    }

    @Test
    fun `een onverwachte fout wordt een 500 zonder de melding zelf`() {
        val uitkomst = UncaughtExceptionMapper().toResponse(IllegalStateException("select * from bericht"))

        assertEquals(500, uitkomst.status)
        assertEquals(PROBLEM_JSON, uitkomst.mediaType.toString())

        val problem = uitkomst.entity as Problem

        assertEquals("Internal Server Error", problem.title)
        assertFalse(problem.detail.contains("select * from bericht"))
    }

    /**
     * `instance` is het veld waarmee support een melding terugvindt, en het echte magazijn zet hem
     * op élk foutantwoord. Ontbreekt hij hier, dan is de simulator juist op zijn foutpad te
     * herkennen — precies wat hij niet mag zijn.
     */
    @Test
    fun `elk foutantwoord draagt een correlatie-id in instance`() {
        val antwoorden = listOf(
            ProblemExceptionMapper().toResponse(NotFoundException()),
            UncaughtExceptionMapper().toResponse(IllegalStateException()),
            ConstraintViolationExceptionMapper().toResponse(schendingen("xOntvanger" to "moet aan het patroon voldoen")),
            problemResponse(status = 404, title = "Not Found"),
        )

        antwoorden.forEach { antwoord ->
            val instance = (antwoord.entity as Problem).instance

            assertTrue(
                instance.orEmpty().startsWith("urn:uuid:"),
                "verwacht een urn:uuid-correlatie-id, was: $instance",
            )
        }
    }

    @Test
    fun `twee foutantwoorden delen hun correlatie-id niet`() {
        val eerste = (problemResponse(404, "Not Found").entity as Problem).instance
        val tweede = (problemResponse(404, "Not Found").entity as Problem).instance

        assertFalse(eerste == tweede, "elk antwoord hoort zijn eigen id te hebben")
    }

    @Test
    fun `een schending wordt een 400 met veldnaam en melding`() {
        val uitkomst = ConstraintViolationExceptionMapper().toResponse(schendingen("pageSize" to "moet ten hoogste 100 zijn"))

        assertEquals(400, uitkomst.status)
        assertEquals(PROBLEM_JSON, uitkomst.mediaType.toString())
        assertEquals("pageSize: moet ten hoogste 100 zijn", (uitkomst.entity as Problem).detail)
    }

    @Test
    fun `meerdere schendingen komen gescheiden terug`() {
        val uitkomst = ConstraintViolationExceptionMapper().toResponse(
            schendingen("page" to "moet ten minste 0 zijn", "pageSize" to "moet ten hoogste 100 zijn"),
        )

        // Op inhoud en niet op volgorde: `ConstraintViolationException` bewaart een `Set`, dus de
        // volgorde ligt niet vast en een assertie daarop zou willekeurig omvallen.
        val detail = (uitkomst.entity as Problem).detail

        assertTrue(detail.contains("page: moet ten minste 0 zijn"), detail)
        assertTrue(detail.contains("pageSize: moet ten hoogste 100 zijn"), detail)
        assertTrue(detail.contains("; "), "verwacht twee schendingen gescheiden door '; ', was: $detail")
    }

    /**
     * Een aanroeper die honderden ongeldige velden stuurt, mag geen even grote response terugkrijgen.
     * Zowel het aantal schendingen als de totale lengte is begrensd; zonder deze test is die
     * begrenzing weg te refactoren zonder dat iets rood wordt.
     */
    @Test
    fun `heel veel schendingen leveren een begrensd antwoord op`() {
        val veel = (1..500).map { "veld$it" to "melding die een beetje lengte heeft nummer $it" }

        val detail = (ConstraintViolationExceptionMapper().toResponse(schendingen(*veel.toTypedArray())).entity as Problem).detail

        assertTrue(detail.length <= MAX_DETAIL, "detail was ${detail.length} tekens")

        // Op het aantal en niet op een specifiek veld: `ConstraintViolationException` bewaart de
        // schendingen in een `Set`, dus wélke er overblijven ligt niet vast. Een assertie dat juist
        // `veld500` wegviel is daarmee een muntworp die af en toe rood wordt zonder dat er iets stuk
        // is. Twee grenzen werken hier samen — hooguit vijftig schendingen worden verwerkt en het
        // detail wordt op tekens afgekapt — en die tweede is bindend: er past maar een handvol in.
        val teruggekomen = detail.split("; ").size

        assertTrue(teruggekomen <= 20, "er kwamen $teruggekomen schendingen terug van de 500")
    }

    @Test
    fun `zonder schendingen blijft detail weg in plaats van leeg`() {
        assertNull((ConstraintViolationExceptionMapper().toResponse(schendingen()).entity as Problem).detail)
    }

    private fun schendingen(vararg velden: Pair<String, String>): ConstraintViolationException =
        ConstraintViolationException(velden.map { (naam, melding) -> VasteSchending(naam, melding) }.toSet())

    /**
     * Een minimale [ConstraintViolation]: alleen `propertyPath` en `message` worden gelezen. Met een
     * mock zou dit twintig `every {}`-regels kosten voor twee waardes.
     */
    private class VasteSchending(
        private val naam: String,
        private val melding: String,
    ) : ConstraintViolation<Any> {
        override fun getMessage(): String = melding
        override fun getPropertyPath(): Path = VastPad(naam)
        override fun getMessageTemplate(): String = melding
        override fun getRootBean(): Any? = null
        override fun getRootBeanClass(): Class<Any>? = null
        override fun getLeafBean(): Any? = null
        override fun getExecutableParameters(): Array<Any>? = null
        override fun getExecutableReturnValue(): Any? = null
        override fun getInvalidValue(): Any? = null
        override fun getConstraintDescriptor(): jakarta.validation.metadata.ConstraintDescriptor<*>? = null
        override fun <U : Any?> unwrap(type: Class<U>?): U = throw UnsupportedOperationException()
    }

    private class VastPad(private val naam: String) : Path {
        override fun iterator(): MutableIterator<Path.Node> = mutableListOf<Path.Node>(VasteNode(naam)).iterator()
        override fun toString(): String = naam
    }

    private class VasteNode(private val naam: String) : Path.Node {
        override fun getName(): String = naam
        override fun isInIterable(): Boolean = false
        override fun getIndex(): Int? = null
        override fun getKey(): Any? = null
        override fun getKind(): jakarta.validation.ElementKind = jakarta.validation.ElementKind.PARAMETER
        override fun <T : Path.Node?> `as`(nodeType: Class<T>?): T = throw UnsupportedOperationException()
    }

    private companion object {
        const val PROBLEM_JSON = "application/problem+json"
        const val MAX_DETAIL = 500
    }
}
