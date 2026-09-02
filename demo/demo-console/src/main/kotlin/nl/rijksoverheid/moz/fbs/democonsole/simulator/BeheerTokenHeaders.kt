package nl.rijksoverheid.moz.fbs.democonsole.simulator

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MultivaluedMap
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory
import java.util.Optional

/**
 * Zet `X-Beheer-Token` op de aanroepen naar het beheerpad van de simulator, maar alleen als er een
 * token is.
 *
 * Niet via `@ClientHeaderParam` met de configuratie-expressie als waarde: SmallRye leest een lege
 * waarde als "geen waarde" en laat de conversie naar String falen, nog vóór het netwerkverkeer.
 * Elke knop van het paneel breekt dan zodra het token leegstaat — wat lokaal de normale stand is.
 *
 * Ontbreekt het token waar de simulator het wél eist, dan antwoordt die met 401. Dat is een fout die
 * zichzelf aanwijst; stilzwijgend een lege header meesturen zou hetzelfde effect hebben met een
 * vagere melding.
 */
@ApplicationScoped
class BeheerTokenHeaders(
    @param:ConfigProperty(name = "simulator.beheer-token") private val token: Optional<String>,
) : ClientHeadersFactory {

    override fun update(
        inkomend: MultivaluedMap<String, String>,
        uitgaand: MultivaluedMap<String, String>,
    ): MultivaluedMap<String, String> {
        token.filter { it.isNotBlank() }.ifPresent { uitgaand.putSingle(HEADER, it) }

        return uitgaand
    }

    companion object {
        const val HEADER = "X-Beheer-Token"
    }
}
