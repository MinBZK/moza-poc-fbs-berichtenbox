package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.BadRequestException

/** Een reeks van acht cijfers of langer: de vorm van een KVK-nummer, BSN, RSIN of OIN. */
private val NUMMERREEKS = Regex("""\d{8,}""")

/** Wat een persona-id kan zijn; het is een sleutel uit `demo.personas.*`, geen vrije tekst. */
private val PERSONA_ID = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * Weigert een aanduiding die geen persona-id kán zijn, zónder de aangeboden waarde te herhalen.
 *
 * Elke weigering gaat via [DemoFoutMapper] onverkort naar de applicatielog, en een
 * identificatienummer hoort daar niet. Een persona aanwijzen met zijn nummer is het te verwachten
 * verkeerde gebruik — de keuzelijst van het paneel toont dat nummer naast de naam — dus zonder deze
 * controle zet de bediener het er zelf in. Wat hier doorheen komt mag verderop wél geciteerd worden;
 * dat citaat is juist wat een tikfout aanwijsbaar maakt.
 *
 * De nummerreeks eerst: een waarde die alleen uit cijfers bestaat voldoet óók aan de vorm van een
 * id, en hoort dan de melding te krijgen die zegt wat er mis is. Dat [nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona]
 * een numerieke id al weigert helpt hier niet — die controle kijkt naar wat er is ingericht, niet
 * naar wat een aanroeper aanbiedt.
 */
internal fun vereisPersonaAanduiding(persona: String, kiesEenPersona: String) {
    if (NUMMERREEKS.containsMatchIn(persona)) {
        throw BadRequestException("een persona wordt met zijn naam aangewezen, niet met een nummer; $kiesEenPersona")
    }

    if (!PERSONA_ID.matches(persona)) {
        throw BadRequestException("een persona-id bestaat uit letters, cijfers, '-' en '_'; $kiesEenPersona")
    }
}
