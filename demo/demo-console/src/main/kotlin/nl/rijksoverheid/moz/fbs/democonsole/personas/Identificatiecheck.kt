package nl.rijksoverheid.moz.fbs.democonsole.personas

/**
 * Minimale identificatienummer-validatie voor de demo-personas; een uitgeklede kopie van
 * `Identificatienummer` in fbs-common, die deze module bewust niet als dependency heeft — de
 * afweging staat in `demo/demo-console/pom.xml`. Doel is fail-fast bij een typfout in de
 * persona-lijst, zodat het magazijn straks geen 400 geeft midden in een demo.
 *
 * Geen enkele melding echoot de aangeboden waarde, ook niet die van `type`: deze meldingen
 * belanden via het opstarten in de applicatielog, en wie `type` en `waarde` verwisselt zou daar
 * anders zijn identificatienummer in terugvinden. De persona-id die de aanroeper eraan plakt is
 * de locator; wat toegestaan is staat in de melding zelf.
 */
object Identificatiecheck {

    private const val TOEGESTAAN = "BSN, RSIN, KVK"

    private val ELFPROEF_GEWICHTEN = intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, -1)

    fun valideer(type: String, waarde: String) {
        when (type) {
            "BSN", "RSIN" -> vereisElfproef(type, waarde)
            "KVK" -> require(waarde.matches(Regex("^[0-9]{8}$")) && waarde != "00000000") {
                "$type moet 8 cijfers zijn (niet louter nullen)"
            }

            else -> throw IllegalArgumentException("onbekend ontvanger-type; toegestaan: $TOEGESTAAN")
        }
    }

    private fun vereisElfproef(type: String, waarde: String) {
        require(waarde.matches(Regex("^[0-9]{9}$")) && waarde != "000000000") {
            "$type moet 9 cijfers zijn (niet louter nullen)"
        }

        val som = waarde.mapIndexed { index, teken -> Character.getNumericValue(teken) * ELFPROEF_GEWICHTEN[index] }.sum()

        require(som % 11 == 0) { "$type doorstaat de elfproef niet" }
    }
}
