package nl.rijksoverheid.moz.fbs.democonsole.opstartvulling

import io.smallrye.config.ConfigMapping
import java.time.Duration

/**
 * De vulling die de console bij het opstarten in de echte magazijnen zet:
 * `opstartvulling.{actief,interval,opgeven-na}`.
 *
 * Niet onder de prefix `demo`: die is geclaimd door `DemoConfig`, en een eigen mapping houdt de
 * test-dubbels van die interface vrij van velden die ze niet gebruiken.
 */
@ConfigMapping(prefix = "opstartvulling")
interface OpstartvullingConfig {

    /** Uit onder test: een testrun hoort geen magazijn te vullen dat toevallig lokaal draait. */
    fun actief(): Boolean

    /** Hoe lang de console wacht voor hij een magazijn dat nog niet klaar was opnieuw probeert. */
    fun interval(): Duration

    /** Na hoeveel tijd sinds de start een magazijn dat nog steeds niet klaar is, wordt opgegeven. */
    fun opgevenNa(): Duration
}
