package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException

/**
 * Vanaf hoeveel cijfers een waarde als identificatienummer telt. Acht is de kortste vorm die
 * meetelt — een KVK-nummer; BSN en RSIN zijn er negen — en een bovengrens is er bewust niet: een
 * geplakte `<OIN>-<BSN>` telt er negenentwintig en draagt het nummer net zo goed.
 *
 * Geteld over de héle waarde en niet als aaneengesloten reeks: `999-993-653` is even goed een
 * burgerservicenummer, en een scheidingsteken is precies wat er tussen staat bij wie het uit een
 * ander scherm overneemt.
 */
private const val NUMMER_VANAF = 8

/**
 * De enige uitzondering: een waarde die niets anders is dan een OIN. Dat is een publiek
 * organisatienummer — `DemoPersona` staat het als id uitdrukkelijk toe — en het mag dus voluit in
 * een melding. Op de vorm en niet op een cijfertelling, zodat een OIN mét iets erachter geplakt er
 * niet alsnog onder valt.
 */
private val OIN = Regex("""\d{20}""")

/**
 * Wat onverkort in een melding mag: geen witruimte, aanhalingstekens of regeleindes, en begrensd op
 * lengte. Een melding gaat naar de applicatielog, waar een regeleinde een tweede logregel zou kunnen
 * verzinnen. Een persona-id daarbuiten is niet verboden — de personadienst bepaalt wat een sleutel
 * mag zijn — die wordt alleen benoemd in plaats van geciteerd.
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
    if (persona.count(Char::isDigit) >= NUMMER_VANAF && !OIN.matches(persona)) {
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
