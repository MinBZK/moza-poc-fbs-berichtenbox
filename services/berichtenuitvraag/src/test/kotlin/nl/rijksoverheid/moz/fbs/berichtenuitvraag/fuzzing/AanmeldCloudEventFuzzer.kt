package nl.rijksoverheid.moz.fbs.berichtenuitvraag.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.smallrye.mutiny.Multi
import jakarta.ws.rs.WebApplicationException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AanmeldDeduplicatie
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AanmeldService
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AangemeldBerichtData
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AangemeldCloudEvent
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AangemeldOntvanger
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld.AfzenderMagazijnIndex
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
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
 * De buren zijn handgeschreven fakes (geen Redis/cache en geen mock-framework: MockK
 * genereert bytecode via een eigen agent en botst met de Jazzer-instrumentatie in de
 * fuzz-runtime). De aandacht ligt op het parse-/validatiepad; de magazijn-index
 * accepteert elke OIN zodat ook het volledige schrijfpad bereikbaar is voor geldige
 * combinaties.
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
        val sessiecache = object : Sessiecache {
            override fun lijst(
                ontvanger: Identificatienummer,
                pagina: Int?,
                paginaGrootte: Int?,
                afzender: String?,
                map: String?,
            ): BerichtenPagina = throw UnsupportedOperationException()

            override fun zoek(
                ontvanger: Identificatienummer,
                q: String,
                pagina: Int?,
                paginaGrootte: Int?,
                afzender: String?,
                map: String?,
            ): BerichtenPagina = throw UnsupportedOperationException()

            override fun bericht(ontvanger: Identificatienummer, berichtId: UUID): Bericht? =
                throw UnsupportedOperationException()

            override fun werkBerichtBij(
                ontvanger: Identificatienummer,
                berichtId: UUID,
                status: Leesstatus?,
                map: String?,
            ): Bericht? = throw UnsupportedOperationException()

            override fun verwijder(ontvanger: Identificatienummer, berichtId: UUID): Unit =
                throw UnsupportedOperationException()

            override fun ophalen(ontvanger: Identificatienummer): Multi<MagazijnEvent> =
                throw UnsupportedOperationException()

            override fun schrijfBericht(ontvanger: Identificatienummer, bericht: Bericht): Bericht = bericht
        }

        val dedup = object : AanmeldDeduplicatie {
            override fun eerstgezien(eventId: String): Boolean = true

            override fun verwijder(eventId: String) = Unit
        }

        // De superclass-constructor vereist een register; hier inhoudsloos omdat de
        // override van magazijnVoor het register nooit raadpleegt.
        val leegRegister = object : Magazijnregister {
            override fun alle(): Collection<Magazijninschrijving> = emptyList()

            override fun voorOin(oin: Oin): Magazijninschrijving? = null
        }

        val index = object : AfzenderMagazijnIndex(leegRegister) {
            override fun magazijnVoor(afzender: Oin): String = "magazijn-a"
        }

        return AanmeldService(sessiecache, dedup, index, LogboekContext())
    }
}
