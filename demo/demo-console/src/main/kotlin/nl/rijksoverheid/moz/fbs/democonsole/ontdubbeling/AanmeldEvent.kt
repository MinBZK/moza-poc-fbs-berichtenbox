package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

/** `data`-payload van het gepubliceerd-event; velden gespiegeld op wat AanmeldService.parse() eist. */
data class AanmeldData(
    val berichtId: String,
    val afzender: String,
    val ontvanger: Ontvanger,
    val onderwerp: String,
    val inhoud: String,
    val tijdstipOntvangst: String,
    val publicatietijdstip: String,
)

/** Getypeerde ontvanger `{type, waarde}` (los van de generator-DTO om de ontdubbeling zelfstandig te houden). */
data class Ontvanger(val type: String, val waarde: String)

/** CloudEvents-envelope voor `POST /aanmeldingen` (media type application/cloudevents+json). */
data class AanmeldEvent(
    val id: String,
    val source: String,
    val specversion: String,
    val type: String,
    val subject: String,
    val time: String,
    val datacontenttype: String,
    val data: AanmeldData,
)
