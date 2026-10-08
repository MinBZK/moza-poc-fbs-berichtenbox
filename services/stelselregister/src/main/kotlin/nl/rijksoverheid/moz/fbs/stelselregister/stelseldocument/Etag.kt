package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

/** Vergelijkt een `If-None-Match`-header met een ETag volgens de zwakke vergelijking van RFC 9110 §8.8.3.2. */
object Etag {

    private const val ZWAK_PREFIX = "W/"

    fun komtOvereen(ifNoneMatch: String?, etag: String): Boolean {
        if (ifNoneMatch.isNullOrBlank()) return false

        val kandidaten = ifNoneMatch.split(',').map(String::trim)

        return kandidaten.any { it == "*" || it.removePrefix(ZWAK_PREFIX) == etag.removePrefix(ZWAK_PREFIX) }
    }
}
