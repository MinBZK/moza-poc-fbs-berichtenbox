package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant
import java.util.UUID

/**
 * Leesstatus van een bericht. De wire-/cache-/RediSearch-representatie blijft exact de
 * bestaande lowercase strings `"gelezen"`/`"ongelezen"`; alleen het in-memory type is
 * getypeerd zodat onbekende waarden niet ongemerkt door de domeingrens lekken.
 */
enum class Leesstatus(@get:JsonValue val wire: String) {
    GELEZEN("gelezen"),
    ONGELEZEN("ongelezen");

    companion object {
        // Case-insensitive zodat upstream-callers die de wire-waarde in een andere
        // casing aanleveren (bv. een geüpperde enum-naam) op de canonieke vorm landen.
        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): Leesstatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Onbekende leesstatus: '$value'")
    }
}

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
    val status: Leesstatus? = null,
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
        // MAX_BIJLAGEN is een zelfstandige defensieve grens tegen pathologische/kwaadaardige
        // input — de magazijn-spec legt géén maxItems op `bijlagen` op. Alleen MAP_MAX_LENGTE
        // (1..64) spiegelt de magazijn-spec (`map` maxLength 64).
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
