package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.opentelemetry.api.trace.Span
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieEinde
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.SessiesApi
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Link
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Sessie
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.SessieLinks
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.leesUitCache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.registreerLdvSubject
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.jboss.logging.Logger

/**
 * REST-resource voor inlogsessies. De sessiestaat zit in de sessiecache; deze resource vertaalt
 * alleen naar HTTP en legt in het logboek vast wat er met de gegevens gebeurde. De 201 op de start
 * komt van de gedeelde `CreatedStatusFilter`, de `Location` van [SessieResponsFilter].
 *
 * Wist een start of een einde de gegevens van de ontvanger, dan volgt naast de logregel van het
 * verzoek een tweede via [SessieLogboek]: het wissen is een eigen verwerking.
 *
 * De controle van de sessie bij elk verzoek krijgt geen eigen logregel: ze leest alleen de
 * sessie-administratie, niet de gegevens van de ontvanger. De controle zelf doet [SessieFilter];
 * hier staat alleen wat het vaststelde.
 */
@ApplicationScoped
class SessieResource(
    private val sessiecache: Sessiecache,
    private val sessieLogboek: SessieLogboek,
    private val logboekContext: LogboekContext,
    @param:Context private val request: ContainerRequestContext,
) : SessiesApi {

    private val log = Logger.getLogger(SessieResource::class.java)

    @Logboek(name = "uitvraag-sessie-start", processingActivityId = ProcessingActivities.UITVRAAG_SESSIE)
    override fun startSessie(xOntvanger: String): Sessie {
        registreerLdvSubject(logboekContext, xOntvanger)

        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        val start = leesUitCache(log, "sessie-start") {
            try {
                sessiecache.startSessie(ontvanger)
            } catch (e: SessiecacheException.Onbereikbaar) {
                // De start kan in Redis al resten gewist hebben. Een volgende start ziet dan de
                // sessie die niemand kent lopen en meldt niets, dus dit is de enige kans op een logregel.
                sessieLogboek.opgeruimd(ontvanger, OpruimAanleiding.NIEUWE_START, OpruimUitkomst.ONBEVESTIGD)
                throw e
            }
        }

        if (start.restenOpgeruimd) sessieLogboek.opgeruimd(ontvanger, OpruimAanleiding.NIEUWE_START)

        return Sessie(start.sessieId).links(links())
    }

    @Logboek(name = "uitvraag-sessie-einde", processingActivityId = ProcessingActivities.UITVRAAG_SESSIE)
    override fun beeindigSessie(xOntvanger: String) {
        registreerLdvSubject(logboekContext, xOntvanger)

        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        val sessieId = huidigeSessie().sessieId
        val einde = leesUitCache(log, "sessie-einde") {
            try {
                sessiecache.beeindigSessie(sessieId, ontvanger)
            } catch (e: SessiecacheException.Onbereikbaar) {
                // Het script kan al gewist hebben; een herhaalde uitlog ziet dan niets meer, dus dit is
                // de enige kans op een logregel.
                sessieLogboek.opgeruimd(ontvanger, OpruimAanleiding.UITGELOGD, OpruimUitkomst.ONBEVESTIGD)
                throw e
            }
        }

        // Zonder authenticatie kan iedereen een einde voor een willekeurige ontvanger aanvragen. Wie
        // de logregel leest, ziet hieraan of dat einde iets deed.
        Span.current().setAttribute(EINDE_UITKOMST_ATTRIBUUT, einde.name.lowercase())

        if (einde == SessieEinde.OPGERUIMD) sessieLogboek.opgeruimd(ontvanger, OpruimAanleiding.UITGELOGD)
    }

    // Het filter zet hem bij elk verzoek dat een sessie vereist; ontbreekt hij, dan is dat een
    // configuratiefout, en die hoort niet stil een lege sessie op te leveren.
    private fun huidigeSessie(): HuidigeSessie =
        checkNotNull(request.getProperty(SessieFilter.HUIDIGE_SESSIE) as? HuidigeSessie) {
            "Geen sessie vastgesteld; draait het SessieFilter op dit pad?"
        }

    private fun links(): SessieLinks = SessieLinks()
        .self(Link().href(SessieResponsFilter.HUIDIGE_SESSIE_PAD))

    companion object {
        /** Wat een einde deed: `onbekend` (niets), `beeindigd` (andere sessies lopen nog) of `opgeruimd`. */
        const val EINDE_UITKOMST_ATTRIBUUT = "fbs.sessie.einde.uitkomst"
    }
}
