package nl.rijksoverheid.moz.fbs.demopersonas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class PersonaServiceTest {

    @Test
    fun `weigert te starten als er geen persona is ingericht`() {
        val melding = weigering().message!!

        assertTrue(melding.contains("demo.personas"), melding)
    }

    @Test
    fun `levert de enige persona`() {
        val personas = service("pietersen" to VastePersona("J. Pietersen", "BSN", "999993653")).alle()

        assertEquals(listOf("pietersen"), personas.map { it.id })
        assertEquals("BSN:999993653", personas.single().ontvanger)
    }

    @Test
    fun `sorteert op label ongeacht hoofdletters en ongeacht de volgorde in de configuratie`() {
        val personas = service(
            "vandijk" to VastePersona("Garage Van Dijk B.V.", "KVK", "90000014"),
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653"),
            "dejong" to VastePersona("de Jong Transport", "KVK", "87654321"),
            "bakkerij" to VastePersona("Bakkerij De Vroege Vogel", "BSN", "999996666"),
        ).alle()

        // Hoofdlettergevoelig sorteren zou "de Jong Transport" achteraan zetten.
        assertEquals(listOf("bakkerij", "dejong", "vandijk", "pietersen"), personas.map { it.id })
    }

    @Test
    fun `houdt bij gelijke labels een vaste volgorde aan`() {
        val personas = service(
            "tweede" to VastePersona("Gelijke Naam B.V.", "KVK", "90000014"),
            "eerste" to VastePersona("Gelijke Naam B.V.", "KVK", "87654321"),
        ).alle()

        assertEquals(listOf("eerste", "tweede"), personas.map { it.id })
    }

    @Test
    fun `noemt de persona-id als een nummer onbruikbaar is`() {
        val melding = weigering("typfout" to VastePersona("Typfout B.V.", "KVK", "1234567")).message!!

        assertTrue(melding.contains("typfout"), melding)
    }

    @Test
    fun `weigert een leeg magazijn-OIN`() {
        val melding = weigering(
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", listOf(TestPersonas.RVO, "")),
        ).message!!

        assertTrue(melding.contains("pietersen"), melding)
    }

    @ParameterizedTest
    @MethodSource("witruimteVarianten")
    fun `weigert een magazijn-OIN met witruimte eromheen, zoals een spatie na de komma oplevert`(oin: String) {
        val melding = weigering(
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", listOf(oin)),
            kenners = listOf(strikt),
        ).message!!

        assertTrue(melding.contains("witruimte"), melding)

        // Mét een kenner erbij, want de witruimte-guard bestaat juist om de kenners over te slaan:
        // hun bezwaar zou naar de magazijn-inrichting wijzen, waar niets mis is, en dan repareert
        // de bediener de verkeerde kant.
        assertFalse(melding.contains("onbekend magazijn"), melding)
    }

    @Test
    fun `staat hetzelfde nummer toe onder twee verschillende types`() {
        val personas = service(
            "bsn" to VastePersona("A B.V.", "BSN", "999993653"),
            "rsin" to VastePersona("B B.V.", "RSIN", "999993653"),
        ).alle()

        assertEquals(listOf("bsn", "rsin"), personas.map { it.id })
    }

    @Test
    fun `houdt melding en bijgevoegde oorzaken in dezelfde volgorde`() {
        val fout = weigering(
            "zebra" to VastePersona("Zebra B.V.", "KVK", "1234567"),
            "alfa" to VastePersona("Alfa B.V.", "KVK", "7654321"),
        )

        assertTrue(fout.message!!.indexOf("alfa") < fout.message!!.indexOf("zebra"), fout.message)
        assertEquals(listOf("demo-persona 'alfa'", "demo-persona 'zebra'"), fout.suppressed.map { it.message })
    }

    @Test
    fun `noemt in de opstartregel hoeveel kenners de magazijnen getoetst hebben`() {
        // Nul kenners is juist in deze dienst en een fout in een afnemer. Zonder dit getal zien die
        // twee er in de opstartlog identiek uit.
        val regel = PersonaService.logregel(emptyList(), kenners = 0)

        assertTrue(regel.contains("0 magazijn-kenner"), regel)
    }

    @Test
    fun `noemt het identificatienummer niet in de opstartregel`() {
        val regel = PersonaService.logregel(
            service("pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", listOf(TestPersonas.RVO))).alle(),
            kenners = 1,
        )

        assertTrue(regel.contains("pietersen"), regel)
        assertFalse(regel.contains("999993653"), regel)
    }

    @Test
    fun `noemt per persona de bron en het aantal magazijnen, ook bij meerdere`() {
        // Het magazijn-aantal staat nergens anders in de runtime — het personas-endpoint geeft het
        // niet terug — terwijl een weggevallen `magazijnen`-regel de generator deze persona stil
        // laat overslaan. Drie persona's, zodat ook de scheiding tussen de regels vastligt.
        val regel = PersonaService.logregel(
            service(
                "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", listOf(TestPersonas.RVO)),
                "grootbedrijf" to VastePersona("Grootbedrijf B.V.", "KVK", "90000001"),
                "verzonnen" to VastePersona("Verzonnen B.V.", "KVK", "87654321", bron = "dataset"),
            ).alle(),
            kenners = 1,
        )

        assertTrue(regel.contains("grootbedrijf (keten, 0 magazijn(en))"), regel)
        assertTrue(regel.contains("pietersen (keten, 1 magazijn(en))"), regel)
        assertTrue(regel.contains("verzonnen (dataset, 0 magazijn(en))"), regel)
    }

    @Test
    fun `weigert twee persona's op hetzelfde identificatienummer`() {
        val melding = weigering(
            "eerste" to VastePersona("Eerste B.V.", "KVK", "90000014"),
            "tweede" to VastePersona("Tweede B.V.", "KVK", "90000014"),
        ).message!!

        assertTrue(melding.contains("eerste") && melding.contains("tweede"), melding)

        // Op elke reeks van acht of meer cijfers en niet op één letterlijk nummer: een assertie op
        // een getal dat in deze test niet voorkomt, kan de invariant niet bewaken.
        assertFalse(NUMMER.containsMatchIn(melding), "het nummer hoort niet in de melding: $melding")
    }

    @Test
    fun `noemt elke botsende groep, ook als het er meer dan één is`() {
        val melding = weigering(
            "alfa" to VastePersona("Alfa B.V.", "KVK", "90000014"),
            "bravo" to VastePersona("Bravo B.V.", "KVK", "90000014"),
            "charlie" to VastePersona("Charlie B.V.", "KVK", "87654321"),
            "delta" to VastePersona("Delta B.V.", "KVK", "87654321"),
            "echo" to VastePersona("Echo B.V.", "KVK", "87654321"),
        ).message!!

        // Twee groepen, waarvan één van drie: met één groep van twee blijft ongetoetst of de
        // melding er meer dan één aankan, en dan kost elke botsing een eigen herstart.
        listOf("alfa", "bravo", "charlie", "delta", "echo").forEach {
            assertTrue(melding.contains(it), "'$it' ontbreekt in: $melding")
        }

        assertTrue(melding.contains("charlie en delta en echo"), melding)
    }

    @Test
    fun `weigert hetzelfde magazijn twee keer bij één persona`() {
        val melding = weigering(
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", listOf(TestPersonas.RVO, TestPersonas.RVO)),
        ).message!!

        assertTrue(melding.contains("pietersen"), melding)
        assertTrue(melding.contains("dubbel"), melding)
    }

    @Test
    fun `een kapot nummer en een onbekend magazijn komen in dezelfde melding`() {
        // Dit is waarom de magazijn-kennis via deze naad loopt en niet als losse controle bij de
        // afnemer staat: met twee controles fixt de bediener de eerste fout, start opnieuw, en
        // krijgt dan pas de tweede te zien.
        val melding = weigering(
            "typfout" to VastePersona("Typfout B.V.", "KVK", "1234567"),
            "verdwaald" to VastePersona("Verdwaald B.V.", "KVK", "90000014", listOf("00000000000000999999")),
            kenners = listOf(strikt),
        ).message!!

        assertTrue(melding.contains("typfout"), melding)
        assertTrue(melding.contains("verdwaald"), melding)
        assertTrue(melding.contains("00000000000000999999"), melding)
    }

    @Test
    fun `twee onbekende magazijnen bij één persona komen allebei in de melding`() {
        // Binnen één persona net zo goed als eroverheen: twee typfouten in dezelfde magazijnen-regel
        // horen geen twee herstarts te kosten.
        val melding = weigering(
            "verdwaald" to VastePersona("Verdwaald B.V.", "KVK", "90000014", listOf(EERSTE, TWEEDE)),
            kenners = listOf(strikt),
        ).message!!

        assertTrue(melding.contains(EERSTE), melding)
        assertTrue(melding.contains(TWEEDE), melding)
    }

    @Test
    fun `elke kenner mag bezwaar maken, en beide bezwaren komen mee`() {
        val melding = weigering(
            "verdwaald" to VastePersona("Verdwaald B.V.", "KVK", "90000014", listOf(EERSTE)),
            kenners = listOf(MagazijnKennis { "eerste kenner" }, MagazijnKennis { "tweede kenner" }),
        ).message!!

        assertTrue(melding.contains("eerste kenner"), melding)
        assertTrue(melding.contains("tweede kenner"), melding)
    }

    @Test
    fun `een persona zonder opt-in wordt niet aan een kenner voorgelegd`() {
        // Nul magazijnen is een geldige inrichting: Grootbedrijf haalt op bij de gesimuleerde
        // magazijnen, waar deze module niets voor aanlevert.
        val personas = service(
            "grootbedrijf" to VastePersona("Grootbedrijf B.V.", "KVK", "90000001"),
            kenners = listOf(MagazijnKennis { error("er valt hier niets te vragen") }),
        ).alle()

        assertEquals(listOf("grootbedrijf"), personas.map { it.id })
    }

    @Test
    fun `een kenner die iets onverwachts gooit houdt zijn persona-id vast`() {
        // De naad is een fun interface; een implementatie kan alles gooien. Dan hoort de fout bij
        // zijn persona te blijven staan in plaats van de hele ronde af te breken — anders verliest
        // de bediener de andere meldingen én de id die zegt wáár het misging.
        val melding = weigering(
            "typfout" to VastePersona("Typfout B.V.", "KVK", "1234567"),
            "verdwaald" to VastePersona("Verdwaald B.V.", "KVK", "90000014", listOf(EERSTE)),
            kenners = listOf(MagazijnKennis { throw NoSuchElementException("config ontbreekt") }),
        ).message!!

        assertTrue(melding.contains("typfout"), melding)
        assertTrue(melding.contains("verdwaald"), melding)
    }

    @Test
    fun `een afnemer zonder magazijn-kennis laat elke opt-in staan`() {
        // De personadienst zelf kent geen magazijnen; dan hoort een opt-in geen fout te zijn.
        assertEquals(
            listOf("verdwaald"),
            service("verdwaald" to VastePersona("Verdwaald B.V.", "KVK", "90000014", listOf("00000000000000999999")))
                .alle().map { it.id },
        )
    }

    @Test
    fun `meldt alle onbruikbare persona's in één keer`() {
        val melding = weigering(
            "eerste" to VastePersona("Eerste B.V.", "KVK", "1234567"),
            "tweede" to VastePersona("Tweede B.V.", "KVK", "7654321"),
        ).message!!

        assertTrue(melding.contains("eerste") && melding.contains("tweede"), melding)
    }

    @Test
    fun `neemt de bron over uit de configuratie`() {
        val personas = service(
            "keten" to VastePersona("A", "KVK", "90000014", listOf(TestPersonas.RVO)),
            "verzonnen" to VastePersona("B", "KVK", "87654321", bron = "dataset"),
        ).alle()

        assertEquals(listOf(PersonaBron.KETEN, PersonaBron.DATASET), personas.map { it.bron })
    }

    @Test
    fun `weigert een dataset-persona die ook ketenberichten zou krijgen`() {
        val melding = weigering(
            "mengvorm" to VastePersona("Mengvorm", "KVK", "90000014", listOf(TestPersonas.RVO), "dataset"),
        ).message!!

        assertTrue(melding.contains("mengvorm"), melding)
        assertTrue(melding.contains("'dataset'"), melding)
    }

    @Test
    fun `weigert een onbekende bron en noemt de persona plus wat wel mag`() {
        val melding = weigering("verzonnen" to VastePersona("Verzonnen B.V.", "KVK", "90000014", bron = "mock")).message!!

        assertTrue(melding.contains("verzonnen"), melding)
        assertTrue(melding.contains("keten") && melding.contains("dataset"), melding)
        assertFalse(melding.contains("mock"), "de aangeboden waarde hoort niet in de melding: $melding")
    }

    @ParameterizedTest
    @MethodSource("optIns")
    fun `alleen persona's met een opt-in krijgen gegenereerde berichten`(magazijnen: List<String>?, verwacht: List<String>) {
        val personas = service(
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", magazijnen),
            "bakkerij" to VastePersona("Bakkerij De Vroege Vogel", "BSN", "999996666", listOf(TestPersonas.BELASTINGDIENST)),
            "grootbedrijf" to VastePersona("Grootbedrijf B.V.", "KVK", "90000001"),
        ).metMagazijnen()

        assertEquals(verwacht, personas.map { it.id })
    }

    private fun service(
        vararg personas: Pair<String, PersonaConfig.PersonaInstelling>,
        kenners: List<MagazijnKennis> = emptyList(),
    ): PersonaService = PersonaService(VastePersonaConfig(personas.toMap()), kenners.toMutableList())

    /** Toetst dat de inrichting de module laat weigeren te starten, en levert de fout voor verdere assertions. */
    private fun weigering(
        vararg personas: Pair<String, PersonaConfig.PersonaInstelling>,
        kenners: List<MagazijnKennis> = emptyList(),
    ): IllegalArgumentException = assertThrows(IllegalArgumentException::class.java) { service(*personas, kenners = kenners) }

    private companion object {

        const val EERSTE = "00000000000000000001"
        const val TWEEDE = "00000000000000000002"

        /** Elke reeks van acht of meer cijfers is een identificatienummer en hoort niet in een melding. */
        val NUMMER = Regex("[0-9]{8,}")

        /** Kent alleen RVO; elk ander OIN levert een bezwaar dat dat OIN noemt. */
        val strikt = MagazijnKennis { oin -> if (oin == TestPersonas.RVO) null else "onbekend magazijn '$oin'" }

        /** Voor- én volgspatie: de tweede is de realistischere typfout in een properties-bestand. */
        @JvmStatic
        fun witruimteVarianten() = listOf(" " + TestPersonas.RVO, TestPersonas.RVO + " ")

        @JvmStatic
        fun optIns() = listOf(
            Arguments.of(null, listOf("bakkerij")),
            Arguments.of(emptyList<String>(), listOf("bakkerij")),
            Arguments.of(listOf(TestPersonas.RVO), listOf("bakkerij", "pietersen")),
            Arguments.of(
                listOf(TestPersonas.RVO, TestPersonas.BELASTINGDIENST),
                listOf("bakkerij", "pietersen"),
            ),
        )
    }
}
