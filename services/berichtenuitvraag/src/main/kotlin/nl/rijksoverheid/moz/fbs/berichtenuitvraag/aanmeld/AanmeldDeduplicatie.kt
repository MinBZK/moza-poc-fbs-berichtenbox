package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

/**
 * Idempotentie-marker voor verwerkte CloudEvents, op basis van het CloudEvents-`id`
 * per magazijn. Twee organisaties kunnen hetzelfde id kiezen; het magazijn is hier het
 * magazijn dat de uitvraag uit de afzender afleidt, niet de vrije `source` uit de
 * envelop. Een webhook-concern (niet de berichten-cache), bewust los van de
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
