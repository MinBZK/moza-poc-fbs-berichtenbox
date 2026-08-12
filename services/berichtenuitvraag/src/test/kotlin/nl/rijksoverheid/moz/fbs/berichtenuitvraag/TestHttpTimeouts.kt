package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.restassured.RestAssured
import io.restassured.config.HttpClientConfig
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

/**
 * Geeft élk testrequest een socket- en connect-timeout.
 *
 * Een afgewezen request wordt beantwoord zónder de body te lezen; zonder deze grens blijft de
 * client schrijven tot iets anders ingrijpt. Dat liet een CI-job zes uur doorlopen voordat de
 * runner hem afkapte, bij een test die bewust een te grote bijlage aanbiedt. Zo'n vangnet hoort
 * niet af te hangen van een parameter die per testklasse goed gezet moet zijn, dus staat hij hier:
 * de launcher roept dit één keer per test-JVM aan, vóór de eerste test.
 */
class TestHttpTimeouts : LauncherSessionListener {

    override fun launcherSessionOpened(session: LauncherSession) {
        RestAssured.config = RestAssured.config().httpClient(
            HttpClientConfig.httpClientConfig()
                .setParam("http.socket.timeout", SOCKET_TIMEOUT_MS)
                .setParam("http.connection.timeout", CONNECT_TIMEOUT_MS),
        )
    }

    private companion object {
        // Ruim boven de traagste legitieme respons (Testcontainers-opstart zit in de fixture,
        // niet in het request), maar ver onder de job-timeout die nu het vangnet vormt.
        const val SOCKET_TIMEOUT_MS = 60_000
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
