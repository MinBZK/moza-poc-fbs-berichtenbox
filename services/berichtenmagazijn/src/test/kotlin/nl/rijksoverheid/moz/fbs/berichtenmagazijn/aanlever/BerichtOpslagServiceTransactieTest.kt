package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Zonder `rollbackOn` legt de transactie-interceptor de opslag vast als er een checked
 * exception uit de methode komt. De resource schrijft daarna "mislukt" in het logboek over
 * een bericht dat er staat.
 */
class BerichtOpslagServiceTransactieTest {

    @Test
    fun `slaBerichtOp draait de transactie terug bij elke Exception, ook een checked`() {
        val transactie = BerichtOpslagService::class.java
            .getMethod("slaBerichtOp", Bericht::class.java, List::class.java)
            .getAnnotation(Transactional::class.java)

        assertTrue(
            transactie.rollbackOn.any { it == Exception::class },
            "rollbackOn was ${transactie.rollbackOn.toList()}",
        )
    }
}
