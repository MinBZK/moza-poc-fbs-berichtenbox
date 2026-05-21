package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import java.time.Instant

/**
 * Port voor het claim/markeer-mechanisme van outbox-rijen.
 *
 * Domein-code ([PublicatieStream], [PublicatieClaimVerwerker]) ziet alleen deze
 * interface en raakt nooit aan DBMS-specifieke constructies zoals `SELECT ...
 * FOR UPDATE SKIP LOCKED`. Adapters (zie de Postgres-implementatie in de
 * subpackage `postgres/`) leveren de implementatie.
 *
 * Contract: alle drie methods moeten binnen dezelfde transactie aangeroepen
 * worden — de adapter heeft `@Transactional(MANDATORY)` en faalt anders. Bij
 * een rollback komen alle gemaakte status-updates terug en wordt de delivery
 * bij volgende poll opnieuw geclaimd; het row-level lock van `SKIP LOCKED`
 * komt vrij.
 */
interface PublicatieClaimer {

    /**
     * Claim atomair tot [maxBatch] outbox-rijen waarvan `volgende_poging`
     * verstreken is en `status = TE_PUBLICEREN`. Geretourneerde rijen zijn
     * binnen de huidige transactie ge-locked; andere instanties claimen
     * gegarandeerd andere rijen (geen duplicate sends bij scale-out).
     *
     * De volgorde waarin rijen worden geretourneerd is een implementatie-
     * detail van de adapter en geen onderdeel van het contract. De Postgres-
     * adapter sorteert op `volgende_poging ASC` om starvation te voorkomen;
     * een toekomstige in-memory adapter mag een andere strategie kiezen
     * zolang fairness gewaarborgd blijft.
     */
    fun claimNuVerwerkbaar(maxBatch: Int): List<PublicatieClaim>

    /**
     * Markeer een geclaimde rij als afgeleverd. Status wordt `GEPUBLICEERD`
     * (terminal); `gepubliceerd_op` wordt gezet op [tijdstip].
     *
     * Gooit [IllegalStateException] als de claim-rij niet (meer) gevonden wordt
     * of niet in `TE_PUBLICEREN`-staat zit; in beide gevallen is het claim-
     * contract gebroken en moet de transactie rollbacken.
     */
    fun markeerGeslaagd(claimId: Long, tijdstip: Instant)

    /**
     * Markeer een geclaimde rij na een mislukte aflevering.
     *
     * - [volgendePoging] != null: status blijft `TE_PUBLICEREN`,
     *   `pogingen` wordt verhoogd, `laatste_fout` opgeslagen.
     * - [volgendePoging] == null: status wordt terminal `MISLUKT`
     *   (max-pogingen bereikt, niet-herstelbare fout zoals 4xx of
     *   configuratie-/serialisatie-bug).
     */
    fun markeerMislukt(claimId: Long, fout: String, volgendePoging: Instant?)
}
