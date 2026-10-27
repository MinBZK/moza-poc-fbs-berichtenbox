package nl.rijksoverheid.moz.fbs.appmanager

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.time.Instant
import java.util.Optional

/** De instellingen van de App Manager; [ConfigControle] toetst ze bij de start. */
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

    /** Tot wanneer de sleutel in het stelseldocument geldt; vanaf dat moment tekent de App Manager niet meer. */
    @WithDefault("2099-01-01T00:00:00Z")
    fun sleutelGeldigTot(): Instant

    /**
     * Het publieke adres van de App Manager; komt als `iss` in het `registration_token`-bericht
     * (4.4.1, stap 13). Vast in de configuratie, zodat een `Host`-header er niets aan verandert.
     */
    fun issuerUrl(): String

    /**
     * Het enige adres waar het `registration_token` heen gaat (de "universal link" van een
     * mobiele app). Vast in de configuratie: een parameter zou een aanvaller het token naar zich
     * toe laten sturen.
     */
    fun terugUrl(): String

    /** Hoe lang een credential geldt: tussen een minuut en 24 uur, de grenzen die een magazijn toetst. */
    @WithDefault("PT1H")
    fun credentialGeldig(): Duration

    /** Hoe lang een `registration_token` een vers credential oplevert. */
    @WithDefault("P1D")
    fun registratieGeldig(): Duration

    companion object {
        /** De OIN van de demo-App Manager, zoals het toepassingsprofiel hem noemt. */
        const val DEMO_OIN = "00000000000000001100"

        const val SCOPE = "nl.rijksoverheid.moz.fbs.berichten"
    }
}
