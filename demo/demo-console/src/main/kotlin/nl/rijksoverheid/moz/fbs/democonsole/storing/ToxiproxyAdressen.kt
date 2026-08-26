package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.BadRequestException

/**
 * Welke proxy op welk adres staat. Deze laag ís de allowlist: een knop voor een
 * niet-geconfigureerde proxy wordt hier geweigerd, zodat het paneel geen willekeurige naam kan
 * doorzetten en een omgeving zonder magazijn-proxies een nette melding geeft in plaats van een 500.
 *
 * Los van [ToxiproxyRegister] omdat het bouwen van REST-clients een draaiende Quarkus vraagt: zo
 * blijft de beslissing — welke proxy bestaat, welke instanties zijn uniek — toetsbaar in een pure
 * unittest.
 */
internal class ToxiproxyAdressen(config: ToxiproxyConfig) {

    // Een lege of blanco URL schakelt de proxy uit. Zo kan een omgeving een proxy laten ontbreken
    // (bv. de magazijn-storingen op ZAD) door de env-var simpelweg leeg te laten — zonder apart
    // codepad of profiel voor die omgeving. De sleutel blijft anders altijd bestaan: de env-var-
    // fallback in application.properties zit op de waarde, niet op het al-dan-niet-zetten ervan.
    private val perProxy: Map<String, String> =
        config.toxiproxy().mapValues { (_, instantie) -> instantie.url() }.filterValues { it.isNotBlank() }

    fun namen(): Set<String> = perProxy.keys

    fun adres(proxy: String): String =
        perProxy[proxy] ?: throw BadRequestException(
            "onbekende proxy '$proxy'; geconfigureerd: ${namen().sorted()}",
        )

    // Eén ingang per uniek adres, niet per proxy: lokaal delen alle proxies één instantie, en
    // reset() moet elke instantie precies één keer langsgaan.
    fun unieke(): List<String> = perProxy.values.distinct()
}
