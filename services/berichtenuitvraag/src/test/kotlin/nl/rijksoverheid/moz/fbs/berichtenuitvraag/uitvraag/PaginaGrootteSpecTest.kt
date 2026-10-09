package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.validation.constraints.Max
import jakarta.ws.rs.QueryParam
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.UitvraagApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Het maximum voor `paginaGrootte` staat in de spec, het plafond in de sessiecache; die twee
 * leven in verschillende modules. Ligt het plafond lager, dan krijgt een afnemer die een
 * toegestane pagina vraagt stil minder berichten, en wie de hele set leest mist er dan zonder het
 * te merken. De gegenereerde interface draagt het spec-maximum als `@Max`.
 */
class PaginaGrootteSpecTest {

    @Test
    fun `het spec-maximum voor paginaGrootte is gelijk aan het plafond van de sessiecache`() {
        val parameter = UitvraagApi::class.java.methods
            .single { it.name == "getBerichten" }
            .parameters
            .single { it.getAnnotation(QueryParam::class.java)?.value == "paginaGrootte" }

        val specMaximum = checkNotNull(parameter.getAnnotation(Max::class.java)) {
            "paginaGrootte heeft geen maximum meer in de spec"
        }.value

        assertEquals(Sessiecache.MAX_PAGINA_GROOTTE.toLong(), specMaximum)
    }
}
