package nl.rijksoverheid.moz.fbs.common

/**
 * De security-headers die op élke response horen te staan, en hun waarde per pad.
 *
 * Framework-vrij en puur: alleen tekst in, tekst uit. Het plaatsen gebeurt in de
 * `SecurityHeadersRegistratie` van elke dienst; hier staat wát er komt te staan en waarom.
 *
 * De waarden volgen de NCSC ICT-beveiligingsrichtlijnen voor webapplicaties (juli 2024),
 * U/PW.03 maatregel 04 "Stel alle securityheaders in volgens een baseline", en zijn
 * gelijk aan wat internet.nl als goed beoordeelt.
 */
object SecurityHeaders {

    const val STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security"
    const val X_FRAME_OPTIONS = "X-Frame-Options"
    const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
    const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"
    const val REFERRER_POLICY = "Referrer-Policy"
    const val CACHE_CONTROL = "Cache-Control"

    /**
     * `no-store` als de response zelf niets zegt: de API-antwoorden dragen
     * persoonsgebonden gegevens, en een endpoint dat bewust cacheable is zet zijn eigen
     * waarde die dan blijft staan.
     */
    const val CACHE_CONTROL_DEFAULT = "no-store"

    /**
     * De volledige baseline. `default-src 'none'` kan omdat een API-response geen
     * subresources laadt: er is geen script, stylesheet of afbeelding die de browser
     * namens dit antwoord zou moeten ophalen. `base-uri` en `form-action` staan er
     * omdat de baseline ze noemt en `default-src` ze niet afdekt.
     */
    private const val CSP_STRIKT = "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    /**
     * Achter het non-application root-pad (standaard `/q`) staan Swagger UI, de dev-UI en
     * de health-endpoints. Dat zijn wél HTML-pagina's die hun eigen script en stylesheet
     * laden, dus `default-src 'none'` zou ze breken. Deze paden dragen geen berichten of
     * identificatienummers, dus de winst van de strengere policy is daar klein en het
     * risico van een stukke Swagger UI groot. De clickjacking-bescherming blijft wél staan.
     */
    private const val CSP_BEHEERPAD = "frame-ancestors 'none'"

    /**
     * Maakt van de geconfigureerde beheerpad-root een absoluut pad.
     *
     * Quarkus levert `quarkus.http.non-application-root-path` terug zoals het is
     * geconfigureerd, en de standaardwaarde is relatief: `q`, niet `/q`. Relatief betekent
     * "onder [rootPad]". Zonder deze omzetting vergelijk je `/q/health` met `q` en valt
     * elk beheerpad stilzwijgend aan de strenge kant van de grens — wat een lege Swagger
     * UI oplevert zonder dat iets een fout meldt.
     *
     * Een lege beheerpad-root levert een lege string op en nooit het root-pad zelf. Zou hij
     * `/` of `/app/` teruggeven, dan hangt élk pad eronder en kreeg de hele API de losse
     * policy — de omgekeerde fout, en de gevaarlijke van de twee.
     */
    fun beheerpadRoot(rootPad: String, nonApplicationRootPad: String): String {
        val root = nonApplicationRootPad.removeSuffix("/")

        if (root.isEmpty()) return ""

        if (root.startsWith("/")) return root

        return "${rootPad.removeSuffix("/")}/$root"
    }

    /**
     * De headers voor [pad], met [beheerpadRoot] als grens tussen de API en de
     * beheerpaden. Alles buiten die grens — de API zelf, maar ook `/openapi.json`, dat
     * bewust op de root staat — krijgt de strenge policy.
     *
     * `Cache-Control` zit er niet in: die kan per endpoint verschillen en wordt daarom
     * alleen gezet als de response er zelf geen heeft. Zie [CACHE_CONTROL_DEFAULT].
     */
    fun voorPad(pad: String, beheerpadRoot: String): Map<String, String> {
        val beheerpad = isBeheerpad(pad, beheerpadRoot)

        return mapOf(
            STRICT_TRANSPORT_SECURITY to "max-age=31536000; includeSubDomains; preload",
            X_FRAME_OPTIONS to "DENY",
            X_CONTENT_TYPE_OPTIONS to "nosniff",
            CONTENT_SECURITY_POLICY to if (beheerpad) CSP_BEHEERPAD else CSP_STRIKT,
            REFERRER_POLICY to "no-referrer",
        )
    }

    /**
     * Een pad hoort bij het beheerpad wanneer het de root zelf is of eronder hangt.
     * De segmentgrens is de reden dat dit geen kale `startsWith` is: `/qux` begint met
     * `/q` maar is een ander pad, en zou anders stilzwijgend de losse policy krijgen.
     */
    private fun isBeheerpad(pad: String, beheerpadRoot: String): Boolean {
        val root = beheerpadRoot.removeSuffix("/")

        if (root.isEmpty() || root == "/") return false

        return pad == root || pad.startsWith("$root/")
    }
}
