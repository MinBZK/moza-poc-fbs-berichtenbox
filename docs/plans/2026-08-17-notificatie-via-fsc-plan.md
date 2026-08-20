**Status:** Uitgevoerd

# Notificatie-events via FSC — implementatieplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Het berichtenmagazijn levert zijn CloudEvents af door zijn eigen FSC-outway bij de inway van `logius`, in plaats van rechtstreeks bij de notificatie-stub.

**Architecture:** `magazijn-a` wordt consumer/outway (nieuw component), `logius` biedt naast `profiel-service` ook `notificatieservice` aan op zijn bestaande inway met de WireMock notificatie-stub als upstream. Applicatiekant: `DownstreamClient` krijgt een optionele grant-hash per downstream en zet daarmee de twee FSC-outway-headers; een downstream mét grant-hash slaat de SSRF-blocklist over (de bestemming volgt dan uit het contract, niet uit onze URL).

**Tech Stack:** Kotlin/Quarkus 3.x (Java 21), JUnit 5 + MockK, Bash + curl/jq/openssl, Docker Compose (OpenFSC v2.5.2), podman, WireMock, cfssl.

**Spec:** `docs/plans/2026-08-17-notificatie-via-fsc-design.md`

## Global Constraints

- Issue: MinBZK/MijnOverheidZakelijk#784. Géén auto-close-keyword in de PR-body; het issue blijft open bij merge.
- Nederlands in commits, comments en documentatie. Vaste technische idiomen blijven Engels: outway, inway, grant-hash, thumbprint, upstream, probe, backoff, retry, timeout.
- Peers en OIN's: `logius` = `00000000000000001000` (provider), `magazijn-a` = `00000000000000100000` (consumer/pusher), directory = `00000000000000000010`, `GROUP_ID` = `moza-fbs-test`.
- Dienstnaam letterlijk: `notificatieservice`.
- Adresschema federatie: `magazijn-a` = `127.20.2.0/24`, octet `.5` = outway. De outway van `magazijn-a` wordt dus `127.20.2.5:8443` (monitoring `:8081`).
- Kotlin-stijl: lege regel vóór én ná elk multi-line blok en elk zelfstandig control-statement (`if`, `when`, `for`, `try`). Zie CLAUDE.md voor de precieze regel.
- `detekt` draait met `maxIssues: 0` zonder baseline. Bewuste uitzonderingen krijgen een inline `@Suppress` mét motivatie.
- Comments leggen het *waarom* vast, nooit het *wat*. Geen verwijzingen naar review-labels, naar CLAUDE.md, of naar "PoC"/"voorlopig".
- Altijd `clean` vóór `test`/`verify`: `./mvnw clean test -pl services/berichtenmagazijn -am`.
- Nooit rechtstreeks naar `main` pushen. Branch: `feature/784-notificatie-via-fsc`.
- Geen reviewer toevoegen aan de PR.

---

### Taak 1: Headerpaar als data in `FscOutwayHeaders`

Het FSC-outway-headercontract zit nu vast aan JAX-RS (`ClientRequestContext`). `DownstreamClient` gebruikt `java.net.http.HttpClient` en kan er niet bij. Deze taak maakt het headerpaar transport-onafhankelijk, zonder het contract te dupliceren.

**Files:**
- Modify: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeaders.kt`
- Test: `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeadersTest.kt`

**Interfaces:**
- Produces: `FscOutwayHeaders.headers(grantHash: String): Map<String, String>` — de twee headers voor één outway-call, met een verse UUID-v7 transaction-id. Taak 2 gebruikt dit.
- `FscOutwayHeaders.zet(requestContext, grantHash)` behoudt exact zijn huidige signatuur en gedrag.

- [ ] **Stap 1: Schrijf de falende test**

Voeg toe aan `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeadersTest.kt` (bestaat al; voeg deze tests toe binnen de bestaande klasse):

```kotlin
    @Test
    fun `headers levert de grant-hash ongewijzigd en een verse transaction-id`() {
        val headers = FscOutwayHeaders.headers("\$1\$4\$k4rwlWTsCM_j89Fc3nrbnQa9-KB43")

        assertEquals("\$1\$4\$k4rwlWTsCM_j89Fc3nrbnQa9-KB43", headers[FscOutwayHeaders.GRANT_HASH_HEADER])

        val transactionId = UUID.fromString(headers.getValue(FscOutwayHeaders.TRANSACTION_ID_HEADER))

        assertEquals(7, transactionId.version(), "de outway weigert een transaction-id die geen UUID v7 is")
    }

    @Test
    fun `headers levert bij elke aanroep een andere transaction-id`() {
        val eerste = FscOutwayHeaders.headers("hash")
        val tweede = FscOutwayHeaders.headers("hash")

        assertNotEquals(
            eerste[FscOutwayHeaders.TRANSACTION_ID_HEADER],
            tweede[FscOutwayHeaders.TRANSACTION_ID_HEADER],
        )
    }
```

Vul de imports aan met `org.junit.jupiter.api.Assertions.assertNotEquals` en `java.util.UUID` als die er nog niet staan.

- [ ] **Stap 2: Draai de test en bevestig dat hij faalt**

```bash
./mvnw clean test -pl libraries/fbs-common -Dtest=FscOutwayHeadersTest
```

Expected: FAIL — `Unresolved reference: headers`.

- [ ] **Stap 3: Implementeer**

Vervang in `FscOutwayHeaders.kt` de functie `zet(...)` door onderstaande twee functies. Laat de constants en `log` ongewijzigd:

```kotlin
    /**
     * Het headerpaar voor één outway-call. Losgetrokken van het transport omdat niet elke
     * caller een JAX-RS-client is: de downstream-aflevering van CloudEvents gebruikt
     * `java.net.http.HttpClient`, en zou het contract anders moeten dupliceren.
     */
    fun headers(grantHash: String): Map<String, String> = mapOf(
        GRANT_HASH_HEADER to grantHash,
        TRANSACTION_ID_HEADER to UuidV7.generate().toString(),
    )

    fun zet(requestContext: ClientRequestContext, grantHash: String) {
        val paar = headers(grantHash)

        paar.forEach { (naam, waarde) -> requestContext.headers.putSingle(naam, waarde) }

        // Zonder deze transaction-id in de app-log is een call niet terug te vinden in de
        // outway-/inway-logs, die 'm ongewijzigd doorgeven. Log alleen de host, nooit het
        // volledige URI: sommige callers (Profiel-service) dragen een BSN in het pad, en
        // deze regel logt bij DEBUG — dus een pad of query hier zou dat BSN naar de
        // applicatielog schrijven. De host identificeert de outway afdoende.
        log.debugf(
            "FSC-outway-call naar %s: Fsc-Transaction-Id=%s",
            requestContext.uri.host,
            paar[TRANSACTION_ID_HEADER],
        )
    }
```

- [ ] **Stap 4: Draai de tests en bevestig dat ze slagen**

```bash
./mvnw clean test -pl libraries/fbs-common
```

Expected: `BUILD SUCCESS`, geen falende tests. Let op: bestaande `FscOutwayHeadersTest`-tests moeten óók nog groen zijn — `zet(...)` mag niet van gedrag veranderd zijn.

- [ ] **Stap 5: detekt**

```bash
./mvnw detekt:check -pl libraries/fbs-common
```

Expected: `BUILD SUCCESS`.

- [ ] **Stap 6: Commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeaders.kt \
        libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/fsc/FscOutwayHeadersTest.kt
git commit -m "refactor(common): maak het FSC-outway-headerpaar transport-onafhankelijk"
```

---

### Taak 2: Grant-hash per downstream in `DownstreamClient`

**Files:**
- Modify: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/PublicatieConfig.kt`
- Modify: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/DownstreamClient.kt`
- Test: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/DownstreamClientTest.kt`

**Interfaces:**
- Consumes: `FscOutwayHeaders.headers(grantHash)` uit Taak 1.
- Produces: configsleutel `magazijn.publicatie.downstreams.<key>.grant-hash` (`Optional<String>`); constante `DownstreamClient.OUTWAY_SSRF_ALERT_TOKEN = "DOWNSTREAM_VIA_OUTWAY"`. Taak 3 zet de property, Taak 8 leunt op het gedrag.

- [ ] **Stap 1: Breid de config-interface uit**

Voeg in `PublicatieConfig.kt` binnen `interface Downstream`, ná `url()` en vóór `maxPogingen()`, toe:

```kotlin
        /**
         * FSC-grant-hash voor een downstream die door de eigen outway loopt. Aanwezig ⇒ de call
         * krijgt `Fsc-Grant-Hash` en `Fsc-Transaction-Id` mee, en de SSRF-blocklist geldt er niet:
         * de outway kiest de bestemming op het contract achter deze hash, niet op onze URL.
         * Afwezig of leeg ⇒ rechtstreeks verkeer, met alle URL-controles onverkort.
         */
        fun grantHash(): Optional<String>
```

Voeg bovenaan het bestand de import `java.util.Optional` toe.

- [ ] **Stap 2: Schrijf de falende tests**

`DownstreamStub` in `DownstreamClientTest.kt` implementeert `PublicatieConfig.Downstream` en moet de nieuwe methode dragen. Vervang de bestaande `DownstreamStub`-klasse door:

```kotlin
    private class DownstreamStub(
        private val u: String,
        private val hash: String? = null,
    ) : PublicatieConfig.Downstream {
        override fun url(): String = u
        override fun grantHash(): java.util.Optional<String> = java.util.Optional.ofNullable(hash)
        override fun maxPogingen(): Int = 5
        override fun backoff(): PublicatieConfig.Backoff = object : PublicatieConfig.Backoff {
            override fun basis(): java.time.Duration = java.time.Duration.ofSeconds(1)
            override fun plafond(): java.time.Duration = java.time.Duration.ofHours(1)
        }
    }
```

Voeg daarna deze tests toe aan de klasse:

```kotlin
    @ParameterizedTest(name = "grant-hash \"{0}\" levert geen FSC-headers")
    @NullSource
    @ValueSource(strings = ["", "   "])
    fun `zonder bruikbare grant-hash gaan er geen FSC-headers mee`(hash: String?) {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, hash))

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertEquals(DownstreamResultaat.Geslaagd, resultaat)

        val ontvangen = server.headers.single()

        assertFalse(ontvangen.keys.any { it.equals(FscOutwayHeaders.GRANT_HASH_HEADER, ignoreCase = true) })
        assertFalse(ontvangen.keys.any { it.equals(FscOutwayHeaders.TRANSACTION_ID_HEADER, ignoreCase = true) })
    }

    @Test
    fun `met grant-hash gaan beide FSC-outway-headers mee`() {
        val hash = "\$1\$4\$k4rwlWTsCM_j89Fc3nrbnQa9-KB43"

        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, hash))

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertEquals(DownstreamResultaat.Geslaagd, resultaat)

        // HttpExchange normaliseert headernamen naar Capitalized-Case; zoek daarom
        // hoofdletter-ongevoelig op in plaats van op de exacte sleutel.
        val ontvangen = server.headers.single().mapKeys { (naam, _) -> naam.lowercase() }

        assertEquals(hash, ontvangen.getValue(FscOutwayHeaders.GRANT_HASH_HEADER.lowercase()).single())

        val transactionId = UUID.fromString(
            ontvangen.getValue(FscOutwayHeaders.TRANSACTION_ID_HEADER.lowercase()).single(),
        )

        assertEquals(7, transactionId.version())
    }

    @Test
    fun `grant-hash met omringende whitespace gaat getrimd de header in`() {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, "  hash-met-ruimte  "))

        client.lever(Publicatiedoel("aanmeld"), event)

        val ontvangen = server.headers.single().mapKeys { (naam, _) -> naam.lowercase() }

        assertEquals("hash-met-ruimte", ontvangen.getValue(FscOutwayHeaders.GRANT_HASH_HEADER.lowercase()).single())
    }

    @Test
    fun `een intern adres wordt geweigerd zonder grant-hash`() {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("http://10.0.0.1:8443/events"))

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertTrue(
            resultaat is DownstreamResultaat.ConfiguratieFout,
            "een RFC1918-adres zonder grant-hash hoort op de TLS-eis of de SSRF-blocklist te stranden",
        )
    }

    @Test
    fun `een intern https-adres mag wel met grant-hash - de outway bepaalt de bestemming`() {
        // De outway van de federatie luistert op een adres dat naar RFC1918 resolveert; de
        // SSRF-blocklist zou dat pad blokkeren terwijl het contract de bestemming al vastlegt.
        // Geen echte call: de host bestaat niet, dus een NetwerkFout bewíjst dat de
        // URL-validatie 'm heeft doorgelaten. Een ConfiguratieFout zou betekenen dat hij is
        // afgekeurd vóór het netwerk.
        every { config.downstreams() } returns
            mapOf("aanmeld" to DownstreamStub("https://10.255.255.1:8443/events", "hash"))

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertFalse(
            resultaat is DownstreamResultaat.ConfiguratieFout,
            "met een grant-hash hoort de SSRF-blocklist niet te gelden; kreeg: $resultaat",
        )
    }
```

Vul de imports aan: `nl.rijksoverheid.moz.fbs.common.fsc.FscOutwayHeaders`, `org.junit.jupiter.params.ParameterizedTest`, `org.junit.jupiter.params.provider.NullSource`, `org.junit.jupiter.params.provider.ValueSource`.

Let op de test-timeout van de laatste test: `10.255.255.1` is niet-routeerbaar, dus de connect-timeout van 5s uit `start()` bepaalt de duur.

- [ ] **Stap 3: Draai de tests en bevestig dat ze falen**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am -Dtest=DownstreamClientTest
```

Expected: FAIL — de headers ontbreken en de laatste test krijgt een `ConfiguratieFout`.

- [ ] **Stap 4: Implementeer in `DownstreamClient`**

Vervang in `lever(...)` het blok vanaf `val url = downstream.url()` tot en met de `requestBuilder`-opbouw door:

```kotlin
        val url = downstream.url()
        val grantHash = bruikbareGrantHash(downstream)
        val urlValidatie = valideerUrl(url, viaOutway = grantHash != null)
        if (urlValidatie != null) return urlValidatie

        val payload = try {
            objectMapper.writeValueAsBytes(event)
        } catch (ex: JsonProcessingException) {
            log.errorf(ex, "Serialisatie van CloudEvent mislukt: doel=%s eventType=%s", doel, event.type)
            return DownstreamResultaat.SerialisatieFout(
                "Serialisatie mislukt voor doel=$doel: ${ex.javaClass.simpleName}",
            )
        }

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(config.client().requestTimeout())
            .header("Content-Type", "application/cloudevents+json")
            .POST(BodyPublishers.ofByteArray(payload))

        if (grantHash != null) {
            FscOutwayHeaders.headers(grantHash).forEach { (naam, waarde) -> requestBuilder.header(naam, waarde) }
        }
```

Voeg de private helper toe, direct ná `lever(...)`:

```kotlin
    /**
     * De grant-hash van een downstream, of `null` als er geen bruikbare staat. Trimmen omdat een
     * hash die via een env-var uit een gegenereerd bestand komt makkelijk een spatie of newline
     * meekrijgt, en de outway daar `400 UNKNOWN_GRANT_HASH_IN_HEADER` op geeft.
     */
    private fun bruikbareGrantHash(downstream: PublicatieConfig.Downstream): String? =
        downstream.grantHash().orElse(null)?.trim()?.takeIf { it.isNotEmpty() }
```

Pas de signatuur van `valideerUrl` aan naar `private fun valideerUrl(url: String, viaOutway: Boolean): DownstreamResultaat.ConfiguratieFout?` en vervang het SSRF-blok aan het einde door:

```kotlin
        // SSRF-blocklist ([blokkeerIntern]): zonder dit kan een operator met config-toegang de
        // magazijn-pod als proxy naar interne services gebruiken. Loopback is hierboven al
        // toegestaan voor dev-stubs (WireMock/embedded HTTP). Een downstream met een grant-hash
        // valt erbuiten: die gaat door de eigen, co-located outway, en dáár bepaalt het
        // FSC-contract achter de hash de bestemming — een URL naar een ander intern adres levert
        // dan geen verkeer maar een fout. De bypass is bij boot zichtbaar gemaakt.
        if (!isLoopback && !viaOutway) {
            val ssrfFout = blokkeerIntern(host)
            if (ssrfFout != null) return ssrfFout
        }
        return null
```

Voeg in het `init`-blok, ná de bestaande `PROFIELEN_ZONDER_TLS_EIS`-waarschuwing, toe:

```kotlin
        val viaOutway = config.downstreams()
            .filterValues { bruikbareGrantHash(it) != null }
            .keys

        if (viaOutway.isNotEmpty()) {
            // Een SSRF-uitzondering hoort niet stil te zijn: zonder deze regel is aan een
            // draaiende pod niet te zien welke downstreams buiten de blocklist vallen.
            log.warnf(
                "%s: downstream(s) %s lopen door de eigen FSC-outway; de SSRF-blocklist geldt daar niet.",
                OUTWAY_SSRF_ALERT_TOKEN,
                viaOutway.joinToString(", "),
            )
        }
```

Voeg in het `companion object` toe, naast `VALIDATIE_UIT_ALERT_TOKEN`:

```kotlin
        /** Alert-token voor de SSRF-uitzondering op outway-downstreams; routeerbaar in log-alerting. */
        const val OUTWAY_SSRF_ALERT_TOKEN = "DOWNSTREAM_VIA_OUTWAY"
```

Voeg de import `nl.rijksoverheid.moz.fbs.common.fsc.FscOutwayHeaders` toe.

- [ ] **Stap 5: Draai de tests en bevestig dat ze slagen**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am -Dtest='DownstreamClientTest,PublicatieConfigValidationTest,PublicatieStreamE2ETest'
```

Expected: `BUILD SUCCESS`. Faalt `PublicatieConfigValidationTest` op een ontbrekende `grantHash`-implementatie in een andere stub, voeg daar dezelfde `override fun grantHash(): Optional<String> = Optional.empty()` toe.

- [ ] **Stap 6: Volledige suite + detekt**

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am
./mvnw detekt:check -pl services/berichtenmagazijn
```

Expected: `BUILD SUCCESS`, JaCoCo-gate ≥90% gehaald, geen nieuwe waarschuwingen in de output behalve de drie bekend-geaccepteerde (jansi, guava/Unsafe, LogManager).

- [ ] **Stap 7: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/PublicatieConfig.kt \
        services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/DownstreamClient.kt \
        services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/
git commit -m "feat(magazijn): lever CloudEvents af door de eigen FSC-outway"
```

---

### Taak 3: Configuratie en demo-stack

**Files:**
- Modify: `services/berichtenmagazijn/src/main/resources/application.properties:198-205`
- Modify: `compose.podman-hostnet.yaml`

**Interfaces:**
- Consumes: de configsleutel uit Taak 2.
- Produces: env-var `NOTIFICATIE_GRANT_HASH` (gevuld door Taak 7) en een overrulebare `NOTIFICATIE_URL` op het magazijn.

- [ ] **Stap 1: Property toevoegen**

Voeg in `application.properties` direct ná de regel `%dev.magazijn.publicatie.downstreams.notificatie.url=...` toe:

```properties
# Grant-hash voor de notificatie-downstream. Gevuld ⇒ de aflevering loopt door de eigen
# FSC-outway (headers + SSRF-uitzondering, zie DownstreamClient); leeg ⇒ rechtstreeks. Lokaal
# komt de waarde uit demo/generated/fsc-grants.env, dat de contract-bootstrap van de federatie
# schrijft; ontbreekt dat bestand, dan blijft de waarde leeg en verandert er niets.
%dev.magazijn.publicatie.downstreams.notificatie.grant-hash=${NOTIFICATIE_GRANT_HASH:}
```

En ná `%prod.magazijn.publicatie.downstreams.notificatie.url=${NOTIFICATIE_URL}`:

```properties
%prod.magazijn.publicatie.downstreams.notificatie.grant-hash=${NOTIFICATIE_GRANT_HASH:}
```

- [ ] **Stap 2: Demo-stack koppelen**

Voeg in `compose.podman-hostnet.yaml` bij de service `berichtenmagazijn-a` (vóór `environment:`) hetzelfde `env_file`-blok toe dat `berichtenuitvraag` al heeft:

```yaml
    # Het grant-hash komt uit de contract-bootstrap van de federatie
    # (demo/environment/federatie/contracts/fbs-contracten.sh). Ontbreekt het bestand — geen
    # federatie op deze machine — dan start de stack gewoon zonder, en levert het magazijn zijn
    # events rechtstreeks af.
    env_file:
      - path: demo/generated/fsc-grants.env
        required: false
```

En vervang in datzelfde `environment:`-blok de regel `NOTIFICATIE_URL: http://127.0.0.1:18084/events` door:

```yaml
      # Default: rechtstreeks via toxiproxy, zodat de storing-knop van de demo werkt. Zet
      # NOTIFICATIE_URL op de outway van magazijn-a (http://127.20.2.5:8443/events) om deze push
      # via de FSC-keten te laten lopen; het grant-hash komt dan uit env_file hierboven.
      NOTIFICATIE_URL: ${NOTIFICATIE_URL:-http://127.0.0.1:18084/events}
```

Laat `berichtenmagazijn-b` ongewijzigd: dat magazijn doet niet mee aan de federatie en moet rechtstreeks blijven werken.

- [ ] **Stap 3: Verifieer de compose-syntax**

Niet met `python3 -c "import yaml..."`: compose' eigen tags (`!reset`, `!override`) laat de
YAML-parser struikelen, ook op een ongewijzigd bestand. Laat compose zelf renderen — dat toetst
meteen de interpolatie:

```bash
docker compose --profile demo -f compose.yaml -f compose.podman.yaml -f compose.podman-hostnet.yaml \
  config --quiet && echo "COMPOSE OK"
```

Expected: `COMPOSE OK`. Controleer daarna dat de default én de override kloppen:

```bash
docker compose --profile demo -f compose.yaml -f compose.podman.yaml -f compose.podman-hostnet.yaml config \
  | grep -A2 'NOTIFICATIE_URL'
NOTIFICATIE_URL=http://127.20.2.5:8443/events docker compose --profile demo \
  -f compose.yaml -f compose.podman.yaml -f compose.podman-hostnet.yaml config | grep 'NOTIFICATIE_URL'
```

Expected: zonder override de toxiproxy-URL (`http://127.0.0.1:18084/events`), mét override het
outway-adres.

- [ ] **Stap 4: Commit**

```bash
git add services/berichtenmagazijn/src/main/resources/application.properties compose.podman-hostnet.yaml
git commit -m "feat(magazijn): maak de notificatie-downstream via de outway configureerbaar"
```

---

### Taak 4: Outway-component bij `magazijn-a`

**Files:**
- Create: `demo/environment/magazijn-a/pki/peers/magazijn-a/outway/csr.json`
- Modify: `demo/environment/magazijn-a/deploy/local/docker-compose.yaml`
- Modify: `demo/environment/magazijn-a/deploy/local/docker-compose.podman.yaml`
- Modify: `demo/environment/magazijn-a/deploy/local/docker-compose.podman-hostnet.yaml`

**Interfaces:**
- Produces: de container `outway-magazijn-a` en het group-cert `pki/out/magazijn-a/outway/cert.pem` — Taak 5 (federatie-adres) en Taak 7 (thumbprint voor het contract) leunen hierop.

- [ ] **Stap 1: PKI-definitie**

Maak `demo/environment/magazijn-a/pki/peers/magazijn-a/outway/csr.json`:

```json
{
  "CN": "outway.magazijn-a.fsc-test.local",
  "key": {
    "algo": "rsa",
    "size": 4096
  },
  "hosts": [
    "outway.magazijn-a.fsc-test.local",
    "magazijna-fscoutway-fsc-magazijna-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl",
    "fsc-magazijna-magazijna-fscoutway",
    "fsc-magazijna-magazijna-fscoutway.rig-prd-mpfm-w3h.svc.cluster.local"
  ],
  "serialnumber": "00000000000000100000",
  "names": [
    {
      "O": "magazijn-a",
      "C": "NL"
    }
  ]
}
```

- [ ] **Stap 2: Certificaten uitgeven en controleren**

```bash
(cd demo/environment/magazijn-a/pki && ./issue.sh && ./verify.sh)
ls demo/environment/magazijn-a/pki/out/magazijn-a/outway/ demo/environment/magazijn-a/pki/internal/magazijn-a/outway/
```

Expected: beide mappen bevatten `cert.pem` en `key.pem`. `verify.sh` eindigt zonder fout.

- [ ] **Stap 3: Outway-service in de basis-compose**

Voeg in `demo/environment/magazijn-a/deploy/local/docker-compose.yaml` direct vóór `inway-magazijn-a:` (regel 190) toe:

```yaml
  outway-magazijn-a:
    image: docker.io/federatedserviceconnectivity/outway:${IMAGE_TAG:-v2.5.2}
    user: "${HOST_UID:-1000}:${HOST_GID:-1000}"   # host-UID -> leest 0600-keys
    restart: on-failure                            # boot-race met de eigen manager
    command:
      - /usr/local/bin/outway
      - serve
    environment:
      LOG_TYPE: local
      LOG_LEVEL: debug
      GROUP_ID: moza-fbs-test
      NAME: magazijn-a-outway
      SELF_ADDRESS: https://outway.magazijn-a.fsc-test.local:443
      LISTEN_ADDRESS: 0.0.0.0:8443
      MONITORING_ADDRESS: 0.0.0.0:8081
      DISABLE_CRL_CHECKS: "true"
      # De outway registreert zich bij de controller en praat met de manager op de authenticated
      # interne poort (:9443). fsc-outway serve eist beide.
      MANAGER_INTERNAL_ADDRESS: https://manager.magazijn-a.fsc-test.local:9443
      CONTROLLER_REGISTRATION_API_ADDRESS: https://controller.magazijn-a.fsc-test.local:9443
      # Echte txlog-api (INTERNAL-PKI mTLS): bij egress logt de outway de transactie (direction: out).
      TX_LOG_API_ADDRESS: https://txlog.magazijn-a.fsc-test.local:9443
      TLS_ROOT_CERT: /pki/internal/magazijn-a/ca/root.pem
      TLS_CERT: /pki/internal/magazijn-a/outway/cert.pem
      TLS_KEY: /pki/internal/magazijn-a/outway/key.pem
      TLS_GROUP_ROOT_CERT: /pki/ca/root.pem
      TLS_GROUP_CERT: /pki/out/magazijn-a/outway/cert.pem
      TLS_GROUP_KEY: /pki/out/magazijn-a/outway/key.pem
    volumes:
      - "${PKI_DIR:?zet PKI_DIR in .env}:/pki:ro"
    networks:
      default:
        # Alias = de internal-cert-hostnaam; géén :443-router-route (de outway is client, geen ingress).
        aliases:
          - outway.magazijn-a.fsc-test.local
    depends_on:
      manager-magazijn-a:
        condition: service_started
      controller-magazijn-a:
        condition: service_started
      txlog-magazijn-a:
        condition: service_started
```

- [ ] **Stap 4: Podman-overlay**

Voeg in `demo/environment/magazijn-a/deploy/local/docker-compose.podman.yaml` vóór `inway-magazijn-a:` toe:

```yaml
  outway-magazijn-a:
    userns_mode: "keep-id"
```

- [ ] **Stap 5: Hostnet-overlay**

Voeg in `demo/environment/magazijn-a/deploy/local/docker-compose.podman-hostnet.yaml` vóór `inway-magazijn-a:` (regel 120) toe:

```yaml
  outway-magazijn-a:
    network_mode: host
    networks: !reset null
    extra_hosts: *hosts
    restart: on-failure:600
    environment:
      # MANAGER_INTERNAL_ADDRESS blijft naar 9443 wijzen — die poort verhuist niet.
      LISTEN_ADDRESS: 127.0.0.1:58443
      MONITORING_ADDRESS: 127.0.0.1:58081
      CONTROLLER_REGISTRATION_API_ADDRESS: https://controller.magazijn-a.fsc-test.local:39443
      TX_LOG_API_ADDRESS: https://txlog.magazijn-a.fsc-test.local:49443
```

Voeg in het `*hosts`-anker bovenaan datzelfde bestand de regel toe:

```yaml
  - "outway.magazijn-a.fsc-test.local:127.0.0.1"
```

- [ ] **Stap 6: Verifieer de compose-syntax van alle drie**

De drie bestanden stapelen en compose laten renderen; een losse YAML-parser struikelt over
`!reset`/`!override`. Draai vanuit de peer-map, want `${PKI_DIR}` komt uit de `.env` daar:

```bash
(cd demo/environment/magazijn-a/deploy/local && \
 docker compose -f docker-compose.yaml -f docker-compose.podman.yaml \
                -f docker-compose.podman-hostnet.yaml config --quiet) && echo "COMPOSE OK"
```

Expected: `COMPOSE OK`.

- [ ] **Stap 7: Commit**

```bash
git add demo/environment/magazijn-a/pki/peers/magazijn-a/outway/csr.json \
        demo/environment/magazijn-a/deploy/local/docker-compose.yaml \
        demo/environment/magazijn-a/deploy/local/docker-compose.podman.yaml \
        demo/environment/magazijn-a/deploy/local/docker-compose.podman-hostnet.yaml
git commit -m "feat(demo): geef magazijn-a een outway, zodat het uitgaand door FSC kan"
```

Let op: `pki/out/` en `pki/internal/` staan in `.gitignore` (sleutelmateriaal). Controleer met `git status --short demo/environment/magazijn-a/pki/` dat er géén `.pem`-bestanden gestaged staan; alleen de `csr.json` hoort erin.

---

### Taak 5: Outway in de federatie-overlay

**Files:**
- Modify: `demo/environment/federatie/compose/magazijn-a.yaml`
- Modify: `demo/environment/federatie/compose/logius.yaml`
- Modify: `demo/environment/federatie/README.md`

**Interfaces:**
- Consumes: `outway-magazijn-a` uit Taak 4.
- Produces: de outway op `127.20.2.5:8443`, bereikbaar voor het magazijn uit de demo-stack.

- [ ] **Stap 1: Adres in de federatie-overlay**

Voeg in `demo/environment/federatie/compose/magazijn-a.yaml` in het `x-hosts-federatie`-anker de regel toe, ná `"txlog.magazijn-a.fsc-test.local:127.20.2.3"`:

```yaml
  - "outway.magazijn-a.fsc-test.local:127.20.2.5"
```

Voeg dezelfde regel toe in het `x-hosts-federatie`-anker van `demo/environment/federatie/compose/logius.yaml` — beide stacks delen één netns en moeten dezelfde namen kennen.

Voeg in `magazijn-a.yaml` ná het `inway-magazijn-a`-blok toe:

```yaml
  outway-magazijn-a:
    extra_hosts: *hosts-federatie
    environment:
      LISTEN_ADDRESS: 127.20.2.5:8443
      MONITORING_ADDRESS: 127.20.2.5:8081
      CONTROLLER_REGISTRATION_API_ADDRESS: https://controller.magazijn-a.fsc-test.local:9443
      MANAGER_INTERNAL_ADDRESS: https://manager.magazijn-a.fsc-test.local:9443
      TX_LOG_API_ADDRESS: https://txlog.magazijn-a.fsc-test.local:9443
```

- [ ] **Stap 2: README bijwerken**

In `demo/environment/federatie/README.md` staat onder "Adresschema" al een rij `.5 | outway`. Voeg onder de peer-tabel géén nieuwe rij toe (die klopt al), maar vervang in de inleiding de zin:

```
Op dit moment doen `logius` (uitvraag-consumer) en `magazijn-a` (provider) mee.
```

door:

```
Op dit moment doen `logius` en `magazijn-a` mee. Beide dragen twee rollen: `logius` biedt
`profiel-service` en `notificatieservice` aan en neemt `berichtenmagazijn` af, `magazijn-a` biedt
`berichtenmagazijn` aan en pusht notificatie-events door zijn eigen outway.
```

- [ ] **Stap 3: Verifieer de compose-syntax**

De federatie-overlay is geen zelfstandig bestand — hij hoort bovenop de drie peer-bestanden.
Laat compose de hele stapel renderen:

```bash
(cd demo/environment/magazijn-a/deploy/local && \
 docker compose -f docker-compose.yaml -f docker-compose.podman.yaml \
                -f docker-compose.podman-hostnet.yaml \
                -f ../../../federatie/compose/magazijn-a.yaml config --quiet) && echo "COMPOSE OK"
```

Expected: `COMPOSE OK`.

- [ ] **Stap 4: Commit**

```bash
git add demo/environment/federatie/compose/magazijn-a.yaml \
        demo/environment/federatie/compose/logius.yaml \
        demo/environment/federatie/README.md
git commit -m "feat(demo): geef de outway van magazijn-a zijn eigen federatie-adres"
```

---

### Taak 6: Meerdere diensten op één inway

**Files:**
- Modify: `demo/environment/logius/deploy/local/publish-service.sh`
- Modify: `demo/environment/magazijn-a/deploy/local/publish-service.sh`
- Modify: `demo/environment/lib/fsc-harness.sh:136-141`

**Interfaces:**
- Produces: `FSC_SERVICE_NAME` als override op beide `publish-service.sh`-scripts, en `fsc_zet_upstream <envdir> <peer> <upstream> [dienst]`. Taak 8 gebruikt het vierde argument.

- [ ] **Stap 1: Dienstnaam overrulebaar maken**

Vervang in `demo/environment/logius/deploy/local/publish-service.sh` de regel `SERVICE_NAME="profiel-service"` door:

```bash
# Overrulebaar omdat één inway meer dan één dienst kan dragen: logius biedt naast profiel-service
# ook notificatieservice aan. Dezelfde variabelenaam als smoke-discover.sh gebruikt.
SERVICE_NAME="${FSC_SERVICE_NAME:-profiel-service}"
```

Vervang in `demo/environment/magazijn-a/deploy/local/publish-service.sh` de regel `SERVICE_NAME="berichtenmagazijn"` door:

```bash
# Overrulebaar, symmetrisch met de andere peers: één inway kan meer dan één dienst dragen.
SERVICE_NAME="${FSC_SERVICE_NAME:-berichtenmagazijn}"
```

- [ ] **Stap 2: `fsc_zet_upstream` een dienst-argument geven**

Vervang in `demo/environment/lib/fsc-harness.sh` de functie `fsc_zet_upstream` door:

```bash
# fsc_zet_upstream <envdir> <peer> <upstream-url> [dienst]
#
# Publiceert (idempotent) een dienst van <peer> met <upstream-url> als endpoint. Zonder <dienst>
# publiceert de peer zijn eigen standaarddienst — de default in zijn publish-service.sh.
fsc_zet_upstream() {
  local envdir="$1" peer="$2" upstream="$3" dienst="${4:-}"

  (
    export FSC_CONTROLLER="https://controller.${peer}.fsc-test.local:9444"
    export FSC_MANAGER="https://manager.${peer}.fsc-test.local:9443"
    export FSC_UPSTREAM_URL="$upstream"

    if [ -n "$dienst" ]; then
      export FSC_SERVICE_NAME="$dienst"
    fi

    "${envdir}/${peer}/deploy/local/publish-service.sh"
  )
}
```

- [ ] **Stap 3: Shellcheck**

```bash
shellcheck demo/environment/lib/fsc-harness.sh \
           demo/environment/logius/deploy/local/publish-service.sh \
           demo/environment/magazijn-a/deploy/local/publish-service.sh
```

Expected: geen output (geen bevindingen). Is `shellcheck` niet geïnstalleerd, sla deze stap over en noteer dat in de PR-body.

- [ ] **Stap 4: Bewijs dat de bestaande aanroepen niet wijzigen**

```bash
grep -rn "fsc_zet_upstream" demo/environment/
```

Expected: de aanroepen in `smoke-keten.sh` en `smoke-contract.sh` geven drie argumenten en blijven dus op de peer-default staan. Verander ze niet.

- [ ] **Stap 5: Commit**

```bash
git add demo/environment/lib/fsc-harness.sh \
        demo/environment/logius/deploy/local/publish-service.sh \
        demo/environment/magazijn-a/deploy/local/publish-service.sh
git commit -m "feat(demo): laat één inway meerdere diensten publiceren"
```

---

### Taak 7: Contract `magazijn-a → logius` voor `notificatieservice`

**Files:**
- Modify: `demo/environment/federatie/peers.env`
- Modify: `demo/environment/federatie/contracts/fbs-contracten.sh`

**Interfaces:**
- Consumes: de outway-thumbprint uit Taak 4, `bootstrap.sh` (ongewijzigd).
- Produces: een regel `NOTIFICATIE_GRANT_HASH=<hash>` in `demo/generated/fsc-grants.env`, gelezen door Taak 3.

- [ ] **Stap 1: Rollen in `peers.env`**

Voeg onderaan `demo/environment/federatie/peers.env`, ná het blok met `UITVRAAG`/`MAGAZIJNEN`/`MAGAZIJN_DIENST`, toe:

```bash
# Wie de notificatiedienst aanbiedt en wie erop pusht. Andersom dan de magazijn-rollen hierboven:
# het magazijn is hier de áfnemer — het duwt CloudEvents door zijn eigen outway naar de inway van
# de aanbieder. Een tweede pusher toevoegen is één naam erbij.
NOTIFICATIE=logius
PUSHERS="magazijn-a"
NOTIFICATIE_DIENST=notificatieservice
```

- [ ] **Stap 2: Het contract-per-combinatie-blok uitfactoren**

Vervang in `demo/environment/federatie/contracts/fbs-contracten.sh` alles vanaf `CONS_NET="$(fsc_peer_waarde NET "$UITVRAAG")"` tot en met de afsluitende `done` van de magazijn-lus, door:

```bash
FOUTEN=0
GEDAAN=0

# De demo-stack moet het grant-hash kennen om `Fsc-Grant-Hash` te kunnen zetten, en compose kan dat
# niet uit een draaiende manager halen. Vandaar een gegenereerd env-bestand dat de apps inlezen.
# Naar `.tmp` en pas aan het eind verplaatsen: een half geschreven bestand zou een app met een
# grant-hash voor de ene bestemming en niets voor de andere laten starten.
GRANTS="$(cd "${ENVDIR}/.." && pwd)/generated/fsc-grants.env"
mkdir -p "$(dirname "$GRANTS")"
: > "$GRANTS.tmp"
# Eén trap voor beide tijdelijke bestanden: fsc_errlog_init zette er al een op $ERRLOG, en een
# tweede `trap ... EXIT` vervangt die in plaats van hem aan te vullen.
trap 'rm -f "$GRANTS.tmp" "$ERRLOG"' EXIT

# grant_regel_voor <consumer-peer> <consumer-oin> <thumbprint> <provider-oin> <dienst> <env-naam>:
# één `<ENV-NAAM>=<hash>`-regel op stdout.
#
# Het grant-hash is NIET het contract-hash: de outway routeert op de grant uit `content.grants[]`,
# en wie het contract-hash op de header zet krijgt structureel 400 UNKNOWN_GRANT_HASH_IN_HEADER.
#
# De contracten komen van de manager van de CONSUMER: dat is de kant waar de outway zijn grant
# vandaan haalt. Bij de provider staat hetzelfde contract, maar de consumer praat daar niet mee.
grant_regel_voor() {
  local consumer="$1" cons_oin="$2" thumb="$3" prov_oin="$4" dienst="$5" envnaam="$6"
  local json contract grant cons_net

  cons_net="$(fsc_peer_waarde NET "$consumer")"
  json="$(fsc_manager_contracts "$ENVDIR" "$consumer" "$(fsc_component_adres "$cons_net" manager)")" || return 1
  contract="$(fsc_grant_actief "$json" "$dienst" "$prov_oin" "$cons_oin" "$thumb" | sort | head -n1)"
  [ -n "$contract" ] || return 1

  grant="$(fsc_grant_hash "$json" "$contract" "$dienst" "$thumb")"
  fsc_grant_bruikbaar "$grant" || return 1

  # Escapen voor compose: een kale `$` in dit bestand wordt als variabele-verwijzing gelezen en
  # vreet de rest van het hash op. Zie fsc_compose_env_waarde.
  printf '%s=%s\n' "$envnaam" "$(fsc_compose_env_waarde "$grant")"
}

# contract_op <consumer-peer> <provider-peer> <dienst> <env-naam>: zet het contract op en schrijft
# het grant-hash weg. Werkt in beide richtingen — welke peer consumer is, staat in peers.env.
contract_op() {
  local consumer="$1" provider="$2" dienst="$3" envnaam="$4"
  local cons_net cons_oin prov_net prov_oin cons_thumb

  cons_net="$(fsc_peer_waarde NET "$consumer")"
  cons_oin="$(fsc_peer_waarde OIN "$consumer")"
  prov_net="$(fsc_peer_waarde NET "$provider")"
  prov_oin="$(fsc_peer_waarde OIN "$provider")"

  if [ -z "$cons_net" ] || [ -z "$cons_oin" ] || [ -z "$prov_net" ] || [ -z "$prov_oin" ]; then
    echo "FAIL: NET_/OIN_ ontbreekt voor '${consumer}' of '${provider}' in peers.env." >&2
    return 1
  fi

  if [ "$prov_oin" = "$cons_oin" ]; then
    # Een zelfreferentieel contract is geldig FSC, maar hier zou het betekenen dat aanbieder en
    # afnemer dezelfde identiteit dragen — dan klopt peers.env niet.
    echo "FAIL: '${consumer}' en '${provider}' hebben dezelfde OIN (${prov_oin})." >&2
    return 1
  fi

  cons_thumb="$(fsc_outway_thumbprint "${ENVDIR}/${consumer}/pki/out/${consumer}/outway/cert.pem")" || {
    echo "FAIL: kon de outway-thumbprint van '${consumer}' niet berekenen: $(fsc_last_error)" >&2
    return 1
  }

  echo "== contract ${consumer} -> ${provider} (${dienst}) =="

  # De interne manager-API zit op het manager-adres van de peer, op de standaardpoort 9443. De
  # octet-toewijzing staat in federatie/README.md en geldt voor elke peer gelijk.
  FSC_CONSUMER_OIN="$cons_oin" \
  FSC_PROVIDER_OIN="$prov_oin" \
  FSC_SERVICE_NAME="$dienst" \
  FSC_OUTWAY_CERT="${ENVDIR}/${consumer}/pki/out/${consumer}/outway/cert.pem" \
  FSC_CONSUMER_MANAGER="https://manager.${consumer}.fsc-test.local:9443" \
  FSC_CONSUMER_ADRES="$(fsc_component_adres "$cons_net" manager)" \
  FSC_CONSUMER_CERT="${ENVDIR}/${consumer}/pki/internal/${consumer}/manager/cert.pem" \
  FSC_CONSUMER_KEY="${ENVDIR}/${consumer}/pki/internal/${consumer}/manager/key.pem" \
  FSC_CONSUMER_CA="${ENVDIR}/${consumer}/pki/internal/${consumer}/ca/root.pem" \
  FSC_PROVIDER_MANAGER="https://manager.${provider}.fsc-test.local:9443" \
  FSC_PROVIDER_ADRES="$(fsc_component_adres "$prov_net" manager)" \
  FSC_PROVIDER_CERT="${ENVDIR}/${provider}/pki/internal/${provider}/manager/cert.pem" \
  FSC_PROVIDER_KEY="${ENVDIR}/${provider}/pki/internal/${provider}/manager/key.pem" \
  FSC_PROVIDER_CA="${ENVDIR}/${provider}/pki/internal/${provider}/ca/root.pem" \
    "${HERE}/bootstrap.sh" || {
      echo "FAIL: contract ${consumer} -> ${provider} niet opgezet." >&2
      return 1
    }

  grant_regel_voor "$consumer" "$cons_oin" "$cons_thumb" "$prov_oin" "$dienst" "$envnaam" >> "$GRANTS.tmp" || {
    echo "FAIL: contract staat, maar het grant-hash van ${provider} (${dienst}) is niet af te leiden." >&2
    return 1
  }
}

# Ophalen: de uitvraag-outway mag `berichtenmagazijn` afnemen bij elk magazijn.
#
# De env-naam is de peernaam in hoofdletters: `magazijn-a` -> `MAGAZIJN_A_GRANT_HASH`. Dat is
# precies de naam die application.properties van de uitvraag leest voor het magazijn met die OIN.
# Hernoem je een peer in peers.env, dan moet die property mee.
for magazijn in $MAGAZIJNEN; do
  ENVNAAM="$(printf '%s' "$magazijn" | tr '[:lower:]-' '[:upper:]_')_GRANT_HASH"

  if contract_op "$UITVRAAG" "$magazijn" "$MAGAZIJN_DIENST" "$ENVNAAM"; then
    GEDAAN=$((GEDAAN + 1))
  else
    FOUTEN=$((FOUTEN + 1))
  fi

  echo
done

# Pushen: elk magazijn mag zijn CloudEvents kwijt bij de notificatiedienst. Andere richting,
# zelfde machinerie — het magazijn is hier de consumer.
for pusher in $PUSHERS; do
  if contract_op "$pusher" "$NOTIFICATIE" "$NOTIFICATIE_DIENST" NOTIFICATIE_GRANT_HASH; then
    GEDAAN=$((GEDAAN + 1))
  else
    FOUTEN=$((FOUTEN + 1))
  fi

  echo
done
```

Pas ook de `:` -controles bovenaan het script aan naar:

```bash
: "${UITVRAAG:?geen UITVRAAG in peers.env}"
: "${MAGAZIJNEN:?geen MAGAZIJNEN in peers.env}"
: "${MAGAZIJN_DIENST:?geen MAGAZIJN_DIENST in peers.env}"
: "${NOTIFICATIE:?geen NOTIFICATIE in peers.env}"
: "${PUSHERS:?geen PUSHERS in peers.env}"
: "${NOTIFICATIE_DIENST:?geen NOTIFICATIE_DIENST in peers.env}"
```

En de slot-melding, zodat die niet meer over "magazijnen" alleen praat:

```bash
  echo "FBS-CONTRACTEN OK (${GEDAAN} contract(en))."
```

Laat de "nul contracten"-uitgang, de `mv`, en het opruimen van `$GRANTS` bij fouten ongewijzigd; die logica klopt nog steeds.

Let op: bij meer dan één pusher schrijft de lus meerdere regels `NOTIFICATIE_GRANT_HASH=` naar hetzelfde bestand, en dan wint de laatste. Dat is nu correct (één pusher) maar niet houdbaar. Voeg daarom bovenaan de pusher-lus toe:

```bash
# Eén env-naam voor alle pushers werkt zolang er één is; een tweede zou de eerste overschrijven.
# Dit is een bewuste grens, geen omissie: een tweede pusher betekent een tweede magazijn-deployment
# met een eigen env, niet twee hashes in één bestand.
if [ "$(printf '%s\n' $PUSHERS | grep -c .)" -gt 1 ]; then
  echo "FAIL: PUSHERS bevat meer dan één peer; NOTIFICATIE_GRANT_HASH kan er maar één dragen." >&2
  exit 2
fi
```

- [ ] **Stap 3: Shellcheck**

```bash
shellcheck demo/environment/federatie/contracts/fbs-contracten.sh
```

Expected: geen bevindingen. (`$PUSHERS` zonder quotes in de `printf` is opzettelijk — woordsplitsing is hier de bedoeling; voeg `# shellcheck disable=SC2086` toe met die motivatie als shellcheck erover valt.)

- [ ] **Stap 4: Commit**

```bash
git add demo/environment/federatie/peers.env demo/environment/federatie/contracts/fbs-contracten.sh
git commit -m "feat(demo): contract magazijn->notificatie naast het ophaalcontract"
```

---

### Taak 8: Smoke — de push door de keten

**Files:**
- Create: `demo/environment/federatie/smoke-notificatie.sh`

**Interfaces:**
- Consumes: Taak 3 t/m 7 én een draaiende demo-stack.
- Produces: het bewijs voor de acceptatiecriteria van het issue.

- [ ] **Stap 1: Schrijf het script**

```bash
#!/usr/bin/env bash
# Smoke: bewijst dat het magazijn zijn CloudEvents door FSC pusht — berichtenmagazijn-a levert af
# via zijn eigen outway -> router -> inway van de notificatie-aanbieder -> WireMock-stub, in plaats
# van rechtstreeks.
#
#   1. data-pad — een bericht dat uniek is voor deze run komt als CloudEvent in de request-journal
#      van de stub terecht, met Content-Type application/cloudevents+json. Een aanwezige event op
#      zichzelf zegt niets: zonder uniek merk houdt een event uit een eerdere run dit groen;
#   2. verantwoording — dezelfde transactie staat in beide txlogs, uitgaand bij het magazijn en
#      inkomend bij de aanbieder. Ging de push rechtstreeks, dan groeit geen van beide;
#   3. fire-and-forget intact — de stub antwoordde 202 en het magazijn leverde precies één keer af;
#      een retry-stapeling zou betekenen dat de keten het antwoord niet terugkrijgt.
#
# Voorwaarden:
#   - de federatie draait en de contracten staan -> ./federatie.sh up && contracts/fbs-contracten.sh
#   - de demo-stack draait met het magazijn door de outway:
#       MODUS=hostnet NOTIFICATIE_URL=http://127.20.2.5:8443/events demo/podman-up.sh
#
# Linux + podman: gebruikt `podman` en `curl`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

# shellcheck source=../lib/fsc-harness.sh
. "${ENVDIR}/lib/fsc-harness.sh"
# shellcheck source=peers.env
. "${HERE}/peers.env"

fsc_errlog_init

MAGAZIJN_A_DIRECT="${MAGAZIJN_A_DIRECT:-http://127.0.0.1:8090/api/v1}"
NOTIFICATIE_STUB="${NOTIFICATIE_STUB:-http://127.0.0.1:8084}"
# De inway moet de stub als upstream krijgen. Host-adres, want de stub draait in de demo-stack en
# niet in de peer-stack; in hostnet-modus delen ze de netns.
NOTIFICATIE_UPSTREAM="${NOTIFICATIE_UPSTREAM:-http://127.0.0.1:8084}"
# De outbox-poller draait op 60s. Bewust niet verlaagd: dan bewijst deze smoke een configuratie die
# niemand draait. Vandaar een bovengrens in plaats van een vaste wachttijd.
PUBLICATIE_TIMEOUT="${PUBLICATIE_TIMEOUT:-120}"
PUBLICATIE_INTERVAL="${PUBLICATIE_INTERVAL:-5}"
PROBE_POGINGEN="${PROBE_POGINGEN:-5}"
PROBE_WACHT="${PROBE_WACHT:-3}"

PUSHER="$(printf '%s' "$PUSHERS" | awk '{print $1}')"
PUSHER_OIN="$(fsc_peer_waarde OIN "$PUSHER")"

FOUTEN=0
fout() { echo "FAIL: $*" >&2; FOUTEN=$((FOUTEN + 1)); }
ok()   { echo "OK: $*"; }

command -v curl >/dev/null 2>&1 || { echo "FAIL: 'curl' is vereist." >&2; exit 1; }

# Elke run een VERSE ontvanger-BSN: het merk zit in onderwerp en inhoud, maar een vaste BSN zou de
# stub-journal met events van eerdere runs vullen en assert 3 (precies één aflevering) breken. De
# BSN moet door de elfproef komen, anders weigert het magazijn hem en leest dat als een ketenfout.
nieuwe_bsn() {
  local cijfers som i c laatste

  while :; do
    cijfers=""; som=0

    for i in 9 8 7 6 5 4 3 2; do
      # Eerste cijfer nooit 0: een BSN met een voorloopnul is negen tekens lang maar wordt door
      # sommige parsers als achtcijferig gelezen.
      if [ "$i" -eq 9 ]; then c=$((RANDOM % 9 + 1)); else c=$((RANDOM % 10)); fi
      cijfers="${cijfers}${c}"
      som=$((som + c * i))
    done

    for laatste in 0 1 2 3 4 5 6 7 8 9; do
      if [ $(( (som - laatste) % 11 )) -eq 0 ]; then
        printf '%s%s' "$cijfers" "$laatste"
        return 0
      fi
    done
  done
}

BSN="$(nieuwe_bsn)"
MERK="notifsmoke-$$-$(od -An -N4 -tu4 < /dev/urandom | tr -d ' ')"

# --- 0. De inway van de aanbieder wijst naar de notificatie-stub --------------------------------
# Hier afdwingen en niet als voorwaarde aan de gebruiker laten: de publicatie is idempotent, en wie
# eerst een andere smoke draait zet de upstream ongemerkt terug.
echo "== 0. ${NOTIFICATIE} biedt ${NOTIFICATIE_DIENST} aan met de stub als upstream =="
if fsc_zet_upstream "$ENVDIR" "$NOTIFICATIE" "$NOTIFICATIE_UPSTREAM" "$NOTIFICATIE_DIENST" \
     >/dev/null 2>"$ERRLOG"; then
  ok "dienst ${NOTIFICATIE_DIENST} gepubliceerd op de inway van ${NOTIFICATIE}"
else
  fout "kon ${NOTIFICATIE_DIENST} niet publiceren: $(fsc_last_error)"
fi

# Probe door de outway: bewijst dat het contract leeft en dat de dienstwijziging is doorgedrongen,
# vóór we het van de applicatie afhankelijk maken. De dienstwijziging propageert asynchroon, dus de
# eerste pogingen kunnen nog bij de vorige upstream uitkomen.
GRANT="$(fsc_compose_env_lees "$(cd "${ENVDIR}/.." && pwd)/generated/fsc-grants.env" NOTIFICATIE_GRANT_HASH || true)"
OUTWAY="http://$(fsc_component_adres "$(fsc_peer_waarde NET "$PUSHER")" outway):8443"

if ! fsc_grant_bruikbaar "$GRANT"; then
  fout "geen bruikbaar NOTIFICATIE_GRANT_HASH in demo/generated/fsc-grants.env — draai contracts/fbs-contracten.sh"
else
  POGING=1
  DOOR=0

  while [ "$POGING" -le "$PROBE_POGINGEN" ]; do
    CODE="$(curl -sS -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 10 \
              -X POST "${OUTWAY}/events" \
              -H "Fsc-Grant-Hash: ${GRANT}" \
              -H 'Content-Type: application/cloudevents+json' \
              -d '{"specversion":"1.0","id":"probe","source":"urn:probe","type":"probe"}' \
              2>"$ERRLOG" || true)"

    if [ "$CODE" = "202" ]; then
      DOOR=1
      break
    fi

    [ "$POGING" -lt "$PROBE_POGINGEN" ] && sleep "$PROBE_WACHT"
    POGING=$((POGING + 1))
  done

  if [ "$DOOR" -eq 1 ]; then
    ok "de outway van ${PUSHER} bereikt de stub door de inway heen (202, na ${POGING} poging(en))"
  else
    fout "de probe door de outway gaf HTTP ${CODE:-<geen>}: $(fsc_last_error)"
  fi
fi

# --- Nulmeting op de txlogs ---------------------------------------------------------------------
# NA de probe, niet ervoor: die gaat zelf door outway en inway en schrijft dus in beide logboeken.
# Stond de nulmeting ervóór, dan telde die rij als "nieuw" en kon assert 2 nooit rood worden.
if PROJECT="$(fsc_compose_project "${ENVDIR}/${GASTHEER}/deploy/local/docker-compose.yaml")"; then
  txids() {  # <db> <richting>
    podman exec "${PROJECT}-postgres-1" psql -U postgres -d "$1" -tA \
      -c "SELECT transaction_id FROM transactionlog.records
          WHERE direction = '$2' AND service_name = '${NOTIFICATIE_DIENST}' AND grant_hash IS NOT NULL" \
      2>"$ERRLOG" | sort -u
  }

  # Eerst opvangen in variabelen, dan pas vergelijken: in een process substitution is de exitstatus
  # onzichtbaar, en een gestopte postgres zou dan als "geen transacties" lezen.
  gedeelde_txids() {
    local uit in

    uit="$(txids "fsc_txlog_$(fsc_peer_var "$PUSHER")" out)" || {
      echo "WAARSCHUWING: txlog van ${PUSHER} niet leesbaar: $(fsc_last_error)" >&2
      return 1
    }
    in="$(txids "fsc_txlog_$(fsc_peer_var "$NOTIFICATIE")" in)" || {
      echo "WAARSCHUWING: txlog van ${NOTIFICATIE} niet leesbaar: $(fsc_last_error)" >&2
      return 1
    }

    comm -12 <(printf '%s\n' "$uit") <(printf '%s\n' "$in")
  }

  TXLOG_LEESBAAR=1
else
  TXLOG_LEESBAAR=0
fi

VOOR_OK=0
VOOR=""

if [ "$TXLOG_LEESBAAR" -eq 1 ]; then
  STABIEL=0
  POGING=1

  # Uitwachten tot de logboeken stilstaan: de in-/outway schrijven hun record out-of-band, dus de
  # rij van de probe kan ná de nulmeting landen en dan als "nieuw" meetellen.
  while [ "$POGING" -le "$PROBE_POGINGEN" ]; do
    HUIDIG="$(gedeelde_txids)" || break

    if [ "$POGING" -gt 1 ] && [ "$HUIDIG" = "$VORIG" ]; then
      STABIEL=1
      break
    fi

    VORIG="$HUIDIG"
    sleep "$PROBE_WACHT"
    POGING=$((POGING + 1))
  done

  if [ "$STABIEL" -eq 1 ]; then
    VOOR="$HUIDIG"
    VOOR_OK=1
  else
    echo "WAARSCHUWING: de txlogs kwamen niet tot stilstand binnen ${PROBE_POGINGEN} metingen" >&2
  fi
fi

# --- 1. Data-pad --------------------------------------------------------------------------------
echo "== 1. de CloudEvent van magazijn ${PUSHER} komt bij de stub aan =="
CODE="$(curl -sS -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 20 \
          -X POST "${MAGAZIJN_A_DIRECT}/berichten" -H 'Content-Type: application/json' \
          -d "{\"afzender\":\"${PUSHER_OIN}\",\"ontvanger\":{\"type\":\"BSN\",\"waarde\":\"${BSN}\"},\"onderwerp\":\"${MERK}\",\"inhoud\":\"${MERK}\"}" \
          2>"$ERRLOG" || true)"

if [ "$CODE" != "201" ] && [ "$CODE" != "200" ]; then
  fout "aanleveren bij ${PUSHER} gaf HTTP ${CODE:-<geen>}: $(fsc_last_error) — draait de demo-stack?"
else
  echo "  bericht '${MERK}' aangeleverd (HTTP ${CODE}); wachten op de outbox-poller..."

  # WireMock's count-API in plaats van de journal uitkammen: dan telt de matcher en niet een grep
  # over een JSON-blob waarin het merk toevallig meer dan één keer voorkomt (het staat in zowel
  # onderwerp als inhoud). tel_afleveringen <extra-matchers-json> geeft het aantal terug.
  tel_afleveringen() {
    curl -sS --noproxy '*' --max-time 10 -X POST "${NOTIFICATIE_STUB}/__admin/requests/count" \
      -H 'Content-Type: application/json' \
      -d "{\"method\":\"POST\",\"url\":\"/events\",\"bodyPatterns\":[{\"contains\":\"${MERK}\"}]$1}" \
      2>"$ERRLOG" \
      | sed -n 's/.*"count"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p'
  }

  AANTAL=0
  elapsed=0

  while [ "$elapsed" -lt "$PUBLICATIE_TIMEOUT" ]; do
    AANTAL="$(tel_afleveringen "" || true)"

    if [ "${AANTAL:-0}" -ge 1 ] 2>/dev/null; then
      break
    fi

    sleep "$PUBLICATIE_INTERVAL"
    elapsed=$((elapsed + PUBLICATIE_INTERVAL))
  done

  if [ "${AANTAL:-0}" -ge 1 ] 2>/dev/null; then
    ok "de stub ontving de CloudEvent van '${MERK}' (na ${elapsed}s)"

    # Zelfde telling, nu mét de header-eis. Blijft hij gelijk, dan heeft de inway het Content-Type
    # ongewijzigd doorgegeven — structured content mode overleeft de keten.
    MET_HEADER="$(tel_afleveringen ',"headers":{"Content-Type":{"contains":"application/cloudevents+json"}}' || true)"

    if [ "${MET_HEADER:-0}" = "$AANTAL" ]; then
      ok "de inway gaf Content-Type application/cloudevents+json ongewijzigd door"
    else
      fout "${MET_HEADER:-0} van ${AANTAL} afleveringen droeg het cloudevents-Content-Type — structured mode gesneuveld in de keten?"
    fi
  else
    fout "de stub ontving geen event voor '${MERK}' binnen ${PUBLICATIE_TIMEOUT}s"
  fi
fi

# --- 2. Verantwoording ---------------------------------------------------------------------------
echo "== 2. verantwoording in beide txlogs =="
if [ -z "${PROJECT:-}" ]; then
  fout "projectnaam van de gastheer niet af te leiden — deze assert heeft niets gemeten"
elif [ "$VOOR_OK" -ne 1 ]; then
  fout "de nulmeting op de txlogs mislukte — deze assert heeft niets gemeten"
elif ! NA="$(gedeelde_txids)"; then
  fout "de txlogs zijn na afloop niet leesbaar — deze assert heeft niets gemeten"
else
  NIEUW="$(comm -13 <(printf '%s\n' "$VOOR") <(printf '%s\n' "$NA") | grep -c . || true)"

  if [ "${NIEUW:-0}" -gt 0 ]; then
    ok "${NIEUW} nieuwe transactie(s) in beide txlogs, uitgaand bij ${PUSHER} en inkomend bij ${NOTIFICATIE}"
  else
    fout "geen NIEUWE gedeelde transactie-id — het magazijn ging buiten de outway om"
  fi
fi

# --- 3. Fire-and-forget --------------------------------------------------------------------------
# Precies één aflevering voor dit merk. Meer betekent dat het magazijn het 202 niet terugkreeg en in
# de retry-lus zat; dat zou de fire-and-forget-eigenschap stilzwijgend hebben opgegeven.
echo "== 3. één aflevering, geen retry-stapeling =="
if [ "${AANTAL:-0}" -eq 0 ] 2>/dev/null; then
  fout "geen aflevering gevonden voor '${MERK}' — assert 1 was al rood"
elif [ "$AANTAL" -eq 1 ]; then
  ok "het magazijn leverde één keer af en kreeg zijn 202 terug"
else
  fout "${AANTAL} afleveringen voor hetzelfde bericht — het antwoord van de stub komt niet terug door de keten"
fi

echo
if [ "$FOUTEN" -eq 0 ]; then
  echo "== NOTIFICATIE-SMOKE GROEN =="
else
  echo "== NOTIFICATIE-SMOKE ROOD: ${FOUTEN} bevinding(en) ==" >&2
  exit 1
fi
```

- [ ] **Stap 2: Uitvoerbaar maken en shellcheck**

```bash
chmod +x demo/environment/federatie/smoke-notificatie.sh
shellcheck demo/environment/federatie/smoke-notificatie.sh
```

Expected: geen bevindingen.

- [ ] **Stap 3: Commit**

```bash
git add demo/environment/federatie/smoke-notificatie.sh
git commit -m "test(demo): smoke voor de notificatie-push door de FSC-keten"
```

---

### Taak 9: End-to-end draaien

Geen bestandswijzigingen tenzij een assert rood is — dan repareren in de taak waar de fout hoort en dáár committen.

**Files:**
- Geen (verificatie).

**Interfaces:**
- Consumes: Taak 1 t/m 8.

- [ ] **Stap 1: Group-CA opnieuw delen**

De outway van `magazijn-a` heeft een vers group-cert dat nog niet onder de gedeelde CA hangt.

```bash
cd demo/environment
./federatie/deel-groep-ca.sh --check
./federatie/deel-groep-ca.sh
```

Expected: `--check` toont wat er gebeurt; de tweede run voert het uit zonder fout.

- [ ] **Stap 2: Federatie opzetten**

```bash
cd demo/environment
./federatie/federatie.sh down || true
./federatie/federatie.sh up
./federatie/smoke-federatie.sh
```

Expected: `FEDERATIE-SMOKE GROEN.` Draait de outway van magazijn-a mee? Controleer met
`./federatie/federatie.sh status`; er hoort een listener op `127.20.2.5:8443` te staan.

- [ ] **Stap 3: Contracten**

```bash
cd demo/environment
./federatie/contracts/fbs-contracten.sh
grep -c GRANT_HASH ../generated/fsc-grants.env
```

Expected: `FBS-CONTRACTEN OK (2 contract(en)).` en `2` regels — `MAGAZIJN_A_GRANT_HASH` en `NOTIFICATIE_GRANT_HASH`.

- [ ] **Stap 4: Demo-stack met de push door de outway**

```bash
MODUS=hostnet NOTIFICATIE_URL=http://127.20.2.5:8443/events ./demo/podman-up.sh
```

Expected: de stack komt op. Controleer daarna in de log van het magazijn dat de bypass-regel er staat:

```bash
podman logs $(podman ps --format '{{.Names}}' | grep -m1 berichtenmagazijn) 2>&1 | grep DOWNSTREAM_VIA_OUTWAY
```

Expected: één regel die `notificatie` noemt. Ontbreekt hij, dan is `NOTIFICATIE_GRANT_HASH` niet
doorgekomen — compose leest `env_file` bij het **aanmaken** van een container, dus een al draaiende
stack houdt de oude waarde tot je hem hercreëert.

- [ ] **Stap 5: De smoke**

```bash
./demo/environment/federatie/smoke-notificatie.sh
```

Expected: `== NOTIFICATIE-SMOKE GROEN ==`.

- [ ] **Stap 6: Bewijs dat het bestaande pad niet gesneuveld is**

```bash
./demo/environment/federatie/smoke-contract.sh
MODUS=hostnet MAGAZIJN_A_URL=http://127.20.1.5:8443 ./demo/environment/federatie/smoke-keten.sh
```

Expected: beide groen. `smoke-keten.sh` bewijst dat het ophaal-pad nog werkt nu de inway van
`logius` twee diensten draagt en `fsc_zet_upstream` een argument erbij heeft.

- [ ] **Stap 7: Opruimen**

Er is geen `podman-down.sh`; breek de stack af met dezelfde drie compose-bestanden die
`podman-up.sh` in hostnet-modus stapelt (zie `demo/podman-up.sh:201-204`):

```bash
docker compose -f compose.yaml -f compose.podman.yaml -f compose.podman-hostnet.yaml down -v
cd demo/environment && ./federatie/federatie.sh down
```

- [ ] **Stap 8: Noteer de uitkomst**

Leg de uitkomst van elke smoke vast (groen/rood + eventuele afwijking) voor de PR-body. Rapporteer
een rode assert als zodanig; niet wegpoetsen door een timeout op te hogen zonder reden.

---

### Taak 10: Documentatie en ZAD-runbooks

**Files:**
- Modify: `demo/environment/federatie/README.md`
- Modify: `demo/environment/logius/deploy/zad/verify-zad.md`
- Modify: `demo/environment/magazijn-a/deploy/zad/verify-zad.md`
- Modify: `docs/plans/2026-08-17-notificatie-via-fsc-design.md`
- Modify: `docs/plans/2026-08-17-notificatie-via-fsc-plan.md`

**Interfaces:**
- Consumes: de bewezen stand uit Taak 9.

- [ ] **Stap 1: Federatie-README — contracten en smokes**

Voeg in `demo/environment/federatie/README.md` onder "Contracten", ná het codeblok met
`fbs-contracten.sh`, toe:

```markdown
`fbs-contracten.sh` zet twee soorten contract op: één per magazijn zodat de uitvraag-outway
`berichtenmagazijn` mag ophalen, en één per pusher zodat het magazijn zijn CloudEvents kwijt kan bij
`notificatieservice`. Die tweede loopt de andere kant op — het magazijn is daar de afnemer.

```bash
./federatie/smoke-notificatie.sh   # bewijst de push: outway magazijn -> inway aanbieder -> stub
```

Voor de push moet de demo-stack het magazijn door de outway laten leveren:

```bash
MODUS=hostnet NOTIFICATIE_URL=http://127.20.2.5:8443/events demo/podman-up.sh
```
```

- [ ] **Stap 2: ZAD-runbook van de aanbieder**

Voeg onderaan `demo/environment/logius/deploy/zad/verify-zad.md` toe:

```markdown
### Inbound data-pad — notificatieservice (lokaal bewezen, ZAD-apply is handmatig vervolgwerk)

Lokaal bewezen in `federatie/` (`smoke-notificatie.sh`, zie
`docs/plans/2026-08-17-notificatie-via-fsc-plan.md`): `logius` biedt naast `profiel-service` ook
`notificatieservice` aan op dezelfde inway, met de notificatie-stub als upstream. Op ZAD moet dit
nog worden herhaald tegen de échte infrastructuur:

1. `CreateService` via de `logius-fscctl` Administration-API (`SERVICE_NAME=notificatieservice`,
   `endpoint_url` = de echte notificatiedienst, `inway_address` = `SELF_ADDRESS` van
   `logius-fscinway`).
2. Het `serviceConnection`-contract met het magazijn opzetten (`bootstrap-consumer.sh` aan
   magazijn-kant, `bootstrap-provider.sh` hier — zie `federatie/contracts/zad-runbook.md`).
3. `NOTIFICATIE_URL=https://fsc-magazijna-magazijna-fscoutway:8443/events` en
   `NOTIFICATIE_GRANT_HASH=<grant-hash uit stap 2>` als env-vars op het gedeployde
   `berichtenmagazijn` (project `mpfm-w3h`).
4. Een smoke voor het pad `berichtenmagazijn → magazijna-fscoutway → logius-fscinway → upstream`.
```

- [ ] **Stap 3: ZAD-runbook van de pusher**

Voeg onderaan `demo/environment/magazijn-a/deploy/zad/verify-zad.md` toe:

```markdown
### Outway — uitgaand verkeer van het magazijn

`magazijn-a` heeft sinds de notificatie-push een outway (`magazijna-fscoutway`). Die is nieuw op
ZAD en moet daar nog aangemaakt worden: component toevoegen aan de projectspec, certificaten uit
`pki/peers/magazijn-a/outway/` uitgeven en bijlagen koppelen zoals `cert-manifest.md` dat voor de
andere componenten beschrijft. De outway is egress-only en heeft geen ingress-route nodig.

Zonder `NOTIFICATIE_GRANT_HASH` op het magazijn blijft de aflevering rechtstreeks lopen; het
component kan dus vooruitlopend worden uitgerold zonder gedrag te veranderen.
```

- [ ] **Stap 4: Status van design en plan bijwerken**

Zet in beide documenten de kopregel om:

```markdown
**Status:** Uitgevoerd
```

- [ ] **Stap 5: Commit**

```bash
git add demo/environment/federatie/README.md \
        demo/environment/logius/deploy/zad/verify-zad.md \
        demo/environment/magazijn-a/deploy/zad/verify-zad.md \
        docs/plans/2026-08-17-notificatie-via-fsc-design.md \
        docs/plans/2026-08-17-notificatie-via-fsc-plan.md
git commit -m "docs(demo): leg de notificatie-push via FSC vast in runbooks en README"
```

- [ ] **Stap 6: Reviewronde vóór de PR**

Draai `/code-review` op het volledige diff van de branch en verwerk de bevindingen; commit de
reparaties in de taak waar ze horen. Doe dit ná Taak 9, niet ervoor: een rode smoke verandert de
code die je anders al hebt laten reviewen.

- [ ] **Stap 7: PR openen**

Schrijf de PR-body naar een bestand en gebruik dat — `gh pr create --body` met meerregelige tekst
is foutgevoelig, en `gh pr edit` faalt in deze repo op Projects-classic:

```bash
cat > /tmp/pr-784.md <<'EOF'
Sluit het notificatie-pad aan op de FSC-mesh: het magazijn levert zijn CloudEvents af door zijn
eigen outway bij de inway van de aanbieder, in plaats van rechtstreeks op de stub.

Twee afwijkingen van het issue, beide bewust:

- **Niet config-only.** De outway kiest de doel-inway op `Fsc-Grant-Hash` en eist een
  `Fsc-Transaction-Id` in UUID-v7. Die headers worden gezet door JAX-RS-clientfilters, en
  `DownstreamClient` is bewust géén JAX-RS-client. Alleen de URL ombuigen levert `service not
  found`. Vandaar een grant-hash per downstream plus het headerpaar op de uitgaande request.
- **Geen eigen `notificatie`-peer.** De dienst staat op de bestaande `logius`-inway, naast
  `profiel-service`. Een derde peer-stack kost een volledige compose-, PKI- en
  adresruimte-duplicatie zonder iets extra's te bewijzen over de eigenschap die dit issue
  aantoont: een magazijn dat uitgaand door FSC pusht. Verhuizen is later een publicatie- en
  contractwijziging, geen wijziging aan het magazijn.

Daarnaast: een downstream mét grant-hash slaat de SSRF-blocklist over. De bestemming volgt daar uit
het FSC-contract in plaats van uit onze URL, en op ZAD resolveert de outway naar een RFC1918-adres
waar de blocklist anders op afketst. De uitzondering logt bij boot een regel met
`DOWNSTREAM_VIA_OUTWAY`. De TLS-eis blijft onverkort staan.

Ontwerp: `docs/plans/2026-08-17-notificatie-via-fsc-design.md`
Plan: `docs/plans/2026-08-17-notificatie-via-fsc-plan.md`

Lokaal bewezen met `demo/environment/federatie/smoke-notificatie.sh`; `smoke-contract.sh` en
`smoke-keten.sh` blijven groen.

Werkt aan #784.
EOF

git push -u origin feature/784-notificatie-via-fsc
gh pr create --title "Notificatie-events door de FSC-keten (#784)" --body-file /tmp/pr-784.md
```

Geen reviewer toevoegen. Let op de laatste regel van de body: "Werkt aan #784" en niet `Closes`/
`Fixes` — het issue moet openblijven bij merge.
