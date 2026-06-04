package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Smoke-test voor [HardDeleteLdvLogger.logHardDelete]: verifieert dat de bean
 * vanuit elke thread (i.h.b. de scheduler-thread, geen REST-context) zonder
 * exception aan te roepen is. Geen assert op de log-output zelf — de
 * gestructureerde log-regel is een nice-to-have, niet de invariant onder test.
 */
@QuarkusTest
class HardDeleteLdvLoggerTest {

    @Inject
    lateinit var logger: HardDeleteLdvLogger

    @Test
    fun `logHardDelete crasht niet buiten een REST-context`() {
        val candidate = HardDeleteKandidaat(
            id = 1L,
            berichtId = UUID.randomUUID(),
            ontvangerType = "BSN",
            ontvangerWaarde = "999993653",
            tijdstipOntvangst = Instant.now().minus(3000, ChronoUnit.DAYS),
            verwijderdOp = Instant.now().minus(3000, ChronoUnit.DAYS),
        )

        logger.logHardDelete(candidate)
    }
}
