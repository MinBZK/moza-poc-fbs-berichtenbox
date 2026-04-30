package nl.rijksoverheid.moz.fbs.common

import nl.rijksoverheid.moz.fbs.common.exception.Problem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

class ProblemTest {

    @Test
    fun `of clamp-et lege title naar Error`() {
        val problem = Problem.of(title = "", status = 400)
        assertEquals("Error", problem.title)
    }

    @Test
    fun `of clamp-et blank title naar Error`() {
        val problem = Problem.of(title = "   ", status = 400)
        assertEquals("Error", problem.title)
    }

    @Test
    fun `of kapt te lange title af op MAX_TITLE_LENGTH`() {
        val lang = "x".repeat(Problem.MAX_TITLE_LENGTH + 50)
        val problem = Problem.of(title = lang, status = 400)
        assertEquals(Problem.MAX_TITLE_LENGTH, problem.title.length)
    }

    @Test
    fun `of valt terug op 500 bij status buiten 400-599`() {
        assertEquals(500, Problem.of(title = "Error", status = 200).status)
        assertEquals(500, Problem.of(title = "Error", status = 600).status)
        assertEquals(500, Problem.of(title = "Error", status = -1).status)
    }

    @Test
    fun `of accepteert geldige status codes`() {
        assertEquals(400, Problem.of(title = "Bad Request", status = 400).status)
        assertEquals(404, Problem.of(title = "Not Found", status = 404).status)
        assertEquals(599, Problem.of(title = "Error", status = 599).status)
    }

    @Test
    fun `of valt terug op ABOUT_BLANK bij niet-absolute type URI`() {
        val relatief = URI.create("just/a/path")
        val problem = Problem.of(title = "Error", status = 500, type = relatief)
        assertEquals(Problem.ABOUT_BLANK, problem.type)
    }

    @Test
    fun `of accepteert absolute type URI`() {
        val absoluut = URI.create("https://example.org/errors/foo")
        val problem = Problem.of(title = "Error", status = 500, type = absoluut)
        assertEquals(absoluut, problem.type)
    }

    @Test
    fun `direct constructor accepteert alle waarden zonder validatie`() {
        // Jackson-deserialisatie en testopstellingen moeten kunnen blijven werken.
        val problem = Problem(title = "", status = 0)
        assertEquals("", problem.title)
        assertEquals(0, problem.status)
    }
}
