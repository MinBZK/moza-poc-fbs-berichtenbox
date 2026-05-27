package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

/**
 * Gegooid bij ontbrekende of niet-parsebare velden in een gecachte hash.
 * Wijst op cache-corruptie of schema-drift (bv. een Bericht-veld is verwijderd
 * maar oude entries hebben de key nog). De caller logt + propageert als 500,
 * niet als 503: "cache onbereikbaar" zou de verkeerde diagnose suggereren.
 */
class CacheCorruptedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
