package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LdvFoutSamenvattingTest {

    @Test
    fun `de message van de oorspronkelijke fout gaat niet mee`() {
        // Vorm van een PostgreSQL NOT NULL-violation: het Detail-veld bevat de volledige
        // rij, dus BSN én berichtinhoud.
        val driverFout = IllegalStateException(
            "ERROR: null value in column \"onderwerp\" violates not-null constraint\n" +
                "  Detail: Failing row contains (1, 999993653, Beste heer, uw uitkering is gewijzigd).",
        )

        val samenvatting = LdvFoutSamenvatting.van(driverFout)

        assertFalse(samenvatting.message!!.contains("999993653"), "BSN mag niet in de samenvatting staan")
        assertFalse(samenvatting.message!!.contains("uitkering"), "berichtinhoud mag niet in de samenvatting staan")
        assertFalse(samenvatting.message!!.contains("Failing row"), "de driver-message mag niet meegaan")
    }

    @Test
    fun `het type van de oorspronkelijke fout blijft bruikbaar voor diagnose`() {
        val samenvatting = LdvFoutSamenvatting.van(IllegalArgumentException("wat dan ook"))

        assertEquals("java.lang.IllegalArgumentException", samenvatting.oorspronkelijkType)
        assertEquals("java.lang.IllegalArgumentException", samenvatting.message)
    }

    @Test
    fun `een fout zonder message levert nog steeds het type`() {
        val samenvatting = LdvFoutSamenvatting.van(RuntimeException())

        assertEquals("java.lang.RuntimeException", samenvatting.oorspronkelijkType)
    }

    @Test
    fun `de samenvatting draagt geen stacktrace en geen oorzaak`() {
        // Een stacktrace wijst alleen naar de factory-methode; een oorzaak zou de
        // oorspronkelijke message alsnog meenemen als de stacktrace-export aan staat.
        val samenvatting = LdvFoutSamenvatting.van(IllegalStateException("Failing row contains (999993653)"))

        assertEquals(0, samenvatting.stackTrace.size)
        assertNull(samenvatting.cause)
        assertFalse(samenvatting.stackTraceToString().contains("999993653"))
    }
}
