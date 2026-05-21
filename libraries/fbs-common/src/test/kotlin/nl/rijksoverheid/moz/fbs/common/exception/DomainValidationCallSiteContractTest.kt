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
 * **Implementatie-noot — balanced-paren-parsing**:
 * Eerdere regex `requireValid\([^)]*\)` faalde op nested haakjes
 * (`requireValid(KEY_PATTERN.matches(key))`). We scannen daarom met een
 * handmatige depth-counter — vangt alle bekende call-site-vormen, ook met
 * geneste functie-calls in de conditie.
 *
 * **Veiligheidsklep — count-pin**: de test eist dat MINIMAAL [VERWACHT_AANTAL_CALLSITES]
 * call-sites worden gevonden. Een refactor naar raw-strings (`"""..."""`) of
 * concat-strings die de regex stilzwijgend leeg maakt zou hier falen — en
 * de operator gewaarschuwd worden dat de invariant niet meer gehandhaafd wordt.
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
        "naam", "voornaam", "achternaam", "voorletters", "geboortedatum",
        "email", "mail", "telefoon", "telefoonnummer", "adres", "postcode",
        "bsn", "rsin", "iban", "bedrag",
    )

    /**
     * Patroon dat user-input interpolatie detecteert binnen een string-template.
     * `$identifier` of `${expression}`. Voor `${...}`-bodies extraheren we ALLE
     * identifier-tokens (niet alleen de eerste) zodat `${functie(naam)}` zowel
     * `functie` als `naam` valideert.
     */
    private val simpelPattern = Regex("""\$([a-zA-Z_][\w.]*)""")
    private val complexPattern = Regex("""\$\{([^}]*)\}""")
    private val identifierPattern = Regex("""\b([a-zA-Z_][\w]*)\b""")

    /**
     * Verwacht aantal call-sites in repo. Wordt gepin'd om te detecteren dat
     * een refactor (raw-strings, helper-functies) de regex-coverage stilzwijgend
     * naar 0 brengt. Onder-grens — bij toevoeging van nieuwe call-sites verhogen.
     */
    companion object {
        private const val VERWACHT_AANTAL_CALLSITES = 12
    }

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

        assertTrue(ktBronnen.isNotEmpty(), "geen Kotlin-sources gevonden — repo-walk faalt vanuit ${System.getProperty("user.dir")}, root=$repoRoot")

        val schendingen = mutableListOf<String>()
        var totaalCallSites = 0
        ktBronnen.forEach { bron ->
            val inhoud = bron.readText()
            val callsites = vindCallSites(inhoud)
            totaalCallSites += callsites.size
            callsites.forEach { (offset, template) ->
                val schendingenInTemplate = checkInterpolaties(template)
                schendingenInTemplate.forEach { (ref) ->
                    val regelnummer = inhoud.substring(0, offset).count { it == '\n' } + 1
                    schendingen.add(
                        "${bron.relativeTo(repoRoot)}:$regelnummer interpoleert '\$$ref' " +
                            "in DomainValidationException-message — " +
                            "deze identifier staat op de REST-input-blocklist (PII/CRLF-risico)",
                    )
                }
            }
        }

        assertTrue(
            totaalCallSites >= VERWACHT_AANTAL_CALLSITES,
            "te weinig call-sites gevonden ($totaalCallSites < $VERWACHT_AANTAL_CALLSITES) — " +
                "regex-coverage mogelijk gebroken na refactor naar raw-strings of helper-functies. " +
                "Update VERWACHT_AANTAL_CALLSITES als bewust verminderd.",
        )

        assertTrue(
            schendingen.isEmpty(),
            "DomainValidationException-call-site invariant geschonden:\n" +
                schendingen.joinToString("\n"),
        )
    }

    /**
     * Vindt alle `requireValid(...) { "..." }`- en `throw DomainValidationException("...")`-
     * call-sites met balanced-paren-scan voor nested haakjes in de conditie.
     * Returnt list van (offset-in-source, message-template).
     */
    private fun vindCallSites(inhoud: String): List<Pair<Int, String>> {
        val resultaat = mutableListOf<Pair<Int, String>>()
        // requireValid(...) { "..." }
        val requireValidPositions = Regex("""\brequireValid\s*\(""").findAll(inhoud)
        requireValidPositions.forEach { m ->
            val openParenIdx = m.range.last
            val sluitParenIdx = vindBalancedClose(inhoud, openParenIdx, '(', ')') ?: return@forEach
            // Scan voorbij sluit-paren tot eerste '{' (witruimte mag ertussen).
            var i = sluitParenIdx + 1
            while (i < inhoud.length && inhoud[i].isWhitespace()) i++
            if (i >= inhoud.length || inhoud[i] != '{') return@forEach
            // i wijst nu naar '{'; pak eerste string-literal in body
            val template = pakEersteStringLiteral(inhoud, i + 1)
            if (template != null) resultaat.add(m.range.first to template)
        }
        // throw DomainValidationException("...") — FQN of import-vorm
        val throwPositions = Regex(
            """throw\s+(?:nl\.rijksoverheid\.moz\.fbs\.common\.exception\.)?DomainValidationException\s*\(""",
        ).findAll(inhoud)
        throwPositions.forEach { m ->
            val openParenIdx = m.range.last
            val template = pakEersteStringLiteral(inhoud, openParenIdx + 1)
            if (template != null) resultaat.add(m.range.first to template)
        }
        return resultaat
    }

    /**
     * Vindt de matching close-character voor het open-character op `openIdx`,
     * door een depth-counter te bijhouden. Returnt null als geen match (truncated source).
     */
    private fun vindBalancedClose(s: String, openIdx: Int, open: Char, close: Char): Int? {
        var depth = 1
        var i = openIdx + 1
        while (i < s.length) {
            when (s[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /**
     * Pakt de eerste `"..."`-string-literal die volgt op positie `start`,
     * ongeacht witruimte ervoor. Single-line only (Kotlin double-quoted string).
     * Multi-line raw-strings (`"""..."""`) worden niet gevangen — beperking
     * gedocumenteerd in class KDoc.
     */
    private fun pakEersteStringLiteral(s: String, start: Int): String? {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || s[i] != '"') return null
        // Skip openings-quote
        i++
        val builder = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                builder.append(c).append(s[i + 1])
                i += 2
            } else if (c == '"') {
                return builder.toString()
            } else if (c == '\n') {
                return null
            } else {
                builder.append(c)
                i++
            }
        }
        return null
    }

    /**
     * Controleert template op interpolaties met identifiers uit blocklist.
     * Returnt list van (verboden-ref-name).
     */
    private fun checkInterpolaties(template: String): List<Pair<String, String>> {
        val schendingen = mutableListOf<Pair<String, String>>()
        // Simpele $identifier
        simpelPattern.findAll(template).forEach { m ->
            val ref = m.groupValues[1]
            val laatsteSegment = ref.substringAfterLast(".").lowercase()
            if (laatsteSegment in verbodenRefs) {
                schendingen.add(ref to template)
            }
        }
        // Complexe ${...} — pak ALLE identifier-tokens binnen body
        complexPattern.findAll(template).forEach { m ->
            val body = m.groupValues[1]
            identifierPattern.findAll(body).forEach { id ->
                val ref = id.groupValues[1]
                val laatsteSegment = ref.lowercase()
                if (laatsteSegment in verbodenRefs) {
                    schendingen.add(ref to template)
                }
            }
        }
        return schendingen
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
            // Root-pom bevat een `<modules>`-blok (Maven multi-module marker);
            // child-poms hebben dat niet. Voorkomt dat de parent-`<artifactId>`-ref
            // in een module-pom abusievelijk als root wordt geïnterpreteerd.
            if (pom.exists() &&
                pom.readText().contains("<artifactId>moza-poc-fbs-berichtenbox</artifactId>") &&
                pom.readText().contains("<modules>")
            ) {
                return dir
            }
            dir = dir.parentFile ?: error("repo-root niet gevonden vanuit ${System.getProperty("user.dir")}")
        }
        error("repo-root niet gevonden binnen 10 niveaus boven ${System.getProperty("user.dir")}")
    }
}
