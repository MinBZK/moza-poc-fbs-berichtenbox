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
 */
internal enum class MagazijnFault { TIMEOUT, MALFORMED, OVERFLOW, HTTP_5XX, HTTP_4XX, NETWORK, INTERNAL_BUG, CIRCUIT_OPEN }

/**
 * Of een fault als availability-storing telt voor de per-magazijn circuit breaker: alleen
 * transient-onbeschikbaarheid (timeout, 5xx, netwerk) opent het circuit. Config-/contract-
 * fouten (4xx), data-issues (malformed/overflow), eigen bugs en de breaker-eigen CIRCUIT_OPEN
 * tellen NIET mee — anders zou een blijvende 4xx of een eenmalige bug het magazijn onnodig
 * uitsluiten. Eén bron van waarheid zodat service en breaker-registratie niet uiteenlopen.
 */
internal val MagazijnFault.teltAlsStoring: Boolean
    get() = when (this) {
        MagazijnFault.TIMEOUT, MagazijnFault.HTTP_5XX, MagazijnFault.NETWORK -> true
        MagazijnFault.MALFORMED, MagazijnFault.OVERFLOW, MagazijnFault.HTTP_4XX,
        MagazijnFault.INTERNAL_BUG, MagazijnFault.CIRCUIT_OPEN,
        -> false
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
