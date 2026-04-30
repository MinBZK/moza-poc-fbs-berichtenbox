package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.requireValid

/**
 * Polymorfe identificatie van een natuurlijk persoon of organisatie in FBS.
 *
 * Type wordt expliciet meegegeven door de caller (zie [of]), niet afgeleid uit de
 * lengte — dat voorkomt dat bv. een RSIN als BSN wordt geclassificeerd (beide 9
 * cijfers) of dat leading-zero-verlies een BSN stilletjes in een KvK-nummer
 * verandert. De afzender in een bericht is altijd een [Oin]; de ontvanger kan
 * elk type zijn.
 */
sealed interface Identificatienummer {
    val type: IdentificatienummerType
    val waarde: String

    companion object {
        /**
         * Bouwt een getypeerd [Identificatienummer] uit een expliciete type-keuze en waarde.
         * Gooit [DomainValidationException] als de waarde niet aan de type-invarianten
         * voldoet (lengte, cijfers-only, elfproef voor BSN/RSIN, niet-geheel-nullen).
         *
         * Geen impliciete trim of normalisatie: aanvullende whitespace is een clientfout.
         * Dit voorkomt asymmetrie tussen `of(type, "  …  ")` en de directe value-class
         * constructors (`Bsn(...)`) — die accepteren whitespace ook niet.
         */
        fun of(type: IdentificatienummerType, waarde: String): Identificatienummer = when (type) {
            IdentificatienummerType.BSN -> Bsn(waarde)
            IdentificatienummerType.RSIN -> Rsin(waarde)
            IdentificatienummerType.KVK -> Kvk(waarde)
            IdentificatienummerType.OIN -> Oin(waarde)
        }
    }
}

enum class IdentificatienummerType { BSN, RSIN, KVK, OIN }

/** Organisatie-identificatienummer van Logius (20 cijfers). */
@JvmInline
value class Oin(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "OIN moet precies 20 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "OIN kan niet geheel uit nullen bestaan" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.OIN

    companion object {
        private val PATTERN = Regex("^[0-9]{20}$")
    }
}

/** KvK-nummer (8 cijfers). */
@JvmInline
value class Kvk(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "KVK-nummer moet precies 8 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "KVK-nummer kan niet geheel uit nullen bestaan" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.KVK

    companion object {
        private val PATTERN = Regex("^[0-9]{8}$")
    }
}

/** Burgerservicenummer (9 cijfers, gevalideerd met elfproef). */
@JvmInline
value class Bsn(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "BSN moet precies 9 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "BSN kan niet geheel uit nullen bestaan" }
        requireValid(isValidElfproef(waarde)) { "BSN voldoet niet aan elfproef" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.BSN

    companion object {
        private val PATTERN = Regex("^[0-9]{9}$")
    }
}

/** Rechtspersonen en Samenwerkingsverbanden Informatie Nummer (9 cijfers, elfproef). */
@JvmInline
value class Rsin(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "RSIN moet precies 9 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "RSIN kan niet geheel uit nullen bestaan" }
        requireValid(isValidElfproef(waarde)) { "RSIN voldoet niet aan elfproef" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.RSIN

    companion object {
        private val PATTERN = Regex("^[0-9]{9}$")
    }
}

// Standaard elfproef voor BSN en RSIN: posities 1-8 met gewichten 9..2,
// positie 9 met gewicht -1. Som modulo 11 moet 0 zijn.
private val ELFPROEF_WEIGHTS = intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, -1)

private fun isValidElfproef(waarde: String): Boolean {
    val som = waarde.mapIndexed { i, c -> (c - '0') * ELFPROEF_WEIGHTS[i] }.sum()
    return som % 11 == 0
}
