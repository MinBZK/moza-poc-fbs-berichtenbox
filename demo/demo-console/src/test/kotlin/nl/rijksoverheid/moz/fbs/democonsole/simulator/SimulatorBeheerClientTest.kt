package nl.rijksoverheid.moz.fbs.democonsole.simulator

import jakarta.ws.rs.core.MultivaluedHashMap
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Optional

/**
 * Het beheerpad van de simulator is buiten dev en test verplicht met een token beveiligd. Stuurt de
 * console dat token niet mee, dan geeft élke knop een 401 terwijl de rest van de keten gezond is —
 * en dat merk je pas op de gedeelde omgeving, want lokaal staat het beheerpad open.
 */
class SimulatorBeheerClientTest {

    private fun headers(token: Optional<String>) =
        BeheerTokenHeaders(token).update(MultivaluedHashMap(), MultivaluedHashMap())

    @Test
    fun `de client laat zijn headers door de beheertoken-factory zetten`() {
        val annotatie = SimulatorBeheerClient::class.java.getAnnotation(RegisterClientHeaders::class.java)

        assertNotNull(annotatie, "SimulatorBeheerClient hoort een beheertoken-header te dragen")
        assertEquals(BeheerTokenHeaders::class.java, annotatie.value.java)
    }

    @Test
    fun `met een token gaat het token mee`() {
        assertEquals("s3cr3t", headers(Optional.of("s3cr3t")).getFirst(BeheerTokenHeaders.HEADER))
    }

    /**
     * Het lokale pad. Een lege waarde als header meesturen zou net zo goed werken tegen een open
     * beheerpad, maar niet tegen een beveiligd pad — en dan is de melding een 401 zonder aanwijzing
     * dat de console niets te sturen had.
     */
    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `zonder token blijft de header weg`(leeg: String) {
        assertNull(headers(Optional.of(leeg)).getFirst(BeheerTokenHeaders.HEADER))
    }

    @Test
    fun `een ontbrekende instelling laat de header net zo goed weg`() {
        assertNull(headers(Optional.empty()).getFirst(BeheerTokenHeaders.HEADER))
    }
}
