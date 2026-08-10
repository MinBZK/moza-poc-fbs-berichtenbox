package nl.rijksoverheid.moz.fbs.common

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * Borgt dat de tabelnaam van het Logboek Dataverwerkingen bruikbaar is vóórdat de service
 * verkeer aanneemt.
 *
 * De wrapper bouwt zijn repository pas bij het eerste gebruik van het logboek, en pas dán
 * blijkt een onbruikbare naam. Zonder deze guard start de service groen op, komt hij door
 * de health checks, en faalt vervolgens élk request met een 500 — fail-closed doet dan
 * precies wat het moet, maar een deploy ziet er geslaagd uit terwijl er niets werkt.
 *
 * Waar de naam vandaan komt: waar meerdere organisaties één database delen, draagt hij een
 * schema-prefix uit `DB_SCHEMA`, zodat elk logboek in het schema van zijn eigen organisatie
 * staat. Een lege of vergeten `DB_SCHEMA` levert dan `.logboek_dataverwerkingen` op — een
 * waarde die als "aanwezig" telt maar nergens naar verwijst.
 *
 * Twee eisen bovenop niet-leeg:
 * - **Vorm.** De wrapper accepteert alleen `^[a-zA-Z_][a-zA-Z0-9_.]*$` en gooit anders bij
 *   constructie. Een schemanaam met een koppelteken — bijvoorbeeld afgeleid van een
 *   deploymentnaam als `pr-168` — valt daarbuiten.
 * - **Kleine letters.** De naam gaat ongequote de SQL in, dus PostgreSQL vouwt hem naar
 *   lowercase. Wie het schema mét hoofdletters aanmaakt (gequote) krijgt een schema dat de
 *   wrapper daarna niet vindt. Kleine letters eisen haalt die dubbelzinnigheid weg.
 *
 * In `dev` en `test` blijft de naam vrij: daar draait geen echte betrokkene-data en zijn
 * afwijkende namen juist nodig om het gedrag te kunnen testen.
 */
@ApplicationScoped
class LdvTabelnaamValidator(
    @param:ConfigProperty(name = LdvEndpointValidator.DBMS_KEY, defaultValue = "clickhouse")
    private val dbms: String,
    // Optional om dezelfde reden als in LdvEndpointValidator: de ongebruikte backend hoeft
    // geen waarde te hebben en mag de start niet blokkeren.
    @param:ConfigProperty(name = TABEL_KEY) private val tabel: Optional<String>,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
) {

    fun onStartup(@Observes event: StartupEvent) {
        validate(profile, dbms, tabel.orElse(""))
    }

    companion object {
        const val TABEL_KEY = "logboekdataverwerking.postgresql.table"

        private val PROFIELEN_ZONDER_EIS = setOf("dev", "test")

        /**
         * Strikter dan de wrapper: geen hoofdletters, en hooguit één punt zodat `schema.tabel`
         * kan maar `a.b.c` niet. Een naam die hier doorkomt, komt ook door de wrapper heen.
         */
        private val TABELNAAM = Regex("^[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?$")

        fun validate(profile: String, dbms: String, tabel: String) {
            if (profile in PROFIELEN_ZONDER_EIS) return

            // Alleen de PostgreSQL-backend gebruikt deze key; op ClickHouse zegt hij niets.
            if (dbms.lowercase() !in setOf("postgresql", "postgres")) return

            require(tabel.isNotBlank()) {
                "$TABEL_KEY is leeg in profiel '$profile'. Staat er een schema-prefix uit een " +
                    "omgevingsvariabele in, controleer dan of die gezet is — een lege waarde levert " +
                    "een naam op die pas bij het eerste request faalt."
            }

            require(TABELNAAM.matches(tabel)) {
                "$TABEL_KEY heeft de onbruikbare waarde '$tabel' in profiel '$profile'. Toegestaan is " +
                    "`tabel` of `schema.tabel`, met kleine letters, cijfers en liggende streepjes, " +
                    "beginnend met een letter of liggend streepje. De naam gaat ongequote de SQL in, " +
                    "dus hoofdletters en koppeltekens leveren een tabel op die niet gevonden wordt."
            }
        }
    }
}
