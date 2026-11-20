package nl.rijksoverheid.moz.fbs.appmanager

import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona

/**
 * De pagina waar de gebruiker een persona kiest, op de plek waar de standaard DigiD zet, en de
 * foutpagina voor dezelfde navigatie. Zonder script en zonder externe bronnen; elke tekst gaat
 * ge-escaped de pagina in.
 */
object Keuzepagina {

    /** Voor een foutpagina: niets laden, niets versturen, niet in een frame. */
    const val CSP_ZONDER_FORMULIER = "default-src 'none'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'"

    /**
     * Voor de keuzepagina: `form-action` noemt ook de origin van de terug-URL, want een browser past
     * `form-action` ook toe op de doorverwijzing na het formulier.
     */
    fun csp(terugOrigin: String): String = "default-src 'none'; form-action 'self' $terugOrigin; frame-ancestors 'none'; base-uri 'none'"

    fun html(personas: List<DemoPersona>): String {
        val knoppen = personas.joinToString("\n") { persona ->
            """      <li><button type="submit" name="persona" value="${escape(persona.id)}">${escape(persona.label)}</button></li>"""
        }

        return pagina(
            """
              <h1>Demo-App Manager</h1>
              <p>Hier logt een gebruiker normaal in met DigiD of eHerkenning. In de demo kies je een
                ondernemer; de app krijgt daarna een bewijs van identiteit, gebonden aan de sleutel
                in deze browser.</p>
              <form method="post" action="/register_app/kies">
                <ul>
            $knoppen
                </ul>
              </form>
            """.trimIndent(),
        )
    }

    fun foutpagina(titel: String, tekst: String): String = pagina("<h1>${escape(titel)}</h1>\n<p>${escape(tekst)}</p>")

    private fun pagina(inhoud: String): String = """
        |<!doctype html>
        |<html lang="nl">
        |<head>
        |  <meta charset="utf-8">
        |  <title>Demo-App Manager</title>
        |</head>
        |<body>
        |$inhoud
        |</body>
        |</html>
    """.trimMargin()

    private fun escape(tekst: String): String = tekst
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
