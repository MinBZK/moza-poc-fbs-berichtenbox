package nl.rijksoverheid.moz.fbs.appmanager

import jakarta.enterprise.context.ApplicationScoped
import org.jose4j.json.JsonUtil
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration

/**
 * Tekent het Verifiable Credential (FSC voor Burgers 4.3.1, stap 28 van 4.4.1): de sleutel van de
 * app in `cnf`, de ontvanger die de gebruiker koos, en een korte geldigheid. FBS zet `ontvanger` in
 * plaats van `bsn`, `given_name` en `family_name`; zie het ontwerp.
 */
@ApplicationScoped
class CredentialUitgifte(
    private val config: AppManagerConfig,
    private val sleutel: Ondertekensleutel,
    private val klok: Clock,
) {

    init {
        require(config.credentialGeldig() <= MAXIMALE_LOOPTIJD) {
            "app-manager.credential-geldig (${config.credentialGeldig()}) mag niet langer zijn dan $MAXIMALE_LOOPTIJD: een magazijn weigert dat credential"
        }
    }

    fun teken(appSleutel: ECPublicKey, ontvanger: String): String {
        val nu = klok.instant().epochSecond

        val claims = mapOf(
            "cnf" to mapOf("jwk" to mapOf("kty" to "EC", "crv" to "P-256", "x" to Ondertekensleutel.x(appSleutel), "y" to Ondertekensleutel.y(appSleutel))),
            "scope" to AppManagerConfig.SCOPE,
            "iss" to config.oin(),
            "iat" to nu,
            "nbf" to nu,
            "exp" to nu + config.credentialGeldig().seconds,
            "ontvanger" to ontvanger,
        )

        return JsonWebSignature().run {
            payload = JsonUtil.toJson(claims)
            algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
            setHeader("typ", "jwt")
            keyIdHeaderValue = config.kid()
            key = sleutel.privaat
            compactSerialization
        }
    }

    private companion object {
        val MAXIMALE_LOOPTIJD: Duration = Duration.ofHours(24)
    }
}
