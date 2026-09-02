package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.GedragModus

/**
 * De vorm van het beheerpad, bewust buiten de gedeelde spec.
 *
 * Het beheerpad hoort bij de simulator en niet bij het contract dat hij naspeelt: de gegenereerde
 * interfaces blijven zo precies wat het echte magazijn ook aanbiedt, en niemand kan het beheerpad
 * per ongeluk voor onderdeel van de spec aanzien.
 */
data class SeedVerzoek(
    /** Voor wie er berichten klaargezet worden, in de vorm `<TYPE>:<WAARDE>` van `X-Ontvanger`. */
    val ontvangers: List<String>,
    /**
     * Hoeveel berichten elke ontvanger per magazijn krijgt.
     *
     * Twintig is niet toevallig: de uitvraag haalt per magazijn één pagina op en het magazijn levert
     * er standaard twintig, dus daarboven ziet de ondernemer niets. Zolang dat gat openstaat
     * (MinBZK/MijnOverheidZakelijk#996) demonstreer je met meer onbedoeld dát gat in plaats van het
     * gedrag dat je wilt tonen. Wie het gat juist wél wil laten zien, zet er bewust meer in.
     */
    val berichtenPerMagazijn: Int = STANDAARD_AANTAL,
    /** Elk hoeveelste bericht een bijlage krijgt; 0 betekent geen bijlagen. */
    val bijlageElke: Int = STANDAARD_BIJLAGE_ELKE,
) {
    companion object {
        const val STANDAARD_AANTAL = 20
        const val STANDAARD_BIJLAGE_ELKE = 4
        const val MAX_AANTAL = 200
    }
}

/**
 * Wat er is klaargezet. [overgeslagen] telt de berichten die er al stonden: vullen is herhaalbaar, en
 * dat verschil zichtbaar maken is beter dan een tweede ronde die "gelukt" meldt zonder dat er iets
 * veranderde.
 */
data class SeedUitkomst(
    val magazijnen: Int,
    val ontvangers: Int,
    val berichten: Int,
    val bijlagen: Int,
    val overgeslagen: Int,
    val duurMs: Long,
)

/** Wat er is opgeruimd. */
data class LeegUitkomst(val berichten: Int, val magazijnenTeruggezet: Int)

/**
 * Het gedrag van één magazijn, zoals het beheerpad het instelt. Alles behalve de modus is optioneel;
 * wat weggelaten wordt, komt uit de standaardwaardes van die modus.
 */
data class GedragVerzoek(
    val modus: GedragModus,
    val latencyP50Ms: Int? = null,
    val latencyP95Ms: Int? = null,
    val foutkans: Double? = null,
    val foutStatus: Int? = null,
)

/**
 * Het gedrag van een reeks magazijnen tegelijk.
 *
 * Eén aanroep en niet honderd losse: een bedieningspaneel dat "zet er k van de honderd op storing"
 * aanbiedt, zou anders bij elke klik honderd verzoeken doen — en dan is de knop trager dan de demo
 * die hij moet ondersteunen.
 */
data class BulkGedragVerzoek(val aanpassingen: List<GedragAanpassing>)

/**
 * Eén regel uit [BulkGedragVerzoek]: welk magazijn, en welk gedrag.
 *
 * De gedrag-velden staan plat naast `oin` en niet in een genest object: zo is één regel uit een
 * bulk letterlijk hetzelfde JSON als een losse [GedragVerzoek] met een OIN erbij, en dat scheelt
 * een bedieningspaneel twee vormen voor dezelfde vraag. [gedrag] houdt het bij één plek die weet
 * welke velden er zijn, zodat een nieuw gedrag-veld hier niet vergeten kan worden.
 */
data class GedragAanpassing(
    val oin: String,
    val modus: GedragModus,
    val latencyP50Ms: Int? = null,
    val latencyP95Ms: Int? = null,
    val foutkans: Double? = null,
    val foutStatus: Int? = null,
) {
    fun gedrag(): GedragVerzoek = GedragVerzoek(
        modus = modus,
        latencyP50Ms = latencyP50Ms,
        latencyP95Ms = latencyP95Ms,
        foutkans = foutkans,
        foutStatus = foutStatus,
    )
}

/**
 * Wat er van een bulk-aanpassing terechtkwam: hoeveel er omstaan, welke OIN's dit magazijn niet
 * simuleert, en welke wél bestaan maar niet weggeschreven konden worden.
 *
 * Het antwoord blijft een 200, ook als er niets is aangepast: de aanroeper kréég antwoord en de
 * lijst zegt precies wat er met elke regel gebeurd is. Een 4xx zou zeggen dat het verzoek niet
 * deugde, terwijl het verzoek prima was en alleen de OIN's er niet zijn.
 */
data class BulkGedragUitkomst(
    val aangepast: Int,
    val onbekend: List<String>,
    val mislukt: List<String> = emptyList(),
)

/** Eén magazijn zoals het beheerpad het toont. */
data class MagazijnOverzicht(
    val oin: String,
    val naam: String,
    val modus: GedragModus,
    val latencyP50Ms: Int,
    val latencyP95Ms: Int,
    val foutkans: Double,
    val foutStatus: Int,
)
