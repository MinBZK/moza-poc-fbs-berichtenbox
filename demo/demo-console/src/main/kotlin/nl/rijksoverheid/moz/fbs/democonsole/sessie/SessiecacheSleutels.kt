package nl.rijksoverheid.moz.fbs.democonsole.sessie

import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Hoe de sessiecache de sessies van een ontvanger in Redis vastlegt, zoals de console die leest.
 *
 * Een kopie van de afleiding in `BerichtenCache.kenmerk`/`cacheKey` en `RedisSessieRegister.sleutels`
 * van `fbs-berichtensessiecache`: een demo-module hangt niet aan een stelsel-library. Klein gehouden
 * en vastgepind door `SessiecacheSleutelsConsistentieTest`, die de broncode van de bibliotheek leest.
 *
 * Het versieprefix (`v4`) staat niet vast in de console: hij leest het uit de gevonden sleutel. Na een
 * versiebump zou een vaste versie stil "0 sessies" melden. De verloopindex hoort bij dezelfde versie
 * als de sessies die erin staan.
 */
object SessiecacheSleutels {

    private const val PREFIX = "berichtensessiecache:"

    /** De sessie-ZSET van elke ontvanger, onder elke versie. */
    const val ALLE_SESSIES = "$PREFIX*:sessies"

    /** `<versieprefix><kenmerk>:sessies`; het kenmerk is een SHA-256 in hex. */
    private val SESSIES = Regex("""($PREFIX[^:]+:)([0-9a-f]{64}):sessies""")

    /** De sessie-ZSET van één ontvanger, onder elke versie. */
    fun sessiesVan(kenmerk: String): String = "$PREFIX*:$kenmerk:sessies"

    /** SHA-256 in hex over de canonieke ontvanger. Een terug te rekenen pseudoniem: niet loggen. */
    fun kenmerk(ontvanger: Identificatienummer): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(ontvanger.toCanonicalString().toByteArray(Charsets.UTF_8)),
        )

    fun administratie(sleutel: String): Sessieadministratie? =
        SESSIES.matchEntire(sleutel)?.let { Sessieadministratie(sleutel, it.groupValues[2], it.groupValues[1]) }
}

/**
 * De sessie-administratie van één ontvanger onder één versie van de sessiecache. Alleen te maken via
 * [SessiecacheSleutels.administratie], zodat de drie sleutels altijd bij elkaar horen.
 */
class Sessieadministratie internal constructor(val sessies: String, val kenmerk: String, versieprefix: String) {

    /** HASH met per sessie haar plafond in ms. */
    val plafonds: String = "$sessies:plafond"

    /** Stelselbrede ZSET met per ontvanger het verloop van zijn laatst verlopende sessie. */
    val verloopindex: String = "${versieprefix}verloop"

    override fun equals(other: Any?): Boolean = other is Sessieadministratie && other.sessies == sessies

    override fun hashCode(): Int = sessies.hashCode()

    // Een toString belandt makkelijk in een log, en het kenmerk is een terug te rekenen hash.
    override fun toString(): String = "Sessieadministratie(***)"
}

/** Een lid van de sessie-ZSET: de SHA-256 van het sessie-id en het verloop in ms. */
data class Sessielid(val hash: String, val verloopMs: Long)
