package nl.rijksoverheid.moz.fbs.common

/**
 * Fout-representatie voor het Logboek Dataverwerkingen: draagt het type van de
 * oorspronkelijke fout, nooit zijn message.
 *
 * De LDV-wrapper zet `exception.type` (`javaClass.name`) en `exception.message`
 * (`message`) als attributen op de per-betrokkene child-spans — dezelfde rijen die
 * `dpl.core.data_subject_id` dragen en die bij een inzageverzoek naar buiten gaan.
 * Driver- en framework-messages bevatten daar geregeld gegevens van de betrokkene in:
 * een PostgreSQL NOT NULL- of CHECK-violation zet met `Failing row contains (…)` de
 * volledige rij in het `Detail:`-veld, inclusief BSN en berichtinhoud. Zulke gegevens
 * horen niet in het logboek (AVG art. 5 lid 1c, dataminimalisatie).
 *
 * Saneren met [FoutBeschrijving.saneer] volstaat hier niet: dat redact cijferreeksen en
 * control-chars, maar laat vrije tekst — inclusief berichtinhoud — ongemoeid. Daarom
 * gaat alleen de klassenaam mee. Dat is geen informatieverlies voor diagnose: het
 * volledige foutbeeld staat in de applicatielog, die via `trace_id`/`span_id` aan de
 * logregel te koppelen is.
 *
 * **Gevolg voor alerting:** `exception.type` op de child-span is hierdoor altijd
 * `nl.rijksoverheid.moz.fbs.common.LdvFoutSamenvatting`; het echte type staat in
 * `exception.message`. Een alert- of dashboardregel die op `exception.type`
 * discrimineert, moet naar `exception.message` verhuizen. De alternatieve vorm — het
 * echte type behouden en de message saneren — bestaat niet: een `Throwable` draagt zijn
 * type in zijn klasse, dus die is alleen te behouden door het originele exemplaar door
 * te geven, mét message.
 */
class LdvFoutSamenvatting private constructor(
    /** Volledig gekwalificeerde klassenaam van de oorspronkelijke fout. */
    val oorspronkelijkType: String,
) : RuntimeException(oorspronkelijkType) {

    companion object {
        /** Vat [fout] samen tot zijn type; de message blijft achter. */
        fun van(fout: Throwable): LdvFoutSamenvatting = LdvFoutSamenvatting(fout.javaClass.name)
    }
}
