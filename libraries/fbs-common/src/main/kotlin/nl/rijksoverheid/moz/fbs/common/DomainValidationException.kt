package nl.rijksoverheid.moz.fbs.common

/**
 * Marker-exception voor domein-validatiefouten afkomstig van expliciete checks in
 * domeinobjecten (bv. init-blocks van value objects). Alleen deze exception mag
 * veilig een door de developer geschreven boodschap aan de client exposen
 * (zie [DomainValidationExceptionMapper] → 400). Generieke [IllegalArgumentException]s
 * uit dependencies/JDK worden behandeld als programmeerfout en krijgen een
 * 500-response met correlation-id (zie [IllegalArgumentExceptionMapper]).
 */
class DomainValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Helper die semantisch lijkt op `require(...)` maar een [DomainValidationException]
 * gooit zodat de boodschap veilig naar de client mag.
 */
inline fun requireValid(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) throw DomainValidationException(lazyMessage())
}
