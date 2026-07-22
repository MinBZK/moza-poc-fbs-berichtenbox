package nl.rijksoverheid.moz.fbs.democonsole.generator

import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Genereert geldige aanlever-opdrachten voor de demo. Deterministisch: dezelfde `Random`
 * en `Clock` geven dezelfde uitvoer, zodat de logica testbaar is zonder infrastructuur.
 *
 * Alle berichten dragen dezelfde geautoriseerde afzender-OIN — de profiel-stub geeft
 * alleen voor die OIN een actieve voorkeur. De herkenbare afzendernaam zit in het onderwerp.
 */
class DemoBerichtGenerator(
    private val personas: List<Persona>,
    private val afzenderOin: String,
    private val magazijnOins: List<String>,
    private val klok: Clock,
) {

    init {
        require(personas.isNotEmpty()) { "minstens één persona vereist" }
        require(magazijnOins.isNotEmpty()) { "minstens één magazijn vereist" }

        // Fail-fast: elke persona moet een geldig identificatienummer zijn, anders weigert
        // het magazijn het straks met een 400 die pas tijdens de demo opvalt.
        personas.forEach { Identificatiecheck.valideer(it.type, it.waarde) }
    }

    fun genereer(aantal: Int, random: Random): List<AanleverOpdracht> =
        (0 until aantal).map { index ->
            val persona = personas[random.nextInt(personas.size)]
            val magazijn = magazijnOins[random.nextInt(magazijnOins.size)]
            val afzenderNaam = AFZENDER_NAMEN[random.nextInt(AFZENDER_NAMEN.size)]
            val dagenTerug = random.nextInt(1, 90).toLong()

            val verzoek = AanleverVerzoek(
                afzender = afzenderOin,
                ontvanger = OntvangerDto(persona.type, persona.waarde),
                onderwerp = "$afzenderNaam — ${ONDERWERPEN[random.nextInt(ONDERWERPEN.size)]} (#${index + 1})",
                inhoud = "Beste ${persona.naam},\n\nDit is een demo-bericht van $afzenderNaam. " +
                    "Er is geen actie vereist; dit dient uitsluitend ter demonstratie.\n\n" +
                    "Met vriendelijke groet,\n$afzenderNaam",
                publicatietijdstip = klok.instant().minus(dagenTerug, ChronoUnit.DAYS).toString(),
            )

            AanleverOpdracht(magazijn, verzoek)
        }

    private companion object {

        val AFZENDER_NAMEN = listOf("Belastingdienst", "KVK", "RVO", "UWV")

        val ONDERWERPEN = listOf(
            "Voorlopige aanslag 2026",
            "Inschrijving bijgewerkt",
            "Subsidieaanvraag ontvangen",
            "Jaaropgave beschikbaar",
            "Herinnering aangifte",
        )
    }
}
