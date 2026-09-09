package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
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
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","naam":"Magazijn A","status":"OK",""" +
                    """"aantalBerichten":3,"afgekapt":false}""",
            ),
            Arguments.of(
                MagazijnBevragingGeslaagd(
                    magazijnId = OIN,
                    naam = "Magazijn A",
                    aantalBerichten = 500,
                    afgekapt = true,
                    totaalBeschikbaar = 1340L,
                ),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","naam":"Magazijn A","status":"OK",""" +
                    """"aantalBerichten":500,"afgekapt":true,"totaalBeschikbaar":1340}""",
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

    /**
     * De naam is verplicht in het register en gaat daarom altijd mee op de lijn. Dit pint dat er
     * geen weglaat-gedrag meer op zit: een portaal hoeft geen terugval op `magazijnId` te bouwen.
     */
    @Test
    fun `naam gaat altijd mee op de lijn`() {
        assertEquals(
            """{"event":"magazijn-bevraging-gestart","magazijnId":"$OIN","naam":"Belastingdienst"}""",
            objectMapper.writeValueAsString(MagazijnBevragingGestart(magazijnId = OIN, naam = "Belastingdienst")),
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
}
