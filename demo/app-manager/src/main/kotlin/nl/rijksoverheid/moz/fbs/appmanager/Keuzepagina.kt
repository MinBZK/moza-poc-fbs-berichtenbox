package nl.rijksoverheid.moz.fbs.appmanager

import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona

/**
 * De pagina waar de gebruiker een persona kiest, op de plek waar de standaard DigiD zet. Zonder
 * script en zonder externe bronnen; elke tekst gaat ge-escaped de pagina in.
 */
object Keuzepagina {

    fun html(personas: List<DemoPersona>): String {
        val knoppen = personas.joinToString("\n") { persona ->
            """      <li><button type="submit" name="persona" value="${escape(persona.id)}">${escape(persona.label)}</button></li>"""
        }

        return """
            <!doctype html>
            <html lang="nl">
            <head>
              <meta charset="utf-8">
              <title>Demo-App Manager</title>
            </head>
            <body>
              <h1>Demo-App Manager</h1>
              <p>Hier logt een gebruiker normaal in met DigiD of eHerkenning. In de demo kies je een
                ondernemer; de app krijgt daarna een bewijs van identiteit, gebonden aan de sleutel
                in deze browser.</p>
              <form method="post" action="/register_app/kies">
                <ul>
            $knoppen
                </ul>
              </form>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escape(tekst: String): String = tekst
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
