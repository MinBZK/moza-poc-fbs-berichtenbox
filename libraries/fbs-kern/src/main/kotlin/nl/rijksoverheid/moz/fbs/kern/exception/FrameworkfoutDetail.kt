package nl.rijksoverheid.moz.fbs.kern.exception

/**
 * Wat er in `detail` staat bij een foutantwoord dat niet uit eigen code komt maar uit het
 * framework: een pad dat niet bestaat, een methode die daar niet mag, een mediatype dat niet te
 * leveren of niet te lezen is.
 *
 * Het framework geeft zelf een melding mee, en die beschrijft zijn eigen werking ("Unable to find
 * matching target resource method", "did not match the value in @Produces"). Volgens de API Design
 * Rules hoort zo'n technisch detail niet in een foutantwoord, en verder is het antwoord Nederlands.
 * Een afnemer heeft wel iets aan uitleg. Daarom krijgt zo'n antwoord een vaste zin in plaats van
 * een leeg veld.
 *
 * Deze regel staat hier en niet per dienst. Het echte magazijn en de simulator moeten op dit
 * foutpad hetzelfde antwoorden, en de simulator kan `fbs-common` niet gebruiken.
 */
object FrameworkfoutDetail {

    /** Wat het framework invult als er geen echte melding is, zoals "HTTP 400 Bad Request". */
    private val STANDAARDMELDING = Regex("""HTTP \d{3}( .*)?""")

    /**
     * De vaste tekst voor een status die het framework zelf produceert, of `null` als die status
     * uit eigen code komt. Een throw-site die zelf een 404 geeft, draagt zijn eigen kenmerk en
     * melding mee en komt hier niet langs.
     */
    fun voorStatus(status: Int): String? = when (status) {
        404 -> "Het opgevraagde pad bestaat niet."
        405 -> "Deze HTTP-methode is op dit pad niet toegestaan."
        406 -> "Het antwoord is niet te leveren in een mediatype uit de Accept-header."
        415 -> "Het mediatype uit de Content-Type-header wordt hier niet ondersteund."
        else -> null
    }

    /**
     * Of [melding] de standaardmelding van het framework is. Die voegt niets toe aan `title` en
     * `status`; beter geen `detail` dan die.
     */
    fun isStandaardmelding(melding: String): Boolean = STANDAARDMELDING.matches(melding.trim())
}
