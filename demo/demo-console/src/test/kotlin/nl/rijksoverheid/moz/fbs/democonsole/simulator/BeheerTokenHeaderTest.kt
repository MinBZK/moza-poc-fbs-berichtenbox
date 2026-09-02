package nl.rijksoverheid.moz.fbs.democonsole.simulator

import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import jakarta.inject.Inject

/**
 * Zonder token moet de client gewoon een aanroep doen — dat is het lokale pad, waar het beheerpad
 * van de simulator openstaat en `MAGAZIJN_SIMULATOR_BEHEER_TOKEN` leeg blijft.
 *
 * Een lege waarde in een `@ClientHeaderParam`-expressie laat SmallRye struikelen op de conversie
 * naar String, en dat gebeurt vóór het netwerkverkeer: elke knop van het paneel geeft dan
 * "Failed to convert value ... to String" in plaats van te doen wat hij moet doen.
 */
@QuarkusTest
class BeheerTokenHeaderTest {

    @Inject
    @RestClient
    lateinit var beheer: SimulatorBeheerClient

    @Test
    fun `een lege beheertoken levert een aanroep op en geen configuratiefout`() {
        // Het nagebootste beheerpad wijst elke aanroep af; dát antwoord bewijst dat het verzoek de
        // deur uit ging. Struikelde de client op het invullen van de header, dan viel de fout vóór
        // het netwerkverkeer en was er niets om af te wijzen.
        val fout = runCatching { beheer.magazijnen() }.exceptionOrNull()

        assertTrue(fout != null, "het nagebootste beheerpad hoort deze aanroep af te wijzen")
        assertTrue(
            fout!!.message.orEmpty().contains("HTTP 401"),
            "verwachtte het antwoord van het beheerpad, kreeg ${fout::class.simpleName}: ${fout.message}",
        )
    }
}
