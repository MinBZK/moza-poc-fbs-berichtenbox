package nl.rijksoverheid.moz.fbs.democonsole.personas

/**
 * Minimale identificatienummer-validatie voor de demo-personas; een uitgeklede kopie van
 * `Identificatienummer` in fbs-common. Die library niet als dependency, omdat haar filters en
 * boot-validators (LDV, TLS, Redis) zich in deze module vanzelf zouden aanzetten. Doel is
 * fail-fast bij een typfout in de persona-lijst, zodat het magazijn straks geen 400 geeft
 * midden in een demo.
 *
 * De waarde staat bewust in géén enkele foutmelding: die meldingen belanden via het opstarten
 * in de applicatielog, en daar hoort een identificatienummer niet in. De configuratieregel zelf
 * wijst de operator naar de foute waarde.
 */
object Identificatiecheck {

    private val ELFPROEF_GEWICHTEN = intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, -1)

    fun valideer(type: String, waarde: String) {
        when (type) {
            "BSN", "RSIN" -> vereisElfproef(type, waarde)
            "KVK" -> require(waarde.matches(Regex("^[0-9]{8}$")) && waarde != "00000000") {
                "$type moet 8 cijfers zijn (niet louter nullen)"
            }

            else -> throw IllegalArgumentException("onbekend ontvanger-type: '$type'")
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
