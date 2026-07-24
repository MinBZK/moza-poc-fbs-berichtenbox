package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI

/** Resultaat van een bewust foutieve aanlevering: de HTTP-status + de (problem+json) body. */
data class FoutResultaat(val status: Int, val body: String)

/**
 * Stuurt één bewust ongeldige aanlevering naar magazijn A (RVO) om de 400 RFC 9457-respons te
 * tonen. De payload is compleet en geldig op één punt na — een BSN die de elfproef niet haalt —
 * zodat de 400 aantoonbaar op de domeinvalidatie slaat, niet op een ontbrekend veld.
 */
@ApplicationScoped
class FoutieveAanleverService(config: MagazijnenConfig) {

    private val client: MagazijnAanleverClient =
        QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(magazijnAUrl(config)))
            .build(MagazijnAanleverClient::class.java)

    fun stuurOngeldig(): FoutResultaat =
        client.leverRuwAan(ongeldigePayload()).use { response ->
            FoutResultaat(response.status, response.readEntity(String::class.java))
        }

    companion object {

        // BSN 111111111 haalt de elfproef niet (som mod 11 != 0) → DomainValidationException → 400.
        fun ongeldigePayload(): String =
            """
            {
              "afzender": "00000001003214345000",
              "ontvanger": { "type": "BSN", "waarde": "111111111" },
              "onderwerp": "Demo: foutieve aanlevering",
              "inhoud": "Deze aanlevering hoort te falen op de elfproef-validatie van de ontvanger-BSN."
            }
            """.trimIndent()

        private fun magazijnAUrl(config: MagazijnenConfig): String =
            config.magazijnen()["00000001003214345000"]?.url()
                ?: error("geen URL geconfigureerd voor magazijn A (00000001003214345000)")
    }
}
