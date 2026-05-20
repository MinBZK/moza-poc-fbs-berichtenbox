package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.time.Period

/**
 * Configuratie voor de hard-delete-job. Bewaartermijnen zijn ISO-8601 Period-strings
 * (`P7Y`, `P3M`, `P90D`); Duration (`PT…`) wordt niet ondersteund — bewaartermijnen
 * zijn altijd in dagen/maanden/jaren, niet uren.
 *
 * Default 7 jaar volgt de administratieve standaardbewaartermijn. Operators MOETEN
 * dit afstemmen op de geldende selectielijst (Archiefwet).
 */
@ConfigMapping(prefix = "retentie.hard-delete")
interface RetentionConfig {

    /** Minimale leeftijd van een bericht (sinds tijdstip_ontvangst) voor hard-delete. */
    @WithName("minimale-leeftijd")
    fun minimaleLeeftijd(): Period

    /** Minimale tijd sinds soft-delete (verwijderd_op) voor hard-delete. */
    @WithName("minimale-soft-delete-leeftijd")
    fun minimaleSoftDeleteLeeftijd(): Period

    /** Cron-expressie voor de scheduler (Quarkus dialect: seconden-precies). */
    fun cron(): String

    /** Max berichten per cron-run. Volgende tick pikt restant op. */
    @WithName("batch-grootte")
    fun batchGrootte(): Int
}
