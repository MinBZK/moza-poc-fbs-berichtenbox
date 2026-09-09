package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.smallrye.config.ConfigMapping
import java.util.Optional
import java.util.OptionalInt

/**
 * De vulling die de simulator zichzelf bij het opstarten geeft:
 * `magazijnsimulator.basisvulling.{ontvangers,berichten-per-magazijn,bijlage-elke}`.
 *
 * De ontvangers staan hier en niet in de code, om dezelfde reden dat de magazijnenset dat doet: één
 * generator-artefact vult beide, zodat de set magazijnen en de ondernemers die erin post hebben niet
 * uit elkaar kunnen lopen. De simulator vult berichtenbakken; wie de ondernemers zijn, is niet aan
 * hem.
 *
 * Alles is optioneel. Zonder [ontvangers] vult de simulator niet — een omgeving die dit niet nodig
 * heeft (een testrun, een laptop) hoort er niets van te merken.
 */
@ConfigMapping(prefix = "magazijnsimulator.basisvulling")
interface BasisvullingConfig {

    /** Voor wie er post klaarstaat, in de vorm `<TYPE>:<WAARDE>` van `X-Ontvanger`. */
    fun ontvangers(): Optional<List<String>>

    /** Hoeveel berichten elke ontvanger per magazijn krijgt; afwezig betekent de standaardhoeveelheid. */
    fun berichtenPerMagazijn(): OptionalInt

    /** Elk hoeveelste bericht een bijlage krijgt; afwezig betekent de standaardverhouding. */
    fun bijlageElke(): OptionalInt
}
