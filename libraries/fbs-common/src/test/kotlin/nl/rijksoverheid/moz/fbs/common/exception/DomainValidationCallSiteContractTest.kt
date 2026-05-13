package nl.rijksoverheid.moz.fbs.common.exception

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Borgt het call-site-invariant van [DomainValidationException]:
 *
 *  > `requireValid { ... }`-lambda's en directe `throw DomainValidationException(...)`
 *  > messages mogen GEEN raw REST-input-velden interpoleren — die landen ongesaneerd
 *  > in `Problem.detail` en kunnen CRLF/JSON-injection of niet-numerieke PII (namen,
 *  > e-mail) lekken naar de 400-response.
 *
 * Reden: [DomainValidationExceptionMapper] geeft `exception.message` ongesaneerd
 * door aan de client. Constants, lengte/byte-counters en interne PK-IDs zijn
 * veilig; identifier-namen die typisch user-input dragen niet.
 *
 * **Blocklist-aanpak** (i.p.v. allowlist): de huidige call-sites gebruiken
 * `$KEY_PATTERN`, `$claimId`, `$pogingen`, `$inhoudBytes` — allemaal interne
 * state. Een allowlist op suffix `length|count|...` zou deze valide refs
 * onterecht weigeren. We blokkeren in plaats daarvan een korte lijst van
 * identifiers die typisch REST-input dragen (Bericht.afzender/ontvanger/
 * onderwerp/inhoud + algemene PII-velden naam/email/adres/...).
 *
 * Een refactor zoals `requireValid { "Onderwerp '$onderwerp' te lang" }`
 * faalt deze test direct. Een ArchUnit-rule zou specifieker zijn maar voegt
 * een dep toe; voor PoC is regex-source-scan een lichtere oplossing met
 * dezelfde regressie-detectie.
 */
class DomainValidationCallSiteContractTest {

    /**
     * Blocklist van identifier-namen die typisch raw REST-input bevatten.
     * Match is case-insensitive **exact** op het laatste segment van de
     * dotted path (bv. `request.onderwerp` matcht via `onderwerp`).
     *
     * Niet exhaustief — uitbreiden zodra een nieuw user-input-veld in de
     * domein-DTO's verschijnt. Bewust kort gehouden om false-positives
     * tegen interne state te voorkomen; nieuwe risico-velden hier expliciet
     * toevoegen blijft een review-gating-moment.
     */
    private val verbodenRefs = setOf(
        // FBS-domein REST-input
        "afzender", "ontvanger", "onderwerp", "inhoud", "waarde",
        // Generieke PII-bronnen
        "naam", "voornaam", "achternaam", "email", "mail", "telefoon",
        "telefoonnummer", "adres", "postcode", "bsn", "rsin",
    )

    /**
     * Patroon dat user-input interpolatie detecteert binnen een string-template.
     * `$identifier` of `${expression}`. Identifier-component (laatste segment
     * van dotted path) wordt tegen de blocklist gehouden.
     */
    private val interpolatiePattern = Regex("""\$\{?([a-zA-Z_][\w.]*)""")

    /**
     * Match call-site-bodies. Pakt eerste string-literal-body in:
     *  - `requireValid(cond) { "..." }` (lambda body)
     *  - `throw DomainValidationException("...")` (FQN of import-vorm)
     *
     * Beperking: nested templates of meerregelige raw-strings (`"""..."""`)
     * worden nu niet gevangen — schendt YAGNI uit te breiden tot ze opduiken.
     */
    private val callSitePattern = Regex(
        """(?:requireValid\([^)]*\)\s*\{\s*"([^"]*)"\s*\}|""" +
            """throw\s+DomainValidationException\s*\(\s*"([^"]*)"|""" +
            """throw\s+nl\.rijksoverheid\.moz\.fbs\.common\.exception\.DomainValidationException\s*\(\s*"([^"]*)")""",
    )

    @Test
    fun `geen DomainValidationException-call-site interpoleert raw REST-input-veld`() {
        val repoRoot = vindRepoRoot()
        val ktBronnen = repoRoot.walkTopDown()
            .onEnter { it.name !in setOf("target", "build", ".git", ".idea", "node_modules") }
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("/src/main/") }
            // Mapper zelf bevat het patroon in KDoc-voorbeelden; sla over.
            .filter { !it.name.startsWith("DomainValidationExceptionMapper") }
            .toList()

        assertTrue(ktBronnen.isNotEmpty(), "geen Kotlin-sources gevonden — repo-walk faalt?")

        val schendingen = mutableListOf<String>()
        ktBronnen.forEach { bron ->
            val inhoud = bron.readText()
            callSitePattern.findAll(inhoud).forEach { match ->
                val template = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@forEach
                interpolatiePattern.findAll(template).forEach { interp ->
                    val ref = interp.groupValues[1]
                    val laatsteSegment = ref.substringAfterLast(".").lowercase()
                    if (laatsteSegment in verbodenRefs) {
                        val regelnummer = inhoud.substring(0, match.range.first).count { it == '\n' } + 1
                        schendingen.add(
                            "${bron.relativeTo(repoRoot)}:$regelnummer interpoleert '\$$ref' " +
                                "in DomainValidationException-message — " +
                                "deze identifier staat op de REST-input-blocklist (PII/CRLF-risico)",
                        )
                    }
                }
            }
        }

        assertTrue(
            schendingen.isEmpty(),
            "DomainValidationException-call-site invariant geschonden:\n" +
                schendingen.joinToString("\n"),
        )
    }

    /**
     * Zoekt repo-root door omhoog te lopen tot een `pom.xml` met
     * `<artifactId>moza-poc-fbs-berichtenbox</artifactId>` gevonden wordt.
     * Surefire start in module-dir; we walken naar boven om alle modules
     * mee te scannen.
     */
    private fun vindRepoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(10) {
            val pom = File(dir, "pom.xml")
            if (pom.exists() && pom.readText().contains("<artifactId>moza-poc-fbs-berichtenbox</artifactId>")) {
                return dir
            }
            dir = dir.parentFile ?: error("repo-root niet gevonden vanuit ${System.getProperty("user.dir")}")
        }
        error("repo-root niet gevonden binnen 10 niveaus boven ${System.getProperty("user.dir")}")
    }
}
