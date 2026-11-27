package nl.rijksoverheid.moz.fbs.common.logboek

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant

class LogboekLezingTest {

    private val betrokkene = Identificatienummer.fromHeader("BSN:999900109")
    private val statement = mockk<PreparedStatement>(relaxed = true)
    private val resultaat = mockk<ResultSet>(relaxed = true)
    private val connection = mockk<Connection> {
        every { prepareStatement(any()) } returns statement
    }

    @Test
    fun `leest rijen en bindt betrokkene, bericht-id-subquery en periode`() {
        every { statement.executeQuery() } returns resultaat
        every { resultaat.next() } returnsMany listOf(true, false)
        every { resultaat.getString(any<String>()) } answers { firstArg<String>() }
        every { resultaat.getString("parent_span_id") } returns null
        every { resultaat.getString("poging") } returns "3"
        every { resultaat.getLong("start_time") } returns 42L

        val rijen = LogboekLezing.lees(
            connection, "schema.logboek", betrokkene, Instant.ofEpochMilli(1), Instant.ofEpochMilli(2), "SELECT 1",
        )

        val rij = rijen.single()
        assertEquals("span_id", rij.spanId)
        assertEquals(3L, rij.poging)
        assertEquals(42L, rij.start.toEpochMilli())
        verify { statement.setString(1, "BSN") }
        verify { statement.setString(2, "999900109") }
        verify { statement.setString(3, "BSN") }
        verify { statement.setString(4, "999900109") }
        verify { statement.setLong(5, 1L) }
        verify { statement.setLong(6, 2L) }
    }

    @Test
    fun `zonder subquery en periode alleen de betrokkene`() {
        every { statement.executeQuery() } returns resultaat
        every { resultaat.next() } returns false

        assertEquals(emptyList<LogboekRij>(), LogboekLezing.lees(connection, "logboek", betrokkene, null, null))
        verify(exactly = 0) { statement.setLong(any(), any()) }
        verify(exactly = 0) { statement.setString(3, any()) }
    }

    @Test
    fun `de SQL bevat de subquery en de periode alleen als ze gevraagd zijn`() {
        val kaal = LogboekLezing.sql("logboek", metVan = false, metTot = false, berichtIdsVanBetrokkene = null)
        val vol = LogboekLezing.sql("logboek", metVan = true, metTot = true, berichtIdsVanBetrokkene = "SELECT 1")

        assertTrue("BERICHT_ID_ONLY" !in kaal && "start_time >=" !in kaal)
        assertTrue("IN (SELECT 1)" in vol && "start_time >= ?" in vol && "start_time < ?" in vol)
    }

    @Test
    fun `een tabel die nog niet bestaat geeft een lege lijst`() {
        every { statement.executeQuery() } throws SQLException("bestaat niet", "42P01")

        assertEquals(emptyList<LogboekRij>(), LogboekLezing.lees(connection, "logboek", betrokkene, null, null))
    }

    @Test
    fun `een andere databasefout gaat door`() {
        every { statement.executeQuery() } throws SQLException("kapot", "08006")

        assertThrows<SQLException> { LogboekLezing.lees(connection, "logboek", betrokkene, null, null) }
    }

    @Test
    fun `een tabelnaam met SQL erin wordt geweigerd`() {
        assertThrows<IllegalArgumentException> {
            LogboekLezing.lees(connection, "logboek; DROP TABLE x", betrokkene, null, null)
        }
    }
}
