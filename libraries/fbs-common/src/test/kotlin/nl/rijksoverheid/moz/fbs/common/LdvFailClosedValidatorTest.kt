package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class LdvFailClosedValidatorTest {

    @Test
    fun `prod met simple en fail-closed slaagt`() {
        assertDoesNotThrow {
            LdvFailClosedValidator.validate("prod", "simple", "fail-closed")
        }
    }

    @Test
    fun `batch-processor faalt fail-fast buiten dev en test`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvFailClosedValidator.validate("prod", "batch", "fail-closed")
        }
        assertTrue(
            ex.message!!.contains(LdvFailClosedValidator.SPAN_PROCESSOR_KEY),
            "foutmelding moet de property noemen die aangepast moet worden",
        )
        assertTrue(ex.message!!.contains("batch"), "foutmelding moet de afgewezen waarde tonen")
    }

    @Test
    fun `fail-open faalt fail-fast buiten dev en test`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvFailClosedValidator.validate("prod", "simple", "fail-open")
        }
        assertTrue(
            ex.message!!.contains(LdvFailClosedValidator.WRITE_FAILURE_POLICY_KEY),
            "foutmelding moet de property noemen die aangepast moet worden",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["staging", "acceptatie", "prod"])
    fun `elk productie-achtig profiel valt onder de eis`(profiel: String) {
        assertThrows<IllegalArgumentException> {
            LdvFailClosedValidator.validate(profiel, "batch", "fail-open")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test zijn vrijgesteld`(profiel: String) {
        // Lokaal en in de suite draait geen echte betrokkene-data; de eis zou elke start
        // van een bereikbaar logboek afhankelijk maken.
        assertDoesNotThrow {
            LdvFailClosedValidator.validate(profiel, "batch", "fail-open")
        }
    }

    @ParameterizedTest
    @CsvSource(
        "simple, fail-closed",
        "SIMPLE, FAIL-CLOSED",
        "Simple, fail_closed",
        "simple, failclosed",
    )
    fun `elke schrijfwijze die de wrapper als fail-closed leest, slaagt ook hier`(
        spanProcessor: String,
        writeFailurePolicy: String,
    ) {
        // Zou de guard strenger zijn dan de wrapper, dan wees hij een werkende
        // configuratie af — een startup-fout zonder inhoudelijk probleem.
        assertDoesNotThrow {
            LdvFailClosedValidator.validate("prod", spanProcessor, writeFailurePolicy)
        }
    }

    @Test
    fun `een onbekende policy-waarde faalt, ook al lijkt hij op fail-closed`() {
        assertThrows<IllegalArgumentException> {
            LdvFailClosedValidator.validate("prod", "simple", "failclosed-ish")
        }
    }
}
