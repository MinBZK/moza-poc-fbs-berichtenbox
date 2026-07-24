**Status:** Alle taken uitgevoerd — Docker-runtime-verificatie openstaand

> **Lokaal geverifieerd:** `genereer-magazijnen.py` produceert consistente files (register-OIN's ==
> profiel-scopes == mapping-afzenders, 20-cijferig); `demo/generated/` is gitignored; demo-console 32
> tests groen incl. `VeelMagazijnenServiceTest` 5/5; augmentatie wiret de `magazijnstubs`-client;
> compose valide met `magazijn-stubs`-service, uitvraag-config-mount, profiel-mount en demo-console-env;
> detekt schoon (na fix van `SwallowedException` in de resource).
>
> **Nog te doen (Docker):** `DEMO_MAGAZIJN_STUBS=12 python3 demo/genereer-magazijnen.py` →
> `docker compose --profile demo up -d` (images incl. demo-console rebuilden, `-Dquarkus.jib.platforms=linux/arm64`
> op Apple Silicon). Dan de "Definition of done": login als Grootbedrijf → Ophalen toont n; "actief=2" →
> n−2 FOUT + partieel; reset → alles OK; test n=2/10/25. **Bevestig de `SMALLRYE_CONFIG_LOCATIONS`-mount**
> (enige onzekere plek; fallback in Risico's).

# Demo-platform fase 6 — veel magazijnen — implementatieplan

> **Voor agentic workers:** VERPLICHTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> of `superpowers:executing-plans`. Stappen gebruiken checkbox-syntax (`- [ ]`).

**Ontwerp:** `docs/plans/2026-07-24-demo-platform-fase-6-veel-magazijnen-design.md`

**Doel:** scenario 3 ("magazijnen onbereikbaar — weinig/veel") demonstreerbaar maken: een variabel
aantal WireMock-stub-magazijnen waar de uitvraag naar fan-out, met een live schuif voor het actieve
aantal — zonder codewijziging in de uitvraag of het magazijnregister.

**Architectuur:** een generatie-script bouwt uit één getal `n` de register-regels, de profiel-stub
en n pad-gebaseerde WireMock-mappings (in het gitignore'd `demo/generated/`). De uitvraag leest de
register-regels via een gemount config-bestand (`SMALLRYE_CONFIG_LOCATIONS`). De demo-console zet
stubs live op storing via de WireMock-admin-API (503-overlay per stub).

**Tech stack:** Python (generatie), WireMock 3.13, Docker Compose, Quarkus REST client, Kotlin, MockK.

## Global Constraints

- **Geen productie-codewijziging.** Alleen het generatie-script, compose/config + de wegwerp demo-console.
- **demo-console valt buiten de coveragegate**, maar detekt geldt (`maxIssues: 0`, schoon houden).
- **Kotlin-stijl:** lege regels rond multi-line blokken en zelfstandige control-statements (CLAUDE.md).
- **Altijd `clean` vóór `test`.** **Nooit direct naar `main`.**
- **OIN-patroon:** `0000000900000000NNNN` (20 cijfers, géén elfproef, `NNNN` = `%04d` van 1..n). Distinct van de echte OIN's `00000001…`.
- **Persona:** Grootbedrijf B.V., KVK `90000001`, `X-Ontvanger: KVK:90000001`.
- Package-root demo-console: `nl.rijksoverheid.moz.fbs.democonsole`.

## Bestandsoverzicht

| Bestand | Verantwoordelijkheid | Actie |
|---|---|---|
| `demo/genereer-magazijnen.py` | genereert register-props + profiel-stub + n mappings | Aanmaken |
| `.gitignore` | negeer `demo/generated/` | Wijzigen |
| `.../veelmagazijnen/WireMockAdminClient.kt` | admin-API-client + stub-DTO's | Aanmaken |
| `.../veelmagazijnen/VeelMagazijnenService.kt` | actief/reset-orkestratie | Aanmaken |
| `.../veelmagazijnen/VeelMagazijnenResource.kt` | endpoints `/api/demo/veel-magazijnen/*` | Aanmaken |
| `.../veelmagazijnen/VeelMagazijnenServiceTest.kt` | overlay-orkestratie unittest | Aanmaken |
| `.../demo-console/src/main/resources/application.properties` | rest-client + `aantal`-config | Wijzigen |
| `.../META-INF/resources/berichtenbox.html` | persona Grootbedrijf | Wijzigen |
| `.../META-INF/resources/index.html` | sectie "Veel magazijnen" | Wijzigen |
| `compose.yaml` | magazijn-stubs-service, uitvraag-config-mount, profiel-mount, demo-console-env | Wijzigen |

## Verificatie zonder Docker

Het script produceert lokaal valide files (JSON parse; register-OIN's == profiel-scope-OIN's ==
mapping-magazijnId's; 20-cijferig, distinct). `VeelMagazijnenServiceTest` (MockK) dekt de overlay-
orkestratie. Augmentatie wiret de `magazijnstubs`-rest-client. Het echte fan-out-/storing-effect is
handmatig tegen de Docker-stack.

---

### Taak 1: Generatie-script

**Files:**
- Create: `demo/genereer-magazijnen.py`
- Modify: `.gitignore`

**Interfaces:**
- Produceert (in `demo/generated/`): `magazijnen-stubs.properties`, `profiel/grootbedrijf-kvk.json`,
  `magazijn-stubs-mappings/mNN.json` × n. OIN(i)=`0000000900000000%04d`, pad(i)=`/m%02d`.

- [ ] **Stap 1: Schrijf het script**

`demo/genereer-magazijnen.py`:

```python
#!/usr/bin/env python3
"""Genereert de artefacten voor 'veel magazijnen' (fase 6) uit één getal n:
register-regels voor de uitvraag, de profiel-stub voor persona Grootbedrijf, en n
pad-gebaseerde WireMock-mappings. Schrijft naar demo/generated/ (staat in .gitignore).

Draai dit VÓÓR `docker compose --profile demo up`:  DEMO_MAGAZIJN_STUBS=12 python3 demo/genereer-magazijnen.py
"""
import json
import os
import sys
from pathlib import Path

KVK_GROOTBEDRIJF = "90000001"
BASIS = Path(__file__).resolve().parent / "generated"


def oin(i: int) -> str:
    return f"0000000900000000{i:04d}"


def pad(i: int) -> str:
    return f"/m{i:02d}"


def bericht(i: int) -> dict:
    o = oin(i)
    return {
        "berichtId": f"00000000-0000-0000-0000-{i:012d}",
        "afzender": o,
        "ontvanger": {"type": "KVK", "waarde": KVK_GROOTBEDRIJF},
        "onderwerp": f"Stub-magazijn {i} — mededeling",
        "inhoud": f"Demo-bericht uit stub-magazijn {i} (OIN {o}).",
        "publicatietijdstip": f"2026-07-{(i % 27) + 1:02d}T09:00:00Z",
    }


def mapping(i: int) -> dict:
    return {
        "priority": 5,
        "request": {
            "method": "GET",
            "urlPathPattern": f"{pad(i)}/api/v1/berichten",
            "headers": {"X-Ontvanger": {"matches": ".+"}},
        },
        "response": {
            "status": 200,
            "headers": {"Content-Type": "application/json"},
            "jsonBody": {"berichten": [bericht(i)]},
        },
    }


def profiel(n: int) -> dict:
    scopes = [{"partij": {"identificatieType": "OIN", "identificatieNummer": oin(i)}} for i in range(1, n + 1)]
    return {
        "priority": 1,
        "request": {"method": "GET", "urlPathPattern": f"/api/profielservice/v1/KVK/{KVK_GROOTBEDRIJF}"},
        "response": {
            "status": 200,
            "headers": {"Content-Type": "application/json"},
            "jsonBody": {
                "partijId": 900,
                "identificaties": [],
                "voorkeuren": [{"id": 1, "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true", "scopes": scopes}],
                "contactgegevens": [],
            },
        },
    }


def main() -> None:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else int(os.environ.get("DEMO_MAGAZIJN_STUBS", "12"))

    if n < 1:
        raise SystemExit(f"n moet >= 1 zijn, was {n}")

    mappings_dir = BASIS / "magazijn-stubs-mappings"
    profiel_dir = BASIS / "profiel"
    mappings_dir.mkdir(parents=True, exist_ok=True)
    profiel_dir.mkdir(parents=True, exist_ok=True)

    # Oude mappings opruimen zodat een kleiner n geen stubs van een vorige run laat staan.
    for oud in mappings_dir.glob("m*.json"):
        oud.unlink()

    regels = []

    for i in range(1, n + 1):
        (mappings_dir / f"m{i:02d}.json").write_text(json.dumps(mapping(i), indent=2))
        regels.append(f'magazijnen."{oin(i)}".url=http://magazijn-stubs:8080{pad(i)}')
        regels.append(f'magazijnen."{oin(i)}".naam=Stub-magazijn {i}')

    (BASIS / "magazijnen-stubs.properties").write_text("\n".join(regels) + "\n")
    (profiel_dir / "grootbedrijf-kvk.json").write_text(json.dumps(profiel(n), indent=2))

    print(f"Gegenereerd: {n} stub-magazijnen in {BASIS}")


if __name__ == "__main__":
    main()
```

- [ ] **Stap 2: Draai het script en controleer de output (self-check)**

Run:
```bash
DEMO_MAGAZIJN_STUBS=5 python3 demo/genereer-magazijnen.py && python3 - <<'PY'
import json, re, glob
from pathlib import Path
b = Path("demo/generated")
props = (b / "magazijnen-stubs.properties").read_text()
oins_reg = sorted(re.findall(r'magazijnen\."(\d{20})"\.url', props))
assert len(oins_reg) == 5, oins_reg
assert all(re.fullmatch(r"\d{20}", o) and set(o) != {"0"} for o in oins_reg)
prof = json.loads((b / "profiel/grootbedrijf-kvk.json").read_text())
oins_prof = sorted(s["partij"]["identificatieNummer"] for s in prof["response"]["jsonBody"]["voorkeuren"][0]["scopes"])
files = glob.glob("demo/generated/magazijn-stubs-mappings/m*.json")
afzenders = sorted(json.loads(Path(f).read_text())["response"]["jsonBody"]["berichten"][0]["afzender"] for f in files)
assert oins_reg == oins_prof, (oins_reg, oins_prof)
assert oins_reg == afzenders, (oins_reg, afzenders)
assert len(files) == 5, files
print("self-check ok:", oins_reg)
PY
```
Expected: `self-check ok: ['00000009000000000001', …]` — register-OIN's == profiel-scope-OIN's == mapping-afzenders, alle 5 present.

- [ ] **Stap 3: Gitignore de gegenereerde output**

Voeg toe aan `.gitignore` (maak het bestand aan als het nog niet bestaat):

```gitignore
# Gegenereerde demo-artefacten (fase 6 — veel magazijnen). Regenereren met demo/genereer-magazijnen.py.
demo/generated/
```

- [ ] **Stap 4: Commit**

```bash
git add demo/genereer-magazijnen.py .gitignore
git commit -m "feat(demo): generatie-script voor n stub-magazijnen (fase 6)

Uit één getal n: register-regels, profiel-stub (persona Grootbedrijf) en n
pad-gebaseerde WireMock-mappings in demo/generated/ (gitignored). OIN-patroon
0000000900000000NNNN (20 cijfers, geen elfproef)."
```

---

### Taak 2: WireMock-admin-client + VeelMagazijnenService

De lokaal-testbare kern: een client tegen de WireMock-admin-API en een service die per stub een
503-overlay plaatst/verwijdert.

**Files:**
- Create: `.../veelmagazijnen/WireMockAdminClient.kt`
- Create: `.../veelmagazijnen/VeelMagazijnenService.kt`
- Modify: `.../demo-console/src/main/resources/application.properties`
- Test: `.../veelmagazijnen/VeelMagazijnenServiceTest.kt`

**Interfaces:**
- Produceert:
  - `WireMockAdminClient` (`zetOverlay(id, WireMockStub)`, `verwijderOverlay(id)`, `herlaad()`).
  - `VeelMagazijnenService.zetActief(k): Map<String,Int>`, `.reset(): Map<String,Int>`; companion
    `overlayId(i): String`, `stubPad(i): String`.
  - `data class WireMockStub(id, priority, request, response)` + `WireMockRequest`, `WireMockResponse`.

- [ ] **Stap 1: Schrijf de falende test**

`services/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/veelmagazijnen/VeelMagazijnenServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VeelMagazijnenServiceTest {

    private val wiremock = mockk<WireMockAdminClient>(relaxed = false)
    private val service = VeelMagazijnenService(wiremock, aantal = 5)

    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    @Test
    fun `zetActief laat 1 tot k actief en zet k+1 tot n op storing`() {
        every { wiremock.verwijderOverlay(any()) } returns respons(200)
        every { wiremock.zetOverlay(any(), any()) } returns respons(201)

        service.zetActief(3)

        // 1..3 actief → overlay verwijderd
        verify { wiremock.verwijderOverlay(VeelMagazijnenService.overlayId(1)) }
        verify { wiremock.verwijderOverlay(VeelMagazijnenService.overlayId(3)) }
        // 4..5 op storing → 503-overlay op het juiste pad
        verify {
            wiremock.zetOverlay(
                VeelMagazijnenService.overlayId(4),
                match { it.response.status == 503 && it.request.urlPathPattern == VeelMagazijnenService.stubPad(4) },
            )
        }
        verify { wiremock.zetOverlay(VeelMagazijnenService.overlayId(5), any()) }
        verify(exactly = 0) { wiremock.zetOverlay(VeelMagazijnenService.overlayId(3), any()) }
    }

    @Test
    fun `zetActief 0 zet alles op storing`() {
        every { wiremock.zetOverlay(any(), any()) } returns respons(201)

        service.zetActief(0)

        verify(exactly = 5) { wiremock.zetOverlay(any(), any()) }
        verify(exactly = 0) { wiremock.verwijderOverlay(any()) }
    }

    @Test
    fun `zetActief n laat alles actief`() {
        every { wiremock.verwijderOverlay(any()) } returns respons(200)

        service.zetActief(5)

        verify(exactly = 5) { wiremock.verwijderOverlay(any()) }
        verify(exactly = 0) { wiremock.zetOverlay(any(), any()) }
    }

    @Test
    fun `zetActief buiten 0 tot n faalt`() {
        assertThrows(IllegalArgumentException::class.java) { service.zetActief(6) }
        assertThrows(IllegalArgumentException::class.java) { service.zetActief(-1) }
    }

    @Test
    fun `reset herlaadt de mappings van schijf`() {
        every { wiremock.herlaad() } returns respons(200)

        service.reset()

        verify { wiremock.herlaad() }
    }
}
```

- [ ] **Stap 2: Draai de test — verwacht falen**

Run: `./mvnw -B clean test -pl services/demo-console -am -Dtest=VeelMagazijnenServiceTest`
Expected: FAIL — de klassen bestaan nog niet.

- [ ] **Stap 3: Schrijf de admin-client + DTO's**

`WireMockAdminClient.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

data class WireMockRequest(val method: String, val urlPathPattern: String)

data class WireMockResponse(val status: Int)

/** Minimale WireMock-stubmapping: een 503-overlay met vaste id en hoge prioriteit (laag getal wint). */
data class WireMockStub(
    val id: String,
    val priority: Int,
    val request: WireMockRequest,
    val response: WireMockResponse,
)

/**
 * Client voor de WireMock-admin-API van de stub-magazijnen. `zetOverlay` plaatst per stub een
 * 503-mapping met vaste id (upsert via PUT); `verwijderOverlay` haalt hem weg; `herlaad` reset naar
 * de mappings van schijf (alles weer actief).
 */
@Path("/__admin/mappings")
@RegisterRestClient(configKey = "magazijnstubs")
interface WireMockAdminClient {

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun zetOverlay(@PathParam("id") id: String, stub: WireMockStub): Response

    @DELETE
    @Path("/{id}")
    fun verwijderOverlay(@PathParam("id") id: String): Response

    @POST
    @Path("/reset")
    fun herlaad(): Response
}
```

- [ ] **Stap 4: Schrijf de service**

`VeelMagazijnenService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Zet het live actieve aantal stub-magazijnen. Magazijnen 1..k blijven actief (base-mapping,
 * priority 5); k+1..n krijgen een 503-overlay met vaste id (priority 1 wint). Stateless: de id en
 * het pad zijn deterministisch uit de index, dus toggles hoeven geen mapping-id's bij te houden.
 */
@ApplicationScoped
class VeelMagazijnenService(
    @param:RestClient private val wiremock: WireMockAdminClient,
    @param:ConfigProperty(name = "demo.veel-magazijnen.aantal") private val aantal: Int,
) {

    fun zetActief(k: Int): Map<String, Int> {
        require(k in 0..aantal) { "k moet tussen 0 en $aantal liggen, was $k" }

        for (i in 1..aantal) {

            if (i <= k) {
                verwijderStoring(i)
            } else {
                plaatsStoring(i)
            }
        }

        return mapOf("actief" to k, "totaal" to aantal)
    }

    fun reset(): Map<String, Int> {
        controleer(wiremock.herlaad(), "herladen mappings")

        return mapOf("actief" to aantal, "totaal" to aantal)
    }

    // Idempotent: een 404 betekent dat er geen overlay stond — dat is precies "actief".
    private fun verwijderStoring(i: Int) {
        wiremock.verwijderOverlay(overlayId(i)).close()
    }

    private fun plaatsStoring(i: Int) {
        val stub = WireMockStub(overlayId(i), STORING_PRIORITEIT, WireMockRequest("GET", stubPad(i)), WireMockResponse(503))

        controleer(wiremock.zetOverlay(overlayId(i), stub), "storing zetten op magazijn $i")
    }

    private fun controleer(response: Response, actie: String) {
        response.use {
            check(it.status in 200..299) { "WireMock-fout bij $actie: HTTP ${it.status}" }
        }
    }

    companion object {

        const val STORING_PRIORITEIT = 1

        fun overlayId(i: Int): String = "11111111-0000-0000-0000-%012d".format(i)

        fun stubPad(i: Int): String = "/m%02d/api/v1/berichten".format(i)
    }
}
```

- [ ] **Stap 5: Config voor de admin-URL + het aantal**

Voeg toe aan `services/demo-console/src/main/resources/application.properties` (bij de andere
rest-client-config):

```properties
# WireMock-admin van de stub-magazijnen (fase 6). In de demo-compose zet MAGAZIJN_STUBS_ADMIN_URL
# de container-DNS; buiten containers de default.
quarkus.rest-client.magazijnstubs.url=${MAGAZIJN_STUBS_ADMIN_URL:http://localhost:8092}

# Ingericht aantal stub-magazijnen (moet gelijk zijn aan het n waarmee demo/genereer-magazijnen.py
# draaide). Bepaalt tot welke index de 'k van n actief'-knop schakelt.
demo.veel-magazijnen.aantal=${DEMO_MAGAZIJN_STUBS:12}
```

- [ ] **Stap 6: Draai de test — verwacht slagen + detekt**

Run: `./mvnw -B clean test detekt:check -pl services/demo-console -am -Dtest=VeelMagazijnenServiceTest`
Expected: `BUILD SUCCESS`, alle `VeelMagazijnenServiceTest`-tests groen, detekt 0 bevindingen.

- [ ] **Stap 7: Commit**

```bash
git add services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/veelmagazijnen services/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/veelmagazijnen services/demo-console/src/main/resources/application.properties
git commit -m "feat(demo-console): VeelMagazijnenService — 'k van n actief' via WireMock-admin

Client + service die per stub-magazijn een 503-overlay (vaste id, priority 1)
plaatst/verwijdert; reset herlaadt de mappings van schijf. Unit-getest met MockK."
```

---

### Taak 3: Endpoints + persona + paneel

**Files:**
- Create: `.../veelmagazijnen/VeelMagazijnenResource.kt`
- Modify: `.../META-INF/resources/berichtenbox.html` (persona)
- Modify: `.../META-INF/resources/index.html` (paneelsectie)

**Interfaces:**
- Consumeert: `VeelMagazijnenService` (taak 2).
- Produceert: `POST /api/demo/veel-magazijnen/actief/{k}`, `POST /api/demo/veel-magazijnen/reset`.

- [ ] **Stap 1: Schrijf de resource**

`VeelMagazijnenResource.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/veel-magazijnen")
@Produces(MediaType.APPLICATION_JSON)
class VeelMagazijnenResource(private val service: VeelMagazijnenService) {

    @POST
    @Path("/actief/{k}")
    fun actief(@PathParam("k") k: Int): Map<String, Int> =
        try {
            service.zetActief(k)
        } catch (fout: IllegalArgumentException) {
            throw BadRequestException(fout.message)
        }

    @POST
    @Path("/reset")
    fun reset(): Map<String, Int> = service.reset()
}
```

- [ ] **Stap 2: Voeg de persona toe aan de Berichtenbox**

In `berichtenbox.html`, in de `<select id="persona">`, ná de Garage-optie:

```html
          <option value="KVK:90000001">Grootbedrijf B.V. (veel magazijnen)</option>
```

- [ ] **Stap 3: Voeg de paneelsectie toe**

In `index.html`, ná de "Technische scenario's (fase 5)"-fieldset:

```html
  <fieldset>
    <legend>Veel magazijnen (fase 6)</legend>
    <p><small>Persona <em>Grootbedrijf B.V.</em> in de Berichtenbox haalt op bij alle stub-magazijnen.
      Het ingerichte aantal n ligt vast bij genereren/opstarten (<code>DEMO_MAGAZIJN_STUBS</code>).</small></p>
    <label>Actief aantal: <input id="actiefAantal" type="number" value="2" min="0"></label>
    <button onclick="post('/api/demo/veel-magazijnen/actief/' + document.getElementById('actiefAantal').value)">Zet actief</button>
    <button onclick="post('/api/demo/veel-magazijnen/reset')">Alle magazijnen aan (reset)</button>
  </fieldset>
```

- [ ] **Stap 4: Bouw (augmentatie wiret de rest-client)**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD|augmentation|ERROR"`
Expected: `BUILD SUCCESS`, augmentatie voltooid (config `quarkus.rest-client.magazijnstubs.url` aanwezig).

- [ ] **Stap 5: Commit**

```bash
git add services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/veelmagazijnen/VeelMagazijnenResource.kt services/demo-console/src/main/resources/META-INF/resources/berichtenbox.html services/demo-console/src/main/resources/META-INF/resources/index.html
git commit -m "feat(demo-console): veel-magazijnen-endpoints + persona Grootbedrijf + paneelsectie"
```

---

### Taak 4: Compose-integratie

**Files:**
- Modify: `compose.yaml`

**Interfaces:**
- Produceert: `magazijn-stubs`-WireMock op `:8092` (mappings uit `demo/generated/`); uitvraag leest de
  gegenereerde register-regels; profiel-service serveert de gegenereerde Grootbedrijf-stub; demo-console
  kent het admin-adres + `n`.

- [ ] **Stap 1: Voeg de magazijn-stubs-service toe**

In `compose.yaml`, bij de andere demo-services (`profiles: [demo]`):

```yaml
  # Lichtgewicht stub-magazijnen voor 'veel magazijnen' (fase 6). Eén WireMock, n pad-gebaseerde
  # magazijnen (/mNN) uit demo/generated/ — draai eerst demo/genereer-magazijnen.py. De demo-console
  # zet stubs live op storing via de admin-API (:8092/__admin/mappings).
  magazijn-stubs:
    image: wiremock/wiremock:3.13.2
    profiles: [demo]
    ports:
      - "8092:8080"
    volumes:
      - ./demo/generated/magazijn-stubs-mappings:/home/wiremock/mappings
    command: ["--verbose"]
```

- [ ] **Stap 2: Laat de uitvraag de gegenereerde register-regels lezen**

In de `berichtenuitvraag`-service: voeg een `volumes`-blok toe (dat er nog niet is) en de env-regel.
Onder `environment`, ná `BERICHTENSESSIECACHE_TTL`:

```yaml
      # Extra register-entries voor de stub-magazijnen (fase 6), gegenereerd in demo/generated/.
      # SmallRye leest dit absolute pad als additionele config-bron en merge't het bij de baked A/B.
      SMALLRYE_CONFIG_LOCATIONS: /demo-config/magazijnen-stubs.properties
```

en direct ná het `environment`-blok van `berichtenuitvraag`:

```yaml
    volumes:
      - ./demo/generated/magazijnen-stubs.properties:/demo-config/magazijnen-stubs.properties:ro
```

- [ ] **Stap 3: Mount de gegenereerde profiel-stub bij de profiel-service**

In de `profiel-service`-service, aan de `volumes`-lijst toevoegen:

```yaml
      # Gegenereerde Grootbedrijf-persona-stub (fase 6). WireMock laadt recursief.
      - ./demo/generated/profiel:/home/wiremock/mappings/demo-profiel-generated
```

- [ ] **Stap 4: Geef demo-console het admin-adres + het aantal**

In de `demo-console`-`environment`, ná `MAGAZIJN_B_DB_URL` (of bij de andere env):

```yaml
      MAGAZIJN_STUBS_ADMIN_URL: http://magazijn-stubs:8080
      # Moet gelijk zijn aan het n waarmee demo/genereer-magazijnen.py draaide.
      DEMO_MAGAZIJN_STUBS: "12"
```

- [ ] **Stap 5: Valideer compose (na genereren, zodat de mounts bestaan)**

Run:
```bash
DEMO_MAGAZIJN_STUBS=12 python3 demo/genereer-magazijnen.py
python3 -c "import yaml; c=yaml.safe_load(open('compose.yaml')); s=c['services']; assert 'magazijn-stubs' in s; u=s['berichtenuitvraag']; assert u['environment']['SMALLRYE_CONFIG_LOCATIONS']=='/demo-config/magazijnen-stubs.properties'; assert any('magazijnen-stubs.properties' in v for v in u['volumes']); assert any('demo-profiel-generated' in v for v in s['profiel-service']['volumes']); assert s['demo-console']['environment']['MAGAZIJN_STUBS_ADMIN_URL']=='http://magazijn-stubs:8080'; print('compose ok')"
```
Expected: `Gegenereerd: 12 stub-magazijnen …` en `compose ok`.

- [ ] **Stap 6: Volledige regressie demo-console**

Run: `./mvnw -B clean verify detekt:check -pl services/demo-console -am 2>&1 | tail -15`
Expected: `BUILD SUCCESS`, alle demo-console-tests groen, detekt schoon.

- [ ] **Stap 7: Commit**

```bash
git add compose.yaml
git commit -m "build(demo): magazijn-stubs-container + uitvraag-register-mount (fase 6)

Nieuwe WireMock-container serveert de gegenereerde stub-magazijnen; de uitvraag leest de
register-regels via SMALLRYE_CONFIG_LOCATIONS; profiel-service mount de Grootbedrijf-stub;
demo-console kent het admin-adres + n. Draai demo/genereer-magazijnen.py vóór 'compose up'."
```

---

## Definition of done

- [ ] `demo/genereer-magazijnen.py n` produceert valide, consistente files (register == profiel == mappings)
- [ ] `VeelMagazijnenServiceTest` groen; detekt schoon; augmentatie wiret de `magazijnstubs`-client
- [ ] Compose valide met de nieuwe service, mounts en env
- [ ] (Docker) Login als Grootbedrijf → Ophalen → n substreams, alle OK, lijst met n berichten
- [ ] (Docker) "Actief = 2" → n−2 magazijnen FOUT + partiële lijst; reset → alles OK
- [ ] (Docker) Test met n=2, 10 en 25 bevestigt variabiliteit
- [ ] `./mvnw clean verify -pl services/demo-console -am` groen

## Risico's

| Risico | Signaal | Mitigatie |
|---|---|---|
| `SMALLRYE_CONFIG_LOCATIONS`-mount grijpt niet | uitvraag kent de stub-OIN's niet → fan-out toont alleen A/B | Enige onzekere plek; bevestig op Docker. Fallback: gegenereerde regels committen in `%dev`-properties. |
| `demo/generated/` ontbreekt bij `up` | lege mounts, geen stubs/register | Script is de gedocumenteerde eerste stap; compose-comment + README vermelden het. |
| File-mount van ontbrekend bestand → docker maakt een dir | uitvraag leest een dir als config → boot-fout | Altijd eerst genereren; de compose-validatie in stap 5 draait ná genereren. |
| WireMock `DELETE` van afwezige overlay → 404 | reset/enable faalt | `verwijderStoring` negeert de status (idempotent). |
| Stub-bericht-schema wijkt af | magazijn OK maar 0 berichten of FOUT | Mapping bevat exact de verplichte velden (`berichtId, afzender, ontvanger{type,waarde}, onderwerp, inhoud, publicatietijdstip`). |

## Niet in deze fase

Traag-varianten op de stubs (fixedDelay) — de eisen vragen "onbereikbaar". Load/stress (#14) → fase 7.
