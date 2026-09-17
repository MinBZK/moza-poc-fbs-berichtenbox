package nl.rijksoverheid.moz.fbs.democonsole

import java.io.File

/**
 * De bronbestanden van het bedieningspaneel, rechtstreeks van schijf.
 *
 * Het paneel is opmaak plus script zonder buildstap, dus er is geen andere manier om het te toetsen
 * dan het te lezen. Op één plek, zodat een verplaatst bestand één test-fout geeft en niet zoveel
 * als er klassen zijn die meelezen.
 */
object PaneelBestanden {

    /** De map die het paneel uitserveert; wie alle pagina's wil aflopen begint hier. */
    const val RESOURCES_PAD = "src/main/resources/META-INF/resources"

    const val PANEEL_PAD = "$RESOURCES_PAD/index.html"

    const val SCRIPT_PAD = "$RESOURCES_PAD/bediening.js"

    const val STIJL_PAD = "$RESOURCES_PAD/bediening.css"

    /** Het gedeelde herstel van de inlogsessie; zowel het paneel als de berichtenbox laadt dit. */
    const val INLOGMUUR_PAD = "$RESOURCES_PAD/inlogmuur.js"

    const val BOX_PAGINA_PAD = "$RESOURCES_PAD/berichtenbox.html"

    const val BOX_SCRIPT_PAD = "$RESOURCES_PAD/berichtenbox.js"

    const val LEESMIJ_PAD = "README.md"

    fun paneel(): String = File(PANEEL_PAD).readText()

    fun script(): String = File(SCRIPT_PAD).readText()

    fun stijl(): String = File(STIJL_PAD).readText()

    fun inlogmuur(): String = File(INLOGMUUR_PAD).readText()

    fun boxPagina(): String = File(BOX_PAGINA_PAD).readText()

    fun boxScript(): String = File(BOX_SCRIPT_PAD).readText()

    fun leesmij(): String = File(LEESMIJ_PAD).readText()
}
