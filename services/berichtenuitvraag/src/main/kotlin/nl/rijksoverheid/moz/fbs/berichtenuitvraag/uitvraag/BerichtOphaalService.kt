package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.InternalServerErrorException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * Bericht-detail uit de sessiecache; bijlagen rechtstreeks uit het magazijn
 * (passthrough). `haalBijlage` levert `(mimeType, bytes)`: de resource (Task 9)
 * legt het mimeType op een request-property zodat [BijlageContentTypeFilter]
 * de response-`Content-Type` kan overrulen.
 *
 * Bytes worden in een ByteArray geladen — bij PoC-MAX 1 MiB is dat acceptabel.
 * Bij grotere bijlagen later: InputStream-streaming.
 */
@ApplicationScoped
class BerichtOphaalService(
    @RestClient private val sessiecache: SessiecacheClient,
    @RestClient private val magazijn: MagazijnClient,
) {
    fun haalBericht(xOntvanger: String, berichtId: UUID): Bericht =
        sessiecache.bericht(xOntvanger, berichtId)

    fun haalBijlage(xOntvanger: String, berichtId: UUID, bijlageId: UUID): Pair<String, ByteArray> {
        val response = magazijn.bijlage(xOntvanger, berichtId, bijlageId)

        try {
            if (response.status >= 400) {
                throw InternalServerErrorException("magazijn-bijlage gaf status ${response.status}")
            }

            val mimeType = response.mediaType?.toString()
                ?: throw InternalServerErrorException("magazijn-bijlage zonder Content-Type")
            val bytes = response.readEntity(ByteArray::class.java)

            return mimeType to bytes
        } finally {
            response.close()
        }
    }
}
