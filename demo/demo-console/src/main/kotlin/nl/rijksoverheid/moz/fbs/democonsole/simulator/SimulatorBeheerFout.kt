package nl.rijksoverheid.moz.fbs.democonsole.simulator

import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper

/**
 * Maakt van een niet-2xx van het beheerpad een fout die het paneel kan tonen.
 *
 * Zonder deze mapper wordt zo'n antwoord stil een geslaagde uitkomst met nullen. De module zet
 * `microprofile.rest.client.disable.default.mapper=true`, dus de status leidt niet vanzelf tot een
 * exception en Jackson krijgt de problem+json-body te lezen. `FAIL_ON_UNKNOWN_PROPERTIES` staat uit
 * en een ontbrekend niet-nullable *primitief* wordt door jackson-module-kotlin met de primitieve
 * default gevuld, dus `LeegUitkomst` en `SeedUitkomst` komen er als enkel nullen uit. Het paneel
 * meldt dan "gelukt, 0 berichten" terwijl het beheerpad de aanroep juist afwees.
 *
 * Een `IllegalStateException` en niet de status doorgeven: `DemoFoutMapper` maakt daar een 500 van
 * met de melding in het `fout`-veld, hetzelfde pad dat de Toxiproxy-knoppen al nemen. Een 401
 * doorgeven zou op een gedeelde omgeving lijken op de authenticatiemuur vóór het paneel zelf.
 */
class SimulatorBeheerFout : ResponseExceptionMapper<Exception> {

    override fun handles(status: Int, headers: MultivaluedMap<String, Any>?): Boolean =
        status >= Response.Status.BAD_REQUEST.statusCode

    override fun toThrowable(respons: Response): Exception = IllegalStateException(melding(respons))

    private fun melding(respons: Response): String {
        val status = respons.status

        if (status == Response.Status.UNAUTHORIZED.statusCode) {
            // De enige fout waarvan de oorzaak buiten het antwoord ligt: de simulator zegt bewust
            // niet wát er mis is met het token, dus zonder deze zin zoekt de bediener in de keten.
            return "Het beheerpad van de simulator wees de console af (HTTP 401): " +
                "MAGAZIJN_SIMULATOR_BEHEER_TOKEN ontbreekt hier of komt niet overeen met dat van de simulator."
        }

        return "De simulator beantwoordde het beheerpad met HTTP $status" + toelichting(respons)
    }

    // De body draagt de reden die de simulator zelf gaf; die is voor een bediener bruikbaarder dan
    // het statusnummer alleen. Ontbreekt hij of is hij onleesbaar, dan blijft de status over.
    private fun toelichting(respons: Response): String {
        val body = runCatching { respons.readEntity(String::class.java) }.getOrNull()

        return if (body.isNullOrBlank()) "" else ": $body"
    }
}
