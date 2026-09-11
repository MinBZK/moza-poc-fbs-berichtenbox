package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.ws.rs.InternalServerErrorException
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.jboss.logging.Logger
import java.util.UUID

private val log = Logger.getLogger("nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.DbRijHydratatie")

/**
 * Bouwt een domeinobject uit een databaserij en vertaalt een geschonden invariant naar een
 * serverfout.
 *
 * Invarianten worden vóór persist al geverifieerd, dus een schending bij het teruglezen betekent
 * een niet-conforme rij: een handmatige edit of een restant van een oude schemaversie. Dat is onze
 * fout, geen fout van de aanroeper. Zonder deze vertaling zou een `DomainValidationException` als
 * 400 bij de client landen, mét de domeinmelding erin — een misleidende statuscode én een detail
 * waar de aanroeper niets aan kan veranderen.
 *
 * Elk pad dat een rij hydrateert hoort hier doorheen te gaan, anders gedraagt dezelfde corrupte
 * rij zich per endpoint anders. [diagnose] levert de context voor de logregel en mag lengtes en
 * typen bevatten, nooit de waarden zelf — die kunnen persoonsgegevens zijn.
 */
internal fun <T> uitDbRij(berichtId: UUID, diagnose: () -> String, bouw: () -> T): T =
    try {
        bouw()
    } catch (ex: DomainValidationException) {
        log.errorf(ex, "DB-rij corrupt of niet-conform: berichtId=%s %s", berichtId, diagnose())

        throw InternalServerErrorException(
            "DB-rij berichtId=$berichtId voldoet niet aan domein-invarianten",
            ex,
        )
    }
