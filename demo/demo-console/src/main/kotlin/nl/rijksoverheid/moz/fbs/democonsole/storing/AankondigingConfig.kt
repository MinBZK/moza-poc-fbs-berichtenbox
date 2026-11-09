package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.smallrye.config.ConfigMapping
import java.util.Optional

/**
 * Waar en wanneer [StoringAankondiging] een storingsknop vooraf meldt. Url en deployment zijn
 * optioneel om dezelfde reden als in [ToxiproxyConfig]: een expliciet lege env-var geldt bij
 * smallrye-config als "niet gezet".
 */
@ConfigMapping(prefix = "demo.aankondiging")
interface AankondigingConfig {

    /** Incoming webhook van het kanaal waar de bewaking meldt. Een wachtwoord: nooit loggen. */
    fun webhookUrl(): Optional<String>

    /** De deployment waarin deze console draait; op ZAD `$DEPLOYMENT_NAME`. */
    fun deployment(): Optional<String>

    /**
     * De enige deployment die de bewaking volgt. Een preview erft de env van `test`, dus ook de
     * webhook; zonder deze vergelijking kondigde elke preview een storing aan die niemand meldt.
     */
    fun bewaakteDeployment(): String

    /** De naam waaronder het bericht in het kanaal verschijnt. */
    fun afzender(): String
}
