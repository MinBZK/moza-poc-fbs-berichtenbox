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

    /**
     * Alleen het type, niet de foutmelding: die kan een stuk van de payload dragen, en daar staat
     * het identificatienummer van de ontvanger in. Het log noemt de regel waar het misging.
     */
    fun onverwacht(magazijnOin: String, fout: Throwable): String =
        "aanleveren bij magazijn $magazijnOin brak onverwacht af (${fout.javaClass.simpleName})"

    /**
     * Het magazijn formuleert zijn eigen afwijzing nauwkeuriger dan een statuscode hier kan raden:
     * achter één 403 zitten een ontvanger die de profielservice niet kent, een ontvanger zonder
     * actieve voorkeur voor déze afzender, en een auth-fout die de profielservice doorgaf — met elk
     * een ander vervolg. Vandaar `detail` uit het problem+json boven de eigen zin.
     *
     * Alleen bij een 4xx. Een 5xx zegt niets over dít bericht, en het `detail` erbij ("probeer over
     * 30 seconden opnieuw") leest dan als een afwijzing terwijl er een storing aanstaat — precies
     * het verschil dat deze meldingen moeten maken.
     *
     * Wat de keten in dat veld zet is handgeschreven of enum-gestuurd, en de mappers houden
     * identificatienummers eruit; wél kan er invoer van de aanleveraar in staan (een afgekeurd
     * mimeType bijvoorbeeld), dus de tekst gaat afgekapt en op één regel het scherm op.
     */
    /** Of het zin heeft de reden van het magazijn zelf op te halen; zie [vanStatus]. */
    fun heeftEigenReden(status: Int): Boolean = status in AFWIJZINGEN

    fun vanStatus(magazijnOin: String, status: Int, detail: String? = null): String {
        val eigenReden = when {
            status == Response.Status.FORBIDDEN.statusCode ->
                "organisatie $magazijnOin weigert het bericht; begin bij de voorkeuren van deze " +
                    "ondernemer in de profielservice"

            status == Response.Status.BAD_REQUEST.statusCode ->
                "magazijn $magazijnOin keurde het bericht af op de inhoud"

            status >= SERVERFOUT ->
                "magazijn $magazijnOin kon het bericht niet verwerken (HTTP $status); mogelijk staat " +
                    "er nog een storing aan"

            else -> "magazijn $magazijnOin antwoordde met HTTP $status"
        }

        if (detail.isNullOrBlank() || status !in AFWIJZINGEN) return eigenReden

        return "magazijn $magazijnOin wees het bericht af (HTTP $status): ${opEenRegel(detail)}"
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

    /** Precies één afsluitend leesteken, ook als de reden er zelf al op eindigde. */
    private fun afgerond(reden: String): String = if (reden.lastOrNull() in LEESTEKENS) reden else "$reden."

    /** De melding is één regel naast een teller; wat het magazijn stuurt bepaalt niet de lay-out. */
    private fun opEenRegel(detail: String): String {
        val plat = detail.replace(REGELEINDEN, " ").trim()

        return if (plat.length <= MAX_DETAIL) plat else plat.take(MAX_DETAIL).trimEnd() + "…"
    }

    /** Het beletselteken hoort erbij: een afgekapte reden eindigt erop en is daarmee al afgesloten. */
    private val LEESTEKENS = setOf('.', '?', '!', '…')
    private val REGELEINDEN = Regex("\\s*[\\r\\n]+\\s*")

    /** Ruim genoeg voor elke zin die de keten zelf schrijft, kort genoeg voor één regel op een scherm. */
    private const val MAX_DETAIL = 200

    private const val SERVERFOUT = 500

    /** Alleen een 4xx is een uitspraak over dít bericht; daarbuiten zegt een `detail` iets anders. */
    private val AFWIJZINGEN = 400..499
}
