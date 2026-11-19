package nl.rijksoverheid.moz.fbs.kern.exception

/**
 * Het pad naar een veld dat niet paste, zoals het in een applicatielog mag.
 *
 * Een veldnaam komt meestal uit het eigen model, maar niet altijd: bij een map of een vrij
 * JSON-object is de sleutel invoer van de aanroeper, en die kan een BSN zijn. Een segment dat
 * niet op een veldnaam lijkt, wordt daarom [ONLEESBAAR]. Twee cijfers achter elkaar tellen als
 * "lijkt niet op een veldnaam": geen veld in de specs heeft ze (`x5c` wel één), en zo valt ook
 * een herkenbaar stuk van een identificatienummer weg.
 */
object Veldpad {

    /** Wat er staat voor een segment dat niet op een veldnaam lijkt. */
    const val ONLEESBAAR = "?"

    /** Meer segmenten heeft geen model; een dieper pad is een aangeboden structuur. */
    const val MAX_SEGMENTEN = 16

    private const val AFGEKAPT = "…"

    private val VELDNAAM = Regex("""[A-Za-z_](?:[A-Za-z_]|[0-9](?![0-9])){0,63}""")

    /**
     * [segmenten] van buiten naar binnen: een [String] is een veldnaam, een [Int] een positie in
     * een lijst. Al het andere, ook `null`, wordt [ONLEESBAAR].
     */
    fun van(segmenten: List<Any?>): String {
        val pad = StringBuilder()

        segmenten.take(MAX_SEGMENTEN).forEach { segment ->
            if (segment is Int && segment >= 0) {
                pad.append('[').append(segment).append(']')
            } else {
                if (pad.isNotEmpty()) pad.append('.')

                pad.append(if (segment is String && VELDNAAM.matches(segment)) segment else ONLEESBAAR)
            }
        }

        if (segmenten.size > MAX_SEGMENTEN) pad.append('.').append(AFGEKAPT)

        return pad.toString()
    }
}
