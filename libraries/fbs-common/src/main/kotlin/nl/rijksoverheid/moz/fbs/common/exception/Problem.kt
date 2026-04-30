package nl.rijksoverheid.moz.fbs.common.exception

import com.fasterxml.jackson.annotation.JsonInclude
import java.net.URI

/**
 * RFC 9457 Problem Details for HTTP APIs.
 *
 * Invarianten worden via de `safe*`-factories afgedwongen in plaats van `require()` zodat
 * een exception-mapper nooit tijdens foutafhandeling cascade-exceptions kan opleveren.
 * Directe constructor-aanroep is toegestaan en valideert niet — dat is expliciet de keuze
 * voor data-binding (Jackson) en testopstellingen. Productiecode hoort Problems altijd via
 * de `of(...)`-factory te bouwen.
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
    companion object {
        val ABOUT_BLANK: URI = URI.create("about:blank")
        const val MAX_TITLE_LENGTH = 255
        val HTTP_ERROR_STATUS_RANGE = 400..599

        /**
         * Bouwt een Problem en clamp-et onveilige input stilzwijgend naar veilige defaults.
         * Nooit gooien: deze factory moet ook werken als alle invoer corrupt is, omdat
         * aanroepers zelf in foutafhandeling zitten.
         */
        fun of(
            title: String?,
            status: Int,
            detail: String? = null,
            instance: URI? = null,
            type: URI = ABOUT_BLANK,
        ): Problem = Problem(
            type = if (type == ABOUT_BLANK || type.isAbsolute) type else ABOUT_BLANK,
            title = clampTitle(title),
            status = clampStatus(status),
            detail = detail,
            instance = instance,
        )

        private fun clampTitle(raw: String?): String {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.isEmpty()) return "Error"
            return if (cleaned.length > MAX_TITLE_LENGTH) cleaned.take(MAX_TITLE_LENGTH) else cleaned
        }

        private fun clampStatus(raw: Int): Int =
            if (raw in HTTP_ERROR_STATUS_RANGE) raw else 500
    }
}
