package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Meldt in het kanaal van de bewaking dat een storingsknop bewust is ingedrukt, vóórdat de
 * bewaking daar een storing meldt.
 *
 * Alleen voor knoppen die een gecontroleerde readiness laten zakken. De Redis-knop maakt de
 * uitvraag NotReady, waarna de router op elk pad 503 antwoordt; van buitenaf is dat niet te
 * onderscheiden van een echte storing, dus de bewaking meldt hem terecht. Dit bericht maakt die
 * melding herkenbaar als verwacht. De andere knoppen raken geen readiness die bewaakt wordt.
 *
 * Best-effort: een bericht dat niet aankomt, mag de knop niet laten falen.
 */
@ApplicationScoped
class StoringAankondiging internal constructor(
    private val config: AankondigingConfig,
    bouwWebhook: (URI) -> MattermostWebhook,
) {

    @Inject
    constructor(config: AankondigingConfig) : this(config, ::bouwWebhook)

    private val log = Logger.getLogger(StoringAankondiging::class.java.name)

    // Een onbruikbare url zet alleen de aankondiging uit. Deze bean ontstaat pas bij de eerste
    // knopdruk; een fout hier liet elke storingsknop falen, en URI.create noemt de url in zijn
    // melding — wie hem heeft, kan in het kanaal posten. Trim, want een plakfout laat vaak een
    // newline achter.
    private val webhook: MattermostWebhook? =
        config.webhookUrl()
            .map { it.trim() }
            .filter { config.deployment().orElse(null) == config.bewaakteDeployment() }
            .map { url ->
                runCatching { bouwVeilig(url, bouwWebhook) }
                    .onFailure { log.warning("Webhook-client niet te bouwen: ${it::class.simpleName}") }
                    .getOrNull()
            }
            .orElse(null)

    private fun bouwVeilig(url: String, bouwWebhook: (URI) -> MattermostWebhook): MattermostWebhook? {
        val uri = runCatching { URI(url) }.getOrNull()

        if (uri?.scheme != "https" || uri.host == null) {
            log.warning("demo.aankondiging.webhook-url is geen bruikbare https-url; storingen worden niet aangekondigd")

            return null
        }

        return bouwWebhook(uri)
    }

    /** Of een overgang op deze proxy iets aan te kondigen heeft; zo niet, dan hoeft niemand hem te lezen. */
    fun bewaakt(proxy: String): Boolean = webhook != null && proxy in GERAAKT

    /**
     * Kondigt een overgang aan tussen "geen storing" en "storing". Van traag naar uit is geen
     * nieuwe storing en levert dus geen tweede bericht op.
     */
    fun overgang(proxy: String, voor: Storingstoestand, na: Storingstoestand) {
        val geraakt = GERAAKT[proxy] ?: return
        val deployment = config.bewaakteDeployment()

        val tekst = when {
            voor !in STORING && na in STORING ->
                "De demo-storingsknop voor $proxy staat op ${na.waarde} in `$deployment`, ingedrukt via het " +
                    "demopaneel. De storingsmelding voor $geraakt die hierop volgt, is verwacht: geen actie nodig."
            voor in STORING && na == Storingstoestand.NORMAAL ->
                "De demo-storing op $proxy in `$deployment` is opgeheven. De herstelmelding voor $geraakt volgt vanzelf."
            else -> return
        }

        verstuur(tekst)
    }

    private fun verstuur(tekst: String) {
        val doel = webhook ?: return

        runCatching { doel.post(MattermostBericht(tekst, config.afzender())).use { it.status } }
            .onSuccess { status ->
                if (status !in 200..299) log.warning("Storingsaankondiging niet geplaatst: HTTP $status")
            }
            .onFailure { fout ->
                log.warning("Storingsaankondiging niet geplaatst: ${fout::class.simpleName}")
            }
    }

    private companion object {

        /** Proxy → wat de bewaking ziet uitvallen als hij in storing staat. */
        val GERAAKT = mapOf("redis" to "de uitvraag")

        val STORING = setOf(Storingstoestand.TRAAG, Storingstoestand.UIT)

        const val CONNECT_TIMEOUT_SECONDEN = 2L

        const val READ_TIMEOUT_SECONDEN = 3L

        fun bouwWebhook(url: URI): MattermostWebhook =
            QuarkusRestClientBuilder.newBuilder()
                .baseUri(url)
                // Kort: de knop wacht op dit bericht, en een demo wacht op de knop.
                .connectTimeout(CONNECT_TIMEOUT_SECONDEN, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDEN, TimeUnit.SECONDS)
                .build(MattermostWebhook::class.java)
    }
}
