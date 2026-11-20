package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import nl.rijksoverheid.moz.fbs.testhulp.Logvanger
import nl.rijksoverheid.moz.fbs.testhulp.herkenbareDelen
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PostgreSQL zet bij een NOT NULL-schending `Failing row contains (…)` in zijn melding, met de hele
 * rij, en Hibernate logt die melding op WARN nog voor een exception mapper eraan toekomt. Zonder die
 * Detail (`logServerErrorDetail=false`) noemt de log de kolom of de constraint, maar niet de ontvanger.
 */
@QuarkusTest
class DatabasefoutUitDeLogTest {

    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var transactie: UserTransaction

    @Test
    fun `een NOT NULL-schending zet de rij met de ontvanger niet in de log`() {
        val waarde = "999993653"

        val (fout, log) = Logvanger().use { logvanger ->
            val fout = runCatching {
                transactie.begin()

                try {
                    // Als bind-parameter, zoals Hibernate hem stuurt: dan kan de waarde alleen via de
                    // melding van de database in de log komen, niet via de tekst van het statement.
                    entityManager.createNativeQuery(
                        """
                        INSERT INTO berichten (bericht_id, afzender, ontvanger_type, ontvanger_waarde, onderwerp, inhoud, tijdstip_ontvangst)
                        VALUES (gen_random_uuid(), '00000001003214345000', 'BSN', :waarde, NULL, 'inhoud', now())
                        """.trimIndent(),
                    ).setParameter("waarde", waarde).executeUpdate()
                } finally {
                    transactie.rollback()
                }
            }.exceptionOrNull()

            fout to logvanger.tekst()
        }

        assertTrue(fout != null, "de insert hoort te falen")
        assertTrue(log.contains("onderwerp"), "de log noemt de kolom: $log")

        herkenbareDelen(waarde).forEach { deel ->
            assertFalse(log.contains(deel), "'$deel' hoort niet in de log: $log")
        }
    }
}
