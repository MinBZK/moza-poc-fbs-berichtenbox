package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class BearerTokenTest {

    @ParameterizedTest
    @CsvSource(
        "Bearer abc123, abc123",
        "bearer abc123, abc123",
        "BEARER abc123, abc123",
        "'Bearer   abc123', abc123",
        "'Bearer abc123 ', abc123",
    )
    fun `het token komt uit het Bearer-schema, ongeacht hoofdletters en extra spaties`(header: String, token: String) {
        assertEquals(token, SimulatorRechtstreeksResource.bearerToken(header))
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "abc123", "Bearer", "Bearer ", "Basic abc123", "Bearerabc123", "Bearer abc 123", " Bearer abc123"])
    fun `zonder Bearer-schema of met meer dan een token is er geen token`(header: String?) {
        assertNull(SimulatorRechtstreeksResource.bearerToken(header))
    }
}
