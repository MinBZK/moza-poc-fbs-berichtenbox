package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

/**
 * Fout-representatie van een mislukte levering voor het Logboek Dataverwerkingen: de
 * categorie en bij een HTTP-fout de statuscode, nooit de [DownstreamResultaat.Mislukt.reden].
 *
 * De wrapper zet de message als `exception.message` op een logregel die de betrokkene
 * draagt en bij een inzageverzoek naar buiten gaat. De reden kan tekst uit de response
 * van de afnemer bevatten; die hoort daar niet. Het volledige foutbeeld staat in de
 * applicatielog en de claim-status.
 *
 * Geen stacktrace: die wijst alleen naar deze factory, niet naar de fout zelf.
 */
class LeveringMislukt private constructor(beschrijving: String) :
    RuntimeException(beschrijving, null, false, false) {

    companion object {
        fun van(resultaat: DownstreamResultaat.Mislukt): LeveringMislukt = LeveringMislukt(
            when (resultaat) {
                is DownstreamResultaat.HttpFout -> "HttpFout ${resultaat.statusCode}"
                else -> resultaat.javaClass.simpleName
            },
        )
    }
}
