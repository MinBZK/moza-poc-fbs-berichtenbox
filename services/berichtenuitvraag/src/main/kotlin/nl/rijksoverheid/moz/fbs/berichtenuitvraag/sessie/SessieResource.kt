package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import jakarta.enterprise.context.ApplicationScoped
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieEinde
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
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
 * komt van de gedeelde `CreatedStatusFilter`.
 *
 * Wist een start of een einde de gegevens van de ontvanger, dan volgt naast de logregel van het
 * verzoek een tweede via [SessieLogboek]: het wissen is een eigen verwerking.
 */
@ApplicationScoped
class SessieResource(
    private val sessiecache: Sessiecache,
    private val sessieLogboek: SessieLogboek,
    private val logboekContext: LogboekContext,
) : SessiesApi {

    private val log = Logger.getLogger(SessieResource::class.java)

    @Logboek(name = "uitvraag-sessie-start", processingActivityId = ProcessingActivities.UITVRAAG_SESSIE)
    override fun startSessie(xOntvanger: String): Sessie {
        registreerLdvSubject(logboekContext, xOntvanger)

        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        val start = leesUitCache(log, "sessie-start") { sessiecache.startSessie(ontvanger) }

        if (start.restenOpgeruimd) sessieLogboek.opgeruimd(ontvanger, OpruimAanleiding.NIEUWE_START)

        return Sessie(start.sessieId, start.uiterlijkGeldigTot)
            .links(SessieLinks().self(Link().href("${ApiInfo.BASE_PATH}/sessies/huidige")))
    }

    @Logboek(name = "uitvraag-sessie-einde", processingActivityId = ProcessingActivities.UITVRAAG_SESSIE)
    override fun beeindigSessie(xOntvanger: String, xSessie: String) {
        registreerLdvSubject(logboekContext, xOntvanger)

        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        val einde = leesUitCache(log, "sessie-einde") {
            try {
                sessiecache.beeindigSessie(xSessie, ontvanger)
            } catch (e: SessiecacheException.Onbereikbaar) {
                // Het script kan al gewist en de ontvanger uit de verloopindex gehaald hebben; een
                // herhaalde uitlog ziet dan niets meer, dus dit is de enige kans op een logregel.
                sessieLogboek.opgeruimd(ontvanger, OpruimAanleiding.UITGELOGD, OpruimUitkomst.ONBEVESTIGD)
                throw e
            }
        }

        if (einde == SessieEinde.OPGERUIMD) sessieLogboek.opgeruimd(ontvanger, OpruimAanleiding.UITGELOGD)
    }
}
