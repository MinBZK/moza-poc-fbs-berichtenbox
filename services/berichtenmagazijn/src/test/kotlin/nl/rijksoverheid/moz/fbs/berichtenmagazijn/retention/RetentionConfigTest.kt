package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.Period
import org.junit.jupiter.api.Assertions.assertEquals

@QuarkusTest
class RetentionConfigTest {

    @Inject
    lateinit var config: RetentionConfig

    @Test
    fun `default minimale leeftijd is 7 jaar`() {
        assertEquals(Period.ofYears(7), config.minimaleLeeftijd())
    }

    @Test
    fun `default minimale soft-delete leeftijd is 7 jaar`() {
        assertEquals(Period.ofYears(7), config.minimaleSoftDeleteLeeftijd())
    }

    @Test
    fun `default batch-grootte is 1000`() {
        assertEquals(1000, config.batchGrootte())
    }

    @Test
    fun `cron-expressie heeft 6 velden`() {
        assertEquals(6, config.cron().trim().split(Regex("\\s+")).size)
    }
}
