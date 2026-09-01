package nl.rijksoverheid.moz.fbs.demopersonas

/**
 * Wie berichten aanlevert weet welke magazijnen ingericht zijn; deze dienst niet — hij kent
 * identiteiten. Een afnemer die dat wél weet levert hier een implementatie, en dan valt zijn oordeel
 * binnen dezelfde ronde als de rest van de persona-validatie.
 *
 * Dat is de reden dat deze naad bestaat in plaats van een losse controle bij de afnemer: één boot
 * hoort álle inrichtingsfouten te melden. Met twee losse controles fixt de bediener de eerste, start
 * opnieuw, en krijgt dan pas de tweede te zien.
 *
 * De implementatie gooit zelf, met haar eigen melding: zij kent de configuratie waar het misgaat,
 * deze module niet.
 */
fun interface MagazijnKennis {

    /** Gooit een [IllegalArgumentException] wanneer dit OIN geen ingericht magazijn is. */
    fun vereisBekend(oin: String)
}
