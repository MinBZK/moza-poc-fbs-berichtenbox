package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Directe-aanroep-test voor [HardDeleteJob.fire]: de cron-trigger zelf testen
 * we niet (Quarkus-eigen), maar de fire-methode moet idempotent doorgeven aan
 * de service. We mocken de service om te isoleren van DB-state.
 */
@QuarkusTest
class HardDeleteJobTest {

    @Inject
    lateinit var job: HardDeleteJob

    private val serviceMock = io.mockk.mockk<HardDeleteService>(relaxed = true)

    @BeforeEach
    fun installMock() {
        io.mockk.every { serviceMock.run() } returns HardDeleteService.RunResultaat(
            totaalVerwijderd = 0,
            fouten = 0,
            ldvFouten = 0,
            durationMs = 0,
        )
        QuarkusMock.installMockForType(serviceMock, HardDeleteService::class.java)
    }

    @Test
    fun `fire roept service-run aan`() {
        job.fire()

        io.mockk.verify(exactly = 1) { serviceMock.run() }
        assertEquals(Unit, Unit) // ankerassert; verify hierboven is de echte check
    }
}
