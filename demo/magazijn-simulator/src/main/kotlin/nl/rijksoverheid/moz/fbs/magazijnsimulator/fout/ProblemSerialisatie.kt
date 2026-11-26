package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Problem

/**
 * Houdt `"empty"` uit elk foutantwoord.
 *
 * Het schema `Problem` in de spec staat extension members toe (`additionalProperties: true`). Daarom
 * maakt de generator van het model een subklasse van `HashMap`, met `@JsonFormat(shape = OBJECT)`
 * zodat Jackson de velden als bean schrijft. Dan ziet Jackson ook `isEmpty()` van de map als getter,
 * en zonder deze mixin staat `"empty": true` in elk foutantwoord. Een echt magazijn stuurt dat veld
 * niet, en het schema laat het toe, dus een contracttest merkt het niet op.
 *
 * Een mixin en geen eigen data class: [problemResponse] blijft dan het gegenereerde model gebruiken,
 * en een spec-wijziging aan `Problem` breekt nog steeds de build. De extension members zelf
 * (`@JsonAnyGetter`) blijven werken; alleen de map-eigenschap verdwijnt.
 */
@Singleton
class ProblemSerialisatie : ObjectMapperCustomizer {

    override fun customize(objectMapper: ObjectMapper) {
        objectMapper.addMixIn(Problem::class.java, ZonderMapEigenschappen::class.java)
    }

    @JsonIgnoreProperties("empty")
    private abstract class ZonderMapEigenschappen
}
