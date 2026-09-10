package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGeslaagd
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGestart
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingMislukt
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnFoutStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenGereed
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenMisluktNaBevraging
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenMisluktVoorBevraging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.reflect.KClass

/**
 * Houdt de gepubliceerde beschrijving van de SSE-stroom tegen de code die hem verstuurt.
 *
 * De payload van `GET /berichten/_ophalen` stond jarenlang als `type: string` in de spec, zodat een
 * afnemer de woordenlijst alleen uit onze broncode kon halen. Nu die woordenlijst wél gepubliceerd
 * is, kan ze weer gaan achterlopen — en géén enkele bestaande gate merkt dat: Spectral blijft groen
 * bij een toegevoegde enum-waarde, en `swagger-request-validator` valideert geen SSE-body.
 *
 * De asserties lopen daarom beide kanten op. Wat de code stuurt moet in de spec staan (anders leest
 * een afnemer over een veld heen), en wat de spec verplicht stelt moet de code sturen (anders
 * belooft het contract iets dat er niet is).
 *
 * Geen `@QuarkusTest`: dit toetst een bestand tegen een handvol data classes, niet een draaiende
 * service.
 */
class SseContractTest {

    companion object {
        private const val SPEC = "openapi/berichtenuitvraag-api.yaml"
        private const val OIN = "00000001001234567890"

        private val spec: OpenAPI =
            requireNotNull(OpenAPIV3Parser().read(SPEC)) { "Spec $SPEC niet gevonden op het test-classpath" }

        private val mapper = ObjectMapper()

        /**
         * Eén voorbeeld per concreet event-type, met alle optionele velden gevuld: een veld dat
         * in geen enkel voorbeeld voorkomt, wordt hier niet tegen de spec gehouden. Dat de lijst
         * alle event-typen dekt, bewaakt een eigen test hieronder.
         */
        @JvmStatic
        fun voorbeeldEvents(): List<MagazijnEvent> = listOf(
            MagazijnBevragingGestart(magazijnId = OIN, naam = "Magazijn A"),
            MagazijnBevragingGeslaagd(
                magazijnId = OIN,
                naam = "Magazijn A",
                aantalBerichten = 3,
                afgekapt = true,
                totaalBeschikbaar = 12L,
            ),
            MagazijnBevragingMislukt(
                magazijnId = OIN,
                naam = "Magazijn A",
                fout = MagazijnFoutStatus.NIET_OPGEHAALD,
                foutmelding = "Nog niet opgehaald",
            ),
            OphalenGereed(totaalBerichten = 5, geslaagd = 1, mislukt = 1, nietOpgehaald = 1, totaalMagazijnen = 3),
            OphalenMisluktVoorBevraging(foutmelding = "Interne fout (ref: abc)", referentie = "abc"),
            OphalenMisluktNaBevraging(
                foutmelding = "Opslaan mislukt (ref: abc)",
                geslaagd = 1,
                mislukt = 1,
                nietOpgehaald = 1,
                totaalMagazijnen = 3,
                referentie = "abc",
            ),
        )

        @JvmStatic
        fun schemaNamen(): Set<String> = discriminatorMapping().values.map { it.substringAfterLast('/') }.toSet()

        private fun discriminatorMapping(): Map<String, String> =
            requireNotNull(spec.components.schemas["OphaalEvent"]?.discriminator?.mapping) {
                "OphaalEvent heeft geen discriminator-mapping; zonder die mapping kan een afnemer de events niet uit elkaar houden"
            }

        /** De veldnamen die het schema kent; leeg als het schema of zijn `properties` ontbreekt. */
        private fun eigenschappenVan(schemaNaam: String): Set<String> =
            spec.components.schemas[schemaNaam]?.properties?.keys.orEmpty()

        private fun verplichtVan(schemaNaam: String): Set<String> =
            spec.components.schemas[schemaNaam]?.required.orEmpty().toSet()

        /** Het spec-schema waar dit event volgens de discriminator onder valt. */
        private fun schemaVoor(event: MagazijnEvent): String {
            val ref = requireNotNull(discriminatorMapping()[event.event.value]) {
                "De discriminator-mapping kent '${event.event.value}' niet"
            }

            return ref.substringAfterLast('/')
        }

        private fun velden(event: MagazijnEvent): Set<String> =
            mapper.readTree(mapper.writeValueAsString(event)).fieldNames().asSequence().toSet()

        private fun KClass<*>.bladtypen(): Set<Class<*>> =
            sealedSubclasses.flatMap { sub -> if (sub.isSealed) sub.bladtypen() else setOf(sub.java) }.toSet()
    }

    @Test
    fun `de statuswaarden in de spec zijn precies die van de code`() {
        val inDeSpec = spec.components.schemas["MagazijnStatus"]?.enum.orEmpty().map { it.toString() }

        assertEquals(MagazijnStatus.entries.map { it.value }, inDeSpec)
    }

    @Test
    fun `de discriminator kent precies de event-soorten van de code`() {
        assertEquals(EventType.entries.map { it.value }.toSet(), discriminatorMapping().keys)
    }

    @ParameterizedTest
    @MethodSource("voorbeeldEvents")
    fun `elk veld dat een event stuurt staat in zijn spec-schema`(event: MagazijnEvent) {
        val schemaNaam = schemaVoor(event)
        val onbekend = velden(event) - eigenschappenVan(schemaNaam)

        assertTrue(onbekend.isEmpty()) { "$schemaNaam mist ${onbekend.sorted()} — de spec loopt achter op ${event.javaClass.simpleName}" }
    }

    /**
     * De andere richting: `required` is een belofte aan de afnemer. Staat er een veld in dat geen
     * enkel event van dat soort stuurt, dan bouwt een afnemer op iets dat nooit komt.
     */
    @ParameterizedTest
    @MethodSource("schemaNamen")
    fun `elk verplicht veld uit de spec wordt door de code gestuurd`(schemaNaam: String) {
        val gestuurd = voorbeeldEvents()
            .filter { schemaVoor(it) == schemaNaam }
            .flatMap { velden(it) }
            .toSet()
        val beloofdMaarAfwezig = verplichtVan(schemaNaam) - gestuurd

        assertTrue(beloofdMaarAfwezig.isEmpty()) { "$schemaNaam eist ${beloofdMaarAfwezig.sorted()}, maar geen enkel event stuurt dat" }
    }

    @Test
    fun `elk event-type staat in de voorbeelden`() {
        val getoetst = voorbeeldEvents().map { it.javaClass }.toSet()

        assertEquals(MagazijnEvent::class.bladtypen(), getoetst)
    }

    /**
     * Zonder deze verwijzing is de hele woordenlijst hierboven onbereikbaar voor een afnemer: hij
     * leest de beschrijving van het endpoint, niet de losse schemas eronder.
     */
    @Test
    fun `het ophaal-endpoint verwijst naar het event-schema`() {
        val ophalen = spec.paths["/berichten/_ophalen"]?.readOperationsMap()?.get(PathItem.HttpMethod.GET)
        val sse = ophalen?.responses?.get("200")?.content?.get("text/event-stream")

        assertEquals("#/components/schemas/OphaalEvent", sse?.schema?.`$ref`)
    }
}
