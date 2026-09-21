package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

/**
 * Een organisatie die in de laatste ophaalronde niet leverde; haar berichten, en daarmee haar
 * mappen, ontbreken in de lijst. De voortgangsberichten van het ophalen melden dat al, maar wie de
 * lijst later opvraagt — na verversen, bladeren of terugkomen — zou zonder deze vermelding een
 * onvolledige lijst voor een volledige houden.
 *
 * [status] is de uitkomst zoals ze op de lijn ging, en dus nooit [MagazijnStatus.OK].
 */
data class NietGeleverd(
    val magazijnId: String,
    val naam: String,
    val status: MagazijnStatus,
) {
    init {
        require(status != MagazijnStatus.OK) { "een organisatie die OK leverde, is geleverd" }
    }
}
