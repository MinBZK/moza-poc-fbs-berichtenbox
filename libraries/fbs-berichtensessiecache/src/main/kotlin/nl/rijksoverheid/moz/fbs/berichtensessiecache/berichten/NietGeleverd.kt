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
 * Hoe volledig de lijst is die bij de laatste ophaalronde hoort. Volledig betekent
 * [aantalNietGeleverd] `== 0`, níet een lege [nietGeleverd]: een ronde van vóórdat de namen
 * bewaard werden, telt wel wie niet leverde maar weet niet wie. Dan is [nietGeleverd] korter dan
 * [aantalNietGeleverd], en moet het portaal "mogelijk onvolledig" tonen zonder namen.
 */
data class Volledigheid(
    val aantalNietGeleverd: Int,
    val nietGeleverd: List<NietGeleverd>,
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
