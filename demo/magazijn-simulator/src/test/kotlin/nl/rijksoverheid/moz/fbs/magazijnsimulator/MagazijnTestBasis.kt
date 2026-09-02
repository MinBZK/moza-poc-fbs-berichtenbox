package nl.rijksoverheid.moz.fbs.magazijnsimulator

import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BijlageRepository
import org.junit.jupiter.api.BeforeEach

/**
 * Gedeelde opruiming voor tests die de database raken.
 *
 * Alle `@QuarkusTest`-klassen delen één draaiende applicatie en dus één database. Zonder opruiming
 * vooraf ziet een test die "leeg" verwacht de berichten van de vorige test, en dan hangt de uitslag
 * af van de volgorde waarin de suite draait — de vervelendste soort falen, want hij komt en gaat.
 *
 * Child-eerst, anders blokkeren de RESTRICT-FK's het. De magazijn-rijen blijven staan: die komen uit
 * de configuratie en worden bij het starten aangemaakt.
 */
abstract class MagazijnTestBasis {

    @Inject
    lateinit var berichten: BerichtRepository

    @Inject
    lateinit var statussen: BerichtStatusRepository

    @Inject
    lateinit var bijlagen: BijlageRepository

    @BeforeEach
    @Transactional
    fun ruimBerichtenOp() {
        statussen.deleteAll()
        bijlagen.deleteAll()
        berichten.deleteAll()
    }
}
