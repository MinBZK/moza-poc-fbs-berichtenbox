**Status:** Concept

# Profiel-service via FSC-outway — implementatieplan (#730)

> **Voor agentic workers:** implementeer taak-voor-taak. Stappen gebruiken checkbox-syntax
> (`- [ ]`) voor voortgang. Ontwerp en rationale staan in
> `docs/plans/2026-07-21-fsc-outway-grant-hash-profielservice.md` — lees die eerst; dit document
> bevat de concrete code.

**Doel:** de Profiel-service optioneel via de FSC-outway benaderen, door naast de URL ook een
grant-hash configureerbaar te maken — per service, met leeg = ongewijzigd gedrag.

**Architectuur:** `ProfielServiceClient` is een declaratieve `@RegisterRestClient`-interface, dus er
is geen builder om een filter op te registreren zoals bij de magazijn-clients. In plaats daarvan
krijgt de interface een `@RegisterProvider` met een filter die zijn grant-hash zelf uit config
leest en niets doet als die ontbreekt. De header-set-logica wordt gedeeld met de bestaande
magazijn-filter, zodat het FSC-header-contract op één plek staat.

**Tech stack:** Kotlin/JVM 21, Quarkus 3.x, MicroProfile REST Client + Fault Tolerance,
JUnit 5 + MockK + WireMock, detekt, JaCoCo.

## Globale randvoorwaarden

Gelden impliciet voor élke taak hieronder.

- **Altijd `clean` vóór `test`** — een achtergebleven `target/` van een andere branch-state geeft
  misleidende fouten.
- Draaien tests via `mcp__maven-host`? Zet `TESTCONTAINERS_RYUK_DISABLED=true` (bind-mount-issue).
- Commentaar legt het **waarom** vast, niet het wat. Geen review-labels, geen verwijzingen naar
  CLAUDE.md, geen "PoC"/"voorlopig".
- Kotlin-stijl: lege regel vóór én ná multi-line blokken en zelfstandige control-statements.
- Domeinbegrippen Nederlands (`zet`, `grantHash`), technische idiomen Engels (`filter`, `provider`).
- detekt-gate is `maxIssues: 0` zonder baseline — élke bevinding faalt de build.
- JaCoCo ≥ 90% line coverage per geraakte module.
- Commits: `<type>(<scope>): <omschrijving> (#730)`. Commit per afgeronde taak.

## Bestandsoverzicht

| Bestand | Verantwoordelijkheid |
|---------|----------------------|
| `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeaders.kt` | **Nieuw.** Enige plek die het FSC-header-contract kent (headernamen + v7-transaction-id). |
| `.../fsc/FscOutwayHeadersFilter.kt` | **Wijzig.** Wordt dunne wrapper; publiek gedrag ongewijzigd. |
| `.../fsc/ProfielFscOutwayHeadersFilter.kt` | **Nieuw.** Config-lezende filter voor de Profiel-client. |
| `.../profiel/ProfielServiceClient.kt` | **Wijzig.** `@RegisterProvider` erbij. |
| `services/*/src/main/resources/application.properties` | **Wijzig.** `profiel-service.grant-hash`. |
| `CLAUDE.md` | **Wijzig.** Env-var-tabel. |

---

## Taak 1: header-logica extraheren naar `FscOutwayHeaders`

Pure extractie. De bestaande `FscOutwayHeadersFilterTest` moet **ongewijzigd** groen blijven — dat
is de regressie-check.

**Bestanden:**
- Maak: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeaders.kt`
- Wijzig: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeadersFilter.kt`
- Test: `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeadersTest.kt`

**Interfaces:**
- Produceert: `FscOutwayHeaders.zet(requestContext: ClientRequestContext, grantHash: String)`,
  `FscOutwayHeaders.GRANT_HASH_HEADER = "Fsc-Grant-Hash"`,
  `FscOutwayHeaders.TRANSACTION_ID_HEADER = "Fsc-Transaction-Id"`. Taak 2 gebruikt alle drie.

- [ ] **Stap 1.1: schrijf de falende test**

Maak `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeadersTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.fsc

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID

class FscOutwayHeadersTest {

    private fun zetHeaders(grantHash: String): MultivaluedHashMap<String, Any> {
        val ctx = mockk<ClientRequestContext>()
        val headers = MultivaluedHashMap<String, Any>()
        every { ctx.headers } returns headers
        every { ctx.uri } returns URI.create("https://outway.voorbeeld.test/api/profielservice/v1/BSN/999993653")

        FscOutwayHeaders.zet(ctx, grantHash)

        return headers
    }

    @Test
    fun `zet de grant-hash ongewijzigd op de Fsc-Grant-Hash-header`() {
        val headers = zetHeaders("abc123")

        assertEquals(listOf("abc123"), headers[FscOutwayHeaders.GRANT_HASH_HEADER])
    }

    @Test
    fun `zet een Fsc-Transaction-Id die als UUID v7 parseert`() {
        val headers = zetHeaders("abc123")

        val uuid = UUID.fromString(headers.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER) as String)

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `twee invocaties leveren twee verschillende transaction-ids`() {
        val eerste = zetHeaders("abc123")
        val tweede = zetHeaders("abc123")

        assertNotEquals(
            eerste.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER),
            tweede.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER),
        )
    }
}
```

- [ ] **Stap 1.2: draai de test en bevestig dat hij faalt**

```bash
./mvnw clean test -pl libraries/fbs-common -am -Dtest=FscOutwayHeadersTest
```

Verwacht: compilatiefout — `Unresolved reference: FscOutwayHeaders`.

- [ ] **Stap 1.3: maak de helper**

Maak `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeaders.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.fsc

import jakarta.ws.rs.client.ClientRequestContext
import org.jboss.logging.Logger

/**
 * Het FSC-outway-headercontract op één plek. De OpenFSC-outway kiest de doel-inway op
 * `Fsc-Grant-Hash` (niet op het pad) en `fsc-outway serve` eist een `Fsc-Transaction-Id` in
 * UUID-v7-vorm; zonder deze headers antwoordt de outway met "service not found" resp.
 * "invalid uuid version, must be v7".
 *
 * Meerdere clients sturen deze headers (magazijn-calls per inschrijving, de Profiel-call),
 * elk met een eigen manier om aan hun grant-hash te komen. Die herkomst verschilt; het
 * contract niet — daarom staat het hier en niet in de afzonderlijke filters.
 */
object FscOutwayHeaders {

    const val GRANT_HASH_HEADER = "Fsc-Grant-Hash"
    const val TRANSACTION_ID_HEADER = "Fsc-Transaction-Id"

    private val log = Logger.getLogger(FscOutwayHeaders::class.java)

    fun zet(requestContext: ClientRequestContext, grantHash: String) {
        val transactionId = UuidV7.generate()

        requestContext.headers.putSingle(GRANT_HASH_HEADER, grantHash)
        requestContext.headers.putSingle(TRANSACTION_ID_HEADER, transactionId.toString())

        // Zonder deze transaction-id in de app-log is een call niet terug te vinden in de
        // outway-/inway-logs, die 'm ongewijzigd doorgeven.
        log.debugf(
            "FSC-outway-call naar %s: Fsc-Transaction-Id=%s",
            requestContext.uri,
            transactionId,
        )
    }
}
```

- [ ] **Stap 1.4: maak `FscOutwayHeadersFilter` een wrapper**

Vervang de volledige inhoud van
`libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeadersFilter.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.fsc

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter

/**
 * Zet de FSC-outway-routeringsheaders op een uitgaande magazijn-call.
 *
 * Bewust géén `@Provider`: de grant-hash is per magazijn verschillend, dus wordt de filter
 * per client handmatig geregistreerd in plaats van als globale JAX-RS-provider. De
 * Profiel-client heeft één vaste grant-hash en gebruikt daarom
 * [ProfielFscOutwayHeadersFilter].
 */
class FscOutwayHeadersFilter(private val grantHash: String) : ClientRequestFilter {

    override fun filter(requestContext: ClientRequestContext) {
        FscOutwayHeaders.zet(requestContext, grantHash)
    }
}
```

- [ ] **Stap 1.5: draai beide tests**

```bash
./mvnw clean test -pl libraries/fbs-common -am -Dtest='FscOutwayHeaders*Test'
```

Verwacht: PASS — zowel `FscOutwayHeadersTest` (nieuw) als `FscOutwayHeadersFilterTest`
(ongewijzigd, bewijst dat de extractie gedragsneutraal is).

- [ ] **Stap 1.6: volledige module + detekt**

```bash
./mvnw clean verify -pl libraries/fbs-common -am
./mvnw detekt:check -pl libraries/fbs-common
```

Verwacht: BUILD SUCCESS, JaCoCo-check haalt 90%, detekt 0 bevindingen.

- [ ] **Stap 1.7: commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeaders.kt \
        libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeadersFilter.kt \
        libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeadersTest.kt
git commit -m "refactor(common): FSC-header-logica naar gedeelde FscOutwayHeaders (#730)"
```

---

## Taak 2: `ProfielFscOutwayHeadersFilter`

**Bestanden:**
- Maak: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/ProfielFscOutwayHeadersFilter.kt`
- Test: `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/ProfielFscOutwayHeadersFilterTest.kt`

**Interfaces:**
- Consumeert: `FscOutwayHeaders.zet(...)` uit taak 1.
- Produceert: `class ProfielFscOutwayHeadersFilter()` (publieke no-arg constructor — de
  rest-client-runtime instantieert 'm) en `ProfielFscOutwayHeadersFilter.CONFIG_KEY =
  "profiel-service.grant-hash"`. Taak 3 gebruikt beide.

- [ ] **Stap 2.1: schrijf de falende test**

Maak `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/ProfielFscOutwayHeadersFilterTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.fsc

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.UUID

class ProfielFscOutwayHeadersFilterTest {

    private fun runFilter(grantHash: String?): MultivaluedHashMap<String, Any> {
        val ctx = mockk<ClientRequestContext>()
        val headers = MultivaluedHashMap<String, Any>()
        every { ctx.headers } returns headers
        every { ctx.uri } returns URI.create("https://outway.voorbeeld.test/api/profielservice/v1/BSN/999993653")

        ProfielFscOutwayHeadersFilter { grantHash }.filter(ctx)

        return headers
    }

    /**
     * De drie vormen waarin "niet geconfigureerd" binnenkomt: de key ontbreekt (null), of
     * `${PROFIEL_SERVICE_GRANT_HASH:}` expandeert naar leeg/whitespace. Alle drie moeten de
     * call byte-identiek laten aan de situatie vóór deze feature — een half gezette header
     * zou de outway een onbruikbare route geven.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "   ", "\t"])
    fun `zonder bruikbare grant-hash worden geen FSC-headers gezet`(grantHash: String?) {
        val headers = runFilter(grantHash)

        assertFalse(headers.containsKey(FscOutwayHeaders.GRANT_HASH_HEADER))
        assertFalse(headers.containsKey(FscOutwayHeaders.TRANSACTION_ID_HEADER))
    }

    @Test
    fun `met grant-hash worden beide FSC-headers gezet`() {
        val headers = runFilter("profiel-hash-1")

        assertEquals(listOf("profiel-hash-1"), headers[FscOutwayHeaders.GRANT_HASH_HEADER])

        val uuid = UUID.fromString(headers.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER) as String)

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `omringende spaties in de config-waarde worden getrimd`() {
        val headers = runFilter("  profiel-hash-1  ")

        assertEquals(listOf("profiel-hash-1"), headers[FscOutwayHeaders.GRANT_HASH_HEADER])
    }

    @Test
    fun `twee calls op dezelfde filter leveren verschillende transaction-ids`() {
        val filter = ProfielFscOutwayHeadersFilter { "profiel-hash-1" }

        val eerste = MultivaluedHashMap<String, Any>()
        val tweede = MultivaluedHashMap<String, Any>()

        listOf(eerste, tweede).forEach { headers ->
            val ctx = mockk<ClientRequestContext>()
            every { ctx.headers } returns headers
            every { ctx.uri } returns URI.create("https://outway.voorbeeld.test/api/profielservice/v1/BSN/999993653")
            filter.filter(ctx)
        }

        assertNotEquals(
            eerste.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER),
            tweede.getFirst(FscOutwayHeaders.TRANSACTION_ID_HEADER),
        )
    }

    @Test
    fun `de config-key blijft de gepubliceerde sleutel`() {
        // Pin de sleutel: application.properties van beide services hangt eraan, en een
        // hernoeming zou de filter stil uitschakelen in plaats van te falen.
        assertEquals("profiel-service.grant-hash", ProfielFscOutwayHeadersFilter.CONFIG_KEY)
    }
}
```

- [ ] **Stap 2.2: draai de test en bevestig dat hij faalt**

```bash
./mvnw clean test -pl libraries/fbs-common -am -Dtest=ProfielFscOutwayHeadersFilterTest
```

Verwacht: compilatiefout — `Unresolved reference: ProfielFscOutwayHeadersFilter`.

- [ ] **Stap 2.3: implementeer de filter**

Maak `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/ProfielFscOutwayHeadersFilter.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.fsc

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import org.eclipse.microprofile.config.ConfigProvider

/**
 * Zet de FSC-outway-headers op de Profiel-service-call, mits er een grant-hash geconfigureerd
 * is. Zonder grant-hash doet de filter niets, zodat een deployment dat nog direct met de
 * Profiel-service praat ongewijzigd blijft.
 *
 * De grant-hash komt uit config in plaats van uit een constructor-argument (zoals bij
 * [FscOutwayHeadersFilter], waar elke magazijn-inschrijving een eigen hash heeft): deze filter
 * wordt via `@RegisterProvider` door de rest-client-runtime geïnstantieerd, en die kan geen
 * constructor-argument leveren. De no-arg constructor houdt de instantiatie daarmee
 * onafhankelijk van CDI.
 */
class ProfielFscOutwayHeadersFilter internal constructor(
    private val grantHashProvider: () -> String?,
) : ClientRequestFilter {

    constructor() : this({
        ConfigProvider.getConfig().getOptionalValue(CONFIG_KEY, String::class.java).orElse(null)
    })

    // Eén keer lezen: config is boot-statisch, en dit zit op de hot-path van elke Profiel-call.
    private val grantHash: String? by lazy {
        grantHashProvider()?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun filter(requestContext: ClientRequestContext) {
        grantHash?.let { FscOutwayHeaders.zet(requestContext, it) }
    }

    companion object {
        /**
         * Eigen prefix, niet onder `quarkus.rest-client.profiel-service.*`: dat is
         * Quarkus-eigen namespace en een eigen sleutel daarin bijplaatsen is fragiel.
         */
        const val CONFIG_KEY = "profiel-service.grant-hash"
    }
}
```

- [ ] **Stap 2.4: draai de test**

```bash
./mvnw clean test -pl libraries/fbs-common -am -Dtest=ProfielFscOutwayHeadersFilterTest
```

Verwacht: PASS, 8 invocaties — 4 uit de `@ParameterizedTest` (null, `""`, `"   "`, `"\t"`) plus
de 4 losse `@Test`-methodes.

- [ ] **Stap 2.5: volledige module + detekt**

```bash
./mvnw clean verify -pl libraries/fbs-common -am
./mvnw detekt:check -pl libraries/fbs-common
```

Verwacht: BUILD SUCCESS, JaCoCo ≥ 90%, detekt 0 bevindingen.

- [ ] **Stap 2.6: commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/ProfielFscOutwayHeadersFilter.kt \
        libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/ProfielFscOutwayHeadersFilterTest.kt
git commit -m "feat(common): ProfielFscOutwayHeadersFilter met optionele grant-hash (#730)"
```

---

## Taak 3: registratie op de client + config in beide services

Na deze taak zit de filter in de chain van élke Profiel-call in beide services. De bestaande
suites zijn de check dat de no-op écht no-op is.

**Bestanden:**
- Wijzig: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceClient.kt`
- Wijzig: `services/berichtenuitvraag/src/main/resources/application.properties`
- Wijzig: `services/berichtenmagazijn/src/main/resources/application.properties`
- Wijzig: `CLAUDE.md`

**Interfaces:**
- Consumeert: `ProfielFscOutwayHeadersFilter` + `CONFIG_KEY` uit taak 2.

- [ ] **Stap 3.1: registreer de provider op de interface**

In `ProfielServiceClient.kt`, voeg twee imports toe:

```kotlin
import nl.rijksoverheid.moz.fbs.common.fsc.ProfielFscOutwayHeadersFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
```

en zet de annotatie direct onder de bestaande `@RegisterRestClient`:

```kotlin
/**
 * Registratie hier en niet per service via `quarkus.rest-client.profiel-service.providers`:
 * beide consumers delen deze interface, dus één plek voorkomt dat de registratie tussen
 * services uit elkaar loopt. De filter is een no-op zolang er geen grant-hash geconfigureerd is.
 */
@RegisterRestClient(configKey = "profiel-service")
@RegisterProvider(ProfielFscOutwayHeadersFilter::class)
interface ProfielServiceClient {
```

- [ ] **Stap 3.2: voeg de config-key toe aan berichtenuitvraag**

In `services/berichtenuitvraag/src/main/resources/application.properties`, direct ná de regel
`quarkus.rest-client.profiel-service.keep-alive-enabled=true`:

```properties
# FSC-outway-routering naar de Profiel-service. Met een grant-hash stuurt de client
# Fsc-Grant-Hash + Fsc-Transaction-Id mee en kiest de outway daarop de doel-inway; leeg
# betekent een directe call zonder FSC-headers. Eigen prefix (niet onder
# quarkus.rest-client.*): die namespace is van Quarkus zelf.
profiel-service.grant-hash=${PROFIEL_SERVICE_GRANT_HASH:}
```

- [ ] **Stap 3.3: voeg dezelfde config-key toe aan berichtenmagazijn**

Zelfde blok, in `services/berichtenmagazijn/src/main/resources/application.properties`, direct ná
`quarkus.rest-client.profiel-service.keep-alive-enabled=true`.

- [ ] **Stap 3.4: documenteer de env-var**

In `CLAUDE.md`, sectie "Omgevingsvariabelen", voeg een rij toe onder `MAGAZIJN_A_GRANT_HASH`:

```markdown
| `PROFIEL_SERVICE_GRANT_HASH` | leeg    | Grant-hash van het valide FSC-contract voor de Profiel-service; leeg = geen `Fsc-Grant-Hash`-header, de Profiel-service wordt dan direct/zonder outway aangeroepen |
```

- [ ] **Stap 3.5: draai beide service-suites**

```bash
docker compose up -d
./mvnw clean test -pl services/berichtenuitvraag -am
./mvnw clean test -pl services/berichtenmagazijn -am
```

Verwacht: BUILD SUCCESS in beide. Alle bestaande Profiel-tests draaien nu mét de filter in de
chain en zonder grant-hash — als er iets rood wordt, is de no-op niet echt een no-op.

- [ ] **Stap 3.6: commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceClient.kt \
        services/berichtenuitvraag/src/main/resources/application.properties \
        services/berichtenmagazijn/src/main/resources/application.properties \
        CLAUDE.md
git commit -m "feat(profiel): FSC-outway-headers op de Profiel-service-client (#730)"
```

---

## Taak 4: integratietests — de headers komen aan

Unit-tests dekken de filter-logica; deze taak bewijst dat de headers via de échte, door CDI
gebouwde client op de wire staan. Per service één nieuwe klasse voor het positieve geval (eigen
`TestProfile` met grant-hash) en één extra test in een bestaande klasse voor het negatieve geval
(die profielen draaien al zónder grant-hash).

**Bestanden:**
- Maak: `libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/ProfielFscOutwayHeadersWireMockTest.kt`
- Wijzig: `libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/ProfielMagazijnResolverIntegrationTest.kt`
- Maak: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielFscOutwayHeadersWireMockTest.kt`
- Wijzig: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceClientWireMockTest.kt`

**Interfaces:**
- Consumeert: de registratie uit taak 3; bestaande `WireMockProfielServiceResource` (beide
  modules, companion `server: WireMockServer?`) en `MockProfielServiceClient`.

- [ ] **Stap 4.1: positieve test in de sessiecache-module**

Maak `libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/ProfielFscOutwayHeadersWireMockTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Bewijst dat de FSC-outway-headers op de échte, door CDI gebouwde Profiel-client staan —
 * niet alleen dat de filter-logica klopt (dat dekt ProfielFscOutwayHeadersFilterTest).
 * Het negatieve geval staat in ProfielMagazijnResolverIntegrationTest, dat zonder
 * grant-hash draait.
 */
@QuarkusTest
@TestProfile(ProfielFscGrantHashTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
class ProfielFscOutwayHeadersWireMockTest {

    @Inject
    @RestClient
    lateinit var client: ProfielServiceClient

    private val wireMock get() = WireMockProfielServiceResource.server!!

    @BeforeEach
    fun resetStubs() {
        wireMock.resetAll()
    }

    @Test
    fun `met grant-hash draagt de Profiel-call Fsc-Grant-Hash en een v7 Fsc-Transaction-Id`() {
        wireMock.stubFor(
            get(urlEqualTo(PROFIEL_PAD)).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren":[]}""")
            )
        )

        client.getPartij("BSN", "999993653")

        wireMock.verify(
            getRequestedFor(urlEqualTo(PROFIEL_PAD))
                .withHeader("Fsc-Grant-Hash", equalTo(ProfielFscGrantHashTestProfile.GRANT_HASH))
                .withHeader("Fsc-Transaction-Id", matching(UUID_V7_REGEX))
        )
    }

    companion object {
        const val PROFIEL_PAD = "/api/profielservice/v1/BSN/999993653"

        // De version-nibble "7" wordt expliciet gepind: de outway wijst v4 af, dus een
        // test die alleen "is een UUID" controleert vangt precies die fout niet.
        const val UUID_V7_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    }
}

class ProfielFscGrantHashTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockBerichtenCache::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Sluit de @Mock-bean uit zodat de échte REST-client (mét de geregistreerde filter)
        // wordt geïnjecteerd en het HTTP-pad onder test komt.
        "quarkus.arc.exclude-types" to MockProfielServiceClient::class.java.name,
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
        "profiel-service.grant-hash" to GRANT_HASH,
    )

    companion object {
        const val GRANT_HASH = "profiel-grant-hash-test"
    }
}
```

- [ ] **Stap 4.2: negatieve test in de sessiecache-module**

Voeg aan `ProfielMagazijnResolverIntegrationTest` (draait zónder grant-hash) deze test toe, plus
de imports `com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor` en
`java.time.Duration` (die laatste staat er al):

```kotlin
    @Test
    fun `zonder grant-hash draagt de Profiel-call geen FSC-outway-headers`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren":[]}""")
            )
        )

        resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))

        wireMock.verify(
            getRequestedFor(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .withoutHeader("Fsc-Grant-Hash")
                .withoutHeader("Fsc-Transaction-Id")
        )
    }
```

- [ ] **Stap 4.3: draai de sessiecache-module**

```bash
docker compose up -d
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am
```

Verwacht: BUILD SUCCESS.

- [ ] **Stap 4.4: positieve test in berichtenmagazijn**

Maak `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielFscOutwayHeadersWireMockTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Bewijst dat de FSC-outway-headers op de échte, door CDI gebouwde Profiel-client staan —
 * niet alleen dat de filter-logica klopt (dat dekt ProfielFscOutwayHeadersFilterTest).
 * Het negatieve geval staat in ProfielServiceClientWireMockTest, dat zonder grant-hash draait.
 */
@QuarkusTest
@TestProfile(ProfielFscGrantHashTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
class ProfielFscOutwayHeadersWireMockTest {

    @Inject
    @RestClient
    lateinit var client: ProfielServiceClient

    private val wireMock get() = WireMockProfielServiceResource.server!!

    @BeforeEach
    fun resetStubs() {
        wireMock.resetAll()
    }

    @Test
    fun `met grant-hash draagt de Profiel-call Fsc-Grant-Hash en een v7 Fsc-Transaction-Id`() {
        wireMock.stubFor(
            get(urlEqualTo(PROFIEL_PAD)).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren":[]}""")
            )
        )

        client.getPartij("BSN", "999993653")

        wireMock.verify(
            getRequestedFor(urlEqualTo(PROFIEL_PAD))
                .withHeader("Fsc-Grant-Hash", equalTo(ProfielFscGrantHashTestProfile.GRANT_HASH))
                .withHeader("Fsc-Transaction-Id", matching(UUID_V7_REGEX))
        )
    }

    companion object {
        const val PROFIEL_PAD = "/api/profielservice/v1/BSN/999993653"

        // De version-nibble "7" wordt expliciet gepind: de outway wijst v4 af, dus een
        // test die alleen "is een UUID" controleert vangt precies die fout niet.
        const val UUID_V7_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    }
}

class ProfielFscGrantHashTestProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Sluit de @Mock-bean uit zodat de échte REST-client (mét de geregistreerde filter)
        // wordt geïnjecteerd en het HTTP-pad onder test komt.
        "quarkus.arc.exclude-types" to MockProfielServiceClient::class.java.name,
        "profiel-service.grant-hash" to GRANT_HASH,
    )

    companion object {
        const val GRANT_HASH = "profiel-grant-hash-test"
    }
}
```

- [ ] **Stap 4.5: negatieve test in berichtenmagazijn**

Voeg aan `ProfielServiceClientWireMockTest` (draait zónder grant-hash) toe, met import
`com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor`:

```kotlin
    @Test
    fun `zonder grant-hash draagt de Profiel-call geen FSC-outway-headers`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren":[]}""")
            )
        )

        client.getPartij("BSN", "999993653")

        wireMock.verify(
            getRequestedFor(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .withoutHeader("Fsc-Grant-Hash")
                .withoutHeader("Fsc-Transaction-Id")
        )
    }
```

- [ ] **Stap 4.6: draai berichtenmagazijn**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am
```

Verwacht: BUILD SUCCESS.

- [ ] **Stap 4.7: commit**

```bash
git add libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/ \
        services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/
git commit -m "test(fsc): integratietest FSC-outway-headers op de Profiel-call (#730)"
```

---

## Eindverificatie

- [ ] **Volledige suite**

```bash
docker compose up -d
./mvnw clean verify
```

Verwacht: BUILD SUCCESS. Controleer de output op nieuwe waarschuwingen; alleen de drie in
CLAUDE.md geaccepteerde (jansi `System::load`, guava `Unsafe`, `LogManager`-volgorde) zijn oké.

- [ ] **detekt**

```bash
./mvnw detekt:check
```

Verwacht: 0 bevindingen.

- [ ] **Coverage**

Lees `target/site/jacoco/jacoco.xml` per geraakte module (niet `target/jacoco-report/jacoco.xml`);
≥ 90% line coverage.

- [ ] **Handmatig op ZAD (ná deploy)**

Zet `PROFIEL_SERVICE_URL` op de **https**-outway-ingress en `PROFIEL_SERVICE_GRANT_HASH` op de
grant-hash van het valide Profiel-contract. Dan:

```bash
curl -N -H "X-Ontvanger: BSN:999273127" https://<uitvraag-host>/api/v1/berichten/_ophalen
```

Verwacht: een resultaat dat de ontvanger-voorkeuren weerspiegelt, geen 503 uit de resolver. De
`Fsc-Transaction-Id` uit de app-log op DEBUG moet terugkomen in de outway-/inway-logs.

**Let op:** blijft `PROFIEL_SERVICE_URL` op `http://` staan, dan faalt de boot op
`ProfielServiceEndpointValidator` — dat is bedoeld gedrag (BSN staat in het URL-pad), geen bug in
deze wijziging.
