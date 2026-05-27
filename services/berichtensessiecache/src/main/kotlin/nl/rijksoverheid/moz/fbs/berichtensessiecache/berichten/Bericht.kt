package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import java.time.Instant
import java.util.UUID

data class Bericht(
    val berichtId: UUID,
    val afzender: String,
    val ontvanger: String,
    val onderwerp: String,
    val inhoud: String,
    val publicatietijdstip: Instant,
    val magazijnId: String,
    val aantalBijlagen: Int,
    val bijlagen: List<BijlageSamenvatting> = emptyList(),
    val map: String? = null,
    val status: String? = null,
) {
    init {
        require(afzender.isNotBlank()) { "afzender mag niet leeg zijn" }
        require(ontvanger.isNotBlank()) { "ontvanger mag niet leeg zijn" }
        require(onderwerp.isNotBlank()) { "onderwerp mag niet leeg zijn" }
        // `inhoud` mag wel leeg-string zijn (bewuste keuze: backwards-compat met oude
        // cache-entries en met magazijnen die nog geen inhoud meeleveren). De OpenAPI-spec
        // markeert het wél als required zodat nieuwe consumers altijd een waarde meekrijgen.
        require(magazijnId.isNotBlank()) { "magazijnId mag niet leeg zijn" }
        require(aantalBijlagen >= 0) { "aantalBijlagen mag niet negatief zijn" }
        require(bijlagen.size <= MAX_BIJLAGEN) { "Maximaal $MAX_BIJLAGEN bijlagen per bericht" }
        map?.let { require(it.length in 1..MAP_MAX_LENGTE) { "map-naam moet 1..$MAP_MAX_LENGTE tekens zijn" } }
    }

    companion object {
        // Caps in lijn met magazijn-spec (max 100 bijlagen, map 1..64 tekens).
        const val MAX_BIJLAGEN = 100
        const val MAP_MAX_LENGTE = 64
    }
}

data class BijlageSamenvatting(
    val bijlageId: UUID,
    val naam: String,
) {
    init {
        require(naam.isNotBlank()) { "bijlage-naam mag niet leeg zijn" }
        require(naam.length <= NAAM_MAX_LENGTE) { "bijlage-naam mag maximaal $NAAM_MAX_LENGTE tekens zijn" }
    }

    companion object {
        const val NAAM_MAX_LENGTE = 255
    }
}
