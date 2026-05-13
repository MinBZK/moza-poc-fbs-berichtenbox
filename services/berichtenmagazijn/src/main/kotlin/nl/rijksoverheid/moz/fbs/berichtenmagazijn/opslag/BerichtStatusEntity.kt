package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Leesstatus van een bericht voor een specifieke ontvanger.
 *
 * Composite PK `(berichtId, ontvangerType, ontvangerWaarde)`: één rij per
 * (bericht, ontvanger). In de PoC heeft elk bericht hooguit één ontvanger, maar
 * de structuur is toekomstvast voor scenario's waarin meerdere partijen toegang
 * krijgen tot hetzelfde bericht (bv. ketenmachtiging).
 *
 * Status wordt impliciet "leeg" gemodelleerd door afwezigheid van een rij: dan
 * is het bericht ongelezen en zit het in geen specifieke map. Pas bij de eerste
 * PATCH wordt een rij aangemaakt.
 */
@Entity
@Table(name = "bericht_status")
internal class BerichtStatusEntity {

    @EmbeddedId
    var id: BerichtStatusKey = BerichtStatusKey()

    @Column(nullable = false)
    var gelezen: Boolean = false

    @Column(length = 64)
    var map: String? = null

    @Column(name = "gewijzigd_op", nullable = false)
    var gewijzigdOp: Instant = Instant.EPOCH
}

/**
 * Composite primary key voor [BerichtStatusEntity]. `Embeddable` met
 * `equals`/`hashCode` is een Hibernate-vereiste voor `@EmbeddedId`.
 */
@Embeddable
class BerichtStatusKey() : Serializable {

    @Column(name = "bericht_id", nullable = false)
    var berichtId: UUID = PLACEHOLDER_UUID

    @Column(name = "ontvanger_type", nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    var ontvangerType: IdentificatienummerType = IdentificatienummerType.OIN

    @Column(name = "ontvanger_waarde", nullable = false, length = 20)
    var ontvangerWaarde: String = ""

    constructor(berichtId: UUID, ontvanger: Identificatienummer) : this() {
        this.berichtId = berichtId
        this.ontvangerType = ontvanger.type
        this.ontvangerWaarde = ontvanger.waarde
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BerichtStatusKey) return false
        return berichtId == other.berichtId &&
            ontvangerType == other.ontvangerType &&
            ontvangerWaarde == other.ontvangerWaarde
    }

    override fun hashCode(): Int {
        var result = berichtId.hashCode()
        result = 31 * result + ontvangerType.hashCode()
        result = 31 * result + ontvangerWaarde.hashCode()
        return result
    }

    companion object {
        private val PLACEHOLDER_UUID: UUID = UUID(0L, 0L)
    }
}
