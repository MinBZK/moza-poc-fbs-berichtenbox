package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class LdvTabelnaamValidatorTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "logboek_dataverwerkingen",
            "magazijna.logboek_dataverwerkingen",
            "_intern.logboek",
            "s1.t2",
        ],
    )
    fun `bruikbare tabelnamen slagen`(tabel: String) {
        assertDoesNotThrow { LdvTabelnaamValidator.validate("prod", "postgresql", tabel) }
    }

    /**
     * Een niet-gezette `DB_SCHEMA` maakt de expressie onexpandeerbaar; de property komt dan
     * als afwezig binnen en bereikt deze functie als lege string.
     */
    @ParameterizedTest
    @ValueSource(strings = ["", " ", "   "])
    fun `lege tabelnaam faalt fail-fast buiten dev en test`(tabel: String) {
        val ex = assertThrows<IllegalArgumentException> {
            LdvTabelnaamValidator.validate("prod", "postgresql", tabel)
        }

        assertTrue(
            ex.message!!.contains(LdvTabelnaamValidator.TABEL_KEY),
            "foutmelding moet de property noemen die aangepast moet worden",
        )
    }

    /**
     * De andere manier waarop `DB_SCHEMA` misgaat: wél gezet, maar leeg. De expansie slaagt
     * en laat een leidende punt achter — een waarde die als aanwezig telt maar nergens naar
     * verwijst.
     */
    @Test
    fun `leeg gezette schema-prefix laat een leidende punt achter en faalt`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvTabelnaamValidator.validate("prod", "postgresql", ".logboek_dataverwerkingen")
        }

        assertTrue(ex.message!!.contains(".logboek_dataverwerkingen"), "foutmelding moet de waarde tonen")
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "pr-168.logboek, koppelteken komt niet door de wrapper-validatie",
            "MagazijnA.logboek, hoofdletters vouwen weg in ongequote SQL",
            "1schema.logboek, mag niet met een cijfer beginnen",
            "a.b.c, hooguit een schema-prefix",
            "schema..logboek, lege tussenliggende naam",
            "logboek dataverwerkingen, spatie",
            "logboek;drop, puntkomma",
            "\"logboek\", quotes",
        ],
    )
    fun `onbruikbare tabelnamen falen fail-fast`(tabel: String, reden: String) {
        assertThrows<IllegalArgumentException>(reden) {
            LdvTabelnaamValidator.validate("prod", "postgresql", tabel)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test mogen elke tabelnaam gebruiken`(profile: String) {
        assertDoesNotThrow { LdvTabelnaamValidator.validate(profile, "postgresql", "") }
        assertDoesNotThrow { LdvTabelnaamValidator.validate(profile, "postgresql", "Rare-Naam") }
    }

    /**
     * Op ClickHouse stuurt een andere key de tabelnaam; deze waarde doet er dan niet toe en
     * mag de start niet blokkeren.
     */
    @ParameterizedTest
    @ValueSource(strings = ["clickhouse", "ClickHouse"])
    fun `clickhouse-backend negeert deze property`(dbms: String) {
        assertDoesNotThrow { LdvTabelnaamValidator.validate("prod", dbms, "") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["postgresql", "postgres", "PostgreSQL"])
    fun `alle schrijfwijzen van de postgresql-backend worden gecontroleerd`(dbms: String) {
        assertThrows<IllegalArgumentException> {
            LdvTabelnaamValidator.validate("prod", dbms, "")
        }
    }

    /**
     * Een onbekende backend mag niet stil overgeslagen worden: dan zou de tabelnaam
     * ongecontroleerd blijven terwijl er wél een backend gekozen is.
     */
    @ParameterizedTest
    @ValueSource(strings = ["mysql", "", "pgsql"])
    fun `onbekende backend faalt in plaats van de controle over te slaan`(dbms: String) {
        assertThrows<IllegalArgumentException> {
            LdvTabelnaamValidator.validate("prod", dbms, "logboek_dataverwerkingen")
        }
    }

    /**
     * Alles buiten dev en test geldt als productie-achtig, net als bij de twee andere
     * LDV-guards — ook een profielnaam die vandaag nergens gebruikt wordt.
     */
    @ParameterizedTest
    @ValueSource(strings = ["staging", "acceptatie", "onbekend"])
    fun `overige profielen worden als productie behandeld`(profile: String) {
        assertThrows<IllegalArgumentException> {
            LdvTabelnaamValidator.validate(profile, "postgresql", "")
        }
    }
}
