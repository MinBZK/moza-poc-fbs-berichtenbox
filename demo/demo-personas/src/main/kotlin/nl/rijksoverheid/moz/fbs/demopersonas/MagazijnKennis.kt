package nl.rijksoverheid.moz.fbs.demopersonas

/**
 * Wie berichten aanlevert weet welke magazijnen ingericht zijn; deze dienst niet — hij kent
 * identiteiten. Een afnemer die dat wél weet levert hier een implementatie, en dan valt zijn
 * oordeel binnen dezelfde ronde als de rest van de persona-validatie.
 *
 * Dat is de reden dat deze naad bestaat in plaats van een losse controle bij de afnemer: één boot
 * hoort álle inrichtingsfouten te melden. Met twee losse controles fixt de bediener de eerste,
 * start opnieuw, en krijgt dan pas de tweede te zien.
 *
 * Een bezwaar teruggeven en niet gooien: de melding hoort bij wie de configuratie kent, de
 * volgorde en het verzamelen bij wie de persona's leest. Gooien zou beide bij de implementatie
 * leggen, en dan stopt de ronde bij het eerste bezwaar — precies wat deze naad moet voorkomen.
 */
fun interface MagazijnKennis {

    /** De reden waarom dit OIN geen ingericht magazijn is, of `null` wanneer het er wel een is. */
    fun bezwaarTegen(oin: String): String?
}
