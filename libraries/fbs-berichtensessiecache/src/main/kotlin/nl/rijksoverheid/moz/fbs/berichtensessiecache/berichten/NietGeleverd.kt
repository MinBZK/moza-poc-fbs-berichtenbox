package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

/**
 * Een organisatie die in de laatste ophaalronde niet leverde; haar berichten, en daarmee haar
 * mappen, ontbreken in de lijst. De voortgangsberichten van het ophalen melden dat al, maar wie de
 * lijst later opvraagt — na verversen, bladeren of terugkomen — zou zonder deze vermelding een
 * onvolledige lijst voor een volledige houden.
 *
 * [status] is een [MagazijnFoutStatus], zodat een organisatie die `OK` leverde hier per type niet
 * in kan staan.
 */
data class NietGeleverd(
    val magazijnId: String,
    val naam: String,
    val status: MagazijnFoutStatus,
)

/**
 * Een organisatie die in de laatste ophaalronde wél leverde, maar niet alles: er staat meer bij
 * haar dan [aantalOpgehaald]. Net als bij [NietGeleverd] meldt de ophaalronde dat al, maar zonder
 * deze vermelding oogt de lijst na verversen of bladeren weer volledig.
 *
 * [totaalBeschikbaar] is wat de organisatie zelf als totaal noemde, of null als ze geen bruikbaar
 * totaal gaf; dan is alleen bekend dát er meer is.
 */
data class Afgekapt(
    val magazijnId: String,
    val naam: String,
    val aantalOpgehaald: Int,
    val totaalBeschikbaar: Long? = null,
) {
    init {
        require(aantalOpgehaald >= 0) { "aantalOpgehaald mag niet negatief zijn" }

        require(totaalBeschikbaar == null || totaalBeschikbaar > aantalOpgehaald) {
            "totaalBeschikbaar moet groter zijn dan aantalOpgehaald, anders is er niets afgekapt"
        }
    }
}

/**
 * Hoe volledig de lijst is die bij de laatste ophaalronde hoort. Volledig betekent
 * [aantalNietGeleverd] `== 0` én een lege [afgekapt]. Het aantal is leidend en niet een lege
 * [nietGeleverd]: het contract staat toe dat er minder namen zijn dan organisaties, en dan hoort
 * het portaal "mogelijk onvolledig" te tonen zonder namen.
 */
data class Volledigheid(
    val aantalNietGeleverd: Int,
    val nietGeleverd: List<NietGeleverd>,
    val afgekapt: List<Afgekapt> = emptyList(),
) {
    init {
        require(aantalNietGeleverd >= 0) { "aantalNietGeleverd mag niet negatief zijn" }
        require(nietGeleverd.size <= aantalNietGeleverd) {
            "nietGeleverd mag niet meer organisaties noemen dan aantalNietGeleverd"
        }
    }

    companion object {
        val VOLLEDIG = Volledigheid(0, emptyList())
    }
}
