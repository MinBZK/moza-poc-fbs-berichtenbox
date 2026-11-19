package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.quarkus.test.common.PathTestHelper
import io.quarkus.test.common.TestClassIndexer
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Borgt de map `test-classes.idx/` in de testresources (`leesmij.txt` daar legt uit waarom):
 * Quarkus mag het indexbestand in de gedeelde `target/test-classes` niet kunnen aanmaken of
 * verwijderen, en moet dan toch een index kunnen lezen.
 *
 * Getoetst via `TestClassIndexer`, de klasse die Quarkus daarvoor gebruikt, en niet via een
 * echte teststart: gaat Quarkus het bestand ooit buiten die klasse om beheren, dan blijft deze
 * test groen.
 */
class TestClassesIndexTest {

    private val index = PathTestHelper.getTestClassesLocation(javaClass).resolve(TestClassIndexer.TEST_CLASSES_IDX)

    @Test
    fun `de index is een map die Quarkus niet kan verwijderen`() {
        assertTrue(Files.isDirectory(index), "$index hoort een niet-lege map te zijn")

        runCatching { TestClassIndexer.removeIndex(javaClass) }

        assertTrue(Files.exists(index.resolve(LEESMIJ)), "$index is leeggemaakt of verwijderd")
    }

    @Test
    fun `schrijven maakt er geen bestand van`() {
        // Eerst de map eisen: zonder die map zou deze test zelf een echt indexbestand in de
        // gedeelde map zetten.
        assertTrue(Files.isDirectory(index), "$index hoort een niet-lege map te zijn")

        TestClassIndexer.writeIndex(TestClassIndexer.indexTestClasses(javaClass), javaClass)

        assertTrue(Files.exists(index.resolve(LEESMIJ)), "$index is door een bestand vervangen")
    }

    @Test
    fun `lezen levert toch een index op`() {
        assertTrue(Files.isDirectory(index), "$index hoort een niet-lege map te zijn")

        assertNotNull(TestClassIndexer.readIndex(javaClass).getClassByName(javaClass.name))
    }

    private companion object {
        const val LEESMIJ = "leesmij.txt"
    }
}
