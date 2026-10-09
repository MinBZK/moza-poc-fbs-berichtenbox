package nl.rijksoverheid.moz.fbs.stelselregister

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.util.Properties

/**
 * Het register staat in de config van elke dienst die het leest. De berichtenuitvraag haalt er
 * berichten mee op, deze dienst publiceert het; lopen de twee uiteen, dan belooft het
 * stelseldocument een andere set magazijnen dan waar de keten mee werkt, zonder dat iets faalt.
 * Deze test legt de basisregels van beide bestanden naast elkaar. Bewust geen `@QuarkusTest`: hij
 * leest de bestanden van disk.
 */
class RegisterGelijkAanUitvraagTest {

    private val naamRegel = Regex("""^magazijnen\."(\d{20})"\.naam$""")
    private val urlRegel = Regex("""^magazijnen\."(\d{20})"\.url$""")

    private fun properties(pad: String) = Properties().apply { File(pad).inputStream().use { load(it) } }

    private val eigen = properties("src/main/resources/application.properties")
    private val uitvraag = properties("../berichtenuitvraag/src/main/resources/application.properties")

    private fun namen(bron: Properties): Map<String, String> = bron.stringPropertyNames()
        .mapNotNull { sleutel -> naamRegel.find(sleutel)?.let { it.groupValues[1] to bron.getProperty(sleutel) } }
        .toMap()

    private fun urls(bron: Properties): Map<String, String> = bron.stringPropertyNames()
        .mapNotNull { sleutel -> urlRegel.find(sleutel)?.let { it.groupValues[1] to bron.getProperty(sleutel) } }
        .toMap()

    @Test
    fun `beide diensten noemen dezelfde organisaties met dezelfde naam`() {
        assertTrue(namen(uitvraag).size >= 2, "de uitvraag noemt geen magazijnen; is het register verhuisd?")
        assertEquals(namen(uitvraag), namen(eigen))
    }

    @Test
    fun `beide diensten lezen het adres van een magazijn uit dezelfde omgevingsvariabele`() {
        assertEquals(urls(uitvraag), urls(eigen))
    }

    @Test
    fun `de grant-hash van de outway staat niet in de config van deze dienst`() {
        assertTrue(eigen.stringPropertyNames().none { it.contains("grantHash", ignoreCase = true) })
    }

    /**
     * Een profielregel schaduwt de basissleutel. Staat daar een kale waarde, dan bereikt de
     * omgevingsvariabele de container niet en publiceert de dienst een localhost-adres.
     */
    @ParameterizedTest(name = "{0} leest {1}")
    @CsvSource(
        "%dev.magazijnen.\"00000000000000100000\".url, MAGAZIJN_A_URL",
        "%dev.magazijnen.\"00000001823288444000\".url, MAGAZIJN_B_URL",
        "%dev.stelseldocument.uitgever-oin, STELSELDOCUMENT_UITGEVER_OIN",
        "%dev.stelseldocument.omgeving, STELSELDOCUMENT_OMGEVING",
        "stelseldocument.keystore.pad, STELSELDOCUMENT_KEYSTORE_PAD",
        "stelseldocument.keystore.wachtwoord, STELSELDOCUMENT_KEYSTORE_WACHTWOORD",
        "stelseldocument.keystore.alias, STELSELDOCUMENT_KEYSTORE_ALIAS",
    )
    fun `regels die een omgeving moet kunnen zetten houden hun env-var-expansie`(sleutel: String, envVar: String) {
        val waarde = eigen.getProperty(sleutel)

        assertTrue(
            waarde != null && waarde.startsWith("\${$envVar:"),
            "$sleutel moet beginnen met \${$envVar:<default>}, maar was: $waarde",
        )
    }

    // Zonder default: een uitrol die uitgever of omgeving vergeet, hoort niet te starten.
    @ParameterizedTest(name = "{0} heeft buiten dev en test geen default")
    @CsvSource(
        "stelseldocument.uitgever-oin, STELSELDOCUMENT_UITGEVER_OIN",
        "stelseldocument.omgeving, STELSELDOCUMENT_OMGEVING",
    )
    fun `uitgever en omgeving hebben buiten dev en test geen default`(sleutel: String, envVar: String) {
        assertEquals("\${$envVar}", eigen.getProperty(sleutel))
    }
}
