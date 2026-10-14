package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import com.fasterxml.jackson.databind.ObjectMapper
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Instant

class StelseldocumentTest {

    private val json = ObjectMapper()
    private val uitgegevenOp = Instant.parse("2026-10-08T10:00:00Z")
    private val verlooptOp = Instant.parse("2026-10-09T10:00:00Z")

    private fun register(vararg inschrijvingen: Magazijninschrijving) = object : Magazijnregister {
        override fun alle() = inschrijvingen.toList()
        override fun voorOin(oin: Oin) = inschrijvingen.firstOrNull { it.oin == oin }
    }

    private fun inschrijving(nummer: Int, naam: String = "Organisatie $nummer", grantHash: String? = null) =
        Magazijninschrijving(
            oin = Oin(nummer.toString().padStart(20, '0')),
            url = URI.create("https://magazijn-$nummer.example"),
            naam = naam,
            grantHash = grantHash,
        )

    private fun payload(document: Stelseldocument) =
        json.readTree(document.payload("00000000000000001000", "test", uitgegevenOp, verlooptOp))

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 100])
    fun `elke inschrijving in het register staat in het document`(aantal: Int) {
        val inschrijvingen = (1..aantal).map { inschrijving(it) }.toTypedArray()

        val organisaties = payload(Stelseldocument.uit(register(*inschrijvingen))).path("organizations")

        assertEquals(aantal, organisaties.size())
        assertEquals(
            inschrijvingen.map { it.oin.waarde }.sorted(),
            organisaties.map { it.path("oin").asText() },
        )
    }

    @Test
    fun `een organisatie draagt oin, naam en het adres van haar magazijn`() {
        val organisatie = payload(Stelseldocument.uit(register(inschrijving(7, naam = "Kadaster")))).path("organizations")[0]

        assertEquals("00000000000000000007", organisatie.path("oin").asText())
        assertEquals("Kadaster", organisatie.path("name").asText())
        assertEquals("https://magazijn-7.example", organisatie.path("magazijn_url").asText())
        assertEquals(listOf("oin", "name", "magazijn_url"), organisatie.fieldNames().asSequence().toList())
    }

    @Test
    fun `de grant-hash van een inschrijving komt niet in het document`() {
        val document = Stelseldocument.uit(register(inschrijving(1, grantHash = "geheime-routering")))

        assertFalse(payload(document).toString().contains("geheime-routering"))
    }

    @Test
    fun `organisaties staan op OIN gesorteerd, ongeacht de volgorde in het register`() {
        val document = Stelseldocument.uit(register(inschrijving(30), inschrijving(4), inschrijving(200)))

        assertEquals(
            listOf("00000000000000000004", "00000000000000000030", "00000000000000000200"),
            document.organisaties.map { it.oin },
        )
    }

    @Test
    fun `de payload draagt uitgever, tijdstippen, omgeving en de vaste lijsten`() {
        val payload = payload(Stelseldocument.uit(register(inschrijving(1))))

        assertEquals("00000000000000001000", payload.path("iss").asText())
        assertEquals(uitgegevenOp.epochSecond, payload.path("iat").asLong())
        assertEquals(verlooptOp.epochSecond, payload.path("exp").asLong())
        assertEquals("test", payload.path("environment").asText())
        assertEquals(0, payload.path("app_managers").size())
        assertEquals("bericht", payload.path("document_types")[0].path("name").asText())
        assertEquals(
            listOf("iss", "iat", "exp", "version", "environment", "organizations", "app_managers", "document_types"),
            payload.fieldNames().asSequence().toList(),
        )
    }

    @Test
    fun `de versie hangt aan de inhoud en niet aan volgorde, uitgever of tijdstip`() {
        val een = Stelseldocument.uit(register(inschrijving(1), inschrijving(2)))
        val omgekeerd = Stelseldocument.uit(register(inschrijving(2), inschrijving(1)))

        assertEquals(een.versie, omgekeerd.versie)
        assertEquals(een.versie, payload(een).path("version").asText())
        assertEquals(43, een.versie.length)
    }

    @Test
    fun `elke wijziging in het register geeft een andere versie`() {
        val basis = Stelseldocument.uit(register(inschrijving(1), inschrijving(2))).versie

        val varianten = listOf(
            register(inschrijving(1)),
            register(inschrijving(1), inschrijving(2), inschrijving(3)),
            register(inschrijving(1), inschrijving(2, naam = "Hernoemd")),
            register(inschrijving(1), inschrijving(2).copy(url = URI.create("https://verhuisd.example"))),
            register(),
        ).map { Stelseldocument.uit(it).versie }

        varianten.forEach { assertNotEquals(basis, it) }
        assertEquals(varianten.size, varianten.toSet().size)
    }
}
