package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.Rsin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdentificatienummerCanonicalJacksonTest {
    private val mapper = ObjectMapper().registerModule(
        SimpleModule()
            .addSerializer(Identificatienummer::class.java, IdentificatienummerCanonicalSerializer())
            .addDeserializer(Identificatienummer::class.java, IdentificatienummerCanonicalDeserializer()),
    )

    @Test
    fun `serialiseert naar canonieke TYPE-waarde string`() {
        val json = mapper.writeValueAsString(Bsn("999993653") as Identificatienummer)
        assertEquals("\"BSN:999993653\"", json)
    }

    @Test
    fun `round-trip behoudt type en waarde`() {
        val origineel: Identificatienummer = Rsin("999993653")
        val terug = mapper.readValue(mapper.writeValueAsString(origineel), Identificatienummer::class.java)
        assertEquals(origineel, terug)
    }
}
