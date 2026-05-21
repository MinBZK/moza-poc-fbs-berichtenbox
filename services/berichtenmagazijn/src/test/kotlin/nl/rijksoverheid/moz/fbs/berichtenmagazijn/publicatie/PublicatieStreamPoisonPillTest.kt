package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Borgt dat [PublicatieStream.pollronde]:
 *  - een `RuntimeException` uit [PublicatieClaimVerwerker] niet doorlaat (anders
 *    deactiveert Quarkus' scheduler de baan in sommige versies),
 *  - een ronde overslaat na [PublicatieConfig.Polling.maxOpeenvolgendeFouten]
 *    opeenvolgende mislukkingen (poison-pill protectie tegen tight-loop),
 *  - de teller reset bij een succesvolle ronde.
 */
class PublicatieStreamPoisonPillTest {

    private val maxFouten = 3
    private val verwerker = mockk<PublicatieClaimVerwerker>()
    private val polling = mockk<PublicatieConfig.Polling>()
    private val config = mockk<PublicatieConfig>()
    private val stream = PublicatieStream(verwerker, config)

    init {
        every { config.batchGrootte() } returns 5
        every { config.polling() } returns polling
        every { polling.maxOpeenvolgendeFouten() } returns maxFouten
    }

    @Test
    fun `RuntimeException uit verwerker wordt geslikt en niet doorgegeven`() {
        every { verwerker.verwerkEenClaim() } throws IllegalStateException("kapot")
        // Geen exception verwacht.
        stream.pollronde()
    }

    @Test
    fun `na MAX_OPEENVOLGENDE_FOUTEN slaat de stream een ronde over`() {
        every { verwerker.verwerkEenClaim() } throws IllegalStateException("kapot")
        // Drie fout-rondes vullen de teller.
        repeat(maxFouten) { stream.pollronde() }
        // Vierde ronde moet slaan zonder verwerker aan te roepen.
        stream.pollronde()
        // Borgt dat verwerker niet 4× is aangeroepen — alleen 3×.
        io.mockk.verify(exactly = maxFouten) {
            verwerker.verwerkEenClaim()
        }
    }

    @Test
    fun `succesvolle ronde reset opeenvolgende-fouten teller`() {
        // Eerst twee fouten...
        every { verwerker.verwerkEenClaim() } throws IllegalStateException("kapot")
        repeat(2) { stream.pollronde() }
        // ...dan een succesvolle ronde (1 claim verwerkt, daarna leeg)
        every { verwerker.verwerkEenClaim() } returnsMany listOf(true, false)
        stream.pollronde()
        // Vervolgens kan opnieuw MAX-1 keer falen zonder skip-ronde
        every { verwerker.verwerkEenClaim() } throws IllegalStateException("kapot")
        repeat(maxFouten - 1) { stream.pollronde() }
        // De volgende foutronde mag nog steeds verwerker aanroepen (teller niet vol).
        // Geen exception, geen skip.
        stream.pollronde()
    }

    @Test
    fun `lege ronde (geen claims) telt als succes`() {
        every { verwerker.verwerkEenClaim() } returns false
        repeat(10) { stream.pollronde() }
        // Alleen verwerker.verwerkEenClaim aangeroepen — geen skip-ronde.
        io.mockk.verify(exactly = 10) { verwerker.verwerkEenClaim() }
    }

    @Test
    fun `stopt loop bij batchGrootte`() {
        every { verwerker.verwerkEenClaim() } returns true
        every { config.batchGrootte() } returns 3
        stream.pollronde()
        io.mockk.verify(exactly = 3) { verwerker.verwerkEenClaim() }
    }
}
