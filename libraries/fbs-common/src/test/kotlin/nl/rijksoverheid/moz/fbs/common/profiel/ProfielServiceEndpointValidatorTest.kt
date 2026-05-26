package nl.rijksoverheid.moz.fbs.common.profiel

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * Borgt fail-closed gedrag van de TLS-eis op de Profiel-Service-URL. Regressies
 * hier zouden BSN/RSIN/KVK over `http://` lekken in prod/staging/acceptatie (de
 * externe Profiel Service zet de identificatie in het URL-pad, dus zonder TLS
 * eindigt PII in netwerk- en proxy-toegangslogs — BIO 13.2.1).
 *
 * Spiegelt [nl.rijksoverheid.moz.fbs.common.LdvEndpointValidatorTest]; bewust
 * gespiegeld zodat een wijziging in één validator het andere niet stilzwijgend
 * regresseert.
 */
class ProfielServiceEndpointValidatorTest {

    @Test
    fun `dev-profiel laat http-endpoint toe`() {
        assertDoesNotThrow {
            ProfielServiceEndpointValidator.validate("dev", "http://localhost:8089")
        }
    }

    @Test
    fun `test-profiel laat http-endpoint toe`() {
        assertDoesNotThrow {
            ProfielServiceEndpointValidator.validate("test", "http://localhost:8089")
        }
    }

    @Test
    fun `prod-profiel met http-endpoint faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            ProfielServiceEndpointValidator.validate("prod", "http://profiel.intern")
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "moet naar BIO 13.2.1 verwijzen")
        assertTrue(ex.message!!.contains("https://"), "moet https:// noemen")
        assertTrue(
            ex.message!!.contains("http://profiel.intern"),
            "moet de huidige (foutieve) waarde tonen voor diagnose",
        )
    }

    @Test
    fun `prod-profiel met https-endpoint slaagt`() {
        assertDoesNotThrow {
            ProfielServiceEndpointValidator.validate("prod", "https://profiel.intern")
        }
    }

    @Test
    fun `staging-profiel valt onder de TLS-eis`() {
        assertThrows<IllegalArgumentException> {
            ProfielServiceEndpointValidator.validate("staging", "http://profiel.acceptatie")
        }
    }

    @Test
    fun `acceptatie-profiel valt onder de TLS-eis`() {
        assertThrows<IllegalArgumentException> {
            ProfielServiceEndpointValidator.validate("acceptatie", "http://profiel.acceptatie")
        }
    }

    @Test
    fun `onbekend profiel valt onder de TLS-eis (typo-protection)`() {
        // Een typo zoals 'productie' (i.p.v. 'prod') of 'Dev' (i.p.v. 'dev') mag
        // de TLS-eis NIET silent bypassen.
        assertThrows<IllegalArgumentException> {
            ProfielServiceEndpointValidator.validate("productie", "http://profiel.intern")
        }
        assertThrows<IllegalArgumentException> {
            ProfielServiceEndpointValidator.validate("Dev", "http://profiel.intern")
        }
    }
}
