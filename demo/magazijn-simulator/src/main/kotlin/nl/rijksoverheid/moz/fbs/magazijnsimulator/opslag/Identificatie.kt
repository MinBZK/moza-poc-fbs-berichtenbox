package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

/** Types identificatienummer die de spec op `X-Ontvanger` en op `Identificatienummer` toelaat. */
enum class IdentificatieType { BSN, RSIN, KVK, OIN }

/**
 * Getypeerd identificatienummer van een afzender of ontvanger.
 *
 * De spec dwingt via de gegenereerde interfaces al de vórm af (regex per type). Wat hier bovenop
 * komt zijn de domein-invarianten die het echte magazijn in zijn servicelaag afdwingt: de elfproef
 * voor BSN en RSIN, en dat een nummer niet geheel uit nullen kan bestaan. Zonder die twee zou de
 * simulator invoer accepteren waar een echt magazijn 400 op geeft — en dan is hij van buiten wél te
 * onderscheiden.
 *
 * Een uitgeklede eigen kopie, net als `Identificatiecheck` in `demo/demo-personas`: deze module
 * heeft bewust geen `fbs-common` als dependency, omdat de JAX-RS-filters daarin de LDV-wrapper
 * vereisen en het logboek per organisatie hoort te zijn — honderd gesimuleerde magazijnen zouden
 * honderd logboeken suggereren die niet bestaan. Gezaghebbend is `Identificatienummer` in
 * `libraries/fbs-common`; wie de elfproef wijzigt, wijzigt alle drie.
 *
 * `toString` maskeert BSN en RSIN: die zijn persoonsgegevens en mogen nooit in een applicatielog
 * belanden. KVK en OIN zijn publiek opvraagbaar en blijven leesbaar.
 */
data class Identificatie(val type: IdentificatieType, val waarde: String) {

    init {
        // Eerst de vorm, dan de inhoud: op een lege of niet-numerieke waarde is "bestaat geheel uit
        // nullen" een misleidende melding.
        when (type) {
            IdentificatieType.BSN, IdentificatieType.RSIN ->
                vereis(waarde.matches(NEGEN_CIJFERS)) { "${type.name} moet 9 cijfers zijn" }

            IdentificatieType.KVK -> vereis(waarde.matches(ACHT_CIJFERS)) { "KVK moet 8 cijfers zijn" }
            IdentificatieType.OIN -> vereis(waarde.matches(TWINTIG_CIJFERS)) { "OIN moet 20 cijfers zijn" }
        }

        vereis(waarde.any { it != '0' }) { "${type.name} kan niet geheel uit nullen bestaan" }

        if (type == IdentificatieType.BSN || type == IdentificatieType.RSIN) {
            vereis(doorstaatElfproef(waarde)) { "${type.name} doorstaat de elfproef niet" }
        }
    }

    override fun toString(): String = when (type) {
        IdentificatieType.BSN, IdentificatieType.RSIN -> "${type.name}:***"
        else -> "${type.name}:$waarde"
    }

    companion object {
        private val NEGEN_CIJFERS = Regex("^[0-9]{9}$")
        private val ACHT_CIJFERS = Regex("^[0-9]{8}$")
        private val TWINTIG_CIJFERS = Regex("^[0-9]{20}$")

        private val TOEGESTANE_TYPES = IdentificatieType.entries.map { it.name }.toSet()

        /** Gewichten van de elfproef; het laatste cijfer telt negatief mee. */
        private val ELFPROEF_GEWICHTEN = intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, -1)

        /**
         * Leest de `X-Ontvanger`-header in de vorm `<TYPE>:<WAARDE>`. De vorm is op spec-niveau al
         * afgedwongen door de regex op de gegenereerde interface; deze functie zet om naar het
         * domein en laat de invarianten door de constructor afdwingen.
         */
        fun uitHeader(header: String): Identificatie {
            val delen = header.split(':', limit = 2)

            vereis(delen.size == 2) { "X-Ontvanger moet de vorm <TYPE>:<WAARDE> hebben" }

            // Zonder de aangeboden waarde: op het beheerpad komt deze functie langs een lijst die
            // geen spec-regex heeft gepasseerd, en dan zou een verkeerd om getypte `123456782:BSN`
            // een BSN in de foutmelding zetten.
            vereis(delen[0] in TOEGESTANE_TYPES) {
                "Onbekend identificatienummer-type; toegestaan: ${TOEGESTANE_TYPES.joinToString()}"
            }

            val type = IdentificatieType.valueOf(delen[0])

            return Identificatie(type, delen[1])
        }

        private fun doorstaatElfproef(waarde: String): Boolean =
            waarde.mapIndexed { index, teken -> teken.digitToInt() * ELFPROEF_GEWICHTEN[index] }.sum() % 11 == 0
    }
}
