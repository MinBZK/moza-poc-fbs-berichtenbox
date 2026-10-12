package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

/**
 * Idempotentie-marker voor verwerkte CloudEvents, op basis van het CloudEvents
 * `id` binnen zijn magazijn: een CloudEvents-`id` is alleen uniek binnen zijn bron,
 * en twee organisaties kunnen hetzelfde id kiezen. Een webhook-concern (niet de berichten-cache), bewust los van de
 * `Sessiecache`-facade met een eigen Redis-keyspace.
 */
interface AanmeldDeduplicatie {

    /**
     * Markeert [eventId] van [magazijnId] als gezien en geeft `true` als dit de éérste keer is.
     * `false` betekent: al eerder verwerkt (duplicaat) — overslaan. Atomair, zodat
     * gelijktijdige dubbele afleveringen er hooguit één als eerste zien.
     */
    fun eerstgezien(magazijnId: String, eventId: String): Boolean

    /**
     * Verwijdert de marker, zodat een event dat door een tijdelijke fout niet kon
     * worden verwerkt bij een latere her-aflevering opnieuw wordt opgepakt.
     */
    fun verwijder(magazijnId: String, eventId: String)
}
