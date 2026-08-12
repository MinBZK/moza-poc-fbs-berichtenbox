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
 */
@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class MagazijnEventTest {

    @Inject
    lateinit var objectMapper: ObjectMapper

    companion object {
        @JvmStatic
        fun wireContract(): List<Arguments> = listOf(
            Arguments.of(
                MagazijnBevragingGestart(magazijnId = "00000001001234567890", naam = "Magazijn A"),
                """{"event":"magazijn-bevraging-gestart","magazijnId":"00000001001234567890","naam":"Magazijn A"}""",
            ),
            Arguments.of(
                MagazijnBevragingGeslaagd(magazijnId = "00000001001234567890", naam = "Magazijn A", aantalBerichten = 3),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"00000001001234567890","naam":"Magazijn A","status":"OK","aantalBerichten":3}""",
            ),
            Arguments.of(
                MagazijnBevragingMislukt(
                    magazijnId = "00000001001234567890",
                    naam = "Magazijn A",
                    status = MagazijnFoutStatus.FOUT,
                    foutmelding = "Magazijn tijdelijk niet bereikbaar",
                ),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"00000001001234567890","naam":"Magazijn A","status":"FOUT","foutmelding":"Magazijn tijdelijk niet bereikbaar"}""",
            ),
            Arguments.of(
                MagazijnBevragingMislukt(
                    magazijnId = "00000001001234567890",
                    naam = "Magazijn A",
                    status = MagazijnFoutStatus.TIMEOUT,
                    foutmelding = "Magazijn reageerde niet binnen de timeout",
                ),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"00000001001234567890","naam":"Magazijn A","status":"TIMEOUT","foutmelding":"Magazijn reageerde niet binnen de timeout"}""",
            ),
            Arguments.of(
                OphalenGereed(totaalBerichten = 5, geslaagd = 2, mislukt = 0, totaalMagazijnen = 2),
                """{"event":"ophalen-gereed","totaalBerichten":5,"geslaagd":2,"mislukt":0,"totaalMagazijnen":2}""",
            ),
            Arguments.of(
                OphalenMislukt(foutmelding = "Interne fout (ref: abc)", referentie = "abc"),
                """{"event":"ophalen-fout","foutmelding":"Interne fout (ref: abc)","totaalMagazijnen":0,"referentie":"abc"}""",
            ),
            Arguments.of(
                OpslaanMislukt(
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

    /**
     * Serialiseert via een `MagazijnEvent`-referentie, niet via het concrete type: zo valt
     * een serializer die op het statische (interface-)type zou schrijven — en dan alleen
     * `event` zou uitschrijven — direct door de mand.
     */
    @ParameterizedTest
    @MethodSource("wireContract")
    fun `event serialiseert naar het afgesproken wire-formaat`(event: MagazijnEvent, verwacht: String) {
        assertEquals(verwacht, objectMapper.writeValueAsString(event))
    }

    @Test
    fun `ontbrekende naam wordt weggelaten in plaats van als null geschreven`() {
        val gestart: MagazijnEvent = MagazijnBevragingGestart(magazijnId = "00000001001234567890", naam = null)
        val geslaagd: MagazijnEvent = MagazijnBevragingGeslaagd(magazijnId = "00000001001234567890", naam = null, aantalBerichten = 0)

        assertEquals(
            """{"event":"magazijn-bevraging-gestart","magazijnId":"00000001001234567890"}""",
            objectMapper.writeValueAsString(gestart),
        )
        assertEquals(
            """{"event":"magazijn-bevraging-voltooid","magazijnId":"00000001001234567890","status":"OK","aantalBerichten":0}""",
            objectMapper.writeValueAsString(geslaagd),
        )
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

    @Test
    fun `een mislukte bevraging draagt nooit de geslaagd-status`() {
        val foutwaarden = MagazijnFoutStatus.entries.map { it.value }.toSet()

        assertTrue(STATUS_GESLAAGD !in foutwaarden, "STATUS_GESLAAGD hoort niet in MagazijnFoutStatus te zitten")
    }
}
