package nl.rijksoverheid.moz.fbs.democonsole.generator

import nl.rijksoverheid.moz.fbs.demopersonas.Identificatiecheck
import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Genereert geldige aanlever-opdrachten voor de demo. Deterministisch: dezelfde `Random`
 * en `Clock` geven dezelfde uitvoer, zodat de logica testbaar is zonder infrastructuur.
 *
 * Trouw aan het FBS-model: één magazijn = één organisatie. Elk bericht krijgt als afzender
 * de OIN van zijn magazijn, en gaat naar een persona die bij die organisatie opt-in staat —
 * anders weigert het magazijn de aanlevering (403).
 */
class DemoBerichtGenerator(
    private val personas: List<Persona>,
    private val organisaties: Map<String, Organisatie>,
    private val klok: Clock,
) {

    init {
        require(personas.isNotEmpty()) { "minstens één persona vereist" }

        personas.forEach { persona ->
            Identificatiecheck.valideer(persona.type, persona.waarde)

            require(persona.magazijnen.isNotEmpty()) { "persona ${persona.naam} heeft geen magazijnen" }

            persona.magazijnen.forEach { oin ->
                require(oin in organisaties) { "onbekende organisatie-OIN '$oin' voor ${persona.naam}" }
            }
        }

        // De id is de sleutel waarop het paneel een persona aanwijst; twee gelijke id's zouden de
        // tweede onbereikbaar maken zonder dat iets dat meldt.
        require(personas.distinctBy { it.id }.size == personas.size) {
            "demo-persona's delen een id: ${personas.groupBy { it.id }.filterValues { it.size > 1 }.keys}"
        }
    }

    /** De persona's die het paneel als doel kan aanwijzen; elk van hen heeft minstens één magazijn. */
    fun doelgroep(): List<Doelpersona> = personas.map { Doelpersona(it.id, it.naam) }

    fun genereer(aantal: Int, random: Random): List<AanleverOpdracht> =
        (0 until aantal).map { opdracht(personas[random.nextInt(personas.size)], random) }

    /**
     * Berichten voor één aangewezen persona. Magazijn en sjabloon blijven willekeurig binnen wat
     * die persona ontvangt — de bediener wilde een bericht voor déze ondernemer, niet een tweede
     * keuzelijst. `null` bij een onbekende id, zodat de aanroeper er een 404 van kan maken in
     * plaats van een 500 met een stacktrace.
     */
    fun genereerVoor(personaId: String, aantal: Int, random: Random): List<AanleverOpdracht>? {
        val persona = personas.firstOrNull { it.id == personaId } ?: return null

        return (0 until aantal).map { opdracht(persona, random) }
    }

    private fun opdracht(persona: Persona, random: Random): AanleverOpdracht {
        val organisatie = organisaties.getValue(persona.magazijnen[random.nextInt(persona.magazijnen.size)])
        val sjabloon = organisatie.sjablonen[random.nextInt(organisatie.sjablonen.size)]

        // Gespreid tijdstip: willekeurige dag én tijd binnen kantooruren, zodat sorteren op
        // datum betekenis heeft en berichten niet allemaal op hetzelfde moment lijken binnen te komen.
        val minutenTerug = random.nextInt(1, 90 * 24 * 60).toLong()

        val verzoek = AanleverVerzoek(
            afzender = organisatie.oin,
            ontvanger = OntvangerDto(persona.type, persona.waarde),
            onderwerp = sjabloon.onderwerp,
            inhoud = "Beste ${persona.naam},\n\n${sjabloon.inhoud}\n\nMet vriendelijke groet,\n${organisatie.naam}",
            publicatietijdstip = klok.instant().minus(minutenTerug, ChronoUnit.MINUTES).toString(),
        )

        return AanleverOpdracht(organisatie.oin, verzoek)
    }
}
