package nl.rijksoverheid.moz.fbs.common

/**
 * **Naam-waarschuwing**: geen data-class maar een sanering-utility (legacy-naam).
 *
 * Saneert untrusted tekst die in een log-regel of persistent foutveld belandt — géén
 * algemene PII-filter en niet voor user-facing 4xx-`detail` (gebruik `sanitizeClientDetail`):
 *
 *  1. Cijferreeksen ≥7 → `[REDACTED]` (BSN/KvK/RSIN/OIN/epoch/trace-id; statuscodes en
 *     poorten blijven leesbaar). Namen/adressen/e-mail worden NIET gefilterd.
 *  2. Control-chars (C0 + DEL) → spatie: voorkomt CRLF log-injection (CWE-117).
 *
 * **Niet per call-site tunen**: een security-baseline die module-overstijgend identiek
 * moet blijven; daarom gecentraliseerd in `fbs-common`. Wijziging = project-brede review.
 */
object FoutBeschrijving {

    private val LANGE_CIJFERREEKS = Regex("\\d{7,}")
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")

    /**
     * Redact ≥7-cijfer-reeksen, strip control-chars (CWE-117), knip op [maxLengte]
     * (default 4 KiB). `take` draait NA substitutie; bounded want `[REDACTED]` is een
     * vaste 10 chars per run.
     */
    fun saneer(tekst: String?, maxLengte: Int = 4_096): String {
        if (tekst.isNullOrEmpty()) return ""
        return CONTROL_CHARS
            .replace(LANGE_CIJFERREEKS.replace(tekst, "[REDACTED]"), " ")
            .take(maxLengte)
    }
}
