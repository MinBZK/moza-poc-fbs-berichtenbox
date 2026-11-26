package nl.rijksoverheid.moz.fbs.testhulp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * De vanger is de grond onder elke toets op persoonsgegevens in de log: wat hij mist, mist elke test
 * die op hem leunt, en een groene test zegt dan niets meer.
 */
class LogvangerTest {

    private val eigen = Logger.getLogger("nl.rijksoverheid.moz.testhulp.voorbeeld")

    @Test
    fun `vangt melding, losse parameters en de stack van thrown, ook op het laagste niveau`() {
        val tekst = Logvanger().use { vanger ->
            eigen.log(Level.FINEST, "alleen de melding")
            eigen.log(regel(Level.FINE, "Waarde {0} en {1}", "parameter-waarde", null))
            eigen.log(Level.FINER, "met exceptie", IllegalStateException("in-de-stack"))

            vanger.tekst()
        }

        assertTrue(tekst.contains("alleen de melding"), tekst)
        assertTrue(tekst.contains("Waarde parameter-waarde en null"), "opgemaakt: $tekst")
        assertTrue(tekst.contains("parameter-waarde | null"), "los, ook een parameter die null is: $tekst")
        assertTrue(tekst.contains("in-de-stack"), "de stack: $tekst")
    }

    @Test
    fun `een melding die MessageFormat niet kan opmaken, telt nog steeds mee`() {
        val tekst = Logvanger().use { vanger ->
            eigen.log(regel(Level.INFO, "kapot {0", "losse-waarde"))

            vanger.tekst()
        }

        assertTrue(tekst.contains("kapot {0"), tekst)
        assertTrue(tekst.contains("losse-waarde"), tekst)
    }

    @Test
    fun `een categorie buiten de eigen code logt op haar gewone niveau, tenzij hij open staat`() {
        val vreemd = Logger.getLogger("org.voorbeeld.bibliotheek")

        val dicht = Logvanger().use { vanger ->
            vreemd.fine("dicht-gelogd")
            vanger.tekst()
        }

        val open = Logvanger(open = listOf("org.voorbeeld")).use { vanger ->
            vreemd.fine("open-gelogd")
            vanger.tekst()
        }

        assertFalse(dicht.contains("dicht-gelogd"), dicht)
        assertTrue(open.contains("open-gelogd"), open)
    }

    @Test
    fun `bronnenVan noemt de categorieën met een fragment, zonder de testclient`() {
        val bronnen = Logvanger(open = listOf("org")).use { vanger ->
            Logger.getLogger("org.voorbeeld.lekker").info("bevat 123456")
            Logger.getLogger("org.apache.http.wire").info("bevat 123456")
            Logger.getLogger("org.voorbeeld.schoon").info("niets")

            vanger.bronnenVan(listOf("123456"))
        }

        assertEquals(setOf("org.voorbeeld.lekker"), bronnen)
    }

    @Test
    fun `sluiten zet het niveau terug en vangt daarna niets meer`() {
        val categorie = Logger.getLogger("nl.rijksoverheid.moz")
        categorie.level = Level.WARNING
        val vanger = Logvanger()

        vanger.close()
        eigen.warning("na het sluiten")

        assertEquals(Level.WARNING, categorie.level)
        assertFalse(vanger.tekst().contains("na het sluiten"))

        categorie.level = null
    }

    @Test
    fun `herkenbareDelen geeft de waarde en elk stuk van zes tekens of meer`() {
        assertEquals(listOf("1234567", "123456", "234567").sorted(), herkenbareDelen("1234567").sorted())
        assertEquals(emptyList<String>(), herkenbareDelen("12345"))
        assertEquals(listOf("aaaaaa"), herkenbareDelen("aaaaaa"))
    }

    private fun regel(niveau: Level, melding: String, vararg parameters: Any?) =
        LogRecord(niveau, melding).apply {
            loggerName = eigen.name
            this.parameters = parameters
        }
}
