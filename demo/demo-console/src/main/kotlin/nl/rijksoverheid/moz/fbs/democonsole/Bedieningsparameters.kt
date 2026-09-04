package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.BadRequestException

/**
 * Leest een getalveld van het bedieningspaneel: een aantal berichten, een interval in seconden.
 *
 * De grenzen staan in de resource en niet alleen in het invoerveld, want deze adressen worden ook
 * rechtstreeks aangeroepen — vanuit het runbook en tijdens het testen. Zonder ondergrens meldt een
 * ronde van nul berichten zich groen als "0 van 0 aangeleverd", en zonder bovengrens houdt één klik
 * de omgeving minutenlang bezig met een antwoord dat pas daarna komt.
 *
 * De parameter komt als tekst binnen en niet als `Int`: laat je JAX-RS de omzetting doen, dan
 * beantwoordt hij een mislukking met een 404 — dat leest als "dit adres bestaat niet" terwijl er
 * een cijfer verkeerd staat. En `BadRequestException` en geen `require()`: [DemoFoutMapper]
 * vertaalt alleen een `WebApplicationException` naar zijn eigen status, dus een `require()` zou een
 * bedieningsfout als HTTP 500 tonen.
 *
 * Witruimte telt als niet opgegeven, net als een afwezige parameter. Die keuze staat hier en niet
 * bij `@DefaultValue`: die vervangt alleen een afwezige waarde, en dat `?aantal=` er vandaag toch
 * doorheen komt is gedrag van JAX-RS dat een upgrade kan veranderen.
 */
internal fun heelGetal(naam: String, waarde: String, standaard: Int, grenzen: IntRange): Int {
    if (waarde.isBlank()) return standaard

    val getal = waarde.toIntOrNull()
        ?: throw BadRequestException(
            "$naam moet een geheel getal zijn tussen ${grenzen.first} en ${grenzen.last}, was: '$waarde'",
        )

    if (getal !in grenzen) {
        throw BadRequestException("$naam moet tussen ${grenzen.first} en ${grenzen.last} liggen, was: $getal")
    }

    return getal
}
