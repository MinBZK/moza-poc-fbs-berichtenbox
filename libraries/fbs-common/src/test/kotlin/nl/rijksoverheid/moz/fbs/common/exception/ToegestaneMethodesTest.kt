package nl.rijksoverheid.moz.fbs.common.exception

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ToegestaneMethodesTest {

    private val spec = ObjectMapper(YAMLFactory()).readTree(
        """
        servers:
          - url: /api/v1
        paths:
          /berichten:
            get: {}
            post: {}
          /berichten/zoeken:
            post: {}
          /berichten/{berichtId}:
            get: {}
            delete: {}
            parameters: []
          /leeg:
            parameters: []
        """.trimIndent(),
    )

    private val methodes = ToegestaneMethodes(spec)

    @Test
    fun `een pad krijgt zijn methodes uit de spec, met HEAD bij GET en altijd OPTIONS`() {
        assertEquals(setOf("GET", "POST", "HEAD", "OPTIONS"), methodes.voor("/api/v1/berichten"))
    }

    @Test
    fun `een pad met een parameter matcht op elke waarde`() {
        assertEquals(setOf("GET", "DELETE", "HEAD", "OPTIONS"), methodes.voor("/api/v1/berichten/5f0c3e4a-0000-4000-8000-000000000001"))
    }

    // Een letterlijk pad gaat vóór een pad met een parameter op dezelfde plek.
    @Test
    fun `een letterlijk pad wint van een parameter`() {
        assertEquals(setOf("POST", "OPTIONS"), methodes.voor("/api/v1/berichten/zoeken"))
    }

    // Quarkus levert het pad soms zonder voorloop- of met een afsluitende slash.
    @ParameterizedTest
    @ValueSource(strings = ["api/v1/berichten", "/api/v1/berichten/", "api/v1/berichten/"])
    fun `slashes aan de randen tellen niet`(pad: String) {
        assertEquals(setOf("GET", "POST", "HEAD", "OPTIONS"), methodes.voor(pad))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/onbekend", "/berichten", "/api/v1/berichten/a/b", "/api/v1/leeg"])
    fun `een onbekend pad, of een zonder operaties, krijgt niets`(pad: String) {
        assertEquals(emptySet<String>(), methodes.voor(pad))
    }

    @Test
    fun `zonder servers geldt het pad zoals het in de spec staat`() {
        val zonder = ToegestaneMethodes(ObjectMapper(YAMLFactory()).readTree("paths:\n  /x:\n    put: {}\n"))

        assertEquals(setOf("PUT", "OPTIONS"), zonder.voor("/x"))
    }

    // Meer letterlijke tekens wint, ook als beide één parameter hebben.
    @Test
    fun `bij evenveel parameters wint het template met meer letterlijke tekens`() {
        val spec = ObjectMapper(YAMLFactory()).readTree(
            """
            paths:
              /a/{x}/c:
                get: {}
              /a/bbb/{y}:
                delete: {}
            """.trimIndent(),
        )

        assertEquals(setOf("DELETE", "OPTIONS"), ToegestaneMethodes(spec).voor("/a/bbb/c"))
    }

    @Test
    fun `een absolute server-URL telt alleen met zijn pad`() {
        val spec = ObjectMapper(YAMLFactory()).readTree("servers:\n  - url: https://example.org/api/v1/\npaths:\n  /x:\n    get: {}\n")

        assertEquals(setOf("GET", "HEAD", "OPTIONS"), ToegestaneMethodes(spec).voor("/api/v1/x"))
    }

    // Raw zoals Quarkus routeert: een gecodeerde slash blijft binnen één segment.
    @Test
    fun `een gecodeerde slash maakt geen extra segment`() {
        assertEquals(setOf("GET", "DELETE", "HEAD", "OPTIONS"), methodes.voor("/api/v1/berichten/a%2Fb"))
    }

    @Test
    fun `een spec op het classpath wordt gelezen`() {
        val geladen = ToegestaneMethodes.laad("openapi/allow-test-api.yaml")

        assertEquals(setOf("GET", "HEAD", "OPTIONS"), geladen?.voor("/api/v1/dingen"))
    }

    // Liever geen Allow dan een onjuiste: zonder spec, of met een onleesbare, is er niets.
    @Test
    fun `zonder spec of met een onleesbare spec is er niets`() {
        assertEquals(null, ToegestaneMethodes.laad(null))
        assertEquals(null, ToegestaneMethodes.laad("openapi/bestaat-niet.yaml"))
        assertEquals(null, ToegestaneMethodes.laad("openapi/allow-test-kapot.yaml"))
    }
}
