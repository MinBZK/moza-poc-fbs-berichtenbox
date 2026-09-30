package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

/**
 * Fout-representatie van een mislukte levering voor het Logboek Dataverwerkingen: alleen de
 * categorie, nooit de [DownstreamResultaat.Mislukt.reden].
 *
 * De wrapper zet de message als `exception.message` op een logregel die de betrokkene
 * draagt en bij een inzageverzoek naar buiten gaat. De reden bevat een exceptie-message met
 * host of adres van de afnemer, en bij een HTTP-fout een fragment van de response; die
 * horen daar niet. Het foutbeeld staat, gesaneerd, in de applicatielog en de claim-status.
 *
 * Geen stacktrace: die wijst alleen naar deze factory, niet naar de fout zelf.
 */
class LeveringMislukt private constructor(beschrijving: String) :
    RuntimeException(beschrijving, null, false, false) {

    companion object {
        fun van(resultaat: DownstreamResultaat.Mislukt): LeveringMislukt = LeveringMislukt(
            // Geen else: een nieuw Mislukt-subtype moet hier een bewuste keuze krijgen.
            when (resultaat) {
                // Onbereikbaar zolang een HTTP-antwoord als onzeker telt; staat er voor de volledigheid.
                is DownstreamResultaat.HttpFout -> "HttpFout ${resultaat.statusCode}"
                is DownstreamResultaat.Timeout -> "Timeout"
                is DownstreamResultaat.NetwerkFout -> "NetwerkFout"
                is DownstreamResultaat.SerialisatieFout -> "SerialisatieFout"
                is DownstreamResultaat.ConfiguratieFout -> "ConfiguratieFout"
            },
        )
    }
}
