package nl.rijksoverheid.moz.fbs.berichtensessiecache

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtSleutel
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtensessiecacheService
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.CacheContentieException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.CacheCorruptedException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AbonnementGesloten
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieVolger
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.sleutel
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.ALERT_OPRUIMEN_NA_SCHRIJVEN
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.Opruiming
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieEinde
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieGeldigheid
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieRegister
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieStart
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.StartUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.VeegKandidaat
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Synchrone facade over de reactieve [BerichtensessiecacheService]: blokkeert
 * begrensd ([timeout]) op de Mutiny-pijplijn en vertaalt infrastructuurfouten
 * naar de gesloten [SessiecacheException]-hiërarchie uit het [Sessiecache]-contract.
 * Houdt zelf géén staat — alle sessiestaat (cache, aggregatie-status, lock) leeft in Redis.
 */
@ApplicationScoped
internal class BlockingSessiecache(
    private val service: BerichtensessiecacheService,
    private val volger: SessieVolger,
    private val register: Magazijnregister,
    private val sessies: SessieRegister,
    // Begrenzing op de blocking-await van de facade over élk lees-/schrijfpad van het
    // Sessiecache-contract (de paden raken een lokale/naast-de-pod Redis, geen remote
    // magazijn-RTT — vandaar dezelfde 5s-ondergrens als cache-await-timeout-seconds).
    // Overschrijden → 503. Moet > 0: Mutiny's `atMost(ZERO)` wacht onbegrensd en zou de
    // bescherming stil uitschakelen.
    @param:ConfigProperty(name = "berichtensessiecache.facade-await-timeout-seconds", defaultValue = "5")
    private val facadeAwaitTimeoutSeconds: Long,
) : Sessiecache {

    private val log = Logger.getLogger(BlockingSessiecache::class.java)

    private val timeout: Duration = Duration.ofSeconds(facadeAwaitTimeoutSeconds)

    init {
        require(facadeAwaitTimeoutSeconds > 0) {
            "berichtensessiecache.facade-await-timeout-seconds ($facadeAwaitTimeoutSeconds) moet groter zijn dan 0"
        }
    }

    override fun lijst(
        ontvanger: Identificatienummer,
        pagina: Int?,
        paginaGrootte: Int?,
        afzender: String?,
        map: String?,
    ): BerichtenPagina {
        val aggregatie = requireGereedStatus(ontvanger)

        return awaitOrServiceUnavailable {
            service.getBerichten(pagina ?: 0, effectieveGrootte(paginaGrootte), ontvanger, afzender, map)
        }.copy(volledigheid = aggregatie.volledigheid())
    }

    override fun zoek(
        ontvanger: Identificatienummer,
        q: String,
        pagina: Int?,
        paginaGrootte: Int?,
        afzender: String?,
        map: String?,
    ): BerichtenPagina {
        val aggregatie = requireGereedStatus(ontvanger)

        return awaitOrServiceUnavailable {
            service.zoekBerichten(q, pagina ?: 0, effectieveGrootte(paginaGrootte), ontvanger, afzender, map)
        }.copy(volledigheid = aggregatie.volledigheid())
    }

    override fun bericht(ontvanger: Identificatienummer, magazijnId: String, berichtId: UUID): Bericht? {
        requireGereedStatus(ontvanger)

        if (!ingeschreven(magazijnId)) return null

        val sleutel = BerichtSleutel(magazijnId, berichtId)
        val bericht = awaitOrServiceUnavailable { service.getBerichtById(sleutel, ontvanger) }

        if (bericht != null) return bericht

        val zelfVerwijderd = awaitOrServiceUnavailable { service.isBerichtVerwijderd(sleutel, ontvanger) }

        if (zelfVerwijderd) throw SessiecacheException.BerichtVerwijderd("Bericht is door de ontvanger verwijderd")

        return null
    }

    override fun werkBerichtBij(
        ontvanger: Identificatienummer,
        magazijnId: String,
        berichtId: UUID,
        status: Leesstatus?,
        map: String?,
    ): Bericht? {
        // Een lege patch zou als no-op-succes ogen; expliciet afwijzen zodat de
        // caller (en diens client) weet dat er niets gewijzigd is.
        if (status == null && map == null) {
            throw SessiecacheException.OngeldigeInvoer(
                "Minimaal één van 'status' of 'map' is vereist (geen geldige waarde meegegeven).",
            )
        }

        // De facade bewaakt zijn eigen contract: zonder deze check strandt een ongeldige naam pas
        // diep in de cache, als een 500 met een log die naar Redis wijst.
        if (map != null && map != Sessiecache.MAP_WISSEN && (map.isBlank() || map.length > Bericht.MAX_MAPNAAM_LENGTE)) {
            throw SessiecacheException.OngeldigeInvoer(
                "Een mapnaam is niet leeg van alleen witruimte en hooguit ${Bericht.MAX_MAPNAAM_LENGTE} tekens; \"\" haalt een bericht uit zijn map.",
            )
        }

        if (!ingeschreven(magazijnId)) return null

        return awaitOrServiceUnavailable {
            service.updateBerichtMetadata(BerichtSleutel(magazijnId, berichtId), ontvanger, status?.wire, map)
        }
    }

    override fun verwijder(ontvanger: Identificatienummer, magazijnId: String, berichtId: UUID) {
        if (!ingeschreven(magazijnId)) return

        try {
            awaitOrServiceUnavailable { service.verwijderBericht(BerichtSleutel(magazijnId, berichtId), ontvanger) }
        } catch (e: SessiecacheException) {
            // Een timeout kan de keten afbreken ná de tombstone en vóór de opruimcontrole erna.
            markeerVoorVeeg(ontvanger, "Verwijderen niet bevestigd")
            throw e
        }
    }

    override fun ophalen(ontvanger: Identificatienummer): Multi<MagazijnEvent> = service.haalBerichtenOp(ontvanger)

    override fun schrijfBericht(ontvanger: Identificatienummer, bericht: Bericht): Bericht {
        if (bericht.ontvanger != ontvanger) {
            throw SessiecacheException.OngeldigeInvoer(
                "Ontvanger in bericht komt niet overeen met de opgegeven ontvanger.",
            )
        }

        if (!ingeschreven(bericht.magazijnId)) {
            throw SessiecacheException.OngeldigeInvoer("Bericht hoort bij geen ingeschreven magazijn.")
        }

        // Aanmeld-pad mag alleen een al opgehaalde lijst bijwerken: als er geen
        // aggregatie heeft plaatsgevonden voor deze ontvanger, hoort er ook geen cache te zijn.
        awaitOrServiceUnavailable { service.getAggregationStatus(ontvanger) }
            ?: throw SessiecacheException.GeenLijstInCache(
                "Geen opgehaalde lijst voor deze ontvanger; bericht niet toegevoegd.",
            )

        val geschreven = try {
            awaitOrServiceUnavailable { service.createBericht(bericht, ontvanger) }
        } catch (e: IllegalArgumentException) {
            // Defensieve limiet overschreden (BerichtValidator): invoerfout van de
            // aanleverende caller, geen infrastructuurfout.
            throw SessiecacheException.OngeldigeInvoer(e.message ?: "Ongeldig bericht.", e)
        } catch (e: SessiecacheException) {
            // Het aanmeldscript kan in Redis gedraaid hebben terwijl het antwoord wegviel, en het kijkt
            // niet naar sessies. Zonder markering zou het geschrevene dan onzichtbaar blijven staan.
            markeerVoorVeeg(ontvanger, "Aanmelding niet bevestigd")
            throw e
        }

        // De statuscontrole hierboven en het schrijven zijn twee stappen; eindigde de laatste sessie
        // daartussen, dan is het bericht nu weer gewist en hoort de aanleveraar dat er geen lijst was.
        val opgeruimd = try {
            awaitOrServiceUnavailable { sessies.ruimOpZonderSessie(ontvanger) }
        } catch (e: SessiecacheException) {
            // De herlevering die op deze fout volgt, vindt na een uitlog geen status meer en ruimt dus
            // niets op; alleen de veegtaak kan het geschrevene dan nog vinden.
            markeerVoorVeeg(ontvanger, "Opruimen na een aanmelding mislukt")
            throw e
        }

        if (opgeruimd) {
            throw SessiecacheException.GeenLijstInCache(
                "Geen opgehaalde lijst voor deze ontvanger; bericht niet toegevoegd.",
            )
        }

        meldAanGevolgdeSessies(ontvanger, geschreven)

        return geschreven
    }

    override fun volg(ontvanger: Identificatienummer, sessieId: String): Multi<SessieGebeurtenis> {
        leesSessie(sessieId, ontvanger)
        requireGereedStatus(ontvanger)
        awaitOrServiceUnavailable { volger.actief() }

        return volger.volg(ontvanger, sessieId)
    }

    override fun startSessie(ontvanger: Identificatienummer, uiterlijkGeldigTot: Instant?): SessieStart =
        when (val uitkomst = awaitOrServiceUnavailable { sessies.start(ontvanger, uiterlijkGeldigTot) }) {
            is StartUitkomst.Gestart -> uitkomst.start
            StartUitkomst.PlafondVerstreken -> throw SessiecacheException.OngeldigeInvoer(
                "uiterlijkGeldigTot ligt niet in de toekomst.",
            )
        }

    override fun raakSessie(sessieId: String, ontvanger: Identificatienummer): SessieGeldigheid =
        awaitOrServiceUnavailable { sessies.raak(sessieId, ontvanger) } ?: throw sessieOngeldig()

    override fun leesSessie(sessieId: String, ontvanger: Identificatienummer): SessieGeldigheid =
        awaitOrServiceUnavailable { sessies.lees(sessieId, ontvanger) } ?: throw sessieOngeldig()

    private fun sessieOngeldig() = SessiecacheException.SessieOngeldig("Sessie is verlopen of ongeldig.")

    override fun beeindigSessie(sessieId: String, ontvanger: Identificatienummer): SessieEinde =
        awaitOrServiceUnavailable { sessies.beeindig(sessieId, ontvanger) }

    override fun ruimVerlopenSessiesOp(max: Int, meld: (Opruiming) -> Unit): Boolean {
        // Redis leest een negatieve LIMIT als "alles": dat maakt van een begrensde ronde een onbegrensde.
        if (max < 1) throw SessiecacheException.OngeldigeInvoer("max ($max) moet minstens 1 zijn.")

        val kandidaten = awaitOrServiceUnavailable { sessies.verlopen(max) }

        for (kandidaat in kandidaten) {
            // De pod stopt: geen volgende ontvanger meer beginnen, de index bewaart hem.
            if (Thread.currentThread().isInterrupted) return false

            val opruiming = veegEen(kandidaat) ?: continue

            if (!Thread.currentThread().isInterrupted) {
                meld(opruiming)

                continue
            }

            // De pod stopt. Deze melding mag niet op de vlag stranden, want het wissen kan al gebeurd
            // zijn; daarna stopt de ronde en pakt een volgende de rest van de index op.
            Thread.interrupted()

            try {
                meld(opruiming)
            } finally {
                Thread.currentThread().interrupt()
            }

            return false
        }

        return kandidaten.size == max
    }

    /**
     * Per ontvanger een eigen timeout en een eigen vangnet: een fout of een trage ontvanger mag de
     * ontvangers erachter niet tegenhouden. `null` als er voor deze ontvanger nog een sessie loopt of
     * een andere pod al veegde.
     *
     * Alleen de fout-soort in de log: de melding van een Redis- of parse-fout kan een fragment van
     * de invoer bevatten.
     */
    private fun veegEen(kandidaat: VeegKandidaat): Opruiming? =
        try {
            if (sessies.veeg(kandidaat.kenmerk).await().atMost(timeout)) kandidaat.opruiming(OpruimUitkomst.GEWIST) else null
        } catch (e: Exception) {
            // Een script dat Redis eenmaal kreeg, loopt tot het einde: na een timeout of een interrupt
            // is de uitkomst onbekend. Een foutantwoord breekt het script af vóór de ZREM aan het einde,
            // dus dan staat de ontvanger nog in de index voor een volgende ronde. Mutiny's eigen
            // TimeoutException (van `atMost`) is geen subklasse van die uit de JDK.
            val keten = generateSequence<Throwable>(e) { it.cause }.toList()
            val onderbroken = keten.any { it is InterruptedException }
            val onbevestigd = onderbroken ||
                keten.any { it is io.smallrye.mutiny.TimeoutException || it is java.util.concurrent.TimeoutException }

            when {
                onderbroken -> {
                    // Mutiny zet de vlag meestal al; hier expliciet, want de aanroeper stopt erop.
                    Thread.currentThread().interrupt()
                    log.infof("Opruimen van een verlopen sessie onderbroken (fout=%s); de veegronde stopt", e.javaClass.name)
                }
                onbevestigd -> log.warnf(
                    "Opruimen van een verlopen sessie niet bevestigd binnen %s (fout=%s); de gegevens zijn mogelijk gewist",
                    timeout,
                    e.javaClass.name,
                )
                else -> log.errorf(
                    "Opruimen van een verlopen sessie mislukt (fout=%s); een volgende veegronde probeert het opnieuw",
                    e.javaClass.name,
                )
            }

            // Ook na een timeout: hangt het script van deze ontvanger structureel, dan kost hij anders
            // elke ronde de volle timeout. `XX` in het uitstel laat een al geveegde ontvanger weg. Na
            // een interrupt niet: de pod stopt, en de entry blijft voor een volgende ronde staan.
            if (!onderbroken) stelUit(kandidaat.kenmerk)

            kandidaat.opruiming(if (onbevestigd) OpruimUitkomst.ONBEVESTIGD else OpruimUitkomst.MISLUKT)
        }

    /**
     * Best-effort: lukt ook dit niet, dan blijft de ontvanger vooraan in de index staan en is de
     * volgende ronde gewoon weer een poging. Een fout hier verandert de uitkomst niet.
     */
    private fun stelUit(kenmerk: String) {
        try {
            sessies.stelUit(kenmerk).await().atMost(timeout)
        } catch (e: Exception) {
            log.warnf("Uitstellen van een mislukte opruiming mislukt (%s); de volgende veegronde ziet hem weer vooraan", e.javaClass.simpleName)
        }
    }

    /**
     * Best-effort, na een schrijfactie waarvan niet vaststaat of een opruimcontrole erachter liep: de
     * aanroeper krijgt hoe dan ook de oorspronkelijke fout. Staat de ontvanger al in de verloopindex,
     * met een lopende sessie of al wachtend, dan verandert dit niets. [wat] noemt in het alert welke
     * stap faalde.
     */
    private fun markeerVoorVeeg(ontvanger: Identificatienummer, wat: String) {
        try {
            sessies.markeerVoorVeeg(ontvanger).await().atMost(timeout)
        } catch (e: Exception) {
            log.errorf(
                "%s %s en ook het markeren voor de veegtaak (fout=%s); de vangnet-TTL ruimt het op zonder logregel",
                ALERT_OPRUIMEN_NA_SCHRIJVEN,
                wat,
                e.javaClass.name,
            )
        }
    }

    /**
     * Alleen een ingeschreven magazijn komt bij de cache. De magazijnId belandt in Redis-sleutels en
     * in het aanmeldkanaal; een spatie of dubbele punt zou die vormen breken, en een magazijn dat
     * het register niet kent kan de uitvraag toch niet routeren. Voor de aanroeper is zo'n bericht
     * gewoon onbekend.
     */
    private fun ingeschreven(magazijnId: String): Boolean {
        val oin = runCatching { Oin(magazijnId) }.getOrNull() ?: return false

        return register.voorOin(oin) != null
    }

    /**
     * Best-effort: het bericht staat al in de cache, en de aanmelding afwijzen zou de
     * aanleveraar laten herafleveren wat niets meer toevoegt. Een open berichtenbox mist het dan
     * tot hij opnieuw verbindt of de lijst ververst.
     */
    private fun meldAanGevolgdeSessies(ontvanger: Identificatienummer, bericht: Bericht) {
        try {
            volger.meldAan(ontvanger, bericht.sleutel).await().atMost(timeout)
        } catch (e: Exception) {
            log.warnf(e, "Nieuw bericht niet doorgegeven aan gevolgde sessies; open berichtenboxen zien het later")
        }
    }

    /**
     * Lees- en zoekpaden vereisen een afgeronde ophaling: zonder status is de cache
     * (mogelijk) leeg of verouderd en zou een lege lijst onterecht "geen berichten"
     * suggereren. De caller krijgt [SessiecacheException.NogNietGevuld] (nog niet gestart)
     * resp. [SessiecacheException.OphalenBezig] (loopt nog) en moet eerst [Sessiecache.ophalen]
     * aanroepen; een mislukte ophaling levert [SessiecacheException.OphalenMislukt].
     */
    private fun requireGereedStatus(ontvanger: Identificatienummer): AggregationStatus {
        val aggregation = awaitOrServiceUnavailable { service.getAggregationStatus(ontvanger) }
            ?: throw SessiecacheException.NogNietGevuld(
                "Berichten zijn nog niet opgehaald. Start eerst het ophalen van berichten.",
            )

        return when (aggregation.status) {
            OphalenStatus.GEREED -> aggregation
            OphalenStatus.BEZIG -> throw SessiecacheException.OphalenBezig(
                "Berichten worden momenteel opgehaald. Wacht tot het ophalen is afgerond.",
            )
            OphalenStatus.FOUT -> throw SessiecacheException.OphalenMislukt(
                "Het ophalen van berichten is mislukt. Start het ophalen van berichten opnieuw.",
            )
        }
    }

    // Moet gelijk zijn aan het maximum dat de uitvraag-API toestaat: een lager plafond kapt een
    // toegestane paginagrootte stil af, en wie de hele set leest, mist dan berichten.
    private fun effectieveGrootte(paginaGrootte: Int?): Int = (paginaGrootte ?: 20).coerceAtMost(Sessiecache.MAX_PAGINA_GROOTTE)

    private fun <T> awaitOrServiceUnavailable(block: () -> Uni<T>): T {
        try {
            return block().await().atMost(timeout)
        } catch (e: java.util.concurrent.CompletionException) {
            // Mutiny's blocking await wrapt checked failures (bv. Jackson's
            // JsonProcessingException) in een CompletionException; classificeer op de
            // cause, anders degradeert elk data-integriteit-issue tot een generieke 503.
            throw vertaalCacheFout(e.cause ?: e)
        } catch (e: Exception) {
            throw vertaalCacheFout(e)
        }
    }

    private fun vertaalCacheFout(e: Throwable): Exception = when (e) {
        // Cache-operatie hing langer dan de facade-await-timeout — log warn met cause zodat
        // operations niet alleen het onbereikbaar-pad ziet maar ook welke await het was. Silent
        // discard verbergt prestatie-regressies in Redis. `atMost` gooit Mutiny's eigen
        // TimeoutException, en die is geen subklasse van die uit de JDK.
        is java.util.concurrent.TimeoutException, is io.smallrye.mutiny.TimeoutException -> {
            log.warnf(e, "Cache-operatie overschreed timeout van %s; onbereikbaar naar caller", timeout)
            SessiecacheException.Onbereikbaar("Cache niet bereikbaar. Probeer het later opnieuw", e)
        }
        // Transiente schrijf-contentie: bron logt al op warn. Retriable onbereikbaar
        // i.p.v. de misleidende not-found die een null-resultaat zou opleveren.
        is CacheContentieException ->
            SessiecacheException.Onbereikbaar("Cache tijdelijk niet bij te werken. Probeer het later opnieuw", e)
        // Invoervalidatie (BerichtValidator, Leesstatus.fromWire): caller-fout,
        // geen infrastructuurfout — laat schrijfBericht dit naar OngeldigeInvoer vertalen.
        is IllegalArgumentException -> e
        // Cache-deserialisatie-fout = data-integriteit-issue (verkeerde versie, corrupte hash),
        // niet "Redis onbereikbaar". Onleesbaar zonder de verkeerde infrastructuur-diagnose.
        is com.fasterxml.jackson.core.JsonProcessingException -> {
            // Log de fout-soort, NIET de exception: Jackson's message bevat bij
            // INCLUDE_SOURCE_IN_LOCATION (default aan) het ruwe JSON-fragment met BSN/RSIN.
            // PII mag nooit in de log.
            log.errorf("Cache-data niet deserialiseerbaar (corruptie of schema-drift); fout=%s", e.javaClass.name)
            SessiecacheException.Onleesbaar("Cache-data niet leesbaar.", e)
        }
        // Hash-velden ontbreken of onleesbaar → eigen data-issue, niet bereikbaarheids-issue.
        is CacheCorruptedException -> {
            // Eigen message is statisch (PII-veilig); de cause kan een Jackson-fout met
            // JSON-fragment zijn (bv. bijlagen), dus log de cause-klasse, niet de cause zelf.
            log.errorf("Cache-data corrupt: %s (oorzaak=%s)", e.message, e.cause?.javaClass?.name ?: "geen")
            SessiecacheException.Onleesbaar("Cache-data niet leesbaar.", e)
        }
        // De pod stopt, of het abonnement viel weg en is al gelogd: per stream geen error erbij.
        is AbonnementGesloten -> {
            log.debugf("Volgen niet gestart: %s", e.message)
            SessiecacheException.Onbereikbaar("Nieuwe berichten volgen kan nu niet. Probeer het straks opnieuw.", e)
        }
        else -> {
            log.errorf(e, "Cache-operatie mislukt")
            SessiecacheException.Onbereikbaar("Cache niet bereikbaar.", e)
        }
    }
}
