package nl.rijksoverheid.moz.fbs.democonsole.generator

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
    }

    fun genereer(aantal: Int, random: Random): List<AanleverOpdracht> =
        (0 until aantal).map {
            val persona = personas[random.nextInt(personas.size)]
            val organisatie = organisaties.getValue(persona.magazijnen[random.nextInt(persona.magazijnen.size)])
            val onderwerp = organisatie.onderwerpen[random.nextInt(organisatie.onderwerpen.size)]
            val dagenTerug = random.nextInt(1, 90).toLong()

            val verzoek = AanleverVerzoek(
                afzender = organisatie.oin,
                ontvanger = OntvangerDto(persona.type, persona.waarde),
                onderwerp = onderwerp,
                inhoud = "Beste ${persona.naam},\n\nDit is een demo-bericht van ${organisatie.naam}. " +
                    "Er is geen actie vereist; dit dient uitsluitend ter demonstratie.\n\n" +
                    "Met vriendelijke groet,\n${organisatie.naam}",
                publicatietijdstip = klok.instant().minus(dagenTerug, ChronoUnit.DAYS).toString(),
            )

            AanleverOpdracht(organisatie.oin, verzoek)
        }
}
