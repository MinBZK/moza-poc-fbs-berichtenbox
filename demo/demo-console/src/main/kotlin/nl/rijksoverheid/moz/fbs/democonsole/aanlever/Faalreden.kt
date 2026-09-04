package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import jakarta.ws.rs.core.Response

/**
 * Waarom een aanlevering niet aankwam, in één zin voor de bediener van het paneel.
 *
 * De faalmodi zien er in het paneel identiek uit — "1 mislukt" — terwijl ze om verschillende
 * reacties vragen: een storing uitzetten, de voorkeuren van een ondernemer nalopen, of de omgeving
 * alsnog inrichten. Deze zinnen maken dat verschil zichtbaar zonder dat iemand de logs opent.
 *
 * De organisatie-OIN mag er voluit in: dat is een publiek organisatienummer. De ontvanger komt hier
 * bewust niet langs — een identificatienummer van een persoon hoort niet in een melding die iemand
 * tijdens een demonstratie op een scherm zet.
 */
internal object Faalreden {

    fun geenMagazijn(magazijnOin: String): String =
        "voor organisatie $magazijnOin is in deze omgeving geen magazijn-adres ingericht"

    fun onbereikbaar(magazijnOin: String): String =
        "magazijn $magazijnOin was niet bereikbaar; mogelijk staat er nog een storing aan"

    fun onverwacht(magazijnOin: String, fout: Throwable): String =
        "aanleveren bij magazijn $magazijnOin brak onverwacht af (${fout.javaClass.simpleName})"

    /**
     * Het magazijn formuleert zijn eigen afwijzing nauwkeuriger dan een statuscode hier kan raden:
     * achter één 403 zitten een ontvanger die de profielservice niet kent, een ontvanger zonder
     * actieve voorkeur voor déze afzender, en een auth-fout die de profielservice doorgaf — met elk
     * een ander vervolg. Vandaar `detail` uit het problem+json boven de eigen zin.
     *
     * Dat is veilig op een scherm: elke mapper in de keten schrijft die tekst met de hand, en de
     * call-site-invariant achter `DomainValidationException` houdt invoer van de aanleveraar eruit.
     * Ontbreekt hij, dan blijft de eigen zin over.
     */
    fun vanStatus(magazijnOin: String, status: Int, detail: String? = null): String {
        if (!detail.isNullOrBlank()) return "magazijn $magazijnOin wees het bericht af (HTTP $status): $detail"

        return when (status) {
            Response.Status.FORBIDDEN.statusCode ->
                "organisatie $magazijnOin weigert het bericht; loop de voorkeuren van deze ondernemer " +
                    "in de profielservice na"

            Response.Status.BAD_REQUEST.statusCode -> "magazijn $magazijnOin keurde het bericht af op de inhoud"

            else -> "magazijn $magazijnOin antwoordde met HTTP $status"
        }
    }

    /**
     * Eén regel voor een hele ronde. Bij honderd berichten hoeft de bediener geen honderd redenen:
     * de meest voorkomende wijst aan waar hij naartoe moet, de rest staat in het log. Het aantal
     * onderscheiden redenen staat er wél bij — anders leest "97 van de 100" als de hele verklaring
     * en blijven de drie berichten met een ándere oorzaak na de herstelpoging opnieuw liggen.
     *
     * `groupingBy` telt in volgorde van eerste voorkomen en `maxByOrNull` pakt bij gelijke stand de
     * eerste — dus dezelfde ronde levert dezelfde zin op, en bij gelijkspel wint wat het eerst
     * misging. Dan heet het ook geen "meest voorkomende": er is er geen.
     */
    fun samenvatting(redenen: List<String>): String? {
        val perReden = redenen.groupingBy { it }.eachCount()
        val (reden, aantal) = perReden.maxByOrNull { it.value } ?: return null

        if (perReden.size == 1) return "Reden: ${afgerond(reden)}"

        val aanhef = if (perReden.count { it.value == aantal } == 1) "Meest voorkomende" else "Eerste"

        return "$aanhef van ${perReden.size} redenen ($aantal van de ${redenen.size}): ${afgerond(reden)}"
    }

    /** Precies één afsluitend leesteken, ook als de reden of het magazijn er zelf al op eindigde. */
    private fun afgerond(reden: String): String = if (reden.lastOrNull() in LEESTEKENS) reden else "$reden."

    private val LEESTEKENS = setOf('.', '?', '!')
}
