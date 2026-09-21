package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

/**
 * Wat een gevolgde sessie meldt terwijl de berichtenbox openstaat (zie
 * [nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache.volg]).
 *
 * De stream begint met precies één [VolgenGestart]. Alles wat daarna in de sessie wordt
 * aangemeld, komt als [BerichtBijgekomen] door; wat ervóór binnenkwam, staat al in de lijst.
 * Een afnemer die bij elke [VolgenGestart] de lijst opnieuw leest en op `berichtId` ontdubbelt,
 * mist dus niets — ook niet na een verbroken connection.
 */
sealed interface SessieGebeurtenis {

    /** Het volgen is actief; lees nu de lijst om bij te zijn. */
    data object VolgenGestart : SessieGebeurtenis

    /** Een bericht is in deze sessie aangemeld en staat nu in de lijst. */
    data class BerichtBijgekomen(val bericht: Bericht) : SessieGebeurtenis

    /** De sessie loopt nog en is zojuist verlengd; houdt ook tussenliggende proxies wakker. */
    data object Hartslag : SessieGebeurtenis

    /** De sessie bestaat niet meer; de stream eindigt direct hierna. */
    data object SessieVerlopen : SessieGebeurtenis
}
