package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.requireValid

/**
 * Polymorfe identificatie van een natuurlijk persoon of organisatie in FBS.
 * Drie varianten, onderscheiden op lengte (8/9/20 cijfers).
 *
 * De afzender in een bericht is altijd een [Oin]; de ontvanger kan elk type zijn.
 * Gebruik [parse] voor een generieke factory vanuit string input.
 */
sealed interface Identificatienummer {
    val waarde: String

    companion object {
        /**
         * Herkent het type aan de lengte van de string (alleen cijfers toegestaan).
         *  - 8 cijfers  → [Kvk]
         *  - 9 cijfers  → [Bsn] (met elfproef)
         *  - 20 cijfers → [Oin]
         */
        fun parse(waarde: String): Identificatienummer {
            val genormaliseerd = waarde.trim()
            return when (genormaliseerd.length) {
                KVK_LENGTE -> Kvk(genormaliseerd)
                BSN_LENGTE -> Bsn(genormaliseerd)
                OIN_LENGTE -> Oin(genormaliseerd)
                else -> throw DomainValidationException(
                    "Identificatienummer moet $KVK_LENGTE (KVK), $BSN_LENGTE (BSN) of $OIN_LENGTE (OIN) cijfers zijn",
                )
            }
        }

        private const val KVK_LENGTE = 8
        private const val BSN_LENGTE = 9
        private const val OIN_LENGTE = 20
    }
}

/** Organisatie-identificatienummer van Logius (20 cijfers). */
@JvmInline
value class Oin(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "OIN moet precies 20 cijfers zijn" }
    }

    companion object {
        private val PATTERN = Regex("^[0-9]{20}$")
    }
}

/** KvK-nummer (8 cijfers). */
@JvmInline
value class Kvk(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "KVK-nummer moet precies 8 cijfers zijn" }
    }

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

    companion object {
        private val PATTERN = Regex("^[0-9]{9}$")

        // Standaard BSN-elfproef: posities 1-8 met gewichten 9..2, positie 9 met gewicht -1.
        // Som modulo 11 moet 0 zijn.
        private val WEIGHTS = intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, -1)

        private fun isValidElfproef(waarde: String): Boolean {
            val som = waarde.mapIndexed { i, c -> (c - '0') * WEIGHTS[i] }.sum()
            return som % 11 == 0
        }
    }
}
