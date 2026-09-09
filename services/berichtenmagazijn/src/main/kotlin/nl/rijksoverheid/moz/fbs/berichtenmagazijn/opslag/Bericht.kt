package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.exception.requireValid
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import java.time.Instant
import java.util.UUID

/**
 * Domeinmodel voor een opgeslagen bericht.
 * Gescheiden van [BerichtEntity] (JPA) om domeinlogica onafhankelijk te houden van persistentie.
 *
 * Afzender is altijd een [Oin] (organisatie). Ontvanger kan elk [Identificatienummer]-
 * type zijn (BSN, RSIN, KvK of OIN). Typen zijn value classes die hun eigen invarianten
 * afdwingen bij constructie — deze data class hoeft die dus niet te herhalen.
 */
data class Bericht(
    val berichtId: UUID,
    val afzender: Oin,
    val ontvanger: Identificatienummer,
    val onderwerp: String,
    val inhoud: String,
    val tijdstipOntvangst: Instant,
    val publicatietijdstip: Instant,
    // Metadata van bijlagen bij het bericht. Bytes worden separaat opgehaald via
    // de bijlage-repository.
    val bijlagen: List<BijlageMetadata> = emptyList(),
    // Leesstatus voor de ontvanger die het bericht opvraagt. `null` als de
    // ontvanger het bericht nog niet heeft aangeraakt.
    val status: BerichtStatus? = null,
) {
    init {
        valideerKopgegevens(onderwerp, afzender, ontvanger)
        requireValid(inhoud.isNotBlank()) { "Inhoud mag niet leeg zijn" }
        // UTF-8 is hooguit 4 bytes/char. Als char-lengte × 4 onder de limiet blijft is
        // bytes-encoding onnodig; dat scheelt een ~MiB ByteArray-allocatie bij elke `copy()`
        // waarmee het detailpad het bericht met status en bijlagen verrijkt.
        if (inhoud.length > MAX_INHOUD_BYTES / Charsets.UTF_8.newEncoder().maxBytesPerChar().toInt()) {
            val inhoudBytes = inhoud.toByteArray(Charsets.UTF_8).size
            requireValid(inhoudBytes <= MAX_INHOUD_BYTES) {
                "Inhoud mag max ${MAX_INHOUD_BYTES / 1024 / 1024} MiB UTF-8 zijn (kreeg $inhoudBytes bytes)"
            }
        }
        // publicatietijdstip mag zowel in de toekomst (uitgestelde publicatie) als in
        // het verleden liggen: bij een late her-aanlevering kan het oorspronkelijke
        // tijdstip al verstreken zijn. Een tijdstip in het verleden laat de outbox
        // direct publiceren.
    }

    companion object {
        const val MAX_ONDERWERP_LENGTE = 255

        /** Max inhoudgrootte in UTF-8 bytes (1 MiB). Bytes, niet characters,
         *  zodat een 4-byte emoji niet 4× meer geheugen kost binnen dezelfde limiet. */
        const val MAX_INHOUD_BYTES = 1_048_576
    }
}

/**
 * Een bericht zonder zijn tekst: wat een lijstweergave nodig heeft.
 *
 * Bestaat naast [Bericht] omdat het lijstpad de `inhoud`-kolom niet leest. Die kolom is
 * een TEXT van maximaal 1 MiB per rij; hem voor een pagina van twintig berichten inlezen
 * om hem daarna weg te gooien is werk dat de database en het geheugen niet hoeven te doen,
 * nu de samenvatting in het koppelvlak toch geen tekst meer draagt.
 */
data class BerichtKop(
    val berichtId: UUID,
    val afzender: Oin,
    val ontvanger: Identificatienummer,
    val onderwerp: String,
    val tijdstipOntvangst: Instant,
    val publicatietijdstip: Instant,
    // Komen NIET uit de projectie: de repository levert ze leeg en BerichtOphaalService.lijst
    // vult ze achteraf in twee batch-queries aan (vermijdt N+1). Wie de repository rechtstreeks
    // aanroept en die stap overslaat, krijgt dus een lijst waarin elk bericht 0 bijlagen meldt.
    val bijlagen: List<BijlageMetadata> = emptyList(),
    val status: BerichtStatus? = null,
) {
    init {
        valideerKopgegevens(onderwerp, afzender, ontvanger)
    }
}

/**
 * De invarianten die [Bericht] en [BerichtKop] delen. Eén plek, zodat een nieuwe regel niet op
 * één van de twee paden blijft hangen en dezelfde rij zich per endpoint anders gaat gedragen.
 *
 * De afzender-ontvanger-vergelijking kijkt naar de volledige identiteit (type én waarde): twee
 * verschillende typen met dezelfde cijferreeks zijn verschillende identificatienummers.
 */
private fun valideerKopgegevens(onderwerp: String, afzender: Oin, ontvanger: Identificatienummer) {
    requireValid(onderwerp.isNotBlank()) { "Onderwerp mag niet leeg zijn" }
    requireValid(onderwerp.length <= Bericht.MAX_ONDERWERP_LENGTE) {
        "Onderwerp mag max ${Bericht.MAX_ONDERWERP_LENGTE} characters zijn"
    }
    requireValid(afzender != ontvanger) {
        "Afzender en ontvanger mogen niet hetzelfde identificatienummer hebben"
    }
}
