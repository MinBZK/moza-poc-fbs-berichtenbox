package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.reflect.KClass

/**
 * Pint het wire-formaat van de voortgangsberichten: het portaal en de demo-console lezen
 * deze JSON, dus veldnamen, veldvolgorde, waarden én het weglaten van een ontbrekende
 * `naam` liggen vast. De verwachte JSON staat hier voluit — een wijziging in de
 * event-typen moet zichtbaar zijn als een wijziging in deze strings, niet stilzwijgend
 * doorwerken naar afnemers.
 *
 * Deze tests pinnen het formaat *per type*. Dat de SSE-writer óók per runtime-type
 * serialiseert (en niet op het gedeclareerde `Multi<MagazijnEvent>`-elementtype, wat elk
 * bericht tot alleen zijn discriminator zou reduceren) is een eigenschap van het
 * transport en wordt daarom in `OphalenSseTest` op de echte stroom vastgelegd.
 */
@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class MagazijnEventTest {

    @Inject
    lateinit var objectMapper: ObjectMapper

    companion object {
        const val OIN = "00000001001234567890"

        @JvmStatic
        fun wireContract(): List<Arguments> = listOf(
            Arguments.of(
                MagazijnBevragingGestart(magazijnId = OIN, naam = "Magazijn A"),
                """{"event":"magazijn-bevraging-gestart","magazijnId":"$OIN","naam":"Magazijn A"}""",
            ),
            Arguments.of(
                MagazijnBevragingGeslaagd(magazijnId = OIN, naam = "Magazijn A", aantalBerichten = 3),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","naam":"Magazijn A","status":"OK","aantalBerichten":3}""",
            ),
            Arguments.of(
                MagazijnBevragingMislukt(
                    magazijnId = OIN,
                    naam = "Magazijn A",
                    fout = MagazijnFoutStatus.FOUT,
                    foutmelding = "Magazijn tijdelijk niet bereikbaar",
                ),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","naam":"Magazijn A","status":"FOUT","foutmelding":"Magazijn tijdelijk niet bereikbaar"}""",
            ),
            Arguments.of(
                MagazijnBevragingMislukt(
                    magazijnId = OIN,
                    naam = "Magazijn A",
                    fout = MagazijnFoutStatus.TIMEOUT,
                    foutmelding = "Magazijn reageerde niet binnen de timeout",
                ),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","naam":"Magazijn A","status":"TIMEOUT","foutmelding":"Magazijn reageerde niet binnen de timeout"}""",
            ),
            Arguments.of(
                MagazijnBevragingMislukt(
                    magazijnId = OIN,
                    naam = "Magazijn A",
                    fout = MagazijnFoutStatus.NIET_OPGEHAALD,
                    foutmelding = "Nog niet opgehaald: te veel organisaties tegelijk in behandeling (probeer het opnieuw)",
                ),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","naam":"Magazijn A","status":"NIET_OPGEHAALD","foutmelding":"Nog niet opgehaald: te veel organisaties tegelijk in behandeling (probeer het opnieuw)"}""",
            ),
            Arguments.of(
                OphalenGereed(totaalBerichten = 5, geslaagd = 2, mislukt = 0, totaalMagazijnen = 2),
                """{"event":"ophalen-gereed","totaalBerichten":5,"geslaagd":2,"mislukt":0,"totaalMagazijnen":2}""",
            ),
            Arguments.of(
                OphalenMisluktVoorBevraging(foutmelding = "Interne fout (ref: abc)", referentie = "abc"),
                """{"event":"ophalen-fout","foutmelding":"Interne fout (ref: abc)","totaalMagazijnen":0,"referentie":"abc"}""",
            ),
            Arguments.of(
                OphalenMisluktNaBevraging(
                    foutmelding = "Resultaten konden niet worden opgeslagen (ref: abc)",
                    geslaagd = 1,
                    mislukt = 1,
                    totaalMagazijnen = 2,
                    referentie = "abc",
                ),
                """{"event":"ophalen-fout","foutmelding":"Resultaten konden niet worden opgeslagen (ref: abc)","geslaagd":1,"mislukt":1,"totaalMagazijnen":2,"referentie":"abc"}""",
            ),
        )

        /** Elk concreet event-type moet in [wireContract] voorkomen, anders glipt een nieuw soort ongetoetst mee. */
        @JvmStatic
        fun concreteEventTypen(): Set<Class<*>> = MagazijnEvent::class.bladtypen()

        private fun KClass<*>.bladtypen(): Set<Class<*>> =
            sealedSubclasses.flatMap { sub -> if (sub.isSealed) sub.bladtypen() else setOf(sub.java) }.toSet()
    }

    @ParameterizedTest
    @MethodSource("wireContract")
    fun `event serialiseert naar het afgesproken wire-formaat`(event: MagazijnEvent, verwacht: String) {
        assertEquals(verwacht, objectMapper.writeValueAsString(event))
    }

    @Test
    fun `ontbrekende naam wordt weggelaten in plaats van als null geschreven`() {
        assertEquals(
            """{"event":"magazijn-bevraging-gestart","magazijnId":"$OIN"}""",
            objectMapper.writeValueAsString(MagazijnBevragingGestart(magazijnId = OIN, naam = null)),
        )
        assertEquals(
            """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","status":"OK","aantalBerichten":0}""",
            objectMapper.writeValueAsString(MagazijnBevragingGeslaagd(magazijnId = OIN, naam = null, aantalBerichten = 0)),
        )
    }

    /**
     * Een lege naam is niet hetzelfde als een ontbrekende naam: hij wordt wél uitgeschreven,
     * waarna het portaal via zijn eigen `naam || magazijnId`-terugval de OIN toont.
     */
    @Test
    fun `lege naam wordt uitgeschreven, niet weggelaten`() {
        assertEquals(
            """{"event":"magazijn-bevraging-gestart","magazijnId":"$OIN","naam":""}""",
            objectMapper.writeValueAsString(MagazijnBevragingGestart(magazijnId = OIN, naam = "")),
        )
    }

    /**
     * De magazijnnaam komt uit beheerconfiguratie en gaat ongefilterd de stroom op. Een
     * regeleinde of quote daarin moet als escape-sequentie op de lijn belanden: een rauwe
     * newline zou het SSE-frame in tweeën knippen, waarna de client een afgekapt JSON-fragment
     * te verwerken krijgt.
     */
    @Test
    fun `regeleinde en quote in de naam worden ge-escaped`() {
        val json = objectMapper.writeValueAsString(
            MagazijnBevragingGestart(magazijnId = OIN, naam = "Bureau \"A\"\nregel2"),
        )

        assertEquals(
            """{"event":"magazijn-bevraging-gestart","magazijnId":"$OIN","naam":"Bureau \"A\"\nregel2"}""",
            json,
        )
        assertEquals(1, json.lines().size, "een voortgangsbericht moet één regel blijven: $json")
    }

    @Test
    fun `elk concreet event-type staat in de wire-contracttabel`() {
        val gedekt = wireContract().map { it.get()[0]!!.javaClass }.toSet()

        assertEquals(concreteEventTypen(), gedekt, "Ongedekt event-type in het wire-contract")
    }

    @Test
    fun `elk EventType wordt door minstens een concreet event-type geproduceerd`() {
        val geproduceerd = wireContract().map { (it.get()[0] as MagazijnEvent).event }.toSet()

        assertEquals(EventType.entries.toSet(), geproduceerd)
    }

    /**
     * De foutstatussen zijn een deelverzameling van de wire-woordenlijst, met `OK` als enige
     * waarde die er niet in zit. Loopt dat uit elkaar, dan zet een mislukte bevraging een
     * statuswoord op de lijn dat het portaal niet kent.
     */
    @Test
    fun `de foutstatussen dekken de wire-woordenlijst op OK na`() {
        assertEquals(
            MagazijnStatus.entries.toSet() - MagazijnStatus.OK,
            MagazijnFoutStatus.entries.map { it.wire }.toSet(),
        )
    }

    /**
     * De twee enums dragen dezelfde namen; alleen de set-gelijkheid hierboven laat een verkeerde
     * koppeling passeren (`TIMEOUT(FOUT)` blijft groen zodra iets anders `TIMEOUT` levert). En de
     * `value` op de lijn hoort woordelijk de naam te zijn: een typefout daarin verandert stil het
     * statuswoord dat het portaal en `demo/smoke.sh` verwachten.
     */
    @Test
    fun `foutstatus, wire-status en het woord op de lijn dragen dezelfde naam`() {
        assertTrue(
            MagazijnFoutStatus.entries.all { it.name == it.wire.name },
            "Foutstatus en wire-status uit elkaar gelopen: ${MagazijnFoutStatus.entries.map { it.name to it.wire.name }}",
        )

        assertTrue(
            MagazijnStatus.entries.all { it.value == it.name },
            "Wire-woord wijkt af van de naam: ${MagazijnStatus.entries.map { it.name to it.value }}",
        )
    }
}
