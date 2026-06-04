package nl.rijksoverheid.moz.fbs.berichtenmagazijn.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BijlageAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto

/**
 * Fuzz de Jackson-deserialisatie van de aanlever-payload-DTO's — de eerste laag die
 * onvertrouwde request-bodies raakt.
 *
 * Scope: dit dekt de **kale Jackson-laag** (de gegenereerde DTO's met de mapper-config
 * die productie spiegelt: JavaTime, Kotlin, `non_null`-inclusion). Het dekt *niet* de
 * RESTEasy-Reactive-binding of de `fbs-common`-exception-mappers; die worden via de
 * contract-/integratietests gevalideerd.
 *
 * Invarianten:
 *  - Een initiële parse mag alleen falen met een [JacksonException] (→ nette 400 in
 *    productie). Elk ander exception-type uit de parse vliegt door en faalt de fuzz.
 *  - Een succesvol geparste DTO moet stabiel terug te schrijven zijn: serialiseren,
 *    opnieuw parsen en opnieuw serialiseren levert exact dezelfde JSON. Een afwijking
 *    wijst op een niet-idempotente (de)serialisatie en faalt de fuzz. Re-parse en
 *    re-serialisatie staan bewust buiten de catch zodat een onverwachte fout daar
 *    niet wordt geslikt.
 */
object AanleverJsonFuzzer {

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    private val targetTypes = arrayOf(
        BerichtAanleverenRequest::class.java,
        BijlageAanleverenRequest::class.java,
        IdentificatienummerDto::class.java,
    )

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val targetType = data.pickValue(targetTypes)
        val json = data.consumeRemainingAsString()

        val parsed = try {
            objectMapper.readValue(json, targetType)
        } catch (_: JacksonException) {
            return
        }

        val eersteSerialisatie = objectMapper.writeValueAsString(parsed)
        val heringelezen = objectMapper.readValue(eersteSerialisatie, targetType)
        val tweedeSerialisatie = objectMapper.writeValueAsString(heringelezen)

        check(eersteSerialisatie == tweedeSerialisatie) {
            "round-trip-(de)serialisatie niet idempotent voor ${targetType.simpleName}"
        }
    }
}
