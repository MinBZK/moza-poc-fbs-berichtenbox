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

    const val PANEEL_PAD = "src/main/resources/META-INF/resources/index.html"

    const val SCRIPT_PAD = "src/main/resources/META-INF/resources/bediening.js"

    fun paneel(): String = File(PANEEL_PAD).readText()

    fun script(): String = File(SCRIPT_PAD).readText()
}
