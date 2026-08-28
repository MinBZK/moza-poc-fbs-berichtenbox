package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import nl.rijksoverheid.moz.fbs.magazijnsimulator.ApiInfo

/**
 * De vorm van het pad waarop een gesimuleerd magazijn bereikbaar is: `/magazijn/<OIN>/api/v1/…`.
 *
 * Het herkennen van dat prefix ([oinUit]), het weghalen ervan ([padNaPrefix]) en het terugzetten
 * ervan in de HAL-links ([basisUri]) staan hier naast elkaar, en niet elk bij hun eigen aanroeper.
 * Lopen ze uiteen, dan komt een request wél bij het juiste magazijn uit terwijl de links naar een
 * adres wijzen dat niet bestaat — en dat merkt niemand tot een client zo'n link volgt.
 *
 * Het prefix is de OIN en geen verzonnen kortcode: die OIN stroomt toch al door de hele keten en
 * staat als sleutel in het register van de uitvraag, dus de configuratieregel wordt
 * zelfbeschrijvend en sleutel en pad-segment kunnen per constructie niet uit elkaar lopen. Het
 * vaste woord `magazijn` ervóór maakt "hoort dit bij een magazijn?" letterlijk beantwoordbaar in
 * plaats van een gok over de vórm van het eerste segment, en houdt de root vrij voor paden die géén
 * magazijn zijn.
 */
object MagazijnPad {

    const val SEGMENT = "magazijn"

    /** Hoe een geldig pad eruitziet; voor in een foutmelding. */
    val VORM: String = "/$SEGMENT/<OIN>${ApiInfo.BASE_PATH}/…"

    private val ROOT = "/$SEGMENT/"
    private val API_PREFIX = "${ApiInfo.BASE_PATH}/"

    /**
     * De OIN uit het pad, of `null` als dit geen magazijn-pad is. Er hoort minstens één segment ná
     * het base-path te staan: `/magazijn/<OIN>/api/v1/` op zichzelf adresseert geen operatie.
     *
     * Werkt op het gedecodeerde pad; Quarkus REST biedt geen onbewerkte variant aan
     * (`UriInfo.getPath(false)` gooit). Dat maakt hier niets uit: een percent-gecodeerd segment kan
     * na decodering hooguit extra scheidingstekens opleveren, en dat leidt tot een OIN die niet in
     * de set staat of een restpad dat geen resource matcht — in beide gevallen een 404, nooit een
     * ánder magazijn.
     */
    fun oinUit(pad: String): String? {
        val volledig = genormaliseerd(pad)

        if (!volledig.startsWith(ROOT)) return null

        val naRoot = volledig.substring(ROOT.length)
        val oin = naRoot.substringBefore('/', missingDelimiterValue = "")

        if (oin.isEmpty()) return null

        val rest = naRoot.substring(oin.length)

        if (!rest.startsWith(API_PREFIX) || rest.length == API_PREFIX.length) return null

        return oin
    }

    /** Het pad zoals de gegenereerde resources het kennen: alles ná `/magazijn/<OIN>/api/v1`. */
    fun padNaPrefix(pad: String, oin: String): String =
        genormaliseerd(pad).removePrefix("$ROOT$oin${ApiInfo.BASE_PATH}")

    /**
     * De basis waarop de HAL-links van dít magazijn worden gebouwd.
     *
     * Dit kán niet uit `UriInfo.baseUri` komen. Het ontwerp ging ervan uit dat het prefix daarin te
     * bewaren viel met de twee-argument-vorm van `ContainerRequestContext.setRequestUri`, maar
     * Quarkus REST gebruikt die `baseUri` uitsluitend om een relatieve request-URI mee op te lossen
     * en bewaart hem niet: `UriInfo.baseUri` blijft de root van de applicatie. Het prefix hoort
     * daarom hier terug in de links, op één plek, naast de code die het eraf haalt.
     */
    fun basisUri(uriInfo: UriInfo, oin: String): UriBuilder =
        uriInfo.baseUriBuilder.path(SEGMENT).path(oin).path(ApiInfo.BASE_PATH)

    private fun genormaliseerd(pad: String): String = "/" + pad.trimStart('/')
}
