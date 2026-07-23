**Status:** Concept

# Demo-platform fase 3 — Toxiproxy + storingsscenario's — implementatieplan

> **Voor agentic workers:** VERPLICHTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> of `superpowers:executing-plans`. Stappen gebruiken checkbox-syntax (`- [ ]`).

**Ontwerp:** `docs/plans/2026-07-23-demo-platform-fase-3-toxiproxy-storingen-design.md`

**Doel:** trage/uitgevallen magazijnen aantoonbaar maken via Toxiproxy, bediend vanuit de
demo-console — zonder demo-logica in de productiecode.

**Architectuur:** een Toxiproxy-container tussen de uitvraag en al zijn afhankelijkheden.
Proxies staan vast (config-bestand); de demo-console voegt runtime *toxics* toe/weg via de
admin-API. Fase 3 bouwt de magazijn-knoppen (traag/uit); de Redis/profiel-proxies staan al
klaar voor fase 5.

**Tech stack:** Toxiproxy 2.11, Docker Compose, Quarkus REST client (MicroProfile), Kotlin,
MockK.

## Global Constraints

- **Geen productie-codewijziging.** Alleen compose/config + de wegwerp demo-console.
- **Latency "traag" = 6000 ms** — boven de 5s-zichtbaarheid, onder de 10s-query-timeout
  (`berichtensessiecache.magazijn-query-timeout-seconds`), zodat traag ≠ fout.
- **Proxy-namen:** `magazijn-a`, `magazijn-b`, `redis`, `profiel`.
- **demo-console valt buiten de coveragegate**, maar detekt geldt (schoon houden).
- **Altijd `clean` vóór `test`.** **Nooit direct naar `main`.**

## Bestandsoverzicht

| Bestand | Verantwoordelijkheid | Actie |
|---|---|---|
| `toxiproxy/proxies.json` | 4 proxy-definities (startup) | Aanmaken |
| `compose.yaml` | Toxiproxy-service; uitvraag/demo-console env | Wijzigen |
| `.../democonsole/storing/ToxiproxyClient.kt` | admin-API REST-client + DTO's | Aanmaken |
| `.../democonsole/storing/StoringService.kt` | traag/uit/reset-orkestratie | Aanmaken |
| `.../democonsole/storing/StoringResource.kt` | endpoints `/api/demo/storing/*` | Aanmaken |
| `.../demo-console/src/main/resources/application.properties` | toxiproxy rest-client-url | Wijzigen |
| `.../democonsole/storing/StoringServiceTest.kt` | unittest orkestratie | Aanmaken |
| `.../META-INF/resources/index.html` | sectie "Storingen" | Wijzigen |

Package-root: `nl.rijksoverheid.moz.fbs.democonsole.storing`.

## Verificatie zonder Docker

De `StoringService`-orkestratie is met MockK lokaal testbaar (welke admin-call bij welke knop,
reset-iteratie). Augmentatie valideert de REST-client-wiring en config. Het echte toxic-effect
(trage/uitgevallen magazijnen) is handmatig tegen de draaiende stack.

---

### Taak 1: Toxiproxy-infrastructuur

**Files:**
- Aanmaken: `toxiproxy/proxies.json`
- Wijzigen: `compose.yaml`

**Interfaces:**
- Produceert: Toxiproxy op `toxiproxy:8474` (admin) met 4 proxies; de uitvraag loopt er
  transparant doorheen.

- [ ] **Stap 1: Schrijf de proxy-definities**

`toxiproxy/proxies.json`:

```json
[
  { "name": "magazijn-a", "listen": "0.0.0.0:18090", "upstream": "berichtenmagazijn-a:8090", "enabled": true },
  { "name": "magazijn-b", "listen": "0.0.0.0:18091", "upstream": "berichtenmagazijn-b:8090", "enabled": true },
  { "name": "redis",      "listen": "0.0.0.0:16379", "upstream": "redis:6379",              "enabled": true },
  { "name": "profiel",    "listen": "0.0.0.0:18089", "upstream": "profiel-service:8080",     "enabled": true }
]
```

- [ ] **Stap 2: Voeg de Toxiproxy-service toe aan het demo-profiel**

In `compose.yaml`, in het `demo`-profielblok (bij de andere app-containers):

```yaml
  # Netwerk-storingssimulator tussen de uitvraag en zijn afhankelijkheden. Proxies vast uit
  # /proxies.json; de demo-console voegt runtime toxics toe via de admin-API (:8474).
  toxiproxy:
    image: ghcr.io/shopify/toxiproxy:2.11.0
    profiles: [demo]
    ports:
      - "8474:8474"
    volumes:
      - ./toxiproxy/proxies.json:/proxies.json:ro
    command: ["-host=0.0.0.0", "-config=/proxies.json"]
```

- [ ] **Stap 3: Leid de uitvraag door Toxiproxy**

Vervang in de `berichtenuitvraag`-service-env:

```yaml
      REDIS_HOSTS: redis://toxiproxy:16379
      MAGAZIJN_A_URL: http://toxiproxy:18090
      MAGAZIJN_B_URL: http://toxiproxy:18091
      PROFIEL_SERVICE_URL: http://toxiproxy:18089
```

en voeg aan de `depends_on` van `berichtenuitvraag` toe:

```yaml
      toxiproxy:
        condition: service_started
```

- [ ] **Stap 4: Geef demo-console het admin-adres**

Voeg aan de `demo-console`-env toe:

```yaml
      TOXIPROXY_ADMIN_URL: http://toxiproxy:8474
```

en aan zijn `depends_on`:

```yaml
      toxiproxy:
        condition: service_started
```

- [ ] **Stap 5: Valideer compose en JSON**

Run:
```bash
python3 -c "import json; json.load(open('toxiproxy/proxies.json')); print('proxies.json ok')"
python3 -c "import yaml; c=yaml.safe_load(open('compose.yaml')); s=c['services']; assert 'toxiproxy' in s; assert s['berichtenuitvraag']['environment']['MAGAZIJN_A_URL']=='http://toxiproxy:18090'; print('compose ok')"
```
Expected: `proxies.json ok` en `compose ok`.

- [ ] **Stap 6: Commit**

```bash
git add toxiproxy/proxies.json compose.yaml
git commit -m "build(demo): Toxiproxy tussen uitvraag en afhankelijkheden

Vier proxies (magazijn a/b, redis, profiel) vast uit config; uitvraag-env wijst
naar Toxiproxy. Transparant bij normaal bedrijf; toxics volgen via de demo-console."
```

---

### Taak 2: Toxiproxy-admin-client en storing-service

De lokaal-testbare kern: een REST-client tegen de admin-API en een service die traag/uit/
reset orkestreert.

**Files:**
- Aanmaken: `.../democonsole/storing/ToxiproxyClient.kt`
- Aanmaken: `.../democonsole/storing/StoringService.kt`
- Test: `.../democonsole/storing/StoringServiceTest.kt`

**Interfaces:**
- Produceert:
  - `interface ToxiproxyClient` met `proxies(): Map<String, ProxyStatus>`,
    `zetProxy(proxy, ProxyPatch): Response`, `voegToxicToe(proxy, ToxicVerzoek): Response`,
    `verwijderToxic(proxy, toxic): Response`.
  - `class StoringService` met `traag(proxy, latencyMs)`, `uit(proxy)`, `reset()`.

- [ ] **Stap 1: Schrijf de admin-client + DTO's**

`ToxiproxyClient.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.storing

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** Toxic-verzoek: `{"type":"latency","attributes":{"latency":6000}}`. */
data class ToxicVerzoek(val type: String, val attributes: Map<String, Int>)

/** Proxy aan/uit: `{"enabled":false}`. */
data class ProxyPatch(val enabled: Boolean)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToxicStatus(val name: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProxyStatus(val enabled: Boolean, val toxics: List<ToxicStatus> = emptyList())

/**
 * Client voor de Toxiproxy-admin-API. Alleen de calls die de demo nodig heeft: proxies
 * lezen (voor reset), proxy aan/uit, latency-toxic toevoegen/verwijderen.
 */
@Path("/proxies")
@RegisterRestClient(configKey = "toxiproxy")
interface ToxiproxyClient {

    @GET
    fun proxies(): Map<String, ProxyStatus>

    @POST
    @Path("/{proxy}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun zetProxy(@PathParam("proxy") proxy: String, patch: ProxyPatch): Response

    @POST
    @Path("/{proxy}/toxics")
    @Consumes(MediaType.APPLICATION_JSON)
    fun voegToxicToe(@PathParam("proxy") proxy: String, toxic: ToxicVerzoek): Response

    @DELETE
    @Path("/{proxy}/toxics/{toxic}")
    fun verwijderToxic(@PathParam("proxy") proxy: String, @PathParam("toxic") toxic: String): Response
}
```

- [ ] **Stap 2: Schrijf de storing-service**

`StoringService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RestClient

/** Orkestreert de storingsknoppen naar Toxiproxy-admin-calls. */
@ApplicationScoped
class StoringService(@param:RestClient private val toxiproxy: ToxiproxyClient) {

    fun traag(proxy: String, latencyMs: Int) {
        controleer(toxiproxy.voegToxicToe(proxy, ToxicVerzoek("latency", mapOf("latency" to latencyMs))), "traag zetten van $proxy")
    }

    fun uit(proxy: String) {
        controleer(toxiproxy.zetProxy(proxy, ProxyPatch(enabled = false)), "uitschakelen van $proxy")
    }

    // Herstel: elke proxy weer aan, alle toxics weg.
    fun reset() {
        toxiproxy.proxies().forEach { (naam, status) ->
            if (!status.enabled) {
                controleer(toxiproxy.zetProxy(naam, ProxyPatch(enabled = true)), "inschakelen van $naam")
            }

            status.toxics.forEach { toxic ->
                controleer(toxiproxy.verwijderToxic(naam, toxic.name), "verwijderen toxic ${toxic.name} van $naam")
            }
        }
    }

    private fun controleer(response: Response, actie: String) {
        response.use {
            check(it.status in 200..299) { "Toxiproxy-fout bij $actie: HTTP ${it.status}" }
        }
    }
}
```

- [ ] **Stap 3: Schrijf de falende test**

`StoringServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Test

class StoringServiceTest {

    private val toxiproxy = mockk<ToxiproxyClient>(relaxed = false)
    private val service = StoringService(toxiproxy)

    // Response via MockK (relaxed sluit .use()/close() af) i.p.v. Response.ok().build(),
    // dat een JAX-RS RuntimeDelegate vereist die in een pure unittest kan ontbreken.
    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    private fun ok() = respons(200)

    private fun noContent() = respons(204)

    @Test
    fun `traag voegt een latency-toxic van 6000ms toe`() {
        every { toxiproxy.voegToxicToe(any(), any()) } returns ok()

        service.traag("magazijn-a", 6000)

        verify { toxiproxy.voegToxicToe("magazijn-a", ToxicVerzoek("latency", mapOf("latency" to 6000))) }
    }

    @Test
    fun `uit schakelt de proxy uit`() {
        every { toxiproxy.zetProxy(any(), any()) } returns ok()

        service.uit("magazijn-b")

        verify { toxiproxy.zetProxy("magazijn-b", ProxyPatch(enabled = false)) }
    }

    @Test
    fun `reset schakelt uitgeschakelde proxies weer in en wist toxics`() {
        every { toxiproxy.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = false, toxics = listOf(ToxicStatus("latency_downstream"))),
            "magazijn-b" to ProxyStatus(enabled = true, toxics = emptyList()),
        )
        every { toxiproxy.zetProxy(any(), any()) } returns ok()
        every { toxiproxy.verwijderToxic(any(), any()) } returns noContent()

        service.reset()

        verify { toxiproxy.zetProxy("magazijn-a", ProxyPatch(enabled = true)) }
        verify { toxiproxy.verwijderToxic("magazijn-a", "latency_downstream") }
        verify(exactly = 0) { toxiproxy.zetProxy("magazijn-b", any()) }
    }

    @Test
    fun `een niet-2xx-respons van Toxiproxy faalt met een duidelijke melding`() {
        every { toxiproxy.zetProxy(any(), any()) } returns respons(404)

        try {
            service.uit("onbekend")
            throw AssertionError("verwacht een IllegalStateException")
        } catch (fout: IllegalStateException) {
            check(fout.message!!.contains("404"))
        }
    }
}
```

- [ ] **Stap 4: Draai de test — verwacht falen**

Run: `./mvnw -B clean test -pl services/demo-console -am`
Expected: FAIL — `ToxiproxyClient`/`StoringService` bestaan nog niet (compilatiefout). (Schrijf
stap 1-2 vóór het draaien; dan is dit meteen groen.)

- [ ] **Stap 5: Draai de test — verwacht slagen + detekt**

Run: `./mvnw -B clean test detekt:check -pl services/demo-console -am`
Expected: `BUILD SUCCESS`, alle `StoringServiceTest`-tests groen, detekt 0 bevindingen.

- [ ] **Stap 6: Commit**

```bash
git add services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing services/demo-console/src/test
git commit -m "feat(demo-console): Toxiproxy-admin-client + storing-service

Client voor de Toxiproxy-admin-API; StoringService orkestreert traag (latency-
toxic), uit (proxy disabled) en reset (proxies aan + toxics weg). Unit-getest met
MockK op de admin-calls en de reset-iteratie."
```

---

### Taak 3: Storing-endpoints + rest-client-config

**Files:**
- Aanmaken: `.../democonsole/storing/StoringResource.kt`
- Wijzigen: `.../demo-console/src/main/resources/application.properties`

**Interfaces:**
- Consumeert: `StoringService` (taak 2).
- Produceert: `POST /api/demo/storing/magazijn/{ab}/traag`, `.../uit`, `POST /api/demo/storing/reset`.

- [ ] **Stap 1: Config voor de admin-URL**

Voeg toe aan `application.properties`:

```properties
# Toxiproxy-admin-API (fase 3 storingssimulator). In de demo-compose zet TOXIPROXY_ADMIN_URL
# de container-DNS; buiten containers de default.
quarkus.rest-client.toxiproxy.url=${TOXIPROXY_ADMIN_URL:http://localhost:8474}
```

- [ ] **Stap 2: Schrijf de resource**

`StoringResource.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/storing")
@Produces(MediaType.APPLICATION_JSON)
class StoringResource(private val storingService: StoringService) {

    @POST
    @Path("/magazijn/{ab}/traag")
    fun magazijnTraag(@PathParam("ab") ab: String): Map<String, String> {
        storingService.traag(magazijnProxy(ab), LATENCY_MS)

        return mapOf("status" to "magazijn-$ab traag (${LATENCY_MS}ms)")
    }

    @POST
    @Path("/magazijn/{ab}/uit")
    fun magazijnUit(@PathParam("ab") ab: String): Map<String, String> {
        storingService.uit(magazijnProxy(ab))

        return mapOf("status" to "magazijn-$ab uit")
    }

    @POST
    @Path("/reset")
    fun reset(): Map<String, String> {
        storingService.reset()

        return mapOf("status" to "alles normaal")
    }

    private fun magazijnProxy(ab: String): String {
        if (ab != "a" && ab != "b") throw BadRequestException("magazijn moet 'a' of 'b' zijn, was: '$ab'")

        return "magazijn-$ab"
    }

    private companion object {

        const val LATENCY_MS = 6000
    }
}
```

- [ ] **Stap 3: Bouw (augmentatie wiret de rest-client)**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Stap 4: Commit**

```bash
git add services/demo-console/src/main/kotlin services/demo-console/src/main/resources/application.properties
git commit -m "feat(demo-console): storing-endpoints (magazijn traag/uit, reset)"
```

---

### Taak 4: Bedieningspaneel — sectie Storingen

**Files:**
- Wijzigen: `.../META-INF/resources/index.html`

**Interfaces:**
- Consumeert: de endpoints uit taak 3. Hergebruikt de bestaande `post()`-helper in `index.html`.

- [ ] **Stap 1: Voeg de Storingen-sectie toe**

In `index.html`, ná de "Vullen"-fieldset:

```html
  <fieldset>
    <legend>Storingen (fase 3)</legend>
    <button onclick="post('/api/demo/storing/magazijn/a/traag')">Magazijn A (RVO) traag</button>
    <button onclick="post('/api/demo/storing/magazijn/a/uit')">Magazijn A (RVO) uit</button>
    <br>
    <button onclick="post('/api/demo/storing/magazijn/b/traag')">Magazijn B (Belastingdienst) traag</button>
    <button onclick="post('/api/demo/storing/magazijn/b/uit')">Magazijn B (Belastingdienst) uit</button>
    <br>
    <button onclick="post('/api/demo/storing/reset')">Alles normaal (reset)</button>
  </fieldset>
```

- [ ] **Stap 2: Bouw en controleer de resource in de jar**

Run:
```bash
./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"
```
Expected: `BUILD SUCCESS`.

- [ ] **Stap 3: Handmatige verificatie (Docker)**

Bouw de images (incl. demo-console, `-Dquarkus.jib.platforms=linux/arm64` op Apple Silicon) en
`docker compose --profile demo up -d`. Verifieer:

```bash
curl -s localhost:8474/proxies | python3 -m json.tool | grep -E '"name"|"enabled"'
```
Expected: de 4 proxies, alle `enabled: true`.

Dan in het paneel:
- **Magazijn A traag** → in de Berichtenbox (persona J. Pietersen) **Ophalen** → RVO meldt pas
  na ~6 s "voltooid", lijst compleet.
- **Magazijn A uit** → **Ophalen** → RVO toont **FOUT** in de voortgang, lijst met alleen
  Belastingdienst; open een RVO-bericht (uit de cache) → bijlage-download faalt.
- **Alles normaal** → `curl localhost:8474/proxies` toont weer alle enabled, geen toxics;
  Ophalen werkt weer volledig.

- [ ] **Stap 4: Regressie + eindcontrole**

Run: `./mvnw -B clean verify -pl services/demo-console -am 2>&1 | tail -15`
Expected: `BUILD SUCCESS`, detekt schoon.

- [ ] **Stap 5: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources/index.html
git commit -m "feat(demo-console): bedieningspaneel-sectie Storingen (magazijn traag/uit/reset)"
```

---

## Definition of done

- [ ] Toxiproxy draait met 4 proxies; `curl :8474/proxies` toont ze
- [ ] Normaal bedrijf ongewijzigd (uitvraag loopt transparant door Toxiproxy)
- [ ] Magazijn traag → ophaal ~6 s vertraagd, lijst compleet
- [ ] Magazijn uit → FOUT-magazijn in de voortgang, partial list, bijlage-download faalt
- [ ] Reset herstelt alles
- [ ] `StoringServiceTest` groen; detekt schoon
- [ ] `./mvnw clean verify -pl services/demo-console -am` groen

## Risico's

| Risico | Signaal | Mitigatie |
|---|---|---|
| Toxiproxy-config-flag verkeerd | container stopt/geen proxies | `-host=0.0.0.0 -config=/proxies.json`; `curl :8474/proxies` na start |
| Redis-via-Toxiproxy breekt boot | uitvraag start niet | proxies staan `enabled:true` in config; Toxiproxy vóór uitvraag (`depends_on`) |
| Latency te hoog → timeout i.p.v. traag | RVO meldt TIMEOUT bij "traag" | 6000 ms < 10s query-timeout; niet verhogen |
| `@ConfigProperty`/rest-client-url niet gevonden | augmentatie faalt op `toxiproxy` | `quarkus.rest-client.toxiproxy.url` gezet; `@RegisterRestClient(configKey="toxiproxy")` |
| Toxic-naam onbekend bij reset | reset laat een toxic staan | reset leest live `GET /proxies` en verwijdert elke gevonden toxic |

## Niet in deze fase

Redis/profiel-**knoppen** (proxies staan klaar) → fase 5. Magazijn-downstreams (notificatie,
aanmeld) → fase 5. Veel-magazijnen-volume → fase 6.
