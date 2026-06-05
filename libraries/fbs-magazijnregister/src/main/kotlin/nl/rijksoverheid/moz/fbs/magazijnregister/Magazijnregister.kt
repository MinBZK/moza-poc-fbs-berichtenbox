package nl.rijksoverheid.moz.fbs.magazijnregister

import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import java.net.URI

/**
 * Eén ingeschreven berichtenmagazijn: de koppeling tussen een deelnemende
 * organisatie ([oin]) en het endpoint van haar magazijn.
 *
 * In het Federatief Berichtenstelsel hoort bij één organisatie precies één
 * magazijn; het `magazijnId` dat door DTO's en SSE-output stroomt is daarom
 * de OIN-waarde zelf ([Oin.waarde] — publiek, geen PII).
 */
data class Magazijninschrijving(
    val oin: Oin,
    val url: URI,
    val naam: String?,
)

/**
 * Register dat per deelnemende organisatie (OIN) bijhoudt welk
 * berichtenmagazijn erbij hoort. De koppeling leeft hier — los van de
 * sessiecache (die magazijnen *bevraagt*) en de uitvraag-service (die
 * *routeert*) — zodat het productie-pad (beheer-interface + database-opslag)
 * die consumenten niet raakt. Nu config-backed; zie [Magazijninschrijving]
 * voor de identiteitsconventie `magazijnId == oin.waarde`.
 */
interface Magazijnregister {

    /** Alle ingeschreven magazijnen — de sessiecache bouwt hieruit zijn REST-clients. */
    fun alle(): Collection<Magazijninschrijving>

    /** Inschrijving voor [oin], of null als die OIN geen magazijn heeft (drift/onbekend). */
    fun voorOin(oin: Oin): Magazijninschrijving?
}
