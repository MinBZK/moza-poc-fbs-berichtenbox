package nl.rijksoverheid.moz.fbs.democonsole.personas

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import nl.rijksoverheid.moz.fbs.demopersonas.MagazijnKennis

/**
 * De personadienst kent de identiteiten, deze module kent de magazijnen waar ze berichten voor
 * krijgen. Wijst een persona naar een magazijn waarvoor hier geen aanlever-URL staat, dan is er
 * niets om aan te leveren en blijft zijn berichtenbox leeg.
 *
 * De generator weigert zo'n persona ook, maar hij ziet er minder: alleen persona's mét magazijnen,
 * en alleen tegen de organisaties waarvoor hij sjablonen heeft. Zijn melding noemt bovendien de
 * persona, terwijl het probleem in `demo.magazijnen` zit.
 */
@ApplicationScoped
class MagazijnKennisUitInrichting(private val config: DemoConfig) : MagazijnKennis {

    override fun vereisBekend(oin: String) {
        val bekend = config.magazijnen().keys

        // Zonder ingericht magazijn wijst de melding naar de configuratie die ontbreekt; is er wél
        // inrichting, dan naar het OIN dat er niet in staat. Anders zoekt de lezer bij de persona
        // terwijl er met die persona niets mis is.
        require(oin in bekend) {
            if (bekend.isEmpty()) "er is geen magazijn ingericht onder demo.magazijnen"
            else "magazijn-OIN '$oin' heeft geen demo.magazijnen-URL"
        }
    }
}
