package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HalBasisTest {

    @ParameterizedTest(name = "root-path {0} + rest-path {1}")
    @CsvSource(
        "/, /api/v1, /api/v1/berichten",
        "/, api/v1, /api/v1/berichten",
        "/fbs, /api/v1, /fbs/api/v1/berichten",
        "/fbs/, /api/v1/, /fbs/api/v1/berichten",
    )
    fun `de basis is een pad zonder schema en host`(rootPad: String, restPad: String, verwacht: String) {
        val uri = HalBasis(rootPad, restPad).builder().path("berichten").build()

        assertEquals(verwacht, uri.toString())
        assertNull(uri.scheme)
        assertNull(uri.rawAuthority)
    }
}
