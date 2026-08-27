package nl.rijksoverheid.moz.fbs.democonsole.personas

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PersonaResourceTest {

    private val personaService = mockk<PersonaService>()
    private val resource = PersonaResource(personaService)

    @Test
    fun `toont geen persona's als er niets is ingericht`() {
        every { personaService.alle() } returns emptyList()

        assertEquals(emptyList<PersonaDto>(), resource.personas())
    }

    @Test
    fun `toont per persona het label, de ontvanger-header en de bron`() {
        every { personaService.alle() } returns listOf(
            DemoPersona("pietersen", "J. Pietersen", "BSN", "999993653", emptyList(), PersonaBron.KETEN),
            DemoPersona("verzonnen", "Verzonnen B.V.", "KVK", "12345678", emptyList(), PersonaBron.DATASET),
        )

        assertEquals(
            listOf(
                PersonaDto("pietersen", "J. Pietersen", "BSN:999993653", "keten"),
                PersonaDto("verzonnen", "Verzonnen B.V.", "KVK:12345678", "dataset"),
            ),
            resource.personas(),
        )
    }
}
