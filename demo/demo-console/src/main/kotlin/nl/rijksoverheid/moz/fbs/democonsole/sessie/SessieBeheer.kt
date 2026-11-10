package nl.rijksoverheid.moz.fbs.democonsole.sessie

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.time.Instant

/** De lopende sessies per persona, voor het info-blad van het paneel. */
data class Sessieoverzicht(val personas: List<PersonaSessies>)

/** [persona] is de persona-id, dezelfde als in `personas` van `/api/demo/omgeving`. */
data class PersonaSessies(val persona: String, val sessies: List<LopendeSessie>)

/**
 * Een lopende sessie zoals het paneel haar toont: wanneer ze verloopt zonder activiteit, en haar
 * plafond. Geen sessie-id en geen hash; [verwijzing] wijst haar aan (zie [Sessieverwijzer]). Het
 * begin staat niet in Redis: de start is alleen af te leiden uit het plafond als de berichtenbox geen
 * eigen plafond meegaf.
 */
data class LopendeSessie(val verwijzing: String, val verloopt: Instant, val plafond: Instant)

data class Beeindigd(val beeindigdeSessies: Int)

/**
 * Toont en beëindigt de sessies van de sessiecache. "Lopend" betekent hier wat het in de sessiecache
 * betekent: een verloop later dan nu en een plafond. Beëindigen laat een sessie verlopen (zie
 * [SessieOpslag.laatVerlopen]); de berichtenbox van die sessie krijgt bij zijn volgende verzoek 401 en
 * start een nieuwe sessie.
 */
@ApplicationScoped
class SessieBeheer(
    private val opslag: SessieOpslag,
    private val personaService: PersonaService,
    private val verwijzer: Sessieverwijzer,
) {

    fun overzicht(): Sessieoverzicht {
        val perKenmerk = lopende(SessiecacheSleutels.ALLE_SESSIES).groupBy { it.administratie.kenmerk }

        return Sessieoverzicht(
            personaService.alle().map { persona ->
                PersonaSessies(
                    persona.id,
                    perKenmerk[kenmerk(persona)].orEmpty().sortedBy { it.verloopt }.map { it.sessie },
                )
            },
        )
    }

    /** Alle lopende sessies, ook van ontvangers die geen persona zijn. */
    fun telLopende(): Int = lopende(SessiecacheSleutels.ALLE_SESSIES).size

    /** Het aantal beëindigde sessies: 0 als [verwijzing] geen lopende sessie (meer) aanwijst. */
    fun beeindig(verwijzing: String): Int {
        val gevonden = lopende(SessiecacheSleutels.ALLE_SESSIES).firstOrNull { it.sessie.verwijzing == verwijzing } ?: return 0

        return opslag.laatVerlopen(gevonden.administratie, listOf(gevonden.hash))
    }

    /** Beëindigt de sessies die nu lopen; een sessie die intussen start, blijft lopen. */
    fun beeindigVan(persona: DemoPersona): Int =
        lopende(SessiecacheSleutels.sessiesVan(kenmerk(persona)))
            .groupBy { it.administratie }
            .entries
            .sumOf { (administratie, sessies) -> opslag.laatVerlopen(administratie, sessies.map { it.hash }) }

    private fun lopende(patroon: String): List<Lopend> {
        val nu = opslag.nuMs()

        return opslag.sleutels(patroon)
            .mapNotNull(SessiecacheSleutels::administratie)
            .flatMap { administratie -> lopendeIn(administratie, nu) }
    }

    private fun lopendeIn(administratie: Sessieadministratie, nu: Long): List<Lopend> {
        val leden = opslag.leden(administratie).filter { it.verloopMs > nu }

        if (leden.isEmpty()) return emptyList()

        val plafonds = opslag.plafonds(administratie, leden.map { it.hash })

        return leden.mapNotNull { lid ->
            plafonds[lid.hash]?.let { plafond ->
                Lopend(
                    administratie,
                    lid.hash,
                    LopendeSessie(
                        verwijzer.voor(administratie.sessies, lid.hash),
                        Instant.ofEpochMilli(lid.verloopMs),
                        Instant.ofEpochMilli(plafond),
                    ),
                )
            }
        }
    }

    private fun kenmerk(persona: DemoPersona): String =
        SessiecacheSleutels.kenmerk(Identificatienummer.fromHeader(persona.ontvanger))

    private class Lopend(val administratie: Sessieadministratie, val hash: String, val sessie: LopendeSessie) {

        val verloopt: Instant get() = sessie.verloopt
    }
}
