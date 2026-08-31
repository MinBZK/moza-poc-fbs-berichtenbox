package nl.rijksoverheid.moz.fbs.democonsole.simulator

import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Het beheerpad van de simulator is buiten dev en test verplicht met een token beveiligd. Stuurt de
 * console dat token niet mee, dan geeft élke knop een 401 terwijl de rest van de keten gezond is —
 * en dat merk je pas op de gedeelde omgeving, want lokaal staat het beheerpad open.
 */
class SimulatorBeheerClientTest {

    @Test
    fun `de client stuurt het beheertoken mee`() {
        val header = SimulatorBeheerClient::class.java.getAnnotation(ClientHeaderParam::class.java)

        assertNotNull(header, "SimulatorBeheerClient hoort een beheertoken-header te dragen")
        assertEquals("X-Beheer-Token", header.name)

        // Met een default, zodat het lokale pad zonder token blijft werken.
        assertEquals("\${simulator.beheer-token:}", header.value.single())
    }
}
