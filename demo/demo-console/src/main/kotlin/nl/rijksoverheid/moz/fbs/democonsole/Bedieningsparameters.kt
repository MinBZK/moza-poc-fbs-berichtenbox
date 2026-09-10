package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.BadRequestException

/** Hoeveel tekens van een afgewezen waarde er in de melding worden herhaald. */
private const val MAX_ECHO = 20

/**
 * Leest een getalveld van het bedieningspaneel: een aantal berichten, een interval in seconden.
 *
 * De grenzen staan bij de aanroeper en niet alleen in het invoerveld, want dat het invoerveld
 * dezelfde grenzen kent is geen contract: deze adressen staan open op de origin van het paneel.
 * Zonder ondergrens meldt een ronde van nul berichten zich als geslaagd terwijl er niets gebeurde;
 * zonder bovengrens houdt één klik de omgeving minutenlang bezig met een antwoord dat pas daarna
 * komt. Voor het interval is de reden een andere — een vergeten stroom die blijft pompen — en die
 * grens hoort dan ook bij `TempoService`.
 *
 * De parameter komt als tekst binnen en niet als `Int`: laat je JAX-RS de omzetting doen, dan
 * beantwoordt hij een mislukking met een 404 — dat leest als "dit adres bestaat niet" terwijl er
 * een cijfer verkeerd staat. En `BadRequestException` en geen `require()`: [DemoFoutMapper]
 * vertaalt alleen een `WebApplicationException` naar zijn eigen status, dus een `require()` zou een
 * bedieningsfout als HTTP 500 tonen. Voor [standaard] geldt het omgekeerde: een default buiten de
 * grenzen is een fout van de aanroeper, en die hoort juist luid te falen.
 *
 * Een waarde die alleen uit witruimte bestaat telt als niet opgegeven, net als een afwezige
 * parameter; een getal mét witruimte eromheen wordt geweigerd. De default staat als [standaard] bij
 * de aanroeper en niet in `@DefaultValue`: die vervangt alleen een afwezige waarde, dus een lege
 * `?aantal=` zou er nog steeds doorheen komen. `@DefaultValue("")` blijft daarnaast nodig, want
 * zonder die annotatie wordt een afwezige parameter `null` in een niet-nullable parameter.
 */
internal fun heelGetal(
    naam: String,
    waarde: String,
    standaard: Int,
    grenzen: IntRange,
    eenheid: String = "",
): Int {
    require(standaard in grenzen) { "default $standaard voor $naam valt buiten $grenzen" }

    if (waarde.isBlank()) return standaard

    val grens = "${grenzen.first} en ${grenzen.last}" + if (eenheid.isEmpty()) "" else " $eenheid"

    val getal = waarde.toIntOrNull()
        ?: throw BadRequestException("$naam moet een geheel getal zijn tussen $grens, was: '${echo(waarde)}'")

    if (getal !in grenzen) throw BadRequestException("$naam moet tussen $grens liggen, was: $getal")

    return getal
}

/**
 * De afgewezen waarde zoals hij herhaald mag worden. [DemoFoutMapper] logt elke weigering, dus wat
 * hier doorheen komt belandt in de applicatielog: een regeleinde zou daar een tweede regel
 * schrijven die als een echte gebeurtenis leest, en een onbegrensde waarde zou de log van een
 * gedeelde omgeving kunnen vullen.
 */
private fun echo(waarde: String): String =
    waarde.take(MAX_ECHO).map { if (it.isISOControl()) ' ' else it }.joinToString("")
