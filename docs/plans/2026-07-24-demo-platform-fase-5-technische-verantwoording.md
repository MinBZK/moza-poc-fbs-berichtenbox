**Status:** Alle taken uitgevoerd — Docker-runtime-verificatie openstaand

> **Lokaal geverifieerd:** `proxies.json` (6 proxies) en compose valide (magazijn a/b downstreams door
> Toxiproxy, poll-tuning, demo-console `UITVRAAG_URL`); demo-console 27 tests groen incl.
> `StoringResourceTest` (allowlist), `FoutieveAanleverServiceTest` (payload), `OntdubbelingServiceTest`
> (zelfde id); augmentatie wiret de `uitvraag`-rest-client + de dubbele client-`@POST`; detekt schoon.
>
> **Nog te doen (Docker):** images rebuilden (incl. demo-console, `-Dquarkus.jib.platforms=linux/arm64`
> op Apple Silicon), `docker compose --profile demo up -d`, dan de scenario's uit "Definition of done":
> redis/profiel/notificatie/aanmeld uit + herstel, foutieve aanlevering (400), ontdubbeling (1 bericht na
> Ophalen). Let op de env-override van `..._DOWNSTREAMS_AANMELD_MAX_POGINGEN` (best-effort; zie Risico's).

# Demo-platform fase 5 — technische verantwoording — implementatieplan

> **Voor agentic workers:** VERPLICHTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> of `superpowers:executing-plans`. Stappen gebruiken checkbox-syntax (`- [ ]`).

**Ontwerp:** `docs/plans/2026-07-24-demo-platform-fase-5-technische-verantwoording-design.md`

**Doel:** de technische degradatie-scenario's 8–13 demonstreerbaar maken vanuit de demo-console,
zonder demo-logica in de productiecode.

**Architectuur:** twee nieuwe Toxiproxy-proxies (notificatie, aanmeld) tussen de magazijnen en hun
downstreams + env-reroute; generieke allowlist-storing-knoppen voor de al bestaande infra-proxies;
twee nieuwe demo-console-features (foutieve aanlevering → 400, ontdubbeling → 1 bericht).

**Tech stack:** Toxiproxy 2.11, Docker Compose, Quarkus REST client (MicroProfile), Kotlin, MockK,
Jackson.

## Global Constraints

- **Geen productie-codewijziging.** Alleen compose/config + de wegwerp demo-console.
- **demo-console valt buiten de coveragegate**, maar detekt geldt (`maxIssues: 0`, schoon houden).
- **Kotlin-stijl:** lege regels rond multi-line blokken en zelfstandige control-statements (zie CLAUDE.md).
- **Altijd `clean` vóór `test`.** **Nooit direct naar `main`.**
- **Bestaande OIN's:** RVO `00000001003214345000` (magazijn A), Belastingdienst `00000001823288444000` (magazijn B).
- **Persona J. Pietersen BSN `999993653`** (geldig, geregistreerd bij beide magazijnen).
- Package-root: `nl.rijksoverheid.moz.fbs.democonsole`.

## Bestandsoverzicht

| Bestand | Verantwoordelijkheid | Actie |
|---|---|---|
| `toxiproxy/proxies.json` | + `notificatie`, `aanmeld` proxies | Wijzigen |
| `compose.yaml` | magazijn a/b env-reroute + poll-tuning + `depends_on` | Wijzigen |
| `.../storing/StoringResource.kt` | generieke allowlist-`{proxy}/uit` | Wijzigen |
| `.../storing/StoringResourceTest.kt` | allowlist-guard unittest | Aanmaken |
| `.../aanlever/MagazijnAanleverClient.kt` | + `leverRuwAan(payload)` | Wijzigen |
| `.../aanlever/FoutieveAanleverService.kt` | ongeldige payload bouwen + POST | Aanmaken |
| `.../aanlever/FoutieveAanleverResource.kt` | `POST /api/demo/foutieve-aanlevering` | Aanmaken |
| `.../aanlever/FoutieveAanleverServiceTest.kt` | payload-opbouw unittest | Aanmaken |
| `.../ontdubbeling/AanmeldWebhookClient.kt` | REST-client uitvraag `/aanmeldingen` | Aanmaken |
| `.../ontdubbeling/AanmeldEvent.kt` | CloudEvent-DTO's | Aanmaken |
| `.../ontdubbeling/OntdubbelingService.kt` | event bouwen + 2× posten | Aanmaken |
| `.../ontdubbeling/OntdubbelingResource.kt` | `POST /api/demo/ontdubbeling` | Aanmaken |
| `.../ontdubbeling/OntdubbelingServiceTest.kt` | zelfde-id-unittest | Aanmaken |
| `.../demo-console/src/main/resources/application.properties` | rest-client `uitvraag`-url | Wijzigen |
| `.../META-INF/resources/index.html` | sectie "Technische scenario's (fase 5)" | Wijzigen |

## Verificatie zonder Docker

Compose/JSON parseren; `StoringResourceTest` (allowlist), `FoutieveAanleverServiceTest` (payload),
`OntdubbelingServiceTest` (zelfde id) via MockK; augmentatie wiret de `uitvraag`-rest-client;
detekt schoon; paneel in de jar. Het echte toxic-/keten-effect is handmatig tegen de Docker-stack.

---

### Taak 1: Magazijn-downstream-proxies + reroute + poll-tuning

**Files:**
- Modify: `toxiproxy/proxies.json`
- Modify: `compose.yaml` (magazijn-a/b env + `depends_on`)

**Interfaces:**
- Produceert: Toxiproxy-proxies `notificatie` (`:18084`→`notificatie-stub:8080`) en `aanmeld`
  (`:18086`→`berichtenuitvraag:8086`); beide magazijnen leveren hun downstreams via Toxiproxy af.

- [ ] **Stap 1: Voeg de twee proxies toe**

Vervang de inhoud van `toxiproxy/proxies.json` door:

```json
[
  { "name": "magazijn-a",  "listen": "0.0.0.0:18090", "upstream": "berichtenmagazijn-a:8090", "enabled": true },
  { "name": "magazijn-b",  "listen": "0.0.0.0:18091", "upstream": "berichtenmagazijn-b:8090", "enabled": true },
  { "name": "redis",       "listen": "0.0.0.0:16379", "upstream": "redis:6379",                "enabled": true },
  { "name": "profiel",     "listen": "0.0.0.0:18089", "upstream": "profiel-service:8080",       "enabled": true },
  { "name": "notificatie", "listen": "0.0.0.0:18084", "upstream": "notificatie-stub:8080",      "enabled": true },
  { "name": "aanmeld",     "listen": "0.0.0.0:18086", "upstream": "berichtenuitvraag:8086",     "enabled": true }
]
```

- [ ] **Stap 2: Herrouteer de downstreams van magazijn A**

In `compose.yaml`, in de `berichtenmagazijn-a`-service. Voeg `depends_on: toxiproxy` toe:

```yaml
    depends_on:
      postgres-a:
        condition: service_healthy
      toxiproxy:
        condition: service_started
```

en vervang in de `environment` van `berichtenmagazijn-a` de twee downstream-URL's en voeg de
poll-tuning toe (env overschrijft de platte properties; demo-only, geen codewijziging):

```yaml
      AANMELD_URL: http://toxiproxy:18086/api/v1/aanmeldingen
      NOTIFICATIE_URL: http://toxiproxy:18084/events
      # Outbox sneller laten pollen zodat storing→herstel binnen seconden zichtbaar is (default 60s),
      # en meer pogingen zodat een uitgeschakelde downstream niet terminal MISLUKT tijdens de uitleg.
      MAGAZIJN_PUBLICATIE_POLLING_INTERVAL: 5s
      MAGAZIJN_PUBLICATIE_DOWNSTREAMS_AANMELD_MAX_POGINGEN: "50"
      MAGAZIJN_PUBLICATIE_DOWNSTREAMS_NOTIFICATIE_MAX_POGINGEN: "50"
```

- [ ] **Stap 3: Idem voor magazijn B**

In `berichtenmagazijn-b`, voeg `depends_on: toxiproxy` toe:

```yaml
    depends_on:
      postgres-b:
        condition: service_healthy
      toxiproxy:
        condition: service_started
```

en vervang/voeg dezelfde vier env-regels + drie tuning-regels toe als bij magazijn A (identiek —
beide magazijnen delen de proxies `aanmeld`/`notificatie`).

- [ ] **Stap 4: Valideer compose en JSON**

Run:
```bash
python3 -c "import json; p=json.load(open('toxiproxy/proxies.json')); n={x['name'] for x in p}; assert {'notificatie','aanmeld'} <= n, n; print('proxies.json ok', sorted(n))"
python3 -c "import yaml; c=yaml.safe_load(open('compose.yaml')); e=c['services']['berichtenmagazijn-a']['environment']; assert e['AANMELD_URL']=='http://toxiproxy:18086/api/v1/aanmeldingen', e['AANMELD_URL']; assert e['NOTIFICATIE_URL']=='http://toxiproxy:18084/events'; assert 'toxiproxy' in c['services']['berichtenmagazijn-a']['depends_on']; assert 'toxiproxy' in c['services']['berichtenmagazijn-b']['depends_on']; print('compose ok')"
```
Expected: `proxies.json ok [...]` met `aanmeld`+`notificatie`, en `compose ok`.

- [ ] **Stap 5: Commit**

```bash
git add toxiproxy/proxies.json compose.yaml
git commit -m "build(demo): magazijn-downstreams door Toxiproxy (notificatie, aanmeld)

Twee proxies erbij; magazijn a/b leveren NOTIFICATIE_URL/AANMELD_URL via Toxiproxy
zodat notificatie- en aanmeld-uitval per knop te tonen zijn. Poll-interval 5s +
ruimere max-pogingen (env-only) maken storing->herstel binnen seconden zichtbaar."
```

---

### Taak 2: Infra-storingsknoppen (profiel/redis/notificatie/aanmeld uit)

De uitvraag loopt al door Toxiproxy voor redis/profiel; taak 1 bracht notificatie/aanmeld erbij.
`StoringService.uit(proxy)`/`reset()` zijn al proxy-agnostisch — alleen een generieke, bewaakte
resource-endpoint + knoppen ontbreken.

**Files:**
- Modify: `.../storing/StoringResource.kt`
- Test: `.../storing/StoringResourceTest.kt`
- Modify: `.../META-INF/resources/index.html`

**Interfaces:**
- Consumeert: `StoringService.uit(proxy: String)` (fase 3).
- Produceert: `POST /api/demo/storing/{proxy}/uit` met allowlist `{profiel, redis, notificatie, aanmeld}`.

- [ ] **Stap 1: Schrijf de falende test**

`services/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing/StoringResourceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.BadRequestException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StoringResourceTest {

    private val storingService = mockk<StoringService>(relaxed = true)
    private val resource = StoringResource(storingService)

    @ParameterizedTest
    @ValueSource(strings = ["profiel", "redis", "notificatie", "aanmeld"])
    fun `infraUit schakelt een toegestane proxy uit`(proxy: String) {
        every { storingService.uit(proxy) } returns Unit

        resource.infraUit(proxy)

        verify { storingService.uit(proxy) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["magazijn-a", "onbekend", "", "redis; drop"])
    fun `infraUit weigert een proxy buiten de allowlist`(proxy: String) {
        assertThrows(BadRequestException::class.java) { resource.infraUit(proxy) }

        verify(exactly = 0) { storingService.uit(any()) }
    }
}
```

- [ ] **Stap 2: Draai de test — verwacht falen**

Run: `./mvnw -B clean test -pl services/demo-console -am -Dtest=StoringResourceTest`
Expected: FAIL — `infraUit` bestaat nog niet (compilatiefout).

- [ ] **Stap 3: Voeg de generieke endpoint toe**

In `StoringResource.kt`, ná de `reset()`-methode een nieuwe endpoint, en breid de companion uit met
de allowlist. Voeg toe boven `magazijnProxy`:

```kotlin
    @POST
    @Path("/{proxy}/uit")
    fun infraUit(@PathParam("proxy") proxy: String): Map<String, String> {
        if (proxy !in INFRA_PROXIES) {
            throw BadRequestException("onbekende proxy '$proxy'; toegestaan: $INFRA_PROXIES")
        }

        storingService.uit(proxy)

        return mapOf("status" to "$proxy uit")
    }
```

en in de companion, náást `LATENCY_MS`:

```kotlin
        // Alleen infra-proxies waarvoor een knop bestaat; magazijn a/b lopen via hun eigen
        // getypeerde endpoints. Voorkomt dat het paneel een willekeurige proxy uitschakelt.
        val INFRA_PROXIES = setOf("profiel", "redis", "notificatie", "aanmeld")
```

(`BadRequestException`, `POST`, `Path`, `PathParam` zijn al geïmporteerd in dit bestand.)

- [ ] **Stap 4: Draai de test — verwacht slagen + detekt**

Run: `./mvnw -B clean test detekt:check -pl services/demo-console -am -Dtest=StoringResourceTest`
Expected: `BUILD SUCCESS`, alle `StoringResourceTest`-tests groen, detekt 0 bevindingen.

- [ ] **Stap 5: Voeg de paneelsectie toe**

In `index.html`, ná de bestaande "Storingen (fase 3)"-fieldset:

```html
  <fieldset>
    <legend>Technische scenario's (fase 5)</legend>
    <button onclick="post('/api/demo/storing/redis/uit')">Redis uit (scenario 12)</button>
    <button onclick="post('/api/demo/storing/profiel/uit')">Profielservice uit (scenario 9)</button>
    <br>
    <button onclick="post('/api/demo/storing/notificatie/uit')">Notificatie uit (scenario 10)</button>
    <button onclick="post('/api/demo/storing/aanmeld/uit')">Uitvraag/aanmeld uit (scenario 11)</button>
    <p><small>Herstellen: gebruik "Alles normaal (reset)" hierboven.</small></p>
  </fieldset>
```

- [ ] **Stap 6: Bouw en commit**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"`
Expected: `BUILD SUCCESS`.

```bash
git add services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing services/demo-console/src/test services/demo-console/src/main/resources/META-INF/resources/index.html
git commit -m "feat(demo-console): infra-storingsknoppen (redis/profiel/notificatie/aanmeld uit)

Generieke, allowlist-bewaakte POST /api/demo/storing/{proxy}/uit; hergebruikt de
proxy-agnostische StoringService.uit/reset. Paneelsectie 'Technische scenario's'."
```

---

### Taak 3: Foutieve aanlevering → 400 problem+json

**Files:**
- Modify: `.../aanlever/MagazijnAanleverClient.kt` (raw-JSON POST)
- Create: `.../aanlever/FoutieveAanleverService.kt`
- Create: `.../aanlever/FoutieveAanleverResource.kt`
- Test: `.../aanlever/FoutieveAanleverServiceTest.kt`
- Modify: `.../META-INF/resources/index.html`

**Interfaces:**
- Consumeert: `MagazijnenConfig.magazijnen()` (map OIN→url), `MagazijnAanleverClient`.
- Produceert:
  - `MagazijnAanleverClient.leverRuwAan(payload: String): Response`
  - `FoutieveAanleverService.ongeldigePayload(): String` (companion), `.stuurOngeldig(): FoutResultaat`
  - `data class FoutResultaat(val status: Int, val body: String)`
  - `POST /api/demo/foutieve-aanlevering`

- [ ] **Stap 1: Schrijf de falende test**

`services/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/aanlever/FoutieveAanleverServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FoutieveAanleverServiceTest {

    private val mapper = ObjectMapper()

    @Test
    fun `ongeldige payload faalt uitsluitend op de elfproef-ongeldige BSN`() {
        val json = mapper.readTree(FoutieveAanleverService.ongeldigePayload())

        // Verplichte velden zijn aanwezig en geldig, zodat alleen de BSN de 400 uitlokt.
        assertEquals("00000001003214345000", json["afzender"].asText())
        assertEquals("BSN", json["ontvanger"]["type"].asText())
        assertEquals("111111111", json["ontvanger"]["waarde"].asText())
        assertTrue(json["onderwerp"].asText().isNotBlank())
        assertTrue(json["inhoud"].asText().isNotBlank())
    }
}
```

- [ ] **Stap 2: Draai de test — verwacht falen**

Run: `./mvnw -B clean test -pl services/demo-console -am -Dtest=FoutieveAanleverServiceTest`
Expected: FAIL — `FoutieveAanleverService` bestaat nog niet.

- [ ] **Stap 3: Voeg de raw-JSON client-methode toe**

In `MagazijnAanleverClient.kt`, ná `leverAan` (geen eigen `@Path` — zelfde pad `/api/v1/berichten`
als `leverAan`; twee `@POST` op hetzelfde interface-`@Path` mag voor een rest-**client**, want elke
methode is een losse invocatie en er is geen server-routering):

```kotlin
    // Rauwe JSON-body zodat de demo-console volledig ongeldige payloads kan sturen (scenario
    // 'foutieve aanlevering'); de getypeerde leverAan kan niet elke ongeldigheid uitdrukken.
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun leverRuwAan(payload: String): Response
```

- [ ] **Stap 4: Schrijf de service**

`FoutieveAanleverService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI

/** Resultaat van een bewust foutieve aanlevering: de HTTP-status + de (problem+json) body. */
data class FoutResultaat(val status: Int, val body: String)

/**
 * Stuurt één bewust ongeldige aanlevering naar magazijn A (RVO) om de 400 RFC 9457-respons te
 * tonen. De payload is compleet en geldig op één punt na — een BSN die de elfproef niet haalt —
 * zodat de 400 aantoonbaar op de domeinvalidatie slaat, niet op een ontbrekend veld.
 */
@ApplicationScoped
class FoutieveAanleverService(config: MagazijnenConfig) {

    private val client: MagazijnAanleverClient =
        QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(magazijnAUrl(config)))
            .build(MagazijnAanleverClient::class.java)

    fun stuurOngeldig(): FoutResultaat =
        client.leverRuwAan(ongeldigePayload()).use { response ->
            FoutResultaat(response.status, response.readEntity(String::class.java))
        }

    companion object {

        // BSN 111111111 haalt de elfproef niet (som mod 11 != 0) → DomainValidationException → 400.
        fun ongeldigePayload(): String =
            """
            {
              "afzender": "00000001003214345000",
              "ontvanger": { "type": "BSN", "waarde": "111111111" },
              "onderwerp": "Demo: foutieve aanlevering",
              "inhoud": "Deze aanlevering hoort te falen op de elfproef-validatie van de ontvanger-BSN."
            }
            """.trimIndent()

        private fun magazijnAUrl(config: MagazijnenConfig): String =
            config.magazijnen()["00000001003214345000"]?.url()
                ?: error("geen URL geconfigureerd voor magazijn A (00000001003214345000)")
    }
}
```

- [ ] **Stap 5: Schrijf de resource**

`FoutieveAanleverResource.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/foutieve-aanlevering")
@Produces(MediaType.APPLICATION_JSON)
class FoutieveAanleverResource(private val service: FoutieveAanleverService) {

    @POST
    fun stuur(): FoutResultaat = service.stuurOngeldig()
}
```

- [ ] **Stap 6: Draai de test — verwacht slagen + detekt**

Run: `./mvnw -B clean test detekt:check -pl services/demo-console -am -Dtest=FoutieveAanleverServiceTest`
Expected: `BUILD SUCCESS`, test groen, detekt 0 bevindingen.

- [ ] **Stap 7: Voeg de paneelknop toe**

In `index.html`, in de "Technische scenario's (fase 5)"-fieldset, vóór de `<p><small>`-regel:

```html
    <br>
    <button onclick="post('/api/demo/foutieve-aanlevering')">Foutieve aanlevering (scenario 8)</button>
```

- [ ] **Stap 8: Bouw en commit**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"`
Expected: `BUILD SUCCESS`.

```bash
git add services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/aanlever services/demo-console/src/test services/demo-console/src/main/resources/META-INF/resources/index.html
git commit -m "feat(demo-console): foutieve aanlevering toont 400 problem+json

Rauwe-JSON client-call naar magazijn A met een elfproef-ongeldige ontvanger-BSN;
de service geeft status + body terug zodat het paneel de RFC 9457-respons toont."
```

---

### Taak 4: Ontdubbeling → één bericht

**Files:**
- Create: `.../ontdubbeling/AanmeldWebhookClient.kt`
- Create: `.../ontdubbeling/AanmeldEvent.kt`
- Create: `.../ontdubbeling/OntdubbelingService.kt`
- Create: `.../ontdubbeling/OntdubbelingResource.kt`
- Test: `.../ontdubbeling/OntdubbelingServiceTest.kt`
- Modify: `.../demo-console/src/main/resources/application.properties`
- Modify: `compose.yaml` (`UITVRAAG_URL` in demo-console-env)
- Modify: `.../META-INF/resources/index.html`

**Interfaces:**
- Consumeert: `AanmeldWebhookClient.meldAan(event: String): Response` (rest-client `uitvraag`).
- Produceert:
  - `OntdubbelingService.demonstreer(ontvangerBsn: String): OntdubbelingResultaat`
  - `data class OntdubbelingResultaat(val eventId: String, val eersteStatus: Int, val tweedeStatus: Int)`
  - `POST /api/demo/ontdubbeling?bsn=<BSN>` (default `999993653`)

- [ ] **Stap 1: Schrijf de falende test**

`services/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/ontdubbeling/OntdubbelingServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OntdubbelingServiceTest {

    private val client = mockk<AanmeldWebhookClient>()
    private val service = OntdubbelingService(client, ObjectMapper())

    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    @Test
    fun `beide aanmeldingen dragen exact dezelfde payload (zelfde CloudEvent-id)`() {
        val payloads = mutableListOf<String>()

        every { client.meldAan(capture(payloads)) } returns respons(202)

        val resultaat = service.demonstreer("999993653")

        assertEquals(2, payloads.size)
        assertEquals(payloads[0], payloads[1])

        val event = ObjectMapper().readTree(payloads[0])
        assertEquals(resultaat.eventId, event["id"].asText())
        assertEquals("nl.rijksoverheid.fbs.bericht.gepubliceerd", event["type"].asText())
        assertEquals("1.0", event["specversion"].asText())
        assertEquals("00000001003214345000", event["data"]["afzender"].asText())
        assertEquals("999993653", event["data"]["ontvanger"]["waarde"].asText())
        assertEquals(202, resultaat.eersteStatus)
        assertEquals(202, resultaat.tweedeStatus)
    }
}
```

- [ ] **Stap 2: Draai de test — verwacht falen**

Run: `./mvnw -B clean test -pl services/demo-console -am -Dtest=OntdubbelingServiceTest`
Expected: FAIL — de klassen bestaan nog niet.

- [ ] **Stap 3: Schrijf de CloudEvent-DTO's**

`AanmeldEvent.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

/** `data`-payload van het gepubliceerd-event; velden gespiegeld op wat AanmeldService.parse() eist. */
data class AanmeldData(
    val berichtId: String,
    val afzender: String,
    val ontvanger: Ontvanger,
    val onderwerp: String,
    val inhoud: String,
    val tijdstipOntvangst: String,
    val publicatietijdstip: String,
)

/** Getypeerde ontvanger `{type, waarde}` (los van de generator-DTO om de ontdubbeling zelfstandig te houden). */
data class Ontvanger(val type: String, val waarde: String)

/** CloudEvents-envelope voor `POST /aanmeldingen` (media type application/cloudevents+json). */
data class AanmeldEvent(
    val id: String,
    val source: String,
    val specversion: String,
    val type: String,
    val subject: String,
    val time: String,
    val datacontenttype: String,
    val data: AanmeldData,
)
```

- [ ] **Stap 4: Schrijf de rest-client**

`AanmeldWebhookClient.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Aanmeld-webhook van de uitvraag. Body is een rauwe JSON-string zodat de twee POST's van de
 * ontdubbeling-demo byte-voor-byte identiek zijn (zelfde CloudEvent-`id`).
 */
@Path("/api/v1/aanmeldingen")
@RegisterRestClient(configKey = "uitvraag")
interface AanmeldWebhookClient {

    @POST
    @Consumes("application/cloudevents+json")
    fun meldAan(event: String): Response
}
```

- [ ] **Stap 5: Schrijf de service**

`OntdubbelingService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.OffsetDateTime
import java.util.UUID

/** Resultaat van de ontdubbeling-demo: het gedeelde event-id en de twee HTTP-statussen (beide 202). */
data class OntdubbelingResultaat(val eventId: String, val eersteStatus: Int, val tweedeStatus: Int)

/**
 * Demonstreert ontdubbeling op de aanmeld-webhook: bouwt één CloudEvent en levert het tweemaal met
 * hetzelfde `id` af. De uitvraag dedupliceert op `id` (keyspace `aanmeld:event:`), dus er ontstaat
 * één bericht — mits de ontvanger een actieve sessie heeft (anders wordt de marker vrijgegeven en
 * verschijnt niets). Id en berichtId worden per aanroep vers gegenereerd zodat opeenvolgende demo's
 * niet tegen elkaars 24u-marker dedupliceren.
 */
@ApplicationScoped
class OntdubbelingService(
    @param:RestClient private val client: AanmeldWebhookClient,
    private val mapper: ObjectMapper,
) {

    fun demonstreer(ontvangerBsn: String): OntdubbelingResultaat {
        val eventId = UUID.randomUUID().toString()
        val payload = mapper.writeValueAsString(bouwEvent(eventId, ontvangerBsn))

        val eerste = post(payload)
        val tweede = post(payload)

        return OntdubbelingResultaat(eventId, eerste, tweede)
    }

    private fun post(payload: String): Int = client.meldAan(payload).use { it.status }

    private fun bouwEvent(eventId: String, ontvangerBsn: String): AanmeldEvent {
        val nu = OffsetDateTime.now().toString()
        val berichtId = UUID.randomUUID().toString()

        return AanmeldEvent(
            id = eventId,
            source = "urn:nld:oin:$AFZENDER_OIN:systeem:fbs-magazijn",
            specversion = "1.0",
            type = "nl.rijksoverheid.fbs.bericht.gepubliceerd",
            subject = berichtId,
            time = nu,
            datacontenttype = "application/json",
            data = AanmeldData(
                berichtId = berichtId,
                afzender = AFZENDER_OIN,
                ontvanger = Ontvanger(type = "BSN", waarde = ontvangerBsn),
                onderwerp = "Demo: ontdubbeling",
                inhoud = "Ditzelfde CloudEvent wordt tweemaal afgeleverd; er hoort één bericht te ontstaan.",
                tijdstipOntvangst = nu,
                publicatietijdstip = nu,
            ),
        )
    }

    private companion object {

        // RVO — magazijn A; moet een geconfigureerd magazijn zijn, anders weigert de uitvraag met 400.
        const val AFZENDER_OIN = "00000001003214345000"
    }
}
```

- [ ] **Stap 6: Schrijf de resource**

`OntdubbelingResource.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/ontdubbeling")
@Produces(MediaType.APPLICATION_JSON)
class OntdubbelingResource(private val service: OntdubbelingService) {

    // Default = persona J. Pietersen; de ontvanger moet een actieve sessie hebben om het bericht te zien.
    @POST
    fun demonstreer(@QueryParam("bsn") @DefaultValue("999993653") bsn: String): OntdubbelingResultaat =
        service.demonstreer(bsn)
}
```

- [ ] **Stap 7: Config voor de uitvraag-rest-client**

Voeg toe aan `services/demo-console/src/main/resources/application.properties` (bij de andere
rest-client-config):

```properties
# Uitvraag-aanmeld-webhook (fase 5 ontdubbeling). In de demo-compose zet UITVRAAG_URL de
# container-DNS; buiten containers de default.
quarkus.rest-client.uitvraag.url=${UITVRAAG_URL:http://localhost:8086}
```

en in `compose.yaml`, in de `demo-console`-`environment`, ná `REDIS_HOSTS`:

```yaml
      UITVRAAG_URL: http://berichtenuitvraag:8086
```

- [ ] **Stap 8: Draai de test — verwacht slagen + detekt**

Run: `./mvnw -B clean test detekt:check -pl services/demo-console -am -Dtest=OntdubbelingServiceTest`
Expected: `BUILD SUCCESS`, test groen, detekt 0 bevindingen.

- [ ] **Stap 9: Voeg de paneelknop toe**

In `index.html`, in de "Technische scenario's (fase 5)"-fieldset, ná de foutieve-aanlevering-knop:

```html
    <br>
    <label>Ontdubbeling voor BSN: <input id="ontdubbelBsn" type="text" value="999993653" style="width:8rem"></label>
    <button onclick="post('/api/demo/ontdubbeling?bsn=' + document.getElementById('ontdubbelBsn').value)">Ontdubbeling (scenario 13)</button>
    <p><small>Let op: laat de persona eerst <em>Ophalen</em> (actieve sessie), anders verschijnt er niets.</small></p>
```

- [ ] **Stap 10: Volledige bouw + regressie**

Run: `./mvnw -B clean verify detekt:check -pl services/demo-console -am 2>&1 | tail -20`
Expected: `BUILD SUCCESS`, alle demo-console-tests groen (incl. fase 3/4), detekt schoon. De
augmentatie wiret de `uitvraag`-rest-client (config `quarkus.rest-client.uitvraag.url` aanwezig).

- [ ] **Stap 11: Commit**

```bash
git add services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/ontdubbeling services/demo-console/src/test services/demo-console/src/main/resources/application.properties services/demo-console/src/main/resources/META-INF/resources/index.html compose.yaml
git commit -m "feat(demo-console): ontdubbeling — zelfde CloudEvent 2x, één bericht

Bouwt één CloudEvent en levert het tweemaal met hetzelfde id aan de uitvraag-
aanmeld-webhook; dedup op id (keyspace aanmeld:event:) geeft één bericht. Ontvanger-
BSN is parameter (default J. Pietersen); vereist een actieve sessie voor de ontvanger."
```

---

## Definition of done

- [ ] Toxiproxy draait met 6 proxies; magazijn-downstreams lopen door Toxiproxy
- [ ] Redis uit → `GET /berichten` geeft 502 problem+json met correlation-id (geen kale 500); cache-verval-knop werkt nog
- [ ] Profiel uit → *Ophalen* degradeert (gedraging genoteerd)
- [ ] Notificatie uit → nieuw bericht verschijnt tóch bij *Vernieuw*; magazijn-log toont notificatie-retry (WARN)
- [ ] Aanmeld uit → nieuw bericht verschijnt niet bij *Vernieuw*, bestaande wél; re-enable → verschijnt alsnog
- [ ] Foutieve aanlevering → 400 problem+json in het paneel
- [ ] Ontdubbeling (na *Ophalen*) → precies één nieuw bericht
- [ ] `reset` herstelt alle zes proxies
- [ ] `StoringResourceTest`, `FoutieveAanleverServiceTest`, `OntdubbelingServiceTest` groen; detekt schoon
- [ ] `./mvnw clean verify -pl services/demo-console -am` groen

## Risico's

| Risico | Signaal | Mitigatie |
|---|---|---|
| Env-override van map-key-property (`..._DOWNSTREAMS_AANMELD_MAX_POGINGEN`) grijpt niet | delivery gaat na 5 pogingen terminal | Best-effort; ook zonder werkt het binnen een ~30–60s venster (poll 5s, backoff PT1S..). Bevestig op Docker via `curl :8474`; anders max-pogingen weglaten. |
| Twee `@POST` op hetzelfde client-`@Path` | augmentatie/aanroep faalt | Rest-**client**-interface routeert niet; per-methode-invocatie. Bevestigd door de package-build. |
| `application/cloudevents+json` niet geserialiseerd | 415/lege body | We posten een **String** met expliciete `@Consumes`; de String-body-writer stuurt de bytes ongewijzigd. |
| Ontdubbeling toont niets | ontvanger zonder actieve sessie → marker vrijgegeven | Demo-instructie + paneel-hint: eerst *Ophalen*. |
| `depends_on: toxiproxy` introduceert cyclus | compose weigert te starten | Toxiproxy heeft geen deps; volgorde toxiproxy→magazijn→uitvraag→console blijft acyclisch. |

## Niet in deze fase

Load/stress (#14) → fase 7. Traag-varianten op infra-proxies (eisen vragen "weg", niet "traag").
Veel-magazijnen-volume → fase 6.
