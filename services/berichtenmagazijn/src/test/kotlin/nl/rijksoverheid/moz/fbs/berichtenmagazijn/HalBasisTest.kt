package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HalBasisTest {

    @ParameterizedTest(name = "root-path \"{0}\"")
    @ValueSource(strings = ["/", ""])
    fun `zonder root-path is de basis het base-path uit de spec`(rootPad: String) {
        val uri = HalBasis(rootPad).builder().path("berichten").build()

        assertEquals("/api/v1/berichten", uri.toString())
        assertNull(uri.scheme)
        assertNull(uri.rawAuthority)
    }

    @ParameterizedTest(name = "root-path \"{0}\"")
    @ValueSource(strings = ["/fbs", "/fbs/", "fbs", "fbs/"])
    fun `een root-path komt voor het base-path, altijd met voorloop-slash`(rootPad: String) {
        val uri = HalBasis(rootPad).builder().path("berichten").build()

        assertEquals("/fbs/api/v1/berichten", uri.toString())
        assertNull(uri.scheme)
        assertNull(uri.rawAuthority)
    }
}
