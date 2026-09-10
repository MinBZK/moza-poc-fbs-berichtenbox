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
    const val CONTENT_DISPOSITION = "Content-Disposition"

    /** De dispositie waarmee `BijlageContentDisposition` een toonbare bijlage aankondigt. */
    private const val INLINE = "inline"

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
     * De policy voor een bijlage die veilig te tonen is. Twee dingen wijken af.
     *
     * `frame-ancestors 'self'` laat een berichtenbox die de keten server-side aanroept de
     * bijlage in een ingesloten viewer tonen. Dat kan alleen omdat zo'n berichtenbox het
     * bijlage-adres onder zijn éigen origin uitserveert (zijn proxy zet de ontvanger uit
     * het cookie om in de header die de keten eist), dus `'self'` dekt precies dat geval —
     * zonder ooit een vreemde origin te hoeven noemen. Een benoemde origin zou bovendien
     * de toets van internet.nl niet halen: die accepteert alleen `'self'` en `'none'`.
     *
     * `img-src`/`object-src 'self'` staan er omdat `default-src 'none'` niet alleen
     * scripts blokkeert. Navigeert een browser top-level naar een afbeelding, dan bouwt
     * hij daar een document omheen en valt het plaatje zelf onder `img-src`; bij een PDF
     * doet de ingebouwde viewer iets vergelijkbaars via `object-src`. Zonder deze twee kan
     * de weergave leeg blijven — de bytes zijn er dan wel, maar je ziet niets.
     *
     * Wat blijft staan is wat ertoe doet: geen `script-src`, dus een aangeleverd bestand
     * dat de browser tóch als HTML zou lezen voert niets uit.
     */
    private const val CSP_BIJLAGE_INLINE =
        "default-src 'none'; img-src 'self'; object-src 'self'; base-uri 'none'; " +
            "form-action 'none'; frame-ancestors 'self'"

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
     * De headers voor een response die als `inline` de deur uit gaat, of `null` wanneer
     * dat niet zo is en de gewone [voorPad]-waarden blijven staan.
     *
     * De beslissing hangt aan de `Content-Disposition` die de response al draagt, en niet
     * aan het pad of aan een aparte vlag. Dat is met opzet: `inline` en "mag in een frame"
     * horen dezelfde verzameling te zijn — de typen waarvan een browser de weergave
     * afhandelt zonder aangeleverde code uit te voeren. Eén bron, dus ze kunnen niet uit
     * elkaar lopen. Staat er `attachment`, dan valt er niets te tonen en dus ook niets te
     * framen, en blijft `DENY` staan.
     *
     * Die koppeling is breder dan alleen bijlagen: élk endpoint dat ooit
     * `Content-Disposition: inline` zet — een export, een gegenereerd document — krijgt
     * hiermee ook het frame-recht, en op een beheerpad overschrijft dat de strengere
     * `frame-ancestors 'none'`. Zet die dispositie dus niet lichtvaardig.
     */
    fun voorInlineBijlage(contentDisposition: String?): Map<String, String>? {
        if (!isInline(contentDisposition)) return null

        return mapOf(
            X_FRAME_OPTIONS to "SAMEORIGIN",
            CONTENT_SECURITY_POLICY to CSP_BIJLAGE_INLINE,
        )
    }

    /**
     * De dispositie is een token, gevolgd door het eind van de waarde of een `;` met de
     * bestandsnaam erachter. De grens hoort er dus bij: zonder die eis zou `inlineaardig`
     * meetellen, en versmalt een naam-achtige waarde de frame-headers.
     *
     * Bewust hoofdlettergevoelig, hoewel RFC 6266 de dispositie case-insensitive noemt.
     * De waarde die hier gelezen wordt is er één die we zélf zetten, altijd in kleine
     * letters. Zo kan alleen onze eigen dispositie de versmalling aanzetten, en niet een
     * component die er ooit een anders geschreven waarde neerzet.
     */
    private fun isInline(contentDisposition: String?): Boolean =
        contentDisposition == INLINE || contentDisposition?.startsWith("$INLINE;") == true

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
