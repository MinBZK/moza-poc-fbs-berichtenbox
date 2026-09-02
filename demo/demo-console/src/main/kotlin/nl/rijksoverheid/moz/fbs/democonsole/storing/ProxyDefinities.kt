package nl.rijksoverheid.moz.fbs.democonsole.storing

/** Alles wat nodig is om één proxy aan te maken op de instantie waar hij hoort. */
internal data class ProxyDefinitie(val naam: String, val listen: String, val upstream: String) {

    init {
        require(adresvorm(listen)) { "listen van $naam hoort host:poort te zijn, was '$listen'" }
        require(adresvorm(upstream)) { "upstream van $naam hoort host:poort te zijn, was '$upstream'" }
    }

    internal companion object {

        /**
         * Toxiproxy weigert een adres zonder poort niet: hij dialt een upstream pas per connection,
         * dus het aanmaken slaagt, de proxy meldt zich als normaal en er komt nooit een verbinding
         * tot stand. Het paneel toont dan "normaal" boven een dode stroom. Hier weigeren is de enige
         * plek waar het nog opvalt.
         */
        fun adresvorm(adres: String): Boolean {
            val poort = adres.substringAfterLast(':', "").toIntOrNull()

            return adres.substringBeforeLast(':', "").isNotBlank() && poort != null && poort in 1..POORT_MAX
        }

        private const val POORT_MAX = 65535
    }
}

/**
 * Welke proxies deze omgeving zelf moet aanmaken. Lokaal zet compose ze uit `toxiproxy/proxies.json`
 * en is het aanmaken een no-op; op ZAD bestaat dat bestand niet, want de inhoud van een attachment
 * wordt daar ongewijzigd gemount en zou in elke preview naar de upstream van `test` wijzen.
 *
 * Een proxy telt alleen mee als hij alle drie de waarden draagt en zijn adressen bruikbaar zijn. Een
 * lege url schakelt hem uit — dezelfde afspraak als in [ToxiproxyAdressen], zodat een omgeving een
 * stroom kan weglaten door enkel de env-var leeg te laten. Ontbreekt of deugt alléén listen of
 * upstream, dan is dat geen keuze maar een fout: [onvolledig] geeft die namen terug zodat de
 * aanroeper erover kan klagen in plaats van de proxy stilzwijgend over te slaan — hij zou nooit
 * ontstaan en de hele stroom zou dood zijn.
 *
 * Los van [ToxiproxyRegister] om dezelfde reden als [ToxiproxyAdressen]: zo blijft de beslissing
 * toetsbaar zonder draaiende Quarkus.
 */
internal class ProxyDefinities(config: ToxiproxyConfig) {

    private val metUrl = config.toxiproxy().filterValues { it.url().orElse("").isNotBlank() }

    private val volledig: List<ProxyDefinitie> = metUrl.mapNotNull { (naam, instantie) ->
        val listen = instantie.listen().orElse("").trim()
        val upstream = instantie.upstream().orElse("").trim()

        val bruikbaar = ProxyDefinitie.adresvorm(listen) && ProxyDefinitie.adresvorm(upstream)

        if (bruikbaar) ProxyDefinitie(naam, listen, upstream) else null
    }

    fun alle(): List<ProxyDefinitie> = volledig

    fun onvolledig(): List<String> = (metUrl.keys - volledig.map { it.naam }.toSet()).sorted()
}
