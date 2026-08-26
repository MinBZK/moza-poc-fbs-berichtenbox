package nl.rijksoverheid.moz.fbs.democonsole.personas

import nl.rijksoverheid.moz.fbs.democonsole.aanlever.DemoConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PersonaServiceTest {

    @Test
    fun `levert een lege lijst als er geen persona is ingericht`() {
        assertEquals(emptyList<DemoPersona>(), service(emptyMap()).alle())
    }

    @Test
    fun `levert de enige persona`() {
        val personas = service(mapOf("pietersen" to instelling("J. Pietersen", "BSN", "999993653"))).alle()

        assertEquals(listOf("pietersen"), personas.map { it.id })
        assertEquals("BSN:999993653", personas.single().ontvanger)
    }

    @Test
    fun `sorteert meerdere persona's op label, ongeacht de volgorde in de configuratie`() {
        val personas = service(
            mapOf(
                "vandijk" to instelling("Garage Van Dijk B.V.", "KVK", "12345678"),
                "pietersen" to instelling("J. Pietersen", "BSN", "999993653"),
                "bakkerij" to instelling("Bakkerij De Vroege Vogel", "BSN", "999996666"),
            ),
        ).alle()

        assertEquals(listOf("bakkerij", "vandijk", "pietersen"), personas.map { it.id })
    }

    @Test
    fun `weigert bij het starten een persona met een onbruikbaar nummer`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            service(mapOf("typfout" to instelling("Typfout B.V.", "KVK", "1234567"))).alle()
        }

        assertEquals(true, fout.message!!.contains("typfout"))
    }

    @Test
    fun `neemt de bron over uit de configuratie`() {
        val personas = service(
            mapOf(
                "keten" to instelling("A", "KVK", "12345678"),
                "verzonnen" to instelling("B", "KVK", "12345678", bron = "dataset"),
            ),
        ).alle()

        assertEquals(listOf(PersonaBron.KETEN, PersonaBron.DATASET), personas.map { it.bron })
    }

    @Test
    fun `levert alleen de persona's die berichten van een organisatie ontvangen aan de generator`() {
        val personas = service(
            mapOf(
                "pietersen" to instelling("J. Pietersen", "BSN", "999993653", magazijnen = listOf(RVO)),
                "grootbedrijf" to instelling("Grootbedrijf B.V.", "KVK", "90000001"),
            ),
        ).metMagazijnen()

        assertEquals(listOf("pietersen"), personas.map { it.id })
    }

    private fun service(personas: Map<String, DemoConfig.PersonaInstelling>) = PersonaService(VasteDemoConfig(personas))

    private fun instelling(
        label: String,
        type: String,
        waarde: String,
        magazijnen: List<String> = emptyList(),
        bron: String = "keten",
    ) = VastePersona(label, type, waarde, magazijnen, bron)

    private companion object {

        const val RVO = "00000000000000100000"
    }
}
