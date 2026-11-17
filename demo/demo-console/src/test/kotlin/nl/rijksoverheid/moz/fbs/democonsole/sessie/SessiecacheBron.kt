package nl.rijksoverheid.moz.fbs.democonsole.sessie

import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * De broncode van `fbs-berichtensessiecache`, rechtstreeks van schijf: de console hangt niet aan die
 * bibliotheek, dus alleen zo zien de tests wat ze daar aanneemt.
 */
object SessiecacheBron {

    private const val BIBLIOTHEEK =
        "../../libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache"

    fun lees(pad: String): String {
        val bron = File("$BIBLIOTHEEK/$pad")

        assertTrue(bron.isFile, "$pad niet gevonden op ${bron.absolutePath}")

        return bron.readText()
    }

    /**
     * Een Lua-script uit `SessieScripts`, zoals de bibliotheek het naar Redis stuurt: de gedeelde
     * functies, een lege regel en het script zelf, elk met `trimIndent`.
     */
    fun script(naam: String): String {
        val scripts = lees("sessie/SessieScripts.kt")
        val gedeeld = tekst(scripts, """private val GEDEELD = """, "GEDEELD")
        val eigen = tekst(scripts, """val $naam = GEDEELD + "\n\n" + """, naam)

        return gedeeld + "\n\n" + eigen
    }

    private fun tekst(bron: String, voor: String, naam: String): String {
        val gevonden = Regex(Regex.escape(voor + "\"\"\"") + """(.*?)"{3}\.trimIndent\(\)""", RegexOption.DOT_MATCHES_ALL)
            .find(bron)
            ?.groupValues
            ?.get(1)

        assertTrue(gevonden != null, "het script $naam niet gevonden in SessieScripts.kt; klopt de vorm nog?")
        // Een Kotlin-template zou hier letterlijk blijven staan en in Redis iets anders betekenen.
        assertTrue('$' !in gevonden!!, "het script $naam bevat een Kotlin-template; die leest deze test niet in")

        return gevonden.trimIndent()
    }
}
