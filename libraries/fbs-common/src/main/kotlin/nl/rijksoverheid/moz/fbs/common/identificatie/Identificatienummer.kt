package nl.rijksoverheid.moz.fbs.common.identificatie

import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.exception.requireValid
import java.security.MessageDigest

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

    /**
     * Canonical string-representatie `<TYPE>:<WAARDE>`. Identiek aan het format dat
     * door `X-Ontvanger`-header wordt verwacht. Gebruikt door call-sites die een
     * unieke string-key nodig hebben (cache-keys, log-correlatie) zonder de
     * type/waarde-onderscheid te verliezen.
     *
     * **Niet voor logs of foutboodschappen.** Voor BSN/RSIN geldt geen
     * PII-vriendelijke variant — gebruik `.toString()` (gemaskeerd) of expliciet
     * `.type` als type-context al volstaat.
     */
    fun toCanonicalString(): String = "${type.name}:$waarde"

    companion object {
        /**
         * Bouwt een getypeerd [Identificatienummer]; gooit [DomainValidationException] bij
         * een type-invariant-schending (lengte, cijfers-only, elfproef voor BSN/RSIN,
         * niet-geheel-nullen). Geen impliciete trim — whitespace is een clientfout, net als
         * bij de directe value-class constructors.
         */
        fun of(type: IdentificatienummerType, waarde: String): Identificatienummer = when (type) {
            IdentificatienummerType.BSN -> Bsn(waarde)
            IdentificatienummerType.RSIN -> Rsin(waarde)
            IdentificatienummerType.KVK -> Kvk(waarde)
            IdentificatienummerType.OIN -> Oin(waarde)
        }

        /**
         * Leest een `X-Ontvanger` header in formaat `<TYPE>:<WAARDE>` en bouwt
         * er een getypeerd [Identificatienummer] van. De header is op spec-niveau
         * al gevalideerd (regex per type, zie `OntvangerHeader` in de OpenAPI-spec)
         * door de JAX-RS Bean Validation annotaties op de gegenereerde interface;
         * deze functie zet om naar het domein-model en delegeert verdere
         * type-invarianten (elfproef, niet-geheel-nullen) aan [of].
         * Gooit [DomainValidationException] bij ongeldige invoer.
         */
        fun fromHeader(header: String): Identificatienummer {
            val parts = header.split(':', limit = 2)
            requireValid(parts.size == 2) {
                "X-Ontvanger header moet in formaat <TYPE>:<WAARDE> zijn"
            }
            val type = try {
                IdentificatienummerType.valueOf(parts[0])
            } catch (ex: IllegalArgumentException) {
                throw DomainValidationException("Onbekend identificatienummer-type: ${parts[0]}", ex)
            }
            return of(type, parts[1])
        }
    }
}

enum class IdentificatienummerType { BSN, RSIN, KVK, OIN }

/**
 * Organisatie-identificatienummer van Logius (20 cijfers, **geen elfproef** — de
 * OIN-spec eist enkel 20-cijferig numeriek). Expliciet vermeld zodat een refactor niet
 * "alsnog" een elfproef-check toevoegt.
 *
 * `toString` toont de volledige waarde: OIN is een publieke organisatie-identificator,
 * geen PII. Voor BSN/RSIN geldt het omgekeerde — daar maskeert `toString` (zie CLAUDE.md).
 */
@JvmInline
value class Oin(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "OIN moet precies 20 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "OIN kan niet geheel uit nullen bestaan" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.OIN

    override fun toString(): String = "OIN:$waarde"

    companion object {
        private val PATTERN = Regex("^[0-9]{20}$")
    }
}

/**
 * KvK-nummer (8 cijfers).
 *
 * `toString` toont de volledige waarde: KvK-nummers zijn publiek opvraagbaar bij de
 * Kamer van Koophandel, geen PII.
 */
@JvmInline
value class Kvk(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "KVK-nummer moet precies 8 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "KVK-nummer kan niet geheel uit nullen bestaan" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.KVK

    override fun toString(): String = "KVK:$waarde"

    companion object {
        private val PATTERN = Regex("^[0-9]{8}$")
    }
}

/**
 * Burgerservicenummer (9 cijfers, gevalideerd met elfproef).
 *
 * `toString` toont een **SHA-256 hash-suffix** in plaats van de waarde: BSN is PII
 * (CLAUDE.md "BSN nooit in applicatie-logs"). Default Kotlin value-class-toString
 * zou `"Bsn(waarde=...)"` printen — een impliciete `"$bsn"`-template zou stilzwijgend
 * de volledige BSN lekken. Het hash-suffix-format `BSN:#a3f2` biedt log-correlatie
 * (zelfde BSN → zelfde hash) zonder dat de waarde herleidbaar is.
 *
 * Voor LDV (TLS-vereist, BIO 13.2.1) en cache-keys gebruik `.waarde` resp.
 * `.toCanonicalString()` expliciet.
 */
@JvmInline
value class Bsn(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "BSN moet precies 9 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "BSN kan niet geheel uit nullen bestaan" }
        requireValid(isValidElfproef(waarde)) { "BSN voldoet niet aan elfproef" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.BSN

    override fun toString(): String = "BSN:#${hashSuffix(waarde)}"

    companion object {
        private val PATTERN = Regex("^[0-9]{9}$")
    }
}

/**
 * Rechtspersonen en Samenwerkingsverbanden Informatie Nummer (9 cijfers, elfproef).
 *
 * `toString` toont een SHA-256 hash-suffix (`RSIN:#a3f2`) conform [Bsn]: RSIN
 * identificeert kleine rechtspersonen en éénmanszaken; laatste categorie is
 * herleidbaar tot een natuurlijke persoon (vaak gelijk aan BSN van de eigenaar).
 * Gebruik `.waarde` expliciet wanneer de volledige identificator nodig is.
 */
@JvmInline
value class Rsin(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "RSIN moet precies 9 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "RSIN kan niet geheel uit nullen bestaan" }
        requireValid(isValidElfproef(waarde)) { "RSIN voldoet niet aan elfproef" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.RSIN

    override fun toString(): String = "RSIN:#${hashSuffix(waarde)}"

    companion object {
        private val PATTERN = Regex("^[0-9]{9}$")
    }
}

/**
 * SHA-256 over [waarde], hex-encoded, eerste 4 chars (16-bit prefix → 65536 buckets).
 * One-way: niet herleidbaar tot de oorspronkelijke waarde. Bucket-grootte is voldoende
 * voor log-correlatie binnen één sessie/incident; voor cross-sessie-correlatie bij
 * grote BSN-populaties zal er ~1 op 65536 collision optreden — acceptabel voor
 * troubleshooting, niet als unieke identificator (gebruik daarvoor `.waarde`).
 */
private fun hashSuffix(waarde: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(waarde.toByteArray(Charsets.UTF_8))
    return digest.take(2).joinToString("") { "%02x".format(it) }
}

// Standaard elfproef voor BSN en RSIN: posities 1-8 met gewichten 9..2,
// positie 9 met gewicht -1. Som modulo 11 moet 0 zijn.
private val ELFPROEF_WEIGHTS = intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, -1)

private fun isValidElfproef(waarde: String): Boolean {
    val som = waarde.mapIndexed { i, c -> (c - '0') * ELFPROEF_WEIGHTS[i] }.sum()
    return som % 11 == 0
}
