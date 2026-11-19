package nl.rijksoverheid.moz.fbs.berichtenapp

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

/** De instellingen van de Berichten-app; [ConfigControle] toetst ze bij de start. */
@ConfigMapping(prefix = "berichten-app")
interface BerichtenAppConfig {

    /**
     * Het publieke adres van de App Manager, eindigend op `/`. De app verwacht het ook als `iss` van
     * het registratiebericht en weigert een bericht met een andere; het moet dus gelijk zijn aan
     * `app-manager.issuer-url` van de App Manager.
     */
    fun appManagerUrl(): String

    /** De OIN van de App Manager: de `aud` van de nonce-handtekening. */
    @WithDefault(DEMO_APP_MANAGER_OIN)
    fun appManagerOin(): String

    companion object {
        /** De demo-App Manager, zoals het toepassingsprofiel hem noemt. */
        const val DEMO_APP_MANAGER_OIN = "00000000000000001100"
    }
}
