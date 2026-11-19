package nl.rijksoverheid.moz.fbs.magazijnsimulator

import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * De klok van de simulator in alle `@QuarkusTest`s van deze module: `@Priority` zet deze
 * alternative aan zonder dat een profiel hem hoeft te noemen. Zo is elk tijdstip in een antwoord
 * exact te toetsen.
 *
 * De klok staat stil op [MOMENT]. Een test die verloop toetst, verzet hem in een eigen profiel:
 * een verzetting verloopt ook de challenges en sessies van andere tests in dezelfde applicatie.
 */
@Alternative
@Priority(1)
@Singleton
class VasteKlokProducer {

    val klok = VerzetbareKlok(MOMENT)

    @Produces
    @ApplicationScoped
    fun clock(): Clock = klok

    companion object {
        val MOMENT: Instant = Instant.parse("2026-09-02T10:00:00Z")
    }
}

/** Een vaste klok in UTC die een test vooruit kan zetten; [verschuiving] hoort na afloop terug op nul. */
class VerzetbareKlok(private val basis: Instant) : Clock() {

    @Volatile
    var verschuiving: Duration = Duration.ZERO

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock {
        if (zone != ZoneOffset.UTC) throw UnsupportedOperationException("de testklok rekent alleen in UTC")

        return this
    }

    override fun instant(): Instant = basis.plus(verschuiving)
}
