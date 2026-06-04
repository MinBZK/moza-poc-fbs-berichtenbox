package nl.rijksoverheid.moz.fbs.berichtenuitvraag.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AanmeldDeduplicatie
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AanmeldService
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AangemeldBerichtData
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AangemeldCloudEvent
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AangemeldOntvanger
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AfzenderMagazijnIndex
import jakarta.ws.rs.WebApplicationException
import java.time.Instant
import java.util.UUID

/**
 * Fuzz de CloudEvent-verwerking met willekeurige, extern-aangeleverde veldwaarden.
 * Security-invariant (de payload komt van een externe organisatie, CRLF/injectie-/
 * PII-risico): [AanmeldService.verwerk] mag op géén enkele input een ongecontroleerde
 * exception gooien — uitsluitend een [WebApplicationException] (→ nette 4xx/5xx) of
 * normaal terugkeren. Een NPE/IllegalArgumentException/etc. die ontsnapt zou een 500
 * met mogelijk lekkende detail betekenen en faalt de fuzz.
 *
 * De buren zijn gemockt (geen Redis/cache): de aandacht ligt op het parse-/validatie-
 * pad. De magazijn-index accepteert elke OIN, zodat ook het volledige schrijf-pad
 * bereikbaar is voor geldige combinaties.
 */
object AanmeldCloudEventFuzzer {

    private val service: AanmeldService = bouwService()

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val event = AangemeldCloudEvent(
            id = data.consumeString(32),
            source = data.consumeString(48),
            specversion = data.consumeString(8),
            type = data.consumeString(48),
            subject = data.consumeString(32),
            time = null,
            datacontenttype = null,
            dataschema = null,
            data = AangemeldBerichtData(
                berichtId = UUID.randomUUID(),
                afzender = data.consumeString(24),
                ontvanger = AangemeldOntvanger(data.consumeString(8), data.consumeString(12)),
                onderwerp = data.consumeString(64),
                inhoud = data.consumeRemainingAsString(),
                tijdstipOntvangst = null,
                publicatietijdstip = Instant.EPOCH,
            ),
        )

        try {
            service.verwerk(event)
        } catch (_: WebApplicationException) {
            // Verwachte, gecontroleerde uitkomst (4xx/5xx) — geen invariant-breuk.
        }
    }

    private fun bouwService(): AanmeldService {
        // Relaxed: vermijdt een expliciete stub op schrijfBericht(Identificatienummer, …)
        // — MockK kan voor de value-class-parameter geen any()-matcher bouwen. De
        // returnwaarde wordt door de service genegeerd.
        val sessiecache = mockk<Sessiecache>(relaxed = true)

        val dedup = mockk<AanmeldDeduplicatie>()
        every { dedup.eerstgezien(any()) } returns true
        every { dedup.verwijder(any()) } just Runs

        val index = mockk<AfzenderMagazijnIndex>()
        every { index.magazijnVoor(any()) } returns "magazijn-a"

        return AanmeldService(sessiecache, dedup, index, mockk<LogboekContext>(relaxed = true))
    }
}
