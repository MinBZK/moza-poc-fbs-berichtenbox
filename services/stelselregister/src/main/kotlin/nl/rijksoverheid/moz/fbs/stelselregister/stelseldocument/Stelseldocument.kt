package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** Een deelnemende organisatie zoals het stelseldocument hem toont. */
data class Organisatie(val oin: String, val naam: String, val magazijnUrl: String)

/**
 * De inhoud van het stelseldocument, los van uitgifte en handtekening.
 *
 * De veldnamen in de JSON zijn Engels en snake_case: ze komen uit BK Connect en zijn daarmee
 * contract met afnemers, geen eigen keuze.
 */
class Stelseldocument(organisaties: Collection<Organisatie>) {

    /** Gesorteerd op OIN, zodat gelijke inhoud altijd dezelfde bytes en dus dezelfde [versie] geeft. */
    val organisaties: List<Organisatie> = organisaties.sortedBy { it.oin }

    /**
     * Kenmerk van de inhoud: verandert alleen als een afnemer iets anders te zien krijgt. Uitgever,
     * omgeving en tijdstippen tellen niet mee — die wisselen per uitgifte of per deployment zonder
     * dat de lijst verandert.
     */
    val versie: String by lazy {
        val inhoud = JSON.createObjectNode().also(::schrijfInhoud)
        val hash = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(inhoud))

        BASE64URL.encodeToString(hash)
    }

    fun payload(uitgeverOin: String, omgeving: String, uitgegevenOp: Instant, verlooptOp: Instant): ByteArray {
        val payload = JSON.createObjectNode()
            .put("iss", uitgeverOin)
            .put("iat", uitgegevenOp.epochSecond)
            .put("exp", verlooptOp.epochSecond)
            .put("version", versie)
            .put("environment", omgeving)

        schrijfInhoud(payload)

        return JSON.writeValueAsBytes(payload)
    }

    private fun schrijfInhoud(doel: ObjectNode) {
        val lijst = doel.putArray("organizations")

        organisaties.forEach { organisatie ->
            lijst.addObject()
                .put("oin", organisatie.oin)
                .put("name", organisatie.naam)
                .put("magazijn_url", organisatie.magazijnUrl)
        }

        doel.putArray("app_managers")
        doel.putArray("document_types").addObject().put("name", DOCUMENTSOORT_BERICHT)
    }

    companion object {
        private const val DOCUMENTSOORT_BERICHT = "bericht"

        private val JSON = ObjectMapper()
        private val BASE64URL = Base64.getUrlEncoder().withoutPadding()

        /**
         * De grant-hash van een inschrijving gaat er bewust niet in: dat is routeringsinformatie
         * van de eigen FSC-outway en zegt een app niets.
         */
        fun uit(register: Magazijnregister): Stelseldocument = Stelseldocument(
            register.alle().map { Organisatie(oin = it.oin.waarde, naam = it.naam, magazijnUrl = it.url.toString()) },
        )
    }
}
