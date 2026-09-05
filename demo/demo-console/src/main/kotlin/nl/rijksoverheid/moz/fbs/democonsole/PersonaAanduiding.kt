package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException

/**
 * Acht tot en met negentien cijfers in één waarde: de lengte van een KVK-nummer, BSN of RSIN.
 * Twintig cijfers is een OIN, een publiek organisatienummer.
 *
 * Geteld over de héle waarde en niet als aaneengesloten reeks: `999-993-653` is even goed een
 * burgerservicenummer, en een scheidingsteken is precies wat er tussen staat bij wie het uit een
 * ander scherm overneemt.
 */
private val NUMMERLENGTES = 8..19

/**
 * Wat onverkort in een melding mag. Ruim genoeg voor elke sleutel die `demo.personas.*` kan dragen,
 * maar zonder witruimte, aanhalingstekens of regeleindes: een melding gaat naar de applicatielog, en
 * een regeleinde zou daar een tweede logregel kunnen verzinnen.
 */
private val VEILIG_IN_MELDING = Regex("""[\w.@+-]{1,64}""")

/**
 * De weigering voor een aanduiding die geen ingerichte persona blijkt te zijn.
 *
 * Draagt de waarde een identificatienummer, dan een 400 die hem niet herhaalt. Een persona
 * aanwijzen met zijn nummer is het te verwachten verkeerde gebruik — het antwoord van de
 * Persona's-knop toont `ontvanger` voluit, dus de bediener heeft dat nummer voor zich — en elke
 * weigering gaat via [DemoFoutMapper] onverkort naar de applicatielog, waar het niet hoort.
 *
 * Pas ná de opzoeking en niet ervoor: een ingerichte persona is per definitie in orde, ook als zijn
 * id toevallig cijfers draagt. Zou deze controle vooraan staan, dan wees hij een persona af die in
 * de keuzelijst gewoon wordt aangeboden — een tweede mening over wat een geldige id is, naast die
 * van de personadienst.
 */
internal fun onbekendePersona(persona: String, kiesEenPersona: String): WebApplicationException =
    if (persona.count(Char::isDigit) in NUMMERLENGTES) {
        BadRequestException("een persona wordt met zijn naam aangewezen, niet met een nummer; $kiesEenPersona")
    } else {
        NotFoundException("onbekende persona ${aanduiding(persona)}; $kiesEenPersona")
    }

/**
 * Hoe een aangeboden waarde in een melding terechtkomt. Het citaat is wat een tikfout aanwijsbaar
 * maakt, dus wat er veilig uitziet gaat voluit mee; de rest wordt benoemd zonder te worden herhaald.
 */
private fun aanduiding(persona: String): String =
    if (VEILIG_IN_MELDING.matches(persona)) "'$persona'" else "de aangeboden waarde"
