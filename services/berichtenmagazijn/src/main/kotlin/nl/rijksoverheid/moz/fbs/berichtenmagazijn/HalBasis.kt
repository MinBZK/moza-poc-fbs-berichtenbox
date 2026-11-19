package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.UriBuilder
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * De basis waarop elke HAL-link van het magazijn wordt gebouwd: een absolute-path reference
 * (`/api/v1/…`), zonder schema en host.
 *
 * Schema en host mogen niet uit het verzoek komen. Achter een ingress die TLS beëindigt is het
 * schema daar `http`: een https-client blokkeert de link dan als mixed content, of stuurt zijn
 * `X-Ontvanger` bij het volgen eerst in klare tekst. De host komt uit de `Host`-header, die de
 * aanroeper zelf kiest. Een geconfigureerde publieke URL lost dat wel op, maar is fout voor een
 * afnemer achter FSC: die praat via zijn eigen outway, en dat adres kent het magazijn niet. Een
 * relatieve verwijzing lost de client op tegen het adres dat hij zelf aanriep (RFC 3986 §5); de spec
 * laat dat toe (`Link.href` is `uri-reference`).
 */
@ApplicationScoped
class HalBasis(
    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/") rootPad: String,
    @ConfigProperty(name = "quarkus.rest.path") restPad: String,
) {

    private val basis: String = UriBuilder.fromPath(rootPad).path(restPad).build().rawPath

    /** Een nieuwe builder per aanroep: een [UriBuilder] is muteerbaar en niet thread-safe. */
    fun builder(): UriBuilder = UriBuilder.fromPath(basis)
}
