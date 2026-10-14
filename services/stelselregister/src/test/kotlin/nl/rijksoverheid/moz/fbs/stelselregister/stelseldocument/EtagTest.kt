package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class EtagTest {

    private val etag = "W/\"abc.def.1\""

    @ParameterizedTest(name = "If-None-Match [{0}] → {1}")
    @CsvSource(
        delimiter = '|',
        nullValues = ["NULL"],
        value = [
            "NULL | false",
            "'' | false",
            "'   ' | false",
            "W/\"abc.def.1\" | true",
            "\"abc.def.1\" | true",
            "* | true",
            "W/\"iets-anders\" | false",
            "W/\"iets-anders\", W/\"abc.def.1\" | true",
            "W/\"een\",W/\"twee\" | false",
            "abc.def.1 | false",
            "W/\"abc.def.2\" | false",
        ],
    )
    fun `zwakke vergelijking van If-None-Match`(header: String?, verwacht: Boolean) {
        assertEquals(verwacht, Etag.komtOvereen(header, etag))
    }
}
