package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource

// Bovenaan het bestand en niet genest: een klasse die de test zelf definieert, mag geen buitenklasse
// hebben die uit een andere classloader komt.
@QuarkusTest
class SoortvoorbeeldQuarkus {
    class Binnen
}

@Target(AnnotationTarget.CLASS)
@QuarkusTest
annotation class SoortvoorbeeldStereotype

@SoortvoorbeeldStereotype
class SoortvoorbeeldMeta

class SoortvoorbeeldGewoon

class TestsoortFilterTest {

    @ParameterizedTest(name = "{0} in {1}: {2}")
    @CsvSource(
        "SoortvoorbeeldGewoon, gewoon, true",
        "SoortvoorbeeldQuarkus, gewoon, false",
        "SoortvoorbeeldQuarkus\$Binnen, gewoon, false",
        "SoortvoorbeeldMeta, gewoon, false",
        "SoortvoorbeeldGewoon, quarkus, false",
    )
    fun `elke klasse belandt in precies één executie`(klasse: String, soort: String, verwacht: Boolean) {
        val resultaat = TestsoortFilter { soort }.apply(test(MethodSource.from(voorbeeld(klasse).name, "test")))

        assertEquals(verwacht, resultaat.included())
    }

    @Test
    fun `een Quarkus-testklasse die Quarkus zelf laadt, hoort in de Quarkus-executie`() {
        val elders = EigenClassLoader().laad(SoortvoorbeeldQuarkus::class.java)

        val resultaat = TestsoortFilter { "quarkus" }.apply(test(ClassSource.from(elders)))

        assertTrue(resultaat.included())
    }

    @Test
    fun `een Quarkus-testklasse die de JVM zelf laadt, laat de Quarkus-executie falen`() {
        val fout = assertThrows<IllegalStateException> {
            TestsoortFilter { "quarkus" }.apply(test(ClassSource.from(SoortvoorbeeldQuarkus::class.java)))
        }

        assertTrue(fout.message!!.contains("niet door Quarkus geladen"), fout.message)
    }

    @Test
    fun `een gewone testklasse die Quarkus laadt, laat de Quarkus-executie falen`() {
        val elders = EigenClassLoader().laad(SoortvoorbeeldGewoon::class.java)

        assertThrows<IllegalStateException> { TestsoortFilter { "quarkus" }.apply(test(ClassSource.from(elders))) }
    }

    @Test
    fun `zonder eigenschap gaat alles door`() {
        val resultaat = TestsoortFilter { null }.apply(test(ClassSource.from(SoortvoorbeeldQuarkus::class.java)))

        assertTrue(resultaat.included())
    }

    @Test
    fun `een onbekende soort is een configuratiefout`() {
        assertThrows<IllegalArgumentException> {
            TestsoortFilter { "alles" }.apply(test(ClassSource.from(SoortvoorbeeldGewoon::class.java)))
        }
    }

    @Test
    fun `zonder eigen bron telt de klasse van de parent`() {
        val parent = test(ClassSource.from(SoortvoorbeeldQuarkus::class.java))
        val child = test(null).also { it.setParent(parent) }

        assertEquals(false, TestsoortFilter { "gewoon" }.apply(child).included())
    }

    @Test
    fun `zonder testklasse gaat het door`() {
        assertTrue(TestsoortFilter { "quarkus" }.apply(test(null)).included())
    }

    private fun voorbeeld(naam: String): Class<*> = Class.forName("${TestsoortFilterTest::class.java.packageName}.$naam")

    private fun test(bron: TestSource?): TestDescriptor =
        object : AbstractTestDescriptor(UniqueId.forEngine("test").append("test", "1"), "test", bron) {
            override fun getType() = TestDescriptor.Type.TEST
        }

    /** Definieert de gegeven klasse zelf, zoals Quarkus dat doet; de rest komt van de app-classloader. */
    private class EigenClassLoader : ClassLoader(ClassLoader.getSystemClassLoader()) {
        fun laad(klasse: Class<*>): Class<*> {
            val bytes = klasse.getResourceAsStream("/${klasse.name.replace('.', '/')}.class")!!.use { it.readBytes() }

            return defineClass(klasse.name, bytes, 0, bytes.size)
        }
    }
}
