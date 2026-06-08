package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Inkomende CloudEvents-envelope (structured content mode) zoals de Publicatie
 * Stream van een magazijn die aflevert. Spiegelt het uitgaande contract van het
 * magazijn, maar is uitvraag-eigen (geen module-overschrijdende type-deling).
 *
 * Velden zijn nullable; de verplichte velden worden in [AanmeldService] gevalideerd
 * en naar een getypeerd [GepubliceerdBerichtEvent] geparset, zodat een ontbrekend
 * verplicht attribuut een nette 400 oplevert i.p.v. een deserialisatie-NPE/500. De
 * Jackson-annotaties maken deserialisatie onafhankelijk van de kotlin-module en
 * `-parameters` compiler-flag (beide niet runtime aanwezig). Onbekende context-
 * attributen (NL GOV-extensies zoals `sequence`) worden genegeerd.
 *
 * `datacontenttype`, `dataschema` en `tijdstipOntvangst` worden bewust wel gemodelleerd
 * maar niet gebruikt: ze horen bij het CloudEvents-/magazijn-contract en blijven zo
 * zichtbaar in de envelope (geen "dead field" om later te verwijderen).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AangemeldCloudEvent
@JsonCreator constructor(
    @param:JsonProperty("id") val id: String?,
    @param:JsonProperty("source") val source: String?,
    @param:JsonProperty("specversion") val specversion: String?,
    @param:JsonProperty("type") val type: String?,
    @param:JsonProperty("subject") val subject: String?,
    @param:JsonProperty("time") val time: Instant?,
    @param:JsonProperty("datacontenttype") val datacontenttype: String?,
    @param:JsonProperty("dataschema") val dataschema: String?,
    @param:JsonProperty("data") val data: AangemeldBerichtData?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AangemeldBerichtData
@JsonCreator constructor(
    @param:JsonProperty("berichtId") val berichtId: UUID?,
    @param:JsonProperty("afzender") val afzender: String?,
    @param:JsonProperty("ontvanger") val ontvanger: AangemeldOntvanger?,
    @param:JsonProperty("onderwerp") val onderwerp: String?,
    @param:JsonProperty("inhoud") val inhoud: String?,
    @param:JsonProperty("tijdstipOntvangst") val tijdstipOntvangst: Instant?,
    @param:JsonProperty("publicatietijdstip") val publicatietijdstip: Instant?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AangemeldOntvanger
@JsonCreator constructor(
    @param:JsonProperty("type") val type: String?,
    @param:JsonProperty("waarde") val waarde: String?,
)
