package nl.rijksoverheid.moz.fbs.common

import io.mockk.mockk
import io.mockk.verify
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import org.junit.jupiter.api.Test

class LogboekContextDefaultFilterTest {

    @Test
    fun `filter zet safe defaults zodat ongevalideerde clientinput nooit in LDV-log terechtkomt`() {
        val context = mockk<LogboekContext>(relaxed = true)
        val filter = LogboekContextDefaultFilter().apply { logboekContext = context }

        filter.filter(mockk(relaxed = true))

        verify { context.dataSubjectId = "unknown" }
        verify { context.dataSubjectType = "system" }
    }
}
