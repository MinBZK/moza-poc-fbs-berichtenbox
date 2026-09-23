package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

/**
 * Wat een gevolgde sessie meldt terwijl de berichtenbox openstaat (zie
 * [nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache.volg]).
 *
 * Een stream bevat precies één [VolgenGestart]. Alles wat daarna in de sessie wordt aangemeld,
 * komt als [BerichtBijgekomen] door; wat ervóór binnenkwam, staat al in de lijst. Een
 * [BerichtBijgekomen] kan [VolgenGestart] ook vóórgaan: de luisteraar staat er al vóórdat de
 * sessie gecontroleerd is. Een afnemer die bij [VolgenGestart] de lijst leest en op `berichtId`
 * ontdubbelt, mist dus niets — ook niet na een verbroken connection.
 *
 * Twee uitzonderingen op dat verloop. Blijkt de sessie bij het starten al weg, dan is
 * [SessieVerlopen] het enige item. En na `berichtensessiecache.volg-max-duur` eindigt de stream
 * zonder afsluitend item: de sessie loopt dan nog, en de afnemer verbindt opnieuw.
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
