package nl.rijksoverheid.moz.fbs.common

import com.fasterxml.jackson.annotation.JsonInclude
import java.net.URI

/**
 * RFC 9457 Problem Details for HTTP APIs.
 *
 * `@JsonInclude(NON_NULL)` omdat RFC 9457 voorschrijft dat afwezige velden weggelaten
 * worden, niet als `null` worden geserialiseerd.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Problem(
    val type: URI = ABOUT_BLANK,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: URI? = null,
) {
    init {
        require(title.isNotBlank()) { "title mag niet leeg zijn" }
        require(title.length <= MAX_TITLE_LENGTH) {
            "title mag max $MAX_TITLE_LENGTH characters zijn (RFC 9457 raadt kort aan)"
        }
        require(status in HTTP_ERROR_STATUS_RANGE) {
            "status moet een HTTP-foutstatuscode zijn ($HTTP_ERROR_STATUS_RANGE); Problem is alleen voor fouten"
        }
        require(type == ABOUT_BLANK || type.isAbsolute) {
            "type moet absolute URI zijn als het niet about:blank is"
        }
    }

    companion object {
        val ABOUT_BLANK: URI = URI.create("about:blank")
        const val MAX_TITLE_LENGTH = 255
        val HTTP_ERROR_STATUS_RANGE = 400..599
    }
}
