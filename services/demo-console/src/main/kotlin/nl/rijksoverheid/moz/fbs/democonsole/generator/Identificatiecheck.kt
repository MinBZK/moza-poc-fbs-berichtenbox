package nl.rijksoverheid.moz.fbs.democonsole.generator

/**
 * Minimale identificatienummer-validatie voor de demo-personas. Geïnlined i.p.v.
 * hergebruik van fbs-common, omdat die library de productie-JAX-RS-stack (LDV-filters)
 * meebrengt die een wegwerp-console niet hoort te erven. Doel is fail-fast bij een typfout
 * in de persona-lijst, zodat het magazijn straks geen 400 midden in een demo geeft.
 */
object Identificatiecheck {

    private val ELFPROEF_GEWICHTEN = intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, -1)

    fun valideer(type: String, waarde: String) {
        when (type) {
            "BSN", "RSIN" -> vereisElfproef(type, waarde)
            "KVK" -> require(waarde.matches(Regex("^[0-9]{8}$")) && waarde != "00000000") {
                "$type moet 8 cijfers zijn (niet louter nullen), was: '$waarde'"
            }

            else -> throw IllegalArgumentException("onbekend ontvanger-type: $type")
        }
    }

    private fun vereisElfproef(type: String, waarde: String) {
        require(waarde.matches(Regex("^[0-9]{9}$")) && waarde != "000000000") {
            "$type moet 9 cijfers zijn (niet louter nullen), was: '$waarde'"
        }

        val som = waarde.mapIndexed { index, teken -> Character.getNumericValue(teken) * ELFPROEF_GEWICHTEN[index] }.sum()

        require(som % 11 == 0) { "$type doorstaat de elfproef niet: '$waarde'" }
    }
}
