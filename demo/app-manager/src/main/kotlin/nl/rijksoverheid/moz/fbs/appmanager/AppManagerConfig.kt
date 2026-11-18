package nl.rijksoverheid.moz.fbs.appmanager

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * De instellingen van de App Manager. Bij de start toetst [ConfigControle] de adressen, de OIN, de
 * `kid` en de looptijden; [CredentialUitgifte] de looptijd van een credential en
 * [Ondertekensleutel] de sleutel.
 */
@ConfigMapping(prefix = "app-manager")
interface AppManagerConfig {

    /** OIN van de App Manager; komt als `iss` in elk credential en als `aud` in de nonce-handtekening. */
    @WithDefault(DEMO_OIN)
    fun oin(): String

    /** De `kid` waaronder de public key in het stelseldocument staat. */
    @WithDefault("app-manager-1")
    fun kid(): String

    /**
     * Pad naar de sleutel: een PEM met een `PRIVATE KEY`- (PKCS#8) en een `PUBLIC KEY`-blok (SPKI).
     * Zonder pad maakt een ontwikkel- of testbuild een wegwerpsleutel; een uitgerolde build start dan niet.
     */
    fun sleutelPad(): Optional<String>

    /** Tot wanneer de sleutel in het stelseldocument geldt; vanaf dat moment ondertekent de App Manager niet meer. */
    @WithDefault("2099-01-01T00:00:00Z")
    fun sleutelGeldigTot(): Instant

    /**
     * Het publieke adres van de App Manager, eindigend op `/`: het adres waaronder de app
     * `register_app` aanroept (4.4.1, stap 3). Komt als `iss` in het `registration_token`-bericht
     * (stap 13), dat de app ertegen toetst, en is de basis van de doorverwijzing en van de `Origin`
     * die de keuze moet dragen. Vast in de configuratie, zodat een `Host`-header er niets aan
     * verandert.
     */
    fun issuerUrl(): String

    /**
     * Het enige adres waar het `registration_token` heen gaat (de "universal link" van een
     * mobiele app). Vast in de configuratie: een parameter zou een aanvaller het token naar zich
     * toe laten sturen.
     */
    fun terugUrl(): String

    /**
     * Hoe lang een credential geldt: tussen twee minuten en 24 uur. Een magazijn weigert er een dat
     * binnen een minuut verloopt; de tweede minuut is speling voor het ophalen.
     */
    @WithDefault("PT1H")
    fun credentialGeldig(): Duration

    /** Hoe lang een `registration_token` een vers credential oplevert; hooguit 30 dagen. */
    @WithDefault("P1D")
    fun registratieGeldig(): Duration

    /**
     * Hoeveel self-signed sessies één adres (IPv6: één /64) tegelijk open mag hebben. Zo'n sessie
     * kost niets; zonder grens vult één adres de opslag. Achter één NAT, zoals in een zaal tijdens
     * een demo, delen alle browsers dit aantal.
     */
    @WithDefault("20")
    fun sessiesPerClient(): Int

    companion object {
        /** De OIN van de demo-App Manager; het toepassingsprofiel noemt hem bij de App Managers. */
        const val DEMO_OIN = "00000000000000001100"

        const val SCOPE = "nl.rijksoverheid.moz.fbs.berichten"
    }
}
