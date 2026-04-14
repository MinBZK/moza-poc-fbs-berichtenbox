package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
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

    @CircuitBreaker(
        requestVolumeThreshold = 20,
        failureRatio = 0.5,
        delay = 5_000L,
        successThreshold = 2,
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
