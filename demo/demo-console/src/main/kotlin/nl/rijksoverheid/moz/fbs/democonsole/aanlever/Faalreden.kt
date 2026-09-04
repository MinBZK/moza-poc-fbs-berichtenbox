package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import jakarta.ws.rs.core.Response

/**
 * Waarom een aanlevering niet aankwam, in één zin voor de bediener van het paneel.
 *
 * De faalmodi zien er in het paneel identiek uit — "1 mislukt" — terwijl ze om verschillende
 * reacties vragen: een storing uitzetten, een ondernemer laten registreren, of de omgeving alsnog
 * inrichten. Deze zinnen maken dat verschil zichtbaar zonder dat iemand de logs opent.
 *
 * De organisatie-OIN mag er voluit in: dat is een publiek organisatienummer. De ontvanger komt hier
 * bewust niet langs — een identificatienummer van een persoon hoort niet in een melding die iemand
 * tijdens een demonstratie op een scherm zet.
 */
internal object Faalreden {

    fun geenMagazijn(magazijnOin: String): String =
        "voor organisatie $magazijnOin is in deze omgeving geen magazijn-adres ingericht"

    fun onbereikbaar(magazijnOin: String): String =
        "magazijn $magazijnOin was niet bereikbaar — staat er nog een storing aan?"

    fun vanStatus(magazijnOin: String, status: Int): String = when (status) {
        Response.Status.FORBIDDEN.statusCode ->
            "organisatie $magazijnOin weigert het bericht — die ondernemer staat daar niet als deelnemer geregistreerd"

        Response.Status.BAD_REQUEST.statusCode ->
            "magazijn $magazijnOin keurde het bericht af op de inhoud"

        else -> "magazijn $magazijnOin antwoordde met HTTP $status"
    }

    /**
     * Eén regel voor een hele ronde. Bij honderd berichten hoeft de bediener geen honderd redenen:
     * de meest voorkomende wijst aan waar hij naartoe moet, de rest staat in het log.
     *
     * `groupingBy` telt in volgorde van eerste voorkomen en `maxByOrNull` pakt bij gelijke stand de
     * eerste — dus dezelfde ronde levert dezelfde zin op, en bij gelijkspel wint wat het eerst
     * misging.
     */
    fun samenvatting(redenen: List<String>): String? {
        val perReden = redenen.groupingBy { it }.eachCount()
        val (reden, aantal) = perReden.maxByOrNull { it.value } ?: return null

        if (perReden.size == 1) return "Reden: $reden."

        return "Meest voorkomende reden ($aantal van de ${redenen.size}): $reden."
    }
}
