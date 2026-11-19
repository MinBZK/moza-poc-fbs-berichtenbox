package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.UriBuilder
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * De basis waarop elke HAL-link van het magazijn wordt gebouwd: een absolute-path reference
 * (`/api/v1/…`), zonder schema en host.
 *
 * Schema en host mogen niet uit het verzoek komen. Achter een ingress die TLS beëindigt is het
 * schema van het verzoek `http`: een browser-app op een https-origin blokkeert de link dan als mixed
 * content, en een client die hem volgt stuurt zijn `X-Ontvanger` eerst in klare tekst. De host komt
 * uit de `Host`-header, die de aanroeper zelf kiest. Een geconfigureerde publieke URL lost dat wel
 * op, maar is fout voor een afnemer achter FSC: die praat via zijn eigen outway, en dat adres kent
 * het magazijn niet. Een relatieve verwijzing lost de client op tegen het adres dat hij zelf aanriep
 * (RFC 3986 §5); de spec laat dat toe (`Link.href` is `uri-reference`).
 *
 * Dat klopt zolang geen hop tussen afnemer en magazijn het pad herschrijft. Een FSC-`endpoint_url`
 * met een pad erin is zo'n hop: die moet dan gelijk zijn aan `quarkus.http.root-path`.
 *
 * Het base-path komt uit de spec ([ApiInfo.BASE_PATH]) en niet uit `quarkus.rest.path`: die laatste
 * ligt bij de build vast, en een runtime-override zou de links laten afwijken van de routering
 * zonder dat iets faalt. Een test bewaakt dat de twee gelijk zijn.
 */
@ApplicationScoped
class HalBasis(
    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/") rootPad: String,
) {

    // Vanaf "/": een root-path zonder voorloop-slash zou anders een relative-path reference
    // opleveren, die de client tegen de map van het verzoek oplost.
    private val basis: String = UriBuilder.fromPath("/").path(rootPad).path(ApiInfo.BASE_PATH).build().rawPath

    /** Een nieuwe builder per aanroep: een [UriBuilder] is muteerbaar en niet thread-safe. */
    fun builder(): UriBuilder = UriBuilder.fromPath(basis)
}
