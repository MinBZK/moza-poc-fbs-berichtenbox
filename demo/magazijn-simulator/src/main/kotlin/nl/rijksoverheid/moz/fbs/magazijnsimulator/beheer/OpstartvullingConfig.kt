package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.smallrye.config.ConfigMapping
import java.util.Optional
import java.util.OptionalInt

/**
 * De vulling die de simulator zichzelf bij het opstarten geeft:
 * `magazijnsimulator.opstartvulling.{ontvangers,berichten-per-magazijn,bijlage-elke}`.
 *
 * De ontvangers staan hier en niet in de code, om dezelfde reden dat de magazijnenset dat doet: één
 * generator-artefact vult beide, zodat de set magazijnen en de ondernemers die erin post hebben niet
 * uit elkaar kunnen lopen. De simulator vult berichtenbakken; wie de ondernemers zijn, is niet aan
 * hem.
 *
 * Ruwe waardes: [OpstartvullingConfiguratie] toetst ze en maakt er een `SeedVerzoek` van. Zonder
 * ontvangers vult de simulator niet — een omgeving die dit niet nodig heeft (een testrun, een
 * laptop) hoort er niets van te merken.
 */
@ConfigMapping(prefix = "magazijnsimulator.opstartvulling")
interface OpstartvullingConfig {

    /** Voor wie er post klaarstaat, in de vorm `<TYPE>:<WAARDE>` van `X-Ontvanger`. */
    fun ontvangers(): Optional<List<String>>

    /** Hoeveel berichten elke ontvanger per magazijn krijgt; afwezig is [SeedVerzoek.STANDAARD_AANTAL]. */
    fun berichtenPerMagazijn(): OptionalInt

    /** Elk hoeveelste bericht een bijlage krijgt; afwezig is [SeedVerzoek.STANDAARD_BIJLAGE_ELKE]. */
    fun bijlageElke(): OptionalInt
}
