**Status:** Alle taken uitgevoerd — Docker-runtime-verificatie openstaand

> **Lokaal geverifieerd:** compose valide (demo-console → redis, uitvraag `BERICHTENSESSIECACHE_TTL=PT2M`);
> `SessieServiceTest` 2/2 groen (KEYS-patroon + DEL van treffers, en 0 bij lege keyspace — de
> MockK-vararg-matcher werkte); augmentatie wiret de Redis-client; detekt schoon; het paneel
> zit in de jar.
>
> **Nog te doen (Docker):** images rebuilden (incl. demo-console), `docker compose --profile demo up -d`,
> dan: persona Ophalen → **Cache verlopen** → `GET /berichten` 409 → Ophalen herstelt; zoeken/
> filteren werkt nog (index intact); en na ~2 min inactiviteit verloopt de sessie vanzelf. Plus
> `./mvnw clean verify -pl services/demo-console -am`.

# Demo-platform fase 4 — sessie & cache — implementatieplan

> **Voor agentic workers:** VERPLICHTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> of `superpowers:executing-plans`. Stappen gebruiken checkbox-syntax (`- [ ]`).

**Ontwerp:** `docs/plans/2026-07-23-demo-platform-fase-4-sessie-cache-design.md`

**Doel:** cache-verval demonstreerbaar maken — een instant-knop die de sessie-keys wist, plus
een korte TTL in de demo-stack voor het natuurlijke sliding-verval.

**Architectuur:** de demo-console krijgt een blocking Redis-client (rechtstreeks op
`redis:6379`, niet via Toxiproxy) die alle `berichtensessiecache:v1:*`-keys wist. De korte
TTL is een env-var op de uitvraag. Geen productie-codewijziging.

**Tech stack:** Quarkus Redis client (imperative `RedisDataSource`), Kotlin, MockK.

## Global Constraints

- **Geen productie-codewijziging.** Alleen compose/config + de wegwerp demo-console.
- **Index-veilig wissen:** alleen `DEL` op `berichtensessiecache:v1:*`; **nooit** `FLUSHDB`
  of `FT.DROPINDEX` — die zouden de RediSearch-index `berichten-idx` slopen.
- **`:status`-key is de trigger:** het wissen daarvan geeft `GET /berichten` een 409.
- **Korte TTL alleen in het demo-profiel:** `BERICHTENSESSIECACHE_TTL=PT2M`; prod houdt PT12H.
- **demo-console valt buiten de coveragegate**, maar detekt geldt.
- **Altijd `clean` vóór `test`.** **Nooit direct naar `main`.**

## Bestandsoverzicht

| Bestand | Actie |
|---|---|
| `services/demo-console/pom.xml` | `quarkus-redis-client` toevoegen |
| `.../demo-console/src/main/resources/application.properties` | `quarkus.redis.hosts` + devservices uit |
| `.../democonsole/sessie/SessieService.kt` | KEYS + DEL sessie-keys |
| `.../democonsole/sessie/SessieResource.kt` | endpoint `/api/demo/sessie/verlopen` |
| `.../democonsole/sessie/SessieServiceTest.kt` | MockK-test |
| `.../META-INF/resources/index.html` | knop "Cache verlopen" |
| `compose.yaml` | demo-console `REDIS_HOSTS` + `depends_on redis`; uitvraag `BERICHTENSESSIECACHE_TTL` |

Package-root: `nl.rijksoverheid.moz.fbs.democonsole.sessie`.

## Verificatie zonder Docker

De `SessieService`-logica (KEYS-patroon, DEL van treffers, lege-keyspace-short-circuit) is met
MockK lokaal testbaar. Augmentatie valideert de Redis-client-wiring. Het echte verval is
handmatig tegen de draaiende stack.

---

### Taak 1: Redis-client-dependency, config en compose

**Files:**
- Wijzigen: `services/demo-console/pom.xml`
- Wijzigen: `.../demo-console/src/main/resources/application.properties`
- Wijzigen: `compose.yaml`

**Interfaces:**
- Produceert: een injecteerbare `RedisDataSource` in de demo-console (verbonden met Redis),
  en een korte TTL op de uitvraag in het demo-profiel.

- [ ] **Stap 1: Voeg de Redis-client toe aan de module-POM**

In `services/demo-console/pom.xml`, bij de andere Quarkus-deps:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-redis-client</artifactId>
        </dependency>
```

- [ ] **Stap 2: Config voor de Redis-verbinding**

Voeg toe aan `application.properties`:

```properties
# Redis-verbinding voor de cache-verval-knop (fase 4). Rechtstreeks op Redis (niet via
# Toxiproxy — het is een beheeractie). Geen Dev Services: de demo verbindt met de compose-Redis.
quarkus.redis.hosts=${REDIS_HOSTS:redis://localhost:6379}
quarkus.redis.devservices.enabled=false
```

- [ ] **Stap 3: Compose — demo-console naar Redis, uitvraag korte TTL**

In `compose.yaml`, bij `demo-console`:
- voeg aan `environment` toe: `REDIS_HOSTS: redis://redis:6379`
- voeg aan `depends_on` toe:
  ```yaml
      redis:
        condition: service_healthy
  ```

Bij `berichtenuitvraag`, voeg aan `environment` toe:

```yaml
      # Korte cache-TTL voor de demo (sliding verval na ~2 min inactiviteit). Alleen demo;
      # prod houdt PT12H. De property heeft geen min-validatie.
      BERICHTENSESSIECACHE_TTL: PT2M
```

- [ ] **Stap 4: Valideer compose en bouw**

Run:
```bash
python3 -c "import yaml; c=yaml.safe_load(open('compose.yaml')); s=c['services']; assert s['demo-console']['environment']['REDIS_HOSTS']=='redis://redis:6379'; assert s['berichtenuitvraag']['environment']['BERICHTENSESSIECACHE_TTL']=='PT2M'; assert 'redis' in s['demo-console']['depends_on']; print('compose ok')"
./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD SUCCESS|BUILD FAILURE" | tail -1
```
Expected: `compose ok` en `BUILD SUCCESS` (augmentatie wiret de Redis-client).

- [ ] **Stap 5: Commit**

```bash
git add services/demo-console/pom.xml services/demo-console/src/main/resources/application.properties compose.yaml
git commit -m "build(demo-console): Redis-client + korte cache-TTL in de demo-stack

Redis-client (direct op redis:6379) voor de cache-verval-knop; uitvraag krijgt
BERICHTENSESSIECACHE_TTL=PT2M in het demo-profiel (prod houdt PT12H)."
```

---

### Taak 2: SessieService + unittest

**Files:**
- Aanmaken: `.../democonsole/sessie/SessieService.kt`
- Test: `.../democonsole/sessie/SessieServiceTest.kt`

**Interfaces:**
- Consumeert: `io.quarkus.redis.datasource.RedisDataSource`.
- Produceert: `class SessieService` met `fun laatSessiesVerlopen(): Int` (aantal gewiste keys).

- [ ] **Stap 1: Schrijf de service**

`SessieService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped

/**
 * Laat de sessiecache "verlopen" door alle sessie-keys te wissen. Het verlies van de
 * `:status`-key doet de uitvraag denken dat er geen actieve sessie is → GET /berichten geeft
 * 409. Bewust alleen DEL op de sessie-prefix; nooit FLUSHDB/FT.DROPINDEX (die slopen de index).
 */
@ApplicationScoped
class SessieService(private val redis: RedisDataSource) {

    fun laatSessiesVerlopen(): Int {
        val keys = redis.key().keys(SESSIE_PATROON)

        return if (keys.isEmpty()) 0 else redis.key().del(*keys.toTypedArray())
    }

    private companion object {

        const val SESSIE_PATROON = "berichtensessiecache:v1:*"
    }
}
```

- [ ] **Stap 2: Schrijf de test**

`SessieServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.keys.KeyCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessieServiceTest {

    private val keyCommands = mockk<KeyCommands<String>>()
    private val redis = mockk<RedisDataSource> { every { key() } returns keyCommands }
    private val service = SessieService(redis)

    @Test
    fun `verlopen wist de gevonden sessie-keys en geeft het aantal terug`() {
        val gevonden = listOf("berichtensessiecache:v1:abc:status", "berichtensessiecache:v1:abc:list")

        every { keyCommands.keys("berichtensessiecache:v1:*") } returns gevonden
        every { keyCommands.del("berichtensessiecache:v1:abc:status", "berichtensessiecache:v1:abc:list") } returns 2

        assertEquals(2, service.laatSessiesVerlopen())

        verify { keyCommands.del("berichtensessiecache:v1:abc:status", "berichtensessiecache:v1:abc:list") }
    }

    @Test
    fun `verlopen bij lege keyspace geeft 0 en roept del niet aan`() {
        every { keyCommands.keys(any()) } returns emptyList()

        assertEquals(0, service.laatSessiesVerlopen())

        verify(exactly = 0) { keyCommands.del(*anyVararg<String>()) }
    }
}
```

- [ ] **Stap 3: Draai test + detekt**

Run: `./mvnw -B clean test detekt:check -pl services/demo-console -am`
Expected: `BUILD SUCCESS`, `SessieServiceTest` groen, detekt 0 bevindingen.

Faalt de MockK-vararg-matcher (`*anyVararg<String>()`) op de `del`-verificatie, vervang die
`verify` door een controle op de return-waarde alleen (de service roept `del` sowieso niet aan
bij een lege lijst — de `assertEquals(0, ...)` bewijst dat al).

- [ ] **Stap 4: Commit**

```bash
git add services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/sessie/SessieService.kt services/demo-console/src/test
git commit -m "feat(demo-console): SessieService — sessie-keys wissen (cache verlopen)

KEYS berichtensessiecache:v1:* + DEL; index blijft heel. Unit-getest met MockK op
de wis-logica en de lege-keyspace-short-circuit."
```

---

### Taak 3: Verval-endpoint

**Files:**
- Aanmaken: `.../democonsole/sessie/SessieResource.kt`

**Interfaces:**
- Consumeert: `SessieService` (taak 2).
- Produceert: `POST /api/demo/sessie/verlopen`.

- [ ] **Stap 1: Schrijf de resource**

`SessieResource.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.sessie

import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/sessie")
@Produces(MediaType.APPLICATION_JSON)
class SessieResource(private val sessieService: SessieService) {

    @POST
    @Path("/verlopen")
    fun verlopen(): Map<String, Int> = mapOf("gewisteKeys" to sessieService.laatSessiesVerlopen())
}
```

- [ ] **Stap 2: Bouw**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Stap 3: Commit**

```bash
git add services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/sessie/SessieResource.kt
git commit -m "feat(demo-console): endpoint POST /api/demo/sessie/verlopen"
```

---

### Taak 4: Bedieningspaneel — knop Cache verlopen

**Files:**
- Wijzigen: `.../META-INF/resources/index.html`

**Interfaces:** consumeert het endpoint uit taak 3; hergebruikt de bestaande `post()`-helper.

- [ ] **Stap 1: Voeg de knop toe aan de sectie Beheer**

In `index.html`, in de `<fieldset>` met `<legend>Beheer</legend>`, ná de bestaande knoppen:

```html
    <button onclick="post('/api/demo/sessie/verlopen')">Cache verlopen (sessies wissen)</button>
```

- [ ] **Stap 2: Bouw en controleer de resource in de jar**

Run:
```bash
./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"
unzip -l services/demo-console/target/demo-console-0.1.0-SNAPSHOT.jar 2>/dev/null | grep -c "META-INF/resources/index.html"
```
Expected: `BUILD SUCCESS` en `1`.

- [ ] **Stap 3: Handmatige verificatie (Docker)**

Bouw de images (incl. demo-console, `-Dquarkus.jib.platforms=linux/arm64` op Apple Silicon) en
`docker compose --profile demo up -d`. Dan:

- Persona J. Pietersen → **Ophalen** → lijst zichtbaar.
- Paneel → **Cache verlopen** → antwoord `{"gewisteKeys": N}` (N > 0).
- Berichtenbox → **Vernieuw** (of een bericht openen) → 409-melding "Nog niet opgehaald".
- **Ophalen** → lijst terug, en zoeken/filteren werkt nog (index niet gedropt).
- Korte TTL: na ~2 min zonder actie verloopt de sessie vanzelf (volgende actie → 409).

- [ ] **Stap 4: Regressie + eindcontrole**

Run: `./mvnw -B clean verify -pl services/demo-console -am 2>&1 | tail -15`
Expected: `BUILD SUCCESS`, detekt schoon.

- [ ] **Stap 5: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources/index.html
git commit -m "feat(demo-console): bedieningspaneel-knop Cache verlopen"
```

---

## Definition of done

- [ ] demo-console verbindt met Redis; augmentatie groen
- [ ] `SessieServiceTest` groen; detekt schoon
- [ ] "Cache verlopen" → `GET /berichten` geeft 409; Ophalen herstelt
- [ ] Zoeken/filteren werkt na verval + opnieuw ophalen (index intact)
- [ ] Korte TTL: sessie verloopt na ~2 min inactiviteit
- [ ] `./mvnw clean verify -pl services/demo-console -am` groen

## Risico's

| Risico | Signaal | Mitigatie |
|---|---|---|
| Redis-health-check faalt de boot | demo-console `/q/health/ready` DOWN | `depends_on redis: service_healthy`; Redis draait in de stack |
| Verkeerde key-prefix → niets gewist | `gewisteKeys` = 0 terwijl er een sessie is | Prefix `berichtensessiecache:v1:*` uit de verkenning; controleer met `redis-cli KEYS` |
| Per ongeluk index droppen | zoeken faalt na verval | Alleen `DEL`; nooit `FLUSHDB`/`FT.DROPINDEX` |
| MockK-vararg-matcher | test compileert/faalt op `del(*anyVararg())` | Fallback: verify weglaten, op return-waarde asserten (taak 2, stap 3) |
| Korte TTL stoort tijdens demo | sessie verloopt onverwacht bij een pauze | `BERICHTENSESSIECACHE_TTL` is een env-var; verhoog desgewenst |

## Niet in deze fase

Per-persona verval (SHA-256-hash) — niet nodig bij één demo-persona. Wijziging aan de
prod-TTL-default. Flow 5 (al functioneel).
