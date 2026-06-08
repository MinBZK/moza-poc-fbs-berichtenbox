package nl.rijksoverheid.moz.fbs.berichtenmagazijn.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.BijlageInvoer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageMetadata
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie.BerichtValidatieService
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import java.time.Instant
import java.util.UUID

/**
 * Fuzz de aanlever-invoer → domein-constructie van het berichtenmagazijn. Het magazijn
 * is het punt waar onvertrouwde gegevens van externe organisaties het stelsel
 * binnenkomen; misvormde of vijandige invoer MOET ofwel een geldig domeinobject
 * opleveren ofwel een gecontroleerde [DomainValidationException] werpen — nooit een
 * andere runtime-exception (die zou als 500 i.p.v. 400 eindigen en de mapper-pipeline
 * omzeilen).
 *
 * Spiegelt het pad in `BerichtOpslagService.slaBerichtOp`: rauwe request-strings
 * worden via `Oin(...)` en `Identificatienummer.of(...)` getypeerd en in een [Bericht]
 * gestopt. Vangt alleen [DomainValidationException]; elk ander exception-type vliegt
 * door en faalt de fuzzer (dat is precies de regressie die we willen zien).
 */
object AanleverDomainFuzzer {

    // ProfielServiceClient is een externe REST-buur; relaxed-mock omdat het
    // MIME-allowlist-pad een OIN-ontvanger gebruikt en de profiel-call dus nooit raakt.
    private val validatieService = BerichtValidatieService(mockk<ProfielServiceClient>(relaxed = true))

    // Hot-path-zuinigheid (fuzzers draaien miljoenen iteraties): hoist de type-array
    // i.p.v. per iteratie `entries.toTypedArray()` te alloceren, en gebruik een vaste
    // UUID i.p.v. `randomUUID()` — de id-waarde raakt geen enkele gefuzzde invariant,
    // dus de SecureRandom-kost per iteratie is pure verspilling.
    private val idTypes = IdentificatienummerType.entries.toTypedArray()
    private val vasteId: UUID = UUID(0L, 0L)

    private val targets = arrayOf(
        ::fuzzBericht,
        ::fuzzBijlage,
        ::fuzzBijlageMetadata,
        ::fuzzBijlageMimeAllowlist,
    )

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        data.pickValue(targets).invoke(data)
    }

    private fun fuzzBericht(data: FuzzedDataProvider) {
        val afzenderRaw = data.consumeString(40)
        val ontvangerType = data.pickValue(idTypes)
        val ontvangerRaw = data.consumeString(40)
        // onderwerp-cap (400) ligt bewust boven MAX_ONDERWERP_LENGTE (255) zodat de
        // lengte-afwijzing wordt geraakt. inhoud cappen we modest: de 1 MiB-grens per
        // iteratie genereren is fuzz-tijd- en geheugenverspilling voor weinig extra
        // dekking — de byte-telling-tak is simpel en deterministisch.
        val onderwerp = data.consumeString(400)
        val inhoud = data.consumeString(2000)

        val bericht = try {
            Bericht(
                berichtId = vasteId,
                afzender = Oin(afzenderRaw),
                ontvanger = Identificatienummer.of(ontvangerType, ontvangerRaw),
                onderwerp = onderwerp,
                inhoud = inhoud,
                tijdstipOntvangst = Instant.EPOCH,
                publicatietijdstip = Instant.EPOCH,
            )
        } catch (_: DomainValidationException) {
            return
        }

        check(bericht.onderwerp.isNotBlank()) { "onderwerp mag niet blank zijn na constructie" }
        check(bericht.onderwerp.length <= Bericht.MAX_ONDERWERP_LENGTE) {
            "onderwerp-lengte ${bericht.onderwerp.length} overschrijdt max na constructie"
        }
        check(bericht.inhoud.isNotBlank()) { "inhoud mag niet blank zijn na constructie" }
        check(bericht.inhoud.toByteArray(Charsets.UTF_8).size <= Bericht.MAX_INHOUD_BYTES) {
            "inhoud overschrijdt MAX_INHOUD_BYTES na constructie"
        }
        check(bericht.afzender != bericht.ontvanger) {
            "afzender en ontvanger mogen niet gelijk zijn na constructie"
        }
    }

    private fun fuzzBijlage(data: FuzzedDataProvider) {
        val naam = data.consumeString(400)
        val mimeType = data.consumeString(200)
        val content = data.consumeBytes(2048)

        val bijlage = try {
            Bijlage(
                bijlageId = vasteId,
                berichtId = vasteId,
                naam = naam,
                mimeType = mimeType,
                content = content,
            )
        } catch (_: DomainValidationException) {
            return
        }

        check(bijlage.naam.isNotBlank()) { "bijlage-naam mag niet blank zijn na constructie" }
        check(bijlage.naam.length <= Bijlage.MAX_NAAM_LENGTE) {
            "bijlage-naam-lengte overschrijdt max na constructie"
        }
        check(bijlage.mimeType.isNotBlank()) { "bijlage-mimeType mag niet blank zijn na constructie" }
        check(bijlage.mimeType.length <= Bijlage.MAX_MIME_LENGTE) {
            "bijlage-mimeType-lengte overschrijdt max na constructie"
        }
        check(bijlage.content.isNotEmpty()) { "bijlage-content mag niet leeg zijn na constructie" }
        check(bijlage.content.size <= Bijlage.MAX_CONTENT_BYTES) {
            "bijlage-content overschrijdt MAX_CONTENT_BYTES na constructie"
        }
    }

    private fun fuzzBijlageMetadata(data: FuzzedDataProvider) {
        val naam = data.consumeString(400)
        val mimeType = data.consumeString(200)

        val metadata = try {
            BijlageMetadata(
                bijlageId = vasteId,
                naam = naam,
                mimeType = mimeType,
            )
        } catch (_: DomainValidationException) {
            return
        }

        check(metadata.naam.isNotBlank()) { "metadata-naam mag niet blank zijn na constructie" }
        check(metadata.naam.length <= Bijlage.MAX_NAAM_LENGTE) {
            "metadata-naam-lengte overschrijdt max na constructie"
        }
        check(metadata.mimeType.isNotBlank()) { "metadata-mimeType mag niet blank zijn na constructie" }
        check(metadata.mimeType.length <= Bijlage.MAX_MIME_LENGTE) {
            "metadata-mimeType-lengte overschrijdt max na constructie"
        }
    }

    /**
     * Fuzz de MIME-allowlist in [BerichtValidatieService.valideer]: alleen
     * `application/pdf` mag door. De ontvanger is een OIN zodat de abonnements-
     * controle (externe profiel-call) direct terugkeert en alleen de allowlist
     * wordt uitgeoefend. Bij een geaccepteerde bijlage moet het mimeType exact
     * `application/pdf` zijn; alles anders hoort een [DomainValidationException]
     * te geven.
     */
    private fun fuzzBijlageMimeAllowlist(data: FuzzedDataProvider) {
        val mimeType = data.consumeString(80)
        val bericht = Bericht(
            berichtId = vasteId,
            afzender = Oin(AFZENDER_OIN),
            ontvanger = Oin(ONTVANGER_OIN),
            onderwerp = "fuzz",
            inhoud = "fuzz",
            tijdstipOntvangst = Instant.EPOCH,
            publicatietijdstip = Instant.EPOCH,
        )
        val bijlagen = listOf(BijlageInvoer(naam = "fuzz", mimeType = mimeType, content = byteArrayOf(1)))

        try {
            validatieService.valideer(bericht, bijlagen)
        } catch (_: DomainValidationException) {
            return
        }

        check(bijlagen.all { it.mimeType == PDF_MIME_TYPE }) {
            "valideer accepteerde een niet-$PDF_MIME_TYPE mimeType"
        }
    }

    private const val PDF_MIME_TYPE = "application/pdf"

    // Twee geldige, verschillende OIN's (20 cijfers, niet geheel nullen). Vast omdat
    // deze target de bijlage-allowlist isoleert, niet de afzender/ontvanger-parsing.
    private const val AFZENDER_OIN = "00000001003214345000"
    private const val ONTVANGER_OIN = "00000004003214345000"
}
