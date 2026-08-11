package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LdvEndpointValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test laten een plaintext ClickHouse-endpoint toe`(profiel: String) {
        assertDoesNotThrow {
            LdvEndpointValidator.validate(profiel, "clickhouse", clickhouseEndpoint = "http://localhost:8123")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test laten een plaintext JDBC-URL toe`(profiel: String) {
        assertDoesNotThrow {
            LdvEndpointValidator.validate(
                profiel,
                "postgresql",
                postgresqlUrl = "jdbc:postgresql://localhost:5432/ldv",
            )
        }
    }

    @Test
    fun `prod met plaintext ClickHouse-endpoint faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", "clickhouse", clickhouseEndpoint = "http://insecure:8123")
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "foutmelding moet naar BIO 13.2.1 verwijzen")
        assertTrue(ex.message!!.contains("https://"), "foutmelding moet https:// noemen")
    }

    @Test
    fun `prod met https ClickHouse-endpoint slaagt`() {
        assertDoesNotThrow {
            LdvEndpointValidator.validate("prod", "clickhouse", clickhouseEndpoint = "https://clickhouse.intern:8443")
        }
    }

    @Test
    fun `prod met plaintext JDBC-URL faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", "postgresql", postgresqlUrl = "jdbc:postgresql://db:5432/ldv")
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "foutmelding moet naar BIO 13.2.1 verwijzen")
        assertTrue(ex.message!!.contains("sslmode"), "foutmelding moet de bruikbare sslmode-waarden noemen")
    }

    @Test
    fun `prod met versleutelde JDBC-URL slaagt`() {
        assertDoesNotThrow {
            LdvEndpointValidator.validate(
                "prod",
                "postgresql",
                postgresqlUrl = "jdbc:postgresql://db:5432/ldv?sslmode=verify-full",
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["staging", "acceptatie"])
    fun `ook staging en acceptatie vallen onder de TLS-eis`(profiel: String) {
        assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate(profiel, "postgresql", postgresqlUrl = "jdbc:postgresql://db:5432/ldv")
        }
    }

    @Test
    fun `lege waarde faalt buiten dev en test, ongeacht de backend`() {
        assertThrows<IllegalArgumentException> { LdvEndpointValidator.validate("prod", "clickhouse") }
        assertThrows<IllegalArgumentException> { LdvEndpointValidator.validate("prod", "postgresql") }
    }

    @Test
    fun `de onveilige override vloeit door naar beide backends`() {
        // Dit is de hele reden dat de parameter bestaat: op ZAD is het logboek intern
        // zonder TLS bereikbaar.
        assertDoesNotThrow {
            LdvEndpointValidator.validate(
                "prod",
                "clickhouse",
                clickhouseEndpoint = "http://ch:8123",
                unsafeAllowPlaintext = true,
            )
        }
        assertDoesNotThrow {
            LdvEndpointValidator.validate(
                "prod",
                "postgresql",
                postgresqlUrl = "jdbc:postgresql://db:5432/ldv",
                unsafeAllowPlaintext = true,
            )
        }
    }

    @Test
    fun `override staat default uit, dus prod met plaintext blijft fail-fast`() {
        assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate(
                "prod",
                "postgresql",
                postgresqlUrl = "jdbc:postgresql://db:5432/ldv",
                unsafeAllowPlaintext = false,
            )
        }
    }

    @Test
    fun `een onbekende dbms-waarde faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", "mysql", postgresqlUrl = "jdbc:mysql://db:3306/ldv")
        }
        assertTrue(ex.message!!.contains("mysql"), "foutmelding moet de onbekende waarde tonen")
    }

    @ParameterizedTest
    @ValueSource(strings = ["postgresql", "postgres", "PostgreSQL", "POSTGRES"])
    fun `elke postgres-schrijfwijze die de wrapper accepteert kiest de JDBC-check`(dbms: String) {
        val ex = assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", dbms, postgresqlUrl = "jdbc:postgresql://db:5432/ldv")
        }
        assertTrue(ex.message!!.contains("sslmode"), "de JDBC-variant van de check moet gedraaid hebben")
    }

    @ParameterizedTest
    @ValueSource(strings = ["clickhouse", "ClickHouse", "CLICKHOUSE"])
    fun `elke clickhouse-schrijfwijze die de wrapper accepteert kiest de https-check`(dbms: String) {
        val ex = assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", dbms, clickhouseEndpoint = "http://ch:8123")
        }
        assertTrue(ex.message!!.contains("https://"), "de https-variant van de check moet gedraaid hebben")
    }

    @Test
    fun `de gekozen backend bepaalt welk endpoint beoordeeld wordt`() {
        // Beide keys worden onvoorwaardelijk geïnjecteerd; een achtergebleven plaintext
        // ClickHouse-endpoint mag een correcte PostgreSQL-deployment niet laten falen.
        assertDoesNotThrow {
            LdvEndpointValidator.validate(
                "prod",
                "postgresql",
                clickhouseEndpoint = "http://achtergebleven:8123",
                postgresqlUrl = "jdbc:postgresql://db:5432/ldv?ssl=true",
            )
        }

        // En andersom: een veilige JDBC-URL redt een plaintext ClickHouse-keuze niet.
        assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate(
                "prod",
                "clickhouse",
                clickhouseEndpoint = "http://ch:8123",
                postgresqlUrl = "jdbc:postgresql://db:5432/ldv?ssl=true",
            )
        }
    }
}
