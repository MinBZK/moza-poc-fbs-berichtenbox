package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.ws.rs.InternalServerErrorException
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Integratietest voor het persist/find-roundtrip van [BerichtRepository].
 * Dekt leespad (via `vind` → `toDomain()`) en verifieert dat CLOB `inhoud` geen
 * truncatie ondergaat.
 */
@QuarkusTest
class BerichtRepositoryIntegrationTest {

    @Inject
    lateinit var repository: BerichtRepository

    @Inject
    lateinit var entityManager: EntityManager

    @BeforeEach
    @Transactional
    fun clean() {
        repository.deleteAll()
    }

    @Test
    @Transactional
    fun `opslaan en vind behoudt alle velden identiek`() {
        val original = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = Bsn("999993653"),
            onderwerp = "Voorlopige aanslag 2026",
            inhoud = "Hierbij ontvangt u de voorlopige aanslag.",
            tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        )

        repository.save(original)

        val found = repository.findByBerichtId(original.berichtId)
        assertEquals(original, found)
    }

    @Test
    @Transactional
    fun `opslaan met grote CLOB-inhoud behoudt volledige inhoud zonder truncatie`() {
        val largeContent = "x".repeat(64_000) // ruim boven VARCHAR-grens in H2 (8K typisch)
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = Bsn("999993653"),
            onderwerp = "Groot bericht",
            inhoud = largeContent,
            tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        )

        repository.save(bericht)

        val loaded = repository.findByBerichtId(bericht.berichtId)
        assertNotNull(loaded)
        assertEquals(largeContent.length, loaded!!.inhoud.length)
        assertEquals(largeContent, loaded.inhoud)
    }

    @Test
    @Transactional
    fun `opslaan met KVK-ontvanger roundtrip behoudt type`() {
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = Kvk("12345678"),
            onderwerp = "Aan organisatie",
            inhoud = "Inhoud",
            tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        )

        repository.save(bericht)

        val loaded = repository.findByBerichtId(bericht.berichtId)!!
        assertEquals(Kvk::class, loaded.ontvanger::class)
        assertEquals("12345678", loaded.ontvanger.waarde)
    }

    @Test
    @Transactional
    fun `opslaan van tweede bericht met zelfde berichtId gooit Hibernate ConstraintViolationException met SQL state 23505`() {
        val berichtId = UUID.randomUUID()
        val first = Bericht(
            berichtId = berichtId,
            afzender = Oin("00000001003214345000"),
            ontvanger = Bsn("999993653"),
            onderwerp = "Eerste",
            inhoud = "1",
            tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        )
        val second = first.copy(onderwerp = "Tweede", inhoud = "2")

        repository.save(first)
        // flush+clear om de eerste entity uit de in-memory persistence context te
        // halen, anders blokkeert Hibernate de tweede persist met EntityExistsException
        // (NonUniqueObjectException) vóór de DB überhaupt wordt geraakt. We willen
        // expliciet de DB-laag 23505-fout testen.
        entityManager.flush()
        entityManager.clear()

        val ex = assertThrows(RuntimeException::class.java) {
            repository.save(second)
            entityManager.flush()
        }
        // Borg expliciet dat een PK-violation als Hibernate ConstraintViolationException
        // met SQL state 23505 door de stack komt. De hele 409-mapperketen
        // (DbConstraintViolationExceptionMapper) hangt hiervan af.
        val chain = generateSequence<Throwable>(ex) { it.cause }.toList()
        val hibernateCve = chain.filterIsInstance<ConstraintViolationException>().firstOrNull()
        assertNotNull(hibernateCve, "verwachte Hibernate ConstraintViolationException in cause-keten; werkelijk: $chain")
        assertEquals("23505", hibernateCve!!.sqlException?.sqlState)
    }

    @Test
    fun `toDomain met corrupte DB-rij gooit InternalServerErrorException, geen DomainValidationException`() {
        // Simuleer dataresidu uit een vorige schemaversie of handmatige DB-edit:
        // een ongeldige afzender (te kort, geen geldige OIN) wordt direct als entity
        // gepersisteerd, buiten fromDomain om. Bij hydratatie moet dit als serverfout
        // (500) worden afgehandeld, niet als clientfout (400) — anders krijgt de
        // aanroeper een misleidende statuscode.
        val berichtId = UUID.randomUUID()
        insertCorruptEntity(berichtId)

        val ex = assertThrows(InternalServerErrorException::class.java) {
            findByIdInTransaction(berichtId)
        }
        assertTrue(
            ex.message!!.contains(berichtId.toString()),
            "foutboodschap bevat berichtId voor diagnose",
        )
    }

    @Transactional
    fun insertCorruptEntity(berichtId: UUID) {
        val corrupt = BerichtEntity().apply {
            this.berichtId = berichtId
            afzender = "kort"  // bewust ongeldig: geen 20-cijferige OIN
            ontvangerType = IdentificatienummerType.BSN
            ontvangerWaarde = "999993653"
            onderwerp = "Test"
            inhoud = "Inhoud"
            tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        }
        entityManager.persist(corrupt)
    }

    @Transactional
    fun findByIdInTransaction(berichtId: UUID): Bericht? = repository.findByBerichtId(berichtId)

    @Test
    @Transactional
    fun `softDelete tweede call op zelfde berichtId retourneert false zonder exception`() {
        // De WHERE-clause filtert verwijderdOp IS NULL — een tweede update
        // mag geen rij meer raken en retourneert false. check(rows lt= 1)
        // mag in dit pad NIET vuren, anders zou de race-tak van
        // BerichtBeheerService.verwijder kapot zijn.
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = Bsn("999993653"),
            onderwerp = "Twee deletes",
            inhoud = "Inhoud",
            tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        )
        repository.save(bericht)
        entityManager.flush()

        val eerste = repository.softDelete(bericht.berichtId, Bsn("999993653"), Instant.now())
        val tweede = repository.softDelete(bericht.berichtId, Bsn("999993653"), Instant.now())

        assertTrue(eerste, "eerste softDelete moet succesvol zijn (1 rij)")
        assertEquals(false, tweede, "tweede softDelete mag niets raken")
    }

    @Test
    @Transactional
    fun `softDelete door verkeerde ontvanger retourneert false zonder rij te wijzigen`() {
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin("00000001003214345000"),
            ontvanger = Bsn("999993653"),
            onderwerp = "Wrong recipient",
            inhoud = "Inhoud",
            tijdstipOntvangst = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        )
        repository.save(bericht)
        entityManager.flush()

        val gewijzigd = repository.softDelete(bericht.berichtId, Bsn("123456782"), Instant.now())

        assertEquals(false, gewijzigd, "ontvanger-mismatch mag geen rij wijzigen")
        val nuNogSteedsActief = repository.findByBerichtId(bericht.berichtId)
        assertNotNull(nuNogSteedsActief, "bericht moet nog steeds zichtbaar zijn")
    }
}
