package nl.rijksoverheid.moz.fbs.common

/**
 * **Naam-waarschuwing**: ondanks de naam is dit GEEN data-class voor
 * fout-beschrijvingen — het is een sanering-utility (legacy-naam, behouden
 * om project-brede rename te vermijden).
 *
 * Sanering voor untrusted tekst die in een log-regel of persistent foutveld
 * terechtkomt. **Niet** een algemene PII-filter, **niet** voor user-facing
 * tekst (use `sanitizeClientDetail` daarvoor in 4xx response-`detail`).
 *
 * Regels:
 *
 *  1. Cijferreeksen ≥7 tekens worden `[REDACTED]`. Bedekt BSN (9), KvK (8),
 *     RSIN (9), OIN (20), epoch-millis (13) en lange trace-id-fragmenten. HTTP-
 *     statuscodes (3) en poortnummers (≤5) blijven leesbaar voor diagnose.
 *  2. Control characters (`U+0000`–`U+001F` plus `U+007F` DEL, dus alle
 *     C0-controls zoals `\r`, `\n`, `\t`) worden vervangen door spatie.
 *     Voorkomt CRLF log-injection (CWE-117) doordat een kwaadaardige
 *     downstream-response geen extra log-regels kan smokkelen.
 *
 * Geen volledig PII-filter — namen/adressen/e-mailadressen/niet-numerieke
 * persoonsgegevens worden niet gefilterd. Behandel exception-`message` als
 * untrusted en geef geen rauwe waardes door waar PII verwacht kan worden.
 *
 * **Niet tunen per call-site**: de regex-grenzen (cijferlengte ≥7,
 * control-char-set) zijn een security-baseline die module-overstijgend
 * identiek moet blijven. Module-eigen tweaks zouden CWE-117/AVG-coverage
 * fragmenteren en compliance-regressies maskeren — vandaar centralisatie
 * in `fbs-common`. Wijziging vereist project-brede review.
 *
 * Toepasselijk op alle plekken waar exception-messages, downstream-responses
 * of upstream-headers in een persistent veld of log-regel belanden:
 *
 *  - persistente `reden`-velden in `publicatie_deliveries` (downstream-fouten)
 *  - `log.*f`-strings die exception-message of header-content interpoleren
 *  - JAX-RS `ExceptionMapper`-instanties die exception-messages naar errorlog schrijven
 *
 * Live in `fbs-common` zodat zowel berichtenmagazijn-code als de gedeelde
 * exception-mappers één saneer-pad delen (geen duplicatie, geen drift).
 */
object FoutBeschrijving {

    private val LANGE_CIJFERREEKS = Regex("\\d{7,}")
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")

    /**
     * Sanering voor log/persistent fout-velden — **niet** algemene PII-filter
     * en **niet** voor user-facing 4xx-detail (gebruik daarvoor
     * `sanitizeClientDetail`). Redact ≥7-cijfer-reeksen en strip control-chars
     * (CWE-117). Knipt op [maxLengte] (default 4 KiB).
     *
     * `take(maxLengte)` draait NA substitutie: `[REDACTED]` (10 chars)
     * vervangt 7+-cijferreeksen, dus de tussentijdse string kan licht
     * groeien vóór truncatie. Bounded — substitutie is monotoon
     * niet-explosief (10 chars per ≥7-cijfer-run).
     */
    fun saneer(tekst: String?, maxLengte: Int = 4_096): String {
        if (tekst.isNullOrEmpty()) return ""
        return CONTROL_CHARS
            .replace(LANGE_CIJFERREEKS.replace(tekst, "[REDACTED]"), " ")
            .take(maxLengte)
    }
}
