package nl.rijksoverheid.moz.fbs.berichtensessiecache

import io.quarkus.test.common.PathTestHelper
import io.quarkus.test.common.TestClassIndexer
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/**
 * Borgt de map `test-classes.idx/` in de testresources (`leesmij.txt` daar legt uit waarom):
 * Quarkus mag het indexbestand in de gedeelde `target/test-classes` niet kunnen aanmaken of
 * verwijderen, en moet de index dan zelf opnieuw opbouwen.
 */
class TestClassesIndexTest {

    private val index = PathTestHelper.getTestClassesLocation(javaClass).resolve(TestClassIndexer.TEST_CLASSES_IDX)

    @Test
    fun `de index is een map die Quarkus niet kan verwijderen`() {
        assertThrows<IllegalStateException> { TestClassIndexer.removeIndex(javaClass) }

        assertTrue(Files.isDirectory(index), "$index hoort een niet-lege map te zijn")
    }

    @Test
    fun `schrijven maakt er geen bestand van`() {
        TestClassIndexer.writeIndex(TestClassIndexer.indexTestClasses(javaClass), javaClass)

        assertTrue(Files.isDirectory(index), "$index hoort een niet-lege map te zijn")
    }

    @Test
    fun `lezen bouwt de index opnieuw op`() {
        assertNotNull(TestClassIndexer.readIndex(javaClass).getClassByName(javaClass.name))
    }
}
