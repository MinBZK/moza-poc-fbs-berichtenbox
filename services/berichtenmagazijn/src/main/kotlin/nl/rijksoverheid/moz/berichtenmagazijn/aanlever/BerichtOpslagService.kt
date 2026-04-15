package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtEntity
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class BerichtOpslagService(
    private val repository: BerichtRepository,
) {

    // Thresholds zijn voorlopige defaults voor de PoC; afstemmen zodra load-testdata
    // beschikbaar is. skipOn: client-fouten (validatie, illegale argumenten) mogen
    // het circuit niet openen — die reflecteren een verkeerde request, niet een
    // infrastructuurprobleem.
    @CircuitBreaker(
        requestVolumeThreshold = 20,
        failureRatio = 0.5,
        delay = 5_000L,
        successThreshold = 2,
        skipOn = [
            IllegalArgumentException::class,
            ConstraintViolationException::class,
            WebApplicationException::class,
        ],
    )
    @Transactional
    fun opslaanBericht(
        afzender: String,
        ontvanger: String,
        onderwerp: String,
        inhoud: String,
    ): Bericht {
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = afzender,
            ontvanger = ontvanger,
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstip = Instant.now(),
        )
        repository.persist(BerichtEntity.fromDomain(bericht))
        return bericht
    }
}
