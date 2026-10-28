package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.arc.ClientProxy
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.redis.client.Response
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Eigenschappen van de sessie-id's over veel willekeurige invoer, tegen een echte Redis:
 * [Sessiecache.leesSessie] en [Sessiecache.beeindigSessie] accepteren alleen een id dat
 * [Sessiecache.startSessie] uitgaf, een onjuist gevormd id komt niet tot Redis, en een uitgegeven id
 * geldt tot zijn einde en daarna nooit meer. Een vaste seed, zodat een falend geval te herhalen is.
 */
@QuarkusTest
@TestProfile(OpruimRedisTestProfile::class)
class SessieIdEigenschapTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Test
    fun `een onjuist gevormd id wordt geweigerd zonder dat Redis een script draait`() {
        val random = Random(SEED)
        val ontvanger = nieuweOntvanger()
        val echt = sessiecache.startSessie(ontvanger).sessieId
        val invoer = List(GEVALLEN) { OnjuisteSessieIds.onjuistGevormd(random) }
        val eigen = telScriptsVan(ontvanger)

        invoer.forEachIndexed { i, id ->
            assertFalse(Sessiecache.SESSIE_ID_VORM.matches(id), "de generator leverde een geldige vorm: ${zichtbaar(id)}")
            assertThrows<SessiecacheException.SessieOngeldig>(zichtbaar(id)) { sessiecache.leesSessie(id, ontvanger) }
            assertEquals(SessieEinde.ONBEKEND, sessiecache.beeindigSessie(id, ontvanger), zichtbaar(id))

            // Ander Redis-verkeer op dezelfde server, zoals een ronde van een andere test die nog naloopt:
            // dat hoort deze telling niet te raken.
            if (i % ANDER_VERKEER_ELKE == 0) sessiecache.startSessie(nieuweOntvanger())
        }

        assertEquals(0, eigen.get(), "een onjuist gevormd id bereikte Redis")

        // Positieve controle: de telling ziet een script van deze ontvanger wel.
        assertDoesNotThrow { sessiecache.leesSessie(echt, ontvanger) }
        assertEquals(1, eigen.get(), "de telling mist de scripts van deze ontvanger")
    }

    @Test
    fun `een goed gevormd maar nooit uitgegeven id wordt geweigerd en laat de lopende sessie en haar gegevens staan`() {
        val random = Random(SEED + 1)
        val ontvanger = nieuweOntvanger()
        val echt = sessiecache.startSessie(ontvanger).sessieId
        val status = "${BerichtenCache.cacheKey(ontvanger)}:status"
        redis.value(String::class.java).set(status, "{}").await().atMost(WACHTTIJD)

        repeat(GEVALLEN) {
            val id = OnjuisteSessieIds.geldigGevormd(random)

            if (id == echt) return@repeat

            assertThrows<SessiecacheException.SessieOngeldig>(id) { sessiecache.leesSessie(id, ontvanger) }
            assertEquals(SessieEinde.ONBEKEND, sessiecache.beeindigSessie(id, ontvanger), id)
        }

        assertDoesNotThrow { sessiecache.leesSessie(echt, ontvanger) }
        assertTrue(redis.key().exists(status).await().atMost(WACHTTIJD), "een onbekend id wiste de gegevens")
    }

    @Test
    fun `een uitgegeven id geldt tot zijn einde en wordt daarna nooit meer geaccepteerd`() {
        val random = Random(SEED + 2)

        repeat(GEVALLEN / 10) { i ->
            val ontvanger = nieuweOntvanger()
            val ids = List(random.nextInt(1, 4)) { sessiecache.startSessie(ontvanger).sessieId }
            val einde = ids.random(random)

            repeat(random.nextInt(1, 6)) { sessiecache.leesSessie(ids.random(random), ontvanger) }

            val uitkomst = sessiecache.beeindigSessie(einde, ontvanger)

            assertEquals(if (ids.size == 1) SessieEinde.OPGERUIMD else SessieEinde.BEEINDIGD, uitkomst, "geval $i")

            // Ook een nieuwe sessie van dezelfde ontvanger maakt het oude id niet weer geldig.
            val nieuw = sessiecache.startSessie(ontvanger).sessieId

            assertFalse(nieuw in ids, "geval $i: een id werd opnieuw uitgegeven")
            assertThrows<SessiecacheException.SessieOngeldig>("geval $i") { sessiecache.leesSessie(einde, ontvanger) }
            assertEquals(SessieEinde.ONBEKEND, sessiecache.beeindigSessie(einde, ontvanger), "geval $i")
            (ids - einde + nieuw).forEach { assertDoesNotThrow("geval $i") { sessiecache.leesSessie(it, ontvanger) } }
        }
    }

    /**
     * Telt vanaf nu, tot het einde van de test, de scripts die de sessiecache voor [ontvanger] naar Redis
     * stuurt: alleen die met zijn sleutels, zodat ander verkeer op dezelfde server niet meetelt.
     */
    private fun telScriptsVan(ontvanger: Identificatienummer): AtomicInteger {
        val sleutel = "${BerichtenCache.cacheKey(ontvanger)}:sessies"
        val teller = AtomicInteger()
        val echt = (redis as ClientProxy).arc_contextualInstance() as ReactiveRedisDataSource
        val tellend = object : ReactiveRedisDataSource by echt {
            override fun execute(command: String, vararg args: String): Uni<Response> {
                if (command.equals("EVAL", ignoreCase = true) && sleutel in args) teller.incrementAndGet()

                return echt.execute(command, *args)
            }
        }

        QuarkusMock.installMockForType(tellend, ReactiveRedisDataSource::class.java)

        return teller
    }

    private fun zichtbaar(waarde: String): String =
        waarde.map { if (it.code in 0x21..0x7e) it.toString() else "\\u%04x".format(it.code) }.joinToString("", "\"", "\"")

    private companion object {
        const val SEED = 943L
        const val GEVALLEN = 500
        const val ANDER_VERKEER_ELKE = 50
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
        private val teller = AtomicLong(System.nanoTime())

        fun nieuweOntvanger() = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))
    }
}

/** Willekeurige sessie-id's in de vormen waar een vormregel op kan struikelen, ook met regeleinden en stuurtekens. */
internal object OnjuisteSessieIds {

    private const val ALFABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val VREEMD = "+/=!.~*%:;,\"'<>()[]{}\\|@#$&? \t"

    // Lijken op base64url maar zijn het niet: Latijn met accent, Cyrillisch en een full-width cijfer.
    private const val UNICODE = "éßøаеоＡ０ĳ"

    fun geldigGevormd(random: Random): String = String(CharArray(22) { ALFABET[random.nextInt(ALFABET.length)] })

    fun onjuistGevormd(random: Random): String {
        val basis = geldigGevormd(random)

        return when (random.nextInt(8)) {
            0 -> ""
            1 -> basis.take(random.nextInt(22))
            2 -> basis + basis.repeat(random.nextInt(1, 10)).take(random.nextInt(1, 200))
            3 -> vervang(basis, random, VREEMD)
            4 -> vervang(basis, random, UNICODE)
            5 -> if (random.nextBoolean()) basis.dropLast(1) + "🔑" else "🔑" + basis.drop(2)
            // `$` in een reguliere expressie past ook vóór een afsluitend regeleinde; de vormregel mag dat niet.
            6 -> listOf("$basis\n", "$basis\r\n", "\n$basis", basis.take(21) + "\u0000").random(random)
            else -> basis.take(random.nextInt(1, 22)) + " " + basis.drop(22 - random.nextInt(1, 22)).take(21)
        }
    }

    private fun vervang(basis: String, random: Random, tekens: String): String {
        val plek = random.nextInt(basis.length)

        return basis.substring(0, plek) + tekens[random.nextInt(tekens.length)] + basis.substring(plek + 1)
    }
}
