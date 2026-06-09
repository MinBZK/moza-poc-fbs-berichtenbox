package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht

internal sealed class MagazijnResult {
    abstract val magazijnId: String
    abstract val naam: String?

    data class Success(
        override val magazijnId: String,
        override val naam: String?,
        val berichten: List<Bericht>,
    ) : MagazijnResult() {
        init {
            require(magazijnId.isNotBlank()) { "magazijnId mag niet leeg zijn" }
        }
    }

    data class Failure(
        override val magazijnId: String,
        override val naam: String?,
        val error: Throwable,
        // Classificatie eenmaal bepaald in service-laag en hier vastgezet zodat
        // downstream-mapping niet opnieuw classificeert (voorkomt drift).
        val fault: MagazijnFault,
    ) : MagazijnResult() {
        init {
            require(magazijnId.isNotBlank()) { "magazijnId mag niet leeg zijn" }
        }
    }
}

/**
 * Classificatie van een magazijn-fout. Bepaalt log-niveau (errorf vs warnf voor
 * alert-routing) en eindgebruiker-foutmelding. Top-level zodat zowel service als
 * MagazijnResult op één enum kunnen leunen.
 *
 * [CIRCUIT_OPEN]: de per-magazijn circuit breaker is open na herhaalde storingen, dus
 * de call is bewust overgeslagen (snelle fail i.p.v. wachten op een timeout). Geen
 * resultaat van een echte call, dus nooit door `classifyMagazijnFault` geproduceerd.
 *
 * [OVERBELAST]: het concurrency-bulkhead ([MagazijnAggregatieBulkhead]) zat vol — er was geen
 * vrije permit, dus het magazijn is niet eens bevraagd. Geen uitspraak over de beschikbaarheid
 * van dít magazijn (de saturatie komt typisch door een ánder, traag magazijn), dus telt niet als
 * storing én niet als succes.
 */
internal enum class MagazijnFault {
    TIMEOUT, MALFORMED, OVERFLOW, HTTP_5XX, HTTP_4XX, NETWORK, INTERNAL_BUG, CIRCUIT_OPEN, OVERBELAST
}

/**
 * Of een fault als availability-storing telt voor de per-magazijn circuit breaker: alleen
 * transient-onbeschikbaarheid (timeout, 5xx, netwerk) opent het circuit. Config-/contract-
 * fouten (4xx), data-issues (malformed/overflow), eigen bugs, de breaker-eigen CIRCUIT_OPEN
 * en bulkhead-OVERBELAST tellen NIET mee — anders zou een blijvende 4xx, een eenmalige bug of een
 * door een ánder magazijn veroorzaakte saturatie dít magazijn onnodig uitsluiten. Eén bron van
 * waarheid zodat service en breaker-registratie niet uiteenlopen.
 */
internal val MagazijnFault.teltAlsStoring: Boolean
    get() = when (this) {
        MagazijnFault.TIMEOUT, MagazijnFault.HTTP_5XX, MagazijnFault.NETWORK -> true
        MagazijnFault.MALFORMED, MagazijnFault.OVERFLOW, MagazijnFault.HTTP_4XX,
        MagazijnFault.INTERNAL_BUG, MagazijnFault.CIRCUIT_OPEN, MagazijnFault.OVERBELAST,
        -> false
    }

/**
 * Of de call het magazijn daadwerkelijk bereikte. CIRCUIT_OPEN (overgeslagen) en OVERBELAST
 * (bulkhead vol vóór de call) deden geen uitspraak over het magazijn — alle overige faults zijn
 * uitkomsten van een échte call. Exhaustief (geen `else`) zodat een nieuwe fault niet stilzwijgend
 * als "bereikt" of "niet bereikt" wordt geclassificeerd: de auteur moet expliciet kiezen.
 */
internal val MagazijnFault.magazijnBereikt: Boolean
    get() = when (this) {
        MagazijnFault.OVERBELAST, MagazijnFault.CIRCUIT_OPEN -> false
        MagazijnFault.TIMEOUT, MagazijnFault.MALFORMED, MagazijnFault.OVERFLOW,
        MagazijnFault.HTTP_5XX, MagazijnFault.HTTP_4XX, MagazijnFault.NETWORK,
        MagazijnFault.INTERNAL_BUG,
        -> true
    }

/** Wat de uitkomst van een echte aggregatie-call met de per-magazijn circuit breaker doet. */
internal enum class CircuitActie { MELD_SUCCES, MELD_FOUT, MELD_ONBESLIST }

/**
 * Vertaalt een [MagazijnResult] naar de circuit-actie. Pure functie (los testbaar) zodat de
 * half-open-afronding voor élke fault-categorie gepind is: een afgeronde call MOET altijd een
 * terminale actie geven, anders blijft een half-open probe hangen en zit het circuit permanent
 * open. Een call die het magazijn niet bereikte ([magazijnBereikt] = false: OVERBELAST/CIRCUIT_OPEN)
 * geeft alleen de probe vrij zonder de fouten-teller te raken; een availability-storing opent het;
 * een functioneel antwoord (succes óf een niet-storing-fout zoals 4xx/malformed: het magazijn ís
 * bereikbaar) sluit het. Via [magazijnBereikt]/[teltAlsStoring] i.p.v. een `else`, zodat een nieuwe
 * fault niet stil in een verkeerde actie valt (bv. een niet-bereikt-fault die het circuit sluit).
 */
internal fun circuitActieVoor(result: MagazijnResult): CircuitActie = when (result) {
    is MagazijnResult.Success -> CircuitActie.MELD_SUCCES
    is MagazijnResult.Failure -> when {
        !result.fault.magazijnBereikt -> CircuitActie.MELD_ONBESLIST
        result.fault.teltAlsStoring -> CircuitActie.MELD_FOUT
        else -> CircuitActie.MELD_SUCCES
    }
}

/**
 * Marker-exception voor de availability-cap op magazijn-responses (zie
 * `berichtensessiecache.max-berichten-per-magazijn`). Geen subclass van
 * `WebApplicationException`/`ProcessingException` — dit is een interne signalering,
 * geen upstream-fault, en wordt door de service in een aparte foutmelding gemapt.
 */
internal class MagazijnResponseOverflow(message: String) : RuntimeException(message)

/**
 * Marker-exception voor een door de circuit breaker overgeslagen magazijn-call. Draagt de
 * `magazijnId` als `error`-veld van de [MagazijnResult.Failure]; nooit een echte upstream-fout.
 */
internal class MagazijnCircuitOpenException(magazijnId: String) :
    RuntimeException("Magazijn '$magazijnId' tijdelijk overgeslagen: circuit open na herhaalde storingen")

/**
 * Marker-exception voor een door het concurrency-bulkhead afgewezen magazijn-call: er was geen
 * vrije permit ([MagazijnAggregatieBulkhead] vol), dus de call is niet gestart. Draagt de
 * `magazijnId` als `error`-veld van de [MagazijnResult.Failure]; nooit een echte upstream-fout.
 */
internal class MagazijnOverbelastException(magazijnId: String) :
    RuntimeException("Magazijn '$magazijnId' tijdelijk afgewezen: aggregatie-bulkhead vol")
