package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "berichten")
class BerichtEntity {

    @Id
    @Column(nullable = false)
    lateinit var berichtId: UUID

    @Column(nullable = false)
    lateinit var afzender: String

    @Column(nullable = false)
    lateinit var ontvanger: String

    @Column(nullable = false)
    lateinit var onderwerp: String

    @Column(nullable = false, columnDefinition = "CLOB")
    lateinit var inhoud: String

    @Column(nullable = false)
    lateinit var tijdstip: Instant

    fun toDomain(): Bericht = Bericht(
        berichtId = berichtId,
        afzender = afzender,
        ontvanger = ontvanger,
        onderwerp = onderwerp,
        inhoud = inhoud,
        tijdstip = tijdstip,
    )

    companion object {
        fun fromDomain(bericht: Bericht): BerichtEntity = BerichtEntity().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender
            ontvanger = bericht.ontvanger
            onderwerp = bericht.onderwerp
            inhoud = bericht.inhoud
            tijdstip = bericht.tijdstip
        }
    }
}
