package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency-bulkhead via [MagazijnAggregatieBulkhead.begrensd]: pint de acquire/release-balans
 * op élke terminatie (succes/fout/cancel/taak-opbouwfout), het wachten op een vrijkomende permit,
 * het verstrijken van het wachtbudget, en de fail-fast config-validatie.
 */
class MagazijnAggregatieBulkheadTest {

    private fun bulkhead(
        maxConcurrent: Int = 1,
        maxParallelPerRonde: Int = 1,
        maxWachttijdMs: Long = WACHTBUDGET_MS,
    ) = MagazijnAggregatieBulkhead(maxConcurrent, maxParallelPerRonde, maxWachttijdMs)

    @Test
    fun `taak draait onder een permit en geeft die vrij bij succes`() {
        val bulkhead = bulkhead()

        val uitkomst = bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = { Uni.createFrom().item("OK") },
        ).await().indefinitely()

        assertEquals("OK", uitkomst)
        assertEquals(1, bulkhead.vrijePermits(), "permit vrijgegeven na succes")
    }

    @Test
    fun `permit vrijgegeven bij een falende taak`() {
        val bulkhead = bulkhead()

        val uni = bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = { Uni.createFrom().failure(RuntimeException("boem")) },
        )

        assertThrows<RuntimeException> { uni.await().indefinitely() }
        assertEquals(1, bulkhead.vrijePermits(), "permit vrijgegeven na fout")
    }

    @Test
    fun `permit vrijgegeven als het opbouwen van de taak-Uni gooit`() {
        // Regressie: gooit de taak-lambda vóór er een Uni (met onTermination) is, dan is er niets om
        // de permit op terminatie vrij te geven — begrensd MOET hem alsnog vrijgeven, anders lekt hij.
        val bulkhead = bulkhead()

        val uni = bulkhead.begrensd<String>(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = { throw IllegalStateException("opbouw faalt") },
        )

        assertThrows<IllegalStateException> { uni.await().indefinitely() }
        assertEquals(1, bulkhead.vrijePermits(), "permit vrijgegeven ondanks opbouwfout")
    }

    @Test
    fun `vol bulkhead wacht en start de taak zodra er een permit vrijkomt`() {
        // Dit is de kern van de wachtrij: een tweede bevraging wordt NIET afgewezen maar wacht,
        // en draait alsnog zodra de vasthoudende bevraging termineert.
        val bulkhead = bulkhead(maxWachttijdMs = 30_000)

        val vastgehouden = bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = { Uni.createFrom().nothing<String>() },
        ).subscribe().with({}, {})

        assertEquals(0, bulkhead.vrijePermits(), "permit geclaimd door de lopende taak")

        val wachtende = UniAssertSubscriber.create<String>()

        bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = { Uni.createFrom().item("OK") },
        ).subscribe().withSubscriber(wachtende)

        // Nog niets: er is geen permit, dus de wachtende hangt in de poll-lus.
        wachtende.assertNotTerminated()

        vastgehouden.cancel()

        assertEquals("OK", wachtende.awaitItem(Duration.ofSeconds(5)).item)
        assertEquals(1, bulkhead.vrijePermits(), "permit vrijgegeven na de wachtende taak")
    }

    @Test
    fun `verstreken wachtbudget levert de verlopen-tak zonder permit te claimen`() {
        val bulkhead = bulkhead()

        val vastgehouden = bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = { Uni.createFrom().nothing<String>() },
        ).subscribe().with({}, {})

        val gestart = AtomicInteger(0)

        val uitkomst = bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = {
                gestart.incrementAndGet()
                Uni.createFrom().item("OK")
            },
        ).await().atMost(Duration.ofSeconds(5))

        assertEquals("VERLOPEN", uitkomst, "wachtbudget verstreken → verlopen-tak")
        assertEquals(0, gestart.get(), "de taak is nooit gestart")
        assertEquals(0, bulkhead.vrijePermits(), "de verlopen-tak claimt geen extra permit")

        // Annuleer de vasthoudende subscription: onTermination(cancel) geeft de permit vrij.
        vastgehouden.cancel()

        assertEquals(1, bulkhead.vrijePermits(), "permit vrijgegeven bij cancel")
    }

    @Test
    fun `een verstreken wachtbudget lekt geen permit voor de volgende bevraging`() {
        // Regressie op de valkuil van een permit-overdragende wachtrij: als een permit aan een al
        // opgegeven wachtende wordt toegekend, is hij van niemand meer en zakt de capaciteit
        // stilletjes. Na een verstreken budget moet de volle capaciteit beschikbaar blijven.
        val bulkhead = bulkhead()

        val vastgehouden = bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = { Uni.createFrom().nothing<String>() },
        ).subscribe().with({}, {})

        bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = { Uni.createFrom().item("OK") },
        ).await().atMost(Duration.ofSeconds(5))

        vastgehouden.cancel()

        assertEquals(1, bulkhead.vrijePermits(), "capaciteit volledig terug na een verlopen wachter")

        // En de volgende bevraging krijgt die permit ook echt.
        assertEquals(
            "OK",
            bulkhead.begrensd(
                verlopen = { Uni.createFrom().item("VERLOPEN") },
                taak = { Uni.createFrom().item("OK") },
            ).await().atMost(Duration.ofSeconds(5)),
        )
    }

    @Test
    fun `alle wachtenden komen aan de beurt - niemand valt structureel buiten beeld`() {
        // Het acceptatiecriterium op bulkhead-niveau: meer bevragingen dan permits levert geen
        // uitgeselecteerde groep op die nooit draait. Elke bevraging houdt zijn permit even vast,
        // zodat de wachtenden echt om de permits moeten strijden.
        val aantal = 24
        val bulkhead = MagazijnAggregatieBulkhead(
            maxConcurrent = 4,
            maxParallelPerRonde = 4,
            maxWachttijdMs = 30_000,
        )

        val gedraaid = ConcurrentLinkedQueue<Int>()
        val subscribers = (1..aantal).map { nummer ->
            UniAssertSubscriber.create<Int>().also { subscriber ->
                bulkhead.begrensd(
                    verlopen = { Uni.createFrom().item(-nummer) },
                    taak = {
                        gedraaid.add(nummer)
                        Uni.createFrom().item(nummer).onItem().delayIt().by(Duration.ofMillis(5))
                    },
                ).subscribe().withSubscriber(subscriber)
            }
        }

        val uitkomsten = subscribers.map { it.awaitItem(Duration.ofSeconds(30)).item }

        assertEquals((1..aantal).toSet(), uitkomsten.toSet(), "elke bevraging leverde zijn eigen uitkomst")
        assertEquals((1..aantal).toSet(), gedraaid.toSet(), "elke taak is daadwerkelijk gestart")
        assertEquals(4, bulkhead.vrijePermits(), "alle permits terug")
    }

    @Test
    fun `nooit meer taken tegelijk dan permits`() {
        // De bescherming zelf: de wachtrij mag de gelijktijdigheidsgrens niet oprekken.
        val bulkhead = MagazijnAggregatieBulkhead(
            maxConcurrent = 3,
            maxParallelPerRonde = 3,
            maxWachttijdMs = 30_000,
        )

        val actief = AtomicInteger(0)
        val piek = AtomicInteger(0)

        val subscribers = (1..15).map {
            UniAssertSubscriber.create<Int>().also { subscriber ->
                bulkhead.begrensd(
                    verlopen = { Uni.createFrom().item(-1) },
                    taak = {
                        val nu = actief.incrementAndGet()

                        piek.updateAndGet { hoogste -> maxOf(hoogste, nu) }

                        Uni.createFrom().item(1)
                            .onItem().delayIt().by(Duration.ofMillis(10))
                            .onTermination().invoke(Runnable { actief.decrementAndGet() })
                    },
                ).subscribe().withSubscriber(subscriber)
            }
        }

        subscribers.forEach { it.awaitItem(Duration.ofSeconds(30)) }

        assertTrue(piek.get() <= 3, "hoogste gelijktijdigheid was ${piek.get()}, verwacht ≤ 3")
        assertEquals(0, actief.get(), "geen taak blijft achter")
    }

    @Test
    fun `niet-positieve configuratie faalt fail-fast bij constructie`() {
        // Het init-blok gooit al bij constructie — vóór de Semaphore-constructie, die zelf bij
        // 0/negatief niet gooit (en dan een altijd-vol bulkhead zou geven waarin elke bevraging
        // zijn wachtbudget volmaakt en daarna niets levert).
        assertThrows<IllegalArgumentException> { bulkhead(maxConcurrent = 0) }
        assertThrows<IllegalArgumentException> { bulkhead(maxConcurrent = -1) }
        assertThrows<IllegalArgumentException> { bulkhead(maxParallelPerRonde = 0) }
        assertThrows<IllegalArgumentException> { bulkhead(maxParallelPerRonde = -1) }
        assertThrows<IllegalArgumentException> { bulkhead(maxWachttijdMs = 0) }
        assertThrows<IllegalArgumentException> { bulkhead(maxWachttijdMs = -1) }
    }

    @Test
    fun `een geannuleerde wachtende kost geen permit`() {
        // De wachtende houdt niets vast: acquire en release zitten in hetzelfde synchrone blok, dus
        // een annulering tijdens het wachten kan er niet tussen vallen. Zou de acquire wél van het
        // aanhaken van de release gescheiden zijn, dan zou deze permit permanent kwijt zijn.
        val bulkhead = bulkhead(maxWachttijdMs = 30_000)

        val vastgehouden = bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = { Uni.createFrom().nothing<String>() },
        ).subscribe().with({}, {})

        val gestart = AtomicInteger(0)
        val wachtende = bulkhead.begrensd(
            verlopen = { Uni.createFrom().item("VERLOPEN") },
            taak = {
                gestart.incrementAndGet()
                Uni.createFrom().item("OK")
            },
        ).subscribe().with({}, {})

        wachtende.cancel()
        vastgehouden.cancel()

        assertEquals(1, bulkhead.vrijePermits(), "beide permits terug na twee annuleringen")

        // En de geannuleerde wachtende is nooit alsnog aan zijn taak begonnen.
        assertEquals(0, gestart.get())
    }

    @Test
    fun `een absurd wachtbudget faalt fail-fast in plaats van stil over te lopen`() {
        // Het budget gaat naar nanoseconden; zonder plafond loopt een waarde als deze daar over naar
        // negatief, is de deadline meteen verstreken en is de wachtrij stil weer een zeef.
        assertThrows<IllegalArgumentException> { bulkhead(maxWachttijdMs = Long.MAX_VALUE) }
        assertThrows<IllegalArgumentException> {
            bulkhead(maxWachttijdMs = MagazijnAggregatieBulkhead.MAX_WACHTTIJD_MS_PLAFOND + 1)
        }

        // Precies op het plafond mag wel.
        bulkhead(maxWachttijdMs = MagazijnAggregatieBulkhead.MAX_WACHTTIJD_MS_PLAFOND)
    }

    @Test
    fun `meer parallel per ronde dan permits faalt fail-fast bij constructie`() {
        // Anders biedt één ronde structureel meer bevragingen aan dan er permits zijn, en verbrandt
        // het overschot zijn wachtbudget in plaats van te wachten op een permit die zo vrijkomt.
        assertThrows<IllegalArgumentException> {
            MagazijnAggregatieBulkhead(maxConcurrent = 4, maxParallelPerRonde = 5, maxWachttijdMs = WACHTBUDGET_MS)
        }

        // Gelijk mag wel: dan gebruikt één ronde precies de volledige capaciteit.
        MagazijnAggregatieBulkhead(maxConcurrent = 4, maxParallelPerRonde = 4, maxWachttijdMs = WACHTBUDGET_MS)
    }

    private companion object {
        // Kort genoeg om een verstreken budget in een test af te wachten, ruim boven het
        // poll-interval van de bulkhead zodat er echt gepolld is voordat het budget verstrijkt.
        const val WACHTBUDGET_MS = 200L
    }
}
