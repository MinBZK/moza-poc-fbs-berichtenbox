package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie

/**
 * Leest de opstartvulling uit de configuratie en keurt hem goed of af.
 *
 * Los van de bean die hem gebruikt, en daarmee te toetsen zonder CDI en zonder database — dezelfde
 * opzet als [nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnConfiguratie], en om
 * dezelfde reden: een fout in de configuratie hoort bij het starten op te vallen en niet pas bij de
 * eerste keer dat de vulling nodig is. Zonder deze scheiding blijft een typefout maandenlang
 * onzichtbaar op een gevulde omgeving, en slaat hij toe op de eerste start ná het opnieuw aanmaken
 * van de database — precies de start waar deze vulling voor bestaat.
 *
 * `check` en niet `require`: dit zijn fouten in de configuratie van de omgeving, geen fouten van een
 * aanroeper. De meldingen noemen de configuratiesleutel en niet het veld uit het beheerpad; wie dit
 * leest heeft een properties-bestand vervangen, geen JSON verstuurd.
 */
object OpstartvullingConfiguratie {

    /** Het verzoek dat bij het opstarten uitgevoerd wordt, of `null` als deze omgeving niets wil. */
    fun valideer(config: OpstartvullingConfig): SeedVerzoek? {
        val ontvangers = config.ontvangers().orElse(emptyList()).filter { it.isNotBlank() }

        if (ontvangers.isEmpty()) return null

        val aantal = config.berichtenPerMagazijn().orElse(SeedVerzoek.STANDAARD_AANTAL)
        val bijlageElke = config.bijlageElke().orElse(SeedVerzoek.STANDAARD_BIJLAGE_ELKE)

        check(aantal in 1..SeedVerzoek.MAX_AANTAL) {
            "$PREFIX.berichten-per-magazijn hoort tussen 1 en ${SeedVerzoek.MAX_AANTAL} te liggen " +
                "(kreeg $aantal)"
        }
        check(bijlageElke >= 0) { "$PREFIX.bijlage-elke mag niet negatief zijn (kreeg $bijlageElke)" }

        ontvangers.forEach { ontvanger ->
            // De waarde staat bewust niet in de melding: bij een BSN is dat een persoonsgegeven, en
            // de plek in de lijst is genoeg om hem in het bestand terug te vinden.
            val plek = "$PREFIX.ontvangers[${ontvangers.indexOf(ontvanger)}]"

            runCatching { Identificatie.uitHeader(ontvanger) }.onFailure { fout ->
                throw IllegalStateException("$plek is geen bruikbare ontvanger: ${fout.message}", fout)
            }
        }

        return SeedVerzoek(ontvangers = ontvangers, berichtenPerMagazijn = aantal, bijlageElke = bijlageElke)
    }

    private const val PREFIX = "magazijnsimulator.opstartvulling"
}
