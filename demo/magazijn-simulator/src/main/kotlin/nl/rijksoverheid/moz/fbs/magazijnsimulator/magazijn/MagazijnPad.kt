package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import nl.rijksoverheid.moz.fbs.magazijnsimulator.ApiInfo
import java.net.URI

/**
 * De vorm van het pad waarop een gesimuleerd magazijn bereikbaar is: `/magazijn/<OIN>/api/v1/…`.
 *
 * Het herkennen van dat prefix ([oinUit]), het weghalen ervan ([zonderPrefix]) en het terugzetten
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
 *
 * [oinUit] krijgt het gedecodeerde pad — Quarkus REST biedt geen onbewerkte variant aan
 * (`UriInfo.getPath(false)` gooit) — en [zonderPrefix] werkt op de onbewerkte request-URI, zodat een
 * gecodeerd segment onderweg niet van betekenis verandert. Voor het prefix zelf maakt dat verschil
 * niets uit: `/magazijn/` en `/api/v1/` bevatten geen tekens die gecodeerd worden. Waar het wél
 * uiteenloopt, knipt [padNaPrefix] niets weg en matcht er geen enkele resource — dus 404, nooit een
 * ánder magazijn.
 */
object MagazijnPad {

    const val SEGMENT = "magazijn"

    /**
     * De root van het beheerpad. Dat pad hoort bij de simulator zelf en niet bij één magazijn: het is
     * er om demo's te vullen, terug te zetten en bij te sturen. Vandaar dat het buiten de
     * magazijn-routering valt — en buiten de simulatie, want een magazijn dat kapot is gezet moet te
     * repareren blijven.
     */
    const val BEHEER_SEGMENT = "beheer"

    /** Hoe een geldig pad eruitziet; voor in een foutmelding. */
    val VORM: String = "/$SEGMENT/<OIN>${ApiInfo.BASE_PATH}/…"

    private val ROOT = "/$SEGMENT/"
    private val API_PREFIX = "${ApiInfo.BASE_PATH}/"

    /** Of dit pad bij het beheerpad hoort in plaats van bij een magazijn. */
    fun isBeheerPad(pad: String): Boolean = genormaliseerd(pad).startsWith("/$BEHEER_SEGMENT/")

    /**
     * De OIN uit het pad, of `null` als dit geen magazijn-pad is. Er hoort minstens één segment ná
     * het base-path te staan: `/magazijn/<OIN>/api/v1/` op zichzelf adresseert geen operatie.
     */
    fun oinUit(onbewerktPad: String): String? {
        val volledig = genormaliseerd(onbewerktPad)

        if (!volledig.startsWith(ROOT)) return null

        val naRoot = volledig.substring(ROOT.length)
        val oin = naRoot.substringBefore('/', missingDelimiterValue = "")

        if (oin.isEmpty()) return null

        val rest = naRoot.substring(oin.length)

        if (!rest.startsWith(API_PREFIX) || rest.length == API_PREFIX.length) return null

        return oin
    }

    /**
     * Dezelfde request-URI, met het magazijn-prefix eraf, zodat de gegenereerde resources hem
     * herkennen.
     *
     * De URI wordt met de hand samengesteld in plaats van via `UriBuilder.replacePath`: die leest
     * zijn argument als URI-template en gooit op accolades, en de query zou opnieuw gecodeerd worden
     * — wat de betekenis van een al gecodeerde query kan veranderen. Gooit alsnog bij een pad waar
     * geen geldige URI van te maken is; de aanroeper vertaalt dat naar een 404.
     */
    fun zonderPrefix(requestUri: URI, oin: String): URI {
        val rest = padNaPrefix(requestUri.rawPath, oin)
        val query = requestUri.rawQuery?.let { "?$it" }.orEmpty()

        return URI("${requestUri.scheme}://${requestUri.rawAuthority}$rest$query")
    }

    /** Het pad zoals de gegenereerde resources het kennen: alles ná `/magazijn/<OIN>/api/v1`. */
    internal fun padNaPrefix(onbewerktPad: String, oin: String): String =
        genormaliseerd(onbewerktPad).removePrefix("$ROOT$oin${ApiInfo.BASE_PATH}")

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
