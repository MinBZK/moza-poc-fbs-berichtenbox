package nl.rijksoverheid.moz.fbs.democonsole.legen

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegenSqlTest {

    @Test
    fun `de domein-truncate noemt alle vier de tabellen ongekwalificeerd`() {
        // Ongekwalificeerd is de kern: op ZAD bepaalt currentSchema van de datasource in welk
        // magazijn-schema de TRUNCATE landt. Een schemaprefix zou beide datasources naar dezelfde
        // tabellen sturen.
        listOf("berichten", "bijlagen", "bericht_status", "publicatie_deliveries").forEach { tabel ->
            assertTrue(LegenSql.DOMEIN.contains(" $tabel") || LegenSql.DOMEIN.contains("($tabel"), "mist $tabel")
        }

        assertFalse(LegenSql.DOMEIN.contains("."), "geen schemaprefix: currentSchema bepaalt het schema")
    }

    @Test
    fun `de logboek-controle kijkt in het schema van de sessie`() {
        // pg_tables zonder schemaname-filter zou het logboek van het ándere magazijn zien en dan
        // een TRUNCATE proberen op een tabel die in dit schema niet bestaat.
        assertTrue(LegenSql.LOGBOEK_BESTAAT.contains("current_schema()"))
        assertTrue(LegenSql.LOGBOEK_BESTAAT.contains("logboek_dataverwerkingen"))
    }

    @Test
    fun `de logboek-truncate is ongekwalificeerd`() {
        assertTrue(LegenSql.LOGBOEK.startsWith("TRUNCATE logboek_dataverwerkingen"))
        assertFalse(LegenSql.LOGBOEK.contains("."), "geen schemaprefix")
    }
}
