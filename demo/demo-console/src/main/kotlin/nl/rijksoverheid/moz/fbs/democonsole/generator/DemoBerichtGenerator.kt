package nl.rijksoverheid.moz.fbs.democonsole.generator

import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona
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
 *
 * De persona's zijn die van de personadienst: één beschrijving van een demo-identiteit, met de
 * controles op id, label en identificatienummer die daar al in het init-blok staan.
 */
class DemoBerichtGenerator(
    private val personas: List<DemoPersona>,
    private val organisaties: Map<String, Organisatie>,
    private val klok: Clock,
) {

    init {
        require(personas.isNotEmpty()) { "minstens één persona vereist" }

        // Nul magazijnen is een geldige demo-identiteit — die haalt op bij een stub-magazijn — maar
        // niet voor wie er berichten voor opvoert: er valt dan geen afzender te kiezen.
        personas.forEach { persona ->
            require(persona.magazijnen.isNotEmpty()) { "persona ${persona.id} heeft geen magazijnen" }

            // Tegen de organisaties van deze generator, niet tegen de ingerichte aanlever-URL's: een
            // OIN kan een URL hebben zonder dat hier sjablonen voor die afzender staan.
            persona.magazijnen.forEach { oin ->
                require(oin in organisaties) { "onbekende organisatie-OIN '$oin' voor ${persona.id}" }
            }
        }

        // De configuratie sleutelt op id en kan er dus geen twee leveren; deze constructor wel.
        // Twee gelijke id's maken de tweede onbereikbaar zonder dat iets dat meldt.
        require(personas.distinctBy { it.id }.size == personas.size) {
            "demo-persona's delen een id: ${personas.groupBy { it.id }.filterValues { it.size > 1 }.keys}"
        }
    }

    fun doelgroep(): List<Doelpersona> = personas.map { Doelpersona(id = it.id, label = it.label) }

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

    private fun opdracht(persona: DemoPersona, random: Random): AanleverOpdracht {
        val organisatie = organisaties.getValue(persona.magazijnen[random.nextInt(persona.magazijnen.size)])
        val sjabloon = organisatie.sjablonen[random.nextInt(organisatie.sjablonen.size)]

        // Gespreid tijdstip: willekeurige dag én tijd binnen kantooruren, zodat sorteren op
        // datum betekenis heeft en berichten niet allemaal op hetzelfde moment lijken binnen te komen.
        val minutenTerug = random.nextInt(1, 90 * 24 * 60).toLong()

        val verzoek = AanleverVerzoek(
            afzender = organisatie.oin,
            ontvanger = OntvangerDto(persona.type, persona.waarde),
            onderwerp = sjabloon.onderwerp,
            inhoud = "Beste ${persona.label},\n\n${sjabloon.inhoud}\n\nMet vriendelijke groet,\n${organisatie.naam}",
            publicatietijdstip = klok.instant().minus(minutenTerug, ChronoUnit.MINUTES).toString(),
        )

        return AanleverOpdracht(organisatie.oin, verzoek)
    }
}
