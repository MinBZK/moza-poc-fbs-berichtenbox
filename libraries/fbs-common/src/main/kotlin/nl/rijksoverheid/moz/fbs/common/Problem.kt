package nl.rijksoverheid.moz.fbs.common

import com.fasterxml.jackson.annotation.JsonInclude
import java.net.URI

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Problem(
    val type: URI = URI.create("about:blank"),
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: URI? = null,
) {
    init {
        require(title.isNotBlank()) { "title mag niet leeg zijn" }
        require(status in 100..599) { "status moet een geldige HTTP-statuscode zijn (100-599)" }
    }
}
