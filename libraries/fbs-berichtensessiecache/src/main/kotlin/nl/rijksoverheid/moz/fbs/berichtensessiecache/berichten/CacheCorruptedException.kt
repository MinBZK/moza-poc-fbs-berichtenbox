package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

/**
 * Gegooid bij ontbrekende of niet-parsebare velden in een gecachte hash.
 * Wijst op cache-corruptie of schema-drift (bv. een Bericht-veld is verwijderd
 * maar oude entries hebben de key nog). De caller logt + propageert als 500,
 * niet als 503: "cache onbereikbaar" zou de verkeerde diagnose suggereren.
 *
 * Constructor is `private`: messages zijn statisch zodat een cause-message (bv.
 * Jackson-fout met JSON-fragment) niet ongezien in een log-context kan lekken.
 */
internal class CacheCorruptedException private constructor(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    companion object {
        fun veldOntbreekt(veld: String) =
            CacheCorruptedException("Veld '$veld' ontbreekt in gecachte hash")

        fun onleesbareWaarde(veld: String, cause: Throwable) =
            CacheCorruptedException("Onleesbare waarde in gecachte hash voor veld '$veld'", cause)
    }
}
