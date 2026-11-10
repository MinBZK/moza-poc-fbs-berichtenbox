package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import java.util.UUID

/**
 * Wat een gevolgde sessie meldt terwijl de berichtenbox openstaat (zie
 * [nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache.volg]).
 *
 * Een stream bevat hoogstens één [VolgenGestart]. Alles wat daarna in de sessie wordt aangemeld,
 * komt als [BerichtBijgekomen] door; wat ervóór binnenkwam, staat al in de lijst. Een
 * [BerichtBijgekomen] kan [VolgenGestart] ook vóórgaan: de luisteraar staat er al vóórdat de
 * sessie gecontroleerd is. Een afnemer die bij [VolgenGestart] de lijst leest en op
 * (`magazijnId`, `berichtId`) ontdubbelt, mist dus niets — ook niet na een verbroken connection.
 *
 * [BerichtGewijzigd] en [BerichtVerwijderd] komen bij elke gevolgde sessie van de ontvanger, ook
 * bij die waaruit de wijziging kwam; de afnemer verwerkt ze dus idempotent. Ze komen in de
 * volgorde waarin ze op deze pod binnenkwamen.
 *
 * Uitzonderingen op dat verloop. Blijkt de sessie bij het starten al weg, dan is
 * [SessieVerlopen] het enige item. Na `berichtensessiecache.volg-max-duur`, of als de pod stopt,
 * eindigt de stream zonder afsluitend item: de sessie loopt dan nog, en de afnemer verbindt opnieuw.
 */
sealed interface SessieGebeurtenis {

    /** Het volgen is actief; lees nu de lijst om bij te zijn. */
    data object VolgenGestart : SessieGebeurtenis

    /** Een bericht is in deze sessie aangemeld en staat nu in de lijst. */
    data class BerichtBijgekomen(val bericht: Bericht) : SessieGebeurtenis

    /**
     * De leesstatus of de map van een bericht is bijgewerkt, ook vanuit de berichtenbox die deze
     * stream volgt. [status] en [map] zijn de waarden op het moment van doorgeven, niet het
     * verschil: twee keer dezelfde gebeurtenis verwerken geeft hetzelfde beeld. `null` voor [map]
     * is Postvak IN.
     */
    data class BerichtGewijzigd(
        val magazijnId: String,
        val berichtId: UUID,
        val status: Leesstatus?,
        val map: String?,
    ) : SessieGebeurtenis

    /** Een bericht staat niet meer in de lijst, ook als de berichtenbox die deze stream volgt het verwijderde. */
    data class BerichtVerwijderd(val magazijnId: String, val berichtId: UUID) : SessieGebeurtenis

    /** De sessie loopt nog; houdt ook tussenliggende proxies wakker. Verlengt de sessie niet. */
    data object Hartslag : SessieGebeurtenis

    /** De sessie bestaat niet meer; de stream eindigt direct hierna. */
    data object SessieVerlopen : SessieGebeurtenis
}
