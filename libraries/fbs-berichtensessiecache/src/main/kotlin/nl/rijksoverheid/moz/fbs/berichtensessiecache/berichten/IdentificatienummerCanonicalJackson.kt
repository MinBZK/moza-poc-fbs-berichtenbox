package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer

/**
 * Serialiseert een [Identificatienummer] in de sessiecache-lijst-JSON als canonieke
 * `"<TYPE>:<WAARDE>"`-string (symmetrisch met [Identificatienummer.fromHeader]). Bewust
 * veld-niveau toegepast op `Bericht.ontvanger`, niet als globale module: de magazijn-DTO
 * deserialiseert een eigen `{type, waarde}`-vorm en mag hier niet door geraakt worden.
 */
class IdentificatienummerCanonicalSerializer : JsonSerializer<Identificatienummer>() {
    override fun serialize(value: Identificatienummer, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString(value.toCanonicalString())
    }
}

class IdentificatienummerCanonicalDeserializer : JsonDeserializer<Identificatienummer>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Identificatienummer =
        Identificatienummer.fromHeader(p.valueAsString)
}
