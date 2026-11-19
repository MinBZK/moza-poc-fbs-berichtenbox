package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import org.junit.platform.engine.FilterResult
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.PostDiscoveryFilter

/**
 * Laat per Surefire-executie alleen de tests van één soort door (`fbs.test.soort`): `quarkus` voor
 * de `@QuarkusTest`-klassen, `gewoon` voor de rest. Zonder die eigenschap (IDE, losse JUnit-run)
 * gaat alles door.
 *
 * Zonder deze scheiding staat MockK in één JVM twee keer: een kopie in de app-classloader voor de
 * gewone tests, en per Quarkus-start een in diens classloader. Elke kopie nummert zijn proxyklassen
 * vanaf nul (`Span$Subclass0`). Een gewone test die een interface mockt, definieert die klasse in
 * de app-classloader; Quarkus zoekt een naam die hij zelf niet kent daar op. Een latere `any()` op
 * hetzelfde type in een Quarkus-test vindt zijn nieuwe naam dan al bezet, MockK valt stil terug op
 * een instantie van de interface zelf en de test faalt met een `InstantiationError`. In één launch
 * zet de class-orderer van Quarkus de gewone tests achteraan, maar met meer forks krijgt een fork
 * één klasse per launch, in de volgorde die Surefire kiest.
 *
 * De soort volgt uit de annotatie en niet uit de classloader: de gewone executie zet de
 * classloader van Quarkus uit (`prod.mode.tests`), anders bouwt Quarkus bij de discovery elke
 * Quarkus-testklasse alsnog op. In de Quarkus-executie moeten beide wel kloppen; wijkt de
 * annotatie af van wat Quarkus zelf als test herkent, dan faalt de run in plaats van dat een
 * klasse in de verkeerde executie belandt.
 */
class TestsoortFilter(
    private val eigenschap: () -> String? = { System.getProperty(EIGENSCHAP) },
) : PostDiscoveryFilter {

    override fun apply(descriptor: TestDescriptor): FilterResult {
        val gevraagd = eigenschap() ?: return FilterResult.included("geen $EIGENSCHAP")

        require(gevraagd == GEWOON || gevraagd == QUARKUS) { "$EIGENSCHAP=$gevraagd; verwacht $GEWOON of $QUARKUS" }

        val klasse = testklasse(descriptor) ?: return FilterResult.included("geen testklasse")
        val soort = if (isQuarkusTest(klasse)) QUARKUS else GEWOON

        if (gevraagd == QUARKUS) {
            val doorQuarkusGeladen = klasse.classLoader !== ClassLoader.getSystemClassLoader()

            check((soort == QUARKUS) == doorQuarkusGeladen) {
                "${klasse.name}: soort $soort, maar ${if (doorQuarkusGeladen) "wel" else "niet"} door Quarkus geladen"
            }
        }

        return FilterResult.includedIf(
            soort == gevraagd,
            { "$soort-test in de $gevraagd-executie" },
            { "$soort-test hoort niet in de $gevraagd-executie" },
        )
    }

    private fun testklasse(descriptor: TestDescriptor): Class<*>? {
        val bron = descriptor.source.orElse(null)

        return when (bron) {
            is MethodSource -> bron.javaClass
            is ClassSource -> bron.javaClass
            else -> descriptor.parent.orElse(null)?.let(::testklasse)
        }
    }

    /** Op naam, want de annotatieklasse van Quarkus kan uit een andere classloader komen. */
    private fun isQuarkusTest(klasse: Class<*>): Boolean =
        generateSequence(klasse) { it.enclosingClass }.any { omsluitend ->
            omsluitend.annotations.any { annotatie ->
                val type = annotatie.annotationClass.java

                type.name == QUARKUS_TEST || type.annotations.any { it.annotationClass.java.name == QUARKUS_TEST }
            }
        }

    companion object {
        const val EIGENSCHAP = "fbs.test.soort"
        const val GEWOON = "gewoon"
        const val QUARKUS = "quarkus"
        private const val QUARKUS_TEST = "io.quarkus.test.junit.QuarkusTest"
    }
}
