**Status:** Fase 1 (lokaal) uitgevoerd. Fase 2 uitgevoerd voor de console; de storingsknoppen zijn
eruit gehaald — zie de herziening onder "Fase 2".

# Demo draaibaar op laptop én ZAD — implementatieplan

> **Voor agentische uitvoerders:** gebruik `superpowers:subagent-driven-development` (aanbevolen)
> of `superpowers:executing-plans` om dit plan taak voor taak uit te voeren. Stappen gebruiken
> checkbox-syntax (`- [ ]`) voor het bijhouden.

**Doel:** de demo van de berichtenbox is met één gedocumenteerde handeling lokaal te starten en
staat als gedeelde omgeving op ZAD, met dezelfde scenario's, een instelbaar tempo en een
herstelknop.

**Aanpak:** alle codewijzigingen zitten in `demo/demo-console`; het stelsel blijft ongemoeid. Op
ZAD komt een preview-loze deployment `demo` in de drie bestaande projecten. Storingsinjectie loopt
via Toxiproxy-instanties die elk vóór hun eigen upstream achter een TLS-terminerende ingress staan,
zodat geen enkele https-eis in de code uitgezet hoeft te worden.

**Techniek:** Kotlin, Quarkus 3.38, MockK + JUnit 5, Toxiproxy 2.12.0, ZAD Operations Manager v2,
GitHub Actions.

**Ontwerp:** `docs/plans/2026-08-26-demo-op-zad-design.md` — lees dat eerst; dit plan argumenteert
eruit.

**Issue:** MinBZK/MijnOverheidZakelijk#936

## Globale randvoorwaarden

- Communicatie en documentatie in het Nederlands; domeinbegrippen Nederlands, vaste technische
  idiomen (proxy, toxic, scheduler, stream, connection, root) Engels.
- Kotlin-stijl: lege regel vóór én ná elk multi-line blok en elk zelfstandig control-statement.
- Commentaar legt het *waarom* vast, niet het *wat*. Geen verwijzingen naar CLAUDE.md, geen
  review-iteratielabels, geen "PoC"/"voorlopig".
- detekt draait over `demo/demo-console` met `maxIssues: 0` en zonder baseline. Elke bevinding
  faalt de build; bewuste uitzonderingen krijgen een inline `@Suppress` met motivatie.
- `demo/demo-console` heeft **geen** JaCoCo-gate en **geen** reactor-afhankelijkheden. Voeg er geen
  toe: `.github/scripts/demo-grens.sh` bewaakt de andere richting, maar de nul-afhankelijkheid van
  deze module is een bewuste keuze (`demo/README.md`).
- Tests in deze module zijn **pure JVM**. Voeg geen Testcontainers of `@QuarkusTest` toe; de
  demo-shard van `test.yml` rekent op een Docker-loze module.
- Altijd `clean` vóór `test`: `./mvnw clean test -pl demo/demo-console -am`.
- Nooit direct naar `main` pushen. Branch `feature/demo-op-zad`, PR als **draft**, geen reviewers.
- PR-body sluitregel: `Closes MinBZK/MijnOverheidZakelijk#936`.

## Bestandsindeling

| Bestand | Verantwoordelijkheid |
|---|---|
| `storing/ToxiproxyConfig.kt` (nieuw) | `@ConfigMapping` van proxy-naam naar admin-URL |
| `storing/ToxiproxyAdressen.kt` (nieuw) | Welke proxy op welk adres staat; is de allowlist. Pure JVM, dus toetsbaar |
| `storing/ToxiproxyRegister.kt` (nieuw) | Bedrading: één REST-client per uniek adres |
| `storing/StoringService.kt` (wijzig) | Werkt via het register; `reset()` over alle instanties |
| `storing/StoringResource.kt` (wijzig) | Levert alleen nog HTTP-vorm; validatie zit in het register |
| `omgeving/OmgevingResource.kt` (nieuw) | `GET /api/demo/omgeving` — API-basis en beschikbare storingen |
| `omgeving/OmgevingConfig.kt` (nieuw) | `@ConfigMapping` voor de browser-zichtbare uitvraag-basis |
| `tempo/TempoKlok.kt` (nieuw) | Seam rond de Quarkus-scheduler, zodat `TempoService` pure-JVM testbaar is |
| `tempo/TempoService.kt` (nieuw) | De stroom: grenzen, tellers, auto-stop |
| `tempo/TempoResource.kt` (nieuw) | `POST /tempo/start`, `POST /tempo/stop`, `GET /tempo` |
| `herstel/HerstelService.kt` (nieuw) | Tempo stoppen, storingen resetten, legen, basisvulling |
| `legen/MagazijnDatabase.kt` (wijzig) | LDV-tabel meenemen, achter een `to_regclass`-guard |
| `demo-console/README.md` (nieuw) | De README waar acceptatiecriterium 1 om vraagt |
| `demo/environment/zad-demo/` (nieuw) | `README.md` met de eenmalige OM-stappen, `verify-zad.md` met de verificatie erna |

---

# Fase 1 — lokaal

## Taak 1: Toxiproxy-register

Lokaal draait één Toxiproxy met zes proxies; op ZAD vier instanties met elk één proxy. Het register
maakt dat een configuratiekwestie in plaats van een codekwestie, en vervangt de ingetypte
`INFRA_PROXIES`-allowlist.

**Bestanden:**
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing/ToxiproxyConfig.kt`
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing/ToxiproxyAdressen.kt`
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing/ToxiproxyRegister.kt`
- Wijzig: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing/StoringService.kt`
- Wijzig: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing/StoringResource.kt`
- Wijzig: `demo/demo-console/src/main/resources/application.properties`
- Nieuw: `demo/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing/ToxiproxyAdressenTest.kt`
- Wijzig: `demo/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing/StoringServiceTest.kt`
- Wijzig: `demo/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/storing/StoringResourceTest.kt`

**Interfaces:**
- Gebruikt: `ToxiproxyClient` (bestaand, ongewijzigd), `QuarkusRestClientBuilder` — zelfde patroon
  als `AanleverService`.
- Levert: `ToxiproxyAdressen.namen(): Set<String>`, `.adres(proxy: String): String`,
  `.unieke(): List<String>` — de beslissing, zonder clients. En daarbovenop
  `ToxiproxyRegister.namen(): Set<String>`, `.client(proxy: String): ToxiproxyClient`,
  `.instanties(): Collection<ToxiproxyClient>`. Taak 2 gebruikt `ToxiproxyRegister.namen()`.

**Waarom die splitsing:** `QuarkusRestClientBuilder` heeft een draaiende Quarkus-runtime nodig, en
deze module houdt zijn tests bewust pure JVM. `ToxiproxyAdressen` draagt daarom alle logica die het
waard is om te toetsen — welke proxy bestaat, welke instanties uniek zijn — en `ToxiproxyRegister`
is de dunne bedradingslaag eromheen.

- [ ] **Stap 1: schrijf de falende test voor de adressen**

`ToxiproxyAdressenTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToxiproxyAdressenTest {

    private fun adressen(vararg paren: Pair<String, String>) =
        ToxiproxyAdressen(object : ToxiproxyConfig {
            override fun toxiproxy() = paren.toMap().mapValues { (_, adres) ->
                object : ToxiproxyConfig.Instantie {
                    override fun url() = adres
                }
            }
        })

    @Test
    fun `een lege configuratie kent geen namen en geen instanties`() {
        val leeg = adressen()

        assertTrue(leeg.namen().isEmpty())
        assertTrue(leeg.unieke().isEmpty())
    }

    @Test
    fun `een configuratie van een proxy levert die proxy`() {
        val een = adressen("profiel" to "http://een:8474")

        assertEquals(setOf("profiel"), een.namen())
        assertEquals(listOf("http://een:8474"), een.unieke())
    }

    @Test
    fun `proxies op hetzelfde adres tellen als een instantie`() {
        // De lokale stack: zes proxies op één Toxiproxy. Zonder ontdubbeling zou reset()
        // dezelfde instantie zes keer langsgaan.
        val gedeeld = adressen("profiel" to "http://een:8474", "redis" to "http://een:8474")

        assertEquals(listOf("http://een:8474"), gedeeld.unieke())
        assertEquals(gedeeld.adres("profiel"), gedeeld.adres("redis"))
    }

    @Test
    fun `proxies op verschillende adressen tellen elk als eigen instantie`() {
        // De ZAD-stack: elke stroom een eigen Toxiproxy vóór zijn upstream.
        val gesplitst = adressen("profiel" to "http://een:8474", "redis" to "http://twee:8474")

        assertEquals(2, gesplitst.unieke().size)
    }

    @Test
    fun `een onbekende proxy wordt geweigerd met de geconfigureerde namen erbij`() {
        val een = adressen("profiel" to "http://een:8474")

        val fout = assertThrows(BadRequestException::class.java) { een.adres("magazijn-a") }

        assertTrue(fout.message!!.contains("magazijn-a"), "melding moet de gevraagde naam noemen")
        assertTrue(fout.message!!.contains("profiel"), "melding moet de beschikbare namen noemen")
    }
}
```

- [ ] **Stap 2: draai de test en stel vast dat hij faalt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=ToxiproxyAdressenTest`
Verwacht: compilatiefout — `ToxiproxyConfig` en `ToxiproxyAdressen` bestaan niet.

- [ ] **Stap 3: schrijf de configuratie-interface**

`ToxiproxyConfig.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.smallrye.config.ConfigMapping

/**
 * Toxiproxy-instanties uit config: `demo.toxiproxy."<proxy>".url`. Lokaal wijzen alle proxies naar
 * één instantie; op ZAD staat elke stroom achter zijn eigen Toxiproxy vóór zijn upstream, omdat
 * een ZAD-component precies één poort publiceert. `@ConfigMapping` leest map-keys mét
 * aanhalingstekens betrouwbaar; een kale `@ConfigProperty Map` doet dat niet.
 */
@ConfigMapping(prefix = "demo")
interface ToxiproxyConfig {

    fun toxiproxy(): Map<String, Instantie>

    interface Instantie {

        fun url(): String
    }
}
```

- [ ] **Stap 4: schrijf de adressen en het register**

`ToxiproxyAdressen.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.BadRequestException

/**
 * Welke proxy op welk adres staat. Deze laag ís de allowlist: een knop voor een
 * niet-geconfigureerde proxy wordt hier geweigerd, zodat het paneel geen willekeurige naam kan
 * doorzetten en een omgeving zonder magazijn-proxies een nette melding geeft in plaats van een 500.
 *
 * Los van [ToxiproxyRegister] omdat het bouwen van REST-clients een draaiende Quarkus vraagt: zo
 * blijft de beslissing — welke proxy bestaat, welke instanties zijn uniek — toetsbaar in een pure
 * unittest.
 */
internal class ToxiproxyAdressen(config: ToxiproxyConfig) {

    private val perProxy: Map<String, String> = config.toxiproxy().mapValues { (_, instantie) -> instantie.url() }

    fun namen(): Set<String> = perProxy.keys

    fun adres(proxy: String): String =
        perProxy[proxy] ?: throw BadRequestException(
            "onbekende proxy '$proxy'; geconfigureerd: ${namen().sorted()}",
        )

    // Eén ingang per uniek adres, niet per proxy: lokaal delen alle proxies één instantie, en
    // reset() moet elke instantie precies één keer langsgaan.
    fun unieke(): List<String> = perProxy.values.distinct()
}
```

`ToxiproxyRegister.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI

/** Bedrading: één REST-client per uniek Toxiproxy-adres, gebouwd zoals `AanleverService` dat doet. */
@ApplicationScoped
class ToxiproxyRegister(config: ToxiproxyConfig) {

    private val adressen = ToxiproxyAdressen(config)

    private val perAdres: Map<String, ToxiproxyClient> =
        adressen.unieke().associateWith { adres ->
            QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(adres))
                .build(ToxiproxyClient::class.java)
        }

    fun namen(): Set<String> = adressen.namen()

    fun client(proxy: String): ToxiproxyClient = perAdres.getValue(adressen.adres(proxy))

    fun instanties(): Collection<ToxiproxyClient> = perAdres.values
}
```

- [ ] **Stap 5: draai de adressen-test en stel vast dat hij slaagt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=ToxiproxyAdressenTest`
Verwacht: PASS, vijf tests.

- [ ] **Stap 6: pas `StoringServiceTest` aan op het register**

Vervang in `StoringServiceTest.kt` het veld `toxiproxy`/`service` en voeg twee tests toe. De
bestaande vier tests blijven inhoudelijk gelijk; alleen de opbouw verandert:

```kotlin
    private val instantie = mockk<ToxiproxyClient>(relaxed = false)
    private val tweede = mockk<ToxiproxyClient>(relaxed = false)

    private fun registerMet(vararg clients: Pair<String, ToxiproxyClient>) =
        mockk<ToxiproxyRegister> {
            every { client(any()) } answers { clients.toMap()[firstArg()] ?: error("niet geconfigureerd") }
            every { instanties() } returns clients.map { it.second }.distinct()
        }

    private val service = StoringService(registerMet("magazijn-a" to instantie, "magazijn-b" to instantie))
```

Twee nieuwe tests:

```kotlin
    @Test
    fun `reset gaat elke instantie langs`() {
        // Op ZAD staat elke stroom op zijn eigen Toxiproxy; een reset die er maar één langsgaat
        // laat de rest van de storingen aan staan.
        every { instantie.proxies() } returns mapOf("profiel" to ProxyStatus(enabled = false))
        every { tweede.proxies() } returns mapOf("redis" to ProxyStatus(enabled = false))
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { tweede.zetProxy(any(), any()) } returns ok()

        StoringService(registerMet("profiel" to instantie, "redis" to tweede)).reset()

        verify { instantie.zetProxy("profiel", ProxyPatch(enabled = true)) }
        verify { tweede.zetProxy("redis", ProxyPatch(enabled = true)) }
    }

    @Test
    fun `reset faalt zodra een van de instanties geen enkele proxy kent`() {
        every { instantie.proxies() } returns mapOf("profiel" to ProxyStatus(enabled = true))
        every { tweede.proxies() } returns emptyMap()

        val fout = assertThrows(IllegalStateException::class.java) {
            StoringService(registerMet("profiel" to instantie, "redis" to tweede)).reset()
        }

        assertTrue(fout.message!!.contains("proxies.json"), "melding moet naar de oorzaak wijzen, was: ${fout.message}")
    }
```

- [ ] **Stap 7: draai de testen en stel vast dat ze falen**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=StoringServiceTest`
Verwacht: compilatiefout — `StoringService` neemt nog een `ToxiproxyClient`.

- [ ] **Stap 8: pas `StoringService` aan**

Vervang de constructor en `reset()`:

```kotlin
@ApplicationScoped
class StoringService(private val register: ToxiproxyRegister) {

    fun traag(proxy: String, latencyMs: Int) {
        controleer(
            register.client(proxy).voegToxicToe(proxy, ToxicVerzoek("latency", mapOf("latency" to latencyMs))),
            "traag zetten van $proxy",
        )
    }

    fun uit(proxy: String) {
        controleer(register.client(proxy).zetProxy(proxy, ProxyPatch(enabled = false)), "uitschakelen van $proxy")
    }

    // Herstel: elke proxy op elke instantie weer aan, alle toxics weg.
    fun reset() {
        register.instanties().forEach { instantie ->
            herstel(instantie)
        }
    }

    private fun herstel(instantie: ToxiproxyClient) {
        val proxies = instantie.proxies()

        // Toxiproxy start gezond op met nul proxies zodra zijn configuratie ontbreekt of misvormd
        // is. Al het verkeer van die stroom loopt erdoorheen, dus dan is de keten dood — en juist
        // deze knop moet dat aanwijzen in plaats van "alles normaal" te bevestigen.
        check(proxies.isNotEmpty()) {
            "Toxiproxy kent geen enkele proxy: de keten loopt nergens doorheen. Controleer proxies.json en herstart toxiproxy."
        }

        proxies.forEach { (naam, status) ->
            if (!status.enabled) {
                controleer(instantie.zetProxy(naam, ProxyPatch(enabled = true)), "inschakelen van $naam")
            }

            status.toxics.forEach { toxic ->
                controleer(instantie.verwijderToxic(naam, toxic.name), "verwijderen toxic ${toxic.name} van $naam")
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

- [ ] **Stap 9: haal de ingetypte allowlist uit `StoringResource`**

Verwijder `INFRA_PROXIES` en de controle in `infraUit`; het register weigert nu. Het
`companion object` houdt alleen `LATENCY_MS`:

```kotlin
    @POST
    @Path("/{proxy}/uit")
    fun infraUit(@PathParam("proxy") proxy: String): Map<String, String> {
        storingService.uit(proxy)

        return mapOf("status" to "$proxy uit")
    }

    private companion object {

        const val LATENCY_MS = 6000
    }
```

Vervang in `StoringResourceTest.kt` de allowlist-tests door delegatie-tests; de weigering zelf
wordt in `ToxiproxyRegisterTest` gedekt:

```kotlin
    @ParameterizedTest
    @ValueSource(strings = ["profiel", "redis", "notificatie", "aanmeld", "magazijn-a"])
    fun `infraUit geeft de naam onveranderd door aan de service`(proxy: String) {
        every { storingService.uit(proxy) } returns Unit

        resource.infraUit(proxy)

        verify { storingService.uit(proxy) }
    }

    @Test
    fun `infraUit laat een weigering van het register door`() {
        // Het register is de allowlist; de resource mag die beslissing niet dubbel nemen, want
        // twee lijsten lopen uiteen zodra de configuratie verandert.
        every { storingService.uit("onbekend") } throws BadRequestException("onbekende proxy")

        assertThrows(BadRequestException::class.java) { resource.infraUit("onbekend") }
    }
```

- [ ] **Stap 10: zet de configuratie neer**

In `application.properties`, ter vervanging van `quarkus.rest-client.toxiproxy.url`:

```properties
# Toxiproxy-instanties per proxy. Lokaal wijzen ze alle zes naar dezelfde container; op ZAD staat
# elke stroom achter zijn eigen Toxiproxy vóór zijn upstream, want een ZAD-component publiceert
# precies één poort. De magazijn-proxies ontbreken daar bewust: hun storingsgedrag komt uit de
# simulator (TODO(#938)).
demo.toxiproxy."profiel".url=${TOXIPROXY_PROFIEL_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."notificatie".url=${TOXIPROXY_NOTIFICATIE_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."aanmeld".url=${TOXIPROXY_AANMELD_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."redis".url=${TOXIPROXY_REDIS_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."magazijn-a".url=${TOXIPROXY_MAGAZIJN_A_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."magazijn-b".url=${TOXIPROXY_MAGAZIJN_B_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
```

`TOXIPROXY_ADMIN_URL` blijft daarmee werken zoals in `compose.yaml`: één env-var zet alle zes
tegelijk, en per stroom overschrijven kan.

- [ ] **Stap 11: draai de volledige module en stel vast dat alles slaagt**

Draai: `./mvnw clean test -pl demo/demo-console -am`
Verwacht: BUILD SUCCESS, alle bestaande tests plus de nieuwe.

- [ ] **Stap 12: draai detekt**

Draai: `./mvnw detekt:check -pl demo/demo-console`
Verwacht: BUILD SUCCESS, nul bevindingen.

- [ ] **Stap 13: commit**

```bash
git add demo/demo-console/src demo/demo-console/src/main/resources/application.properties
git commit -m "feat(demo): Toxiproxy-instanties uit config in plaats van één vast adres"
```

---

## Taak 2: Omgevings-endpoint en UI

De Berichtenbox-pagina leidt zijn API-adres nu af uit de browser-locatie plus een vaste poort 8086.
Op ZAD bestaat die poort niet en is het schema https.

**Bestanden:**
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/omgeving/OmgevingConfig.kt`
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/omgeving/OmgevingResource.kt`
- Wijzig: `demo/demo-console/src/main/resources/META-INF/resources/berichtenbox.js`
- Wijzig: `demo/demo-console/src/main/resources/META-INF/resources/index.html`
- Wijzig: `demo/demo-console/src/main/resources/application.properties`
- Nieuw: `demo/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/omgeving/OmgevingResourceTest.kt`

**Interfaces:**
- Gebruikt: `ToxiproxyRegister.namen()` uit taak 1.
- Levert: `GET /api/demo/omgeving` → `Omgeving(uitvraagBasis: String, storingen: List<String>)`.

- [ ] **Stap 1: schrijf de falende test**

`OmgevingResourceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

class OmgevingResourceTest {

    private fun resource(basis: String?, vararg proxies: String): OmgevingResource {
        val config = mockk<OmgevingConfig> { every { uitvraagBasis() } returns Optional.ofNullable(basis) }
        val register = mockk<ToxiproxyRegister> { every { namen() } returns proxies.toSet() }

        return OmgevingResource(config, register)
    }

    @Test
    fun `zonder geconfigureerde basis blijft het veld leeg zodat de pagina terugvalt`() {
        // Lokaal is er geen vaste basis: de pagina leidt hem dan af uit de browser-locatie, wat
        // ook op een VM- of containeradres werkt. Een verzonnen default zou dat breken.
        assertEquals("", resource(null).omgeving().uitvraagBasis)
    }

    @Test
    fun `een geconfigureerde basis komt ongewijzigd door`() {
        val basis = "https://uitvraag-demo-mpfb-8wh.example/api/v1"

        assertEquals(basis, resource(basis).omgeving().uitvraagBasis)
    }

    @Test
    fun `storingen spiegelt het register, gesorteerd`() {
        assertEquals(
            listOf("aanmeld", "profiel", "redis"),
            resource(null, "redis", "profiel", "aanmeld").omgeving().storingen,
        )
    }

    @Test
    fun `een omgeving zonder storingen levert een lege lijst en geen fout`() {
        assertEquals(emptyList<String>(), resource(null).omgeving().storingen)
    }
}
```

- [ ] **Stap 2: draai de test en stel vast dat hij faalt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=OmgevingResourceTest`
Verwacht: compilatiefout — `OmgevingConfig` en `OmgevingResource` bestaan niet.

- [ ] **Stap 3: schrijf de configuratie**

`OmgevingConfig.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import io.smallrye.config.ConfigMapping
import java.util.Optional

/**
 * Het adres van de uitvraag zoals de *browser* het moet gebruiken. Bewust los van
 * `quarkus.rest-client.uitvraag.url`, dat de console zelf server-side aanroept en container-interne
 * DNS mag zijn: dat adres is vanuit een browser onbereikbaar. Leeg laten betekent "leid het af uit
 * de browser-locatie", wat lokaal het gewenste gedrag is.
 */
@ConfigMapping(prefix = "demo.omgeving")
interface OmgevingConfig {

    fun uitvraagBasis(): Optional<String>
}
```

`OmgevingResource.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister

/**
 * Wat de statische pagina's over hun omgeving moeten weten. Zonder dit endpoint zou de
 * Berichtenbox-pagina zijn API-adres moeten raden en zouden de storingsknoppen per omgeving
 * verschillen — twee varianten van dezelfde pagina, die gegarandeerd uit elkaar lopen.
 */
data class Omgeving(val uitvraagBasis: String, val storingen: List<String>)

@Path("/api/demo/omgeving")
@Produces(MediaType.APPLICATION_JSON)
class OmgevingResource(
    private val config: OmgevingConfig,
    private val register: ToxiproxyRegister,
) {

    @GET
    fun omgeving(): Omgeving = Omgeving(
        uitvraagBasis = config.uitvraagBasis().orElse(""),
        storingen = register.namen().sorted(),
    )
}
```

- [ ] **Stap 4: draai de test en stel vast dat hij slaagt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=OmgevingResourceTest`
Verwacht: PASS, vier tests.

- [ ] **Stap 5: zet de property neer**

In `application.properties`:

```properties
# Browser-zichtbaar adres van de uitvraag-API. Leeg lokaal: de Berichtenbox-pagina leidt het adres
# dan af uit de browser-locatie, wat ook op een VM-adres klopt. Op ZAD is er geen poort 8086 en is
# het schema https, dus daar zet de deployment deze waarde.
demo.omgeving.uitvraag-basis=${UITVRAAG_BASIS:}
```

- [ ] **Stap 6: laat de Berichtenbox-pagina het adres ophalen**

In `berichtenbox.js`, vervang de `const BASIS`-regel:

```js
// Adres van de uitvraag-API. De console levert het op /api/demo/omgeving, want de demo draait ook
// op ZAD, waar geen poort 8086 bestaat en het schema https is. Zolang die waarde leeg is (de
// lokale stack) valt de pagina terug op de browser-locatie plus de vaste dev-poort; elk adres
// waarop de demo dan geopend wordt moet in de CORS-allowlist van de uitvraag staan (`DEMO_HOST`).
let BASIS = `http://${window.location.hostname}:8086/api/v1`;

const omgevingGeladen = fetch('/api/demo/omgeving')
  .then((respons) => (respons.ok ? respons.json() : null))
  .then((omgeving) => {
    if (omgeving && omgeving.uitvraagBasis) BASIS = omgeving.uitvraagBasis;
  })
  .catch(() => {
    // Console onbereikbaar: de fallback hierboven blijft staan. Dit mag de pagina niet blokkeren —
    // een unhandled rejection zou elke knop stil laten falen.
  });
```

En laat `api()` erop wachten (de promise is na de eerste keer al vervuld, dus dit kost niets):

```js
async function api(pad, opties = {}) {
  await omgevingGeladen;

  const headers = Object.assign({ 'X-Ontvanger': huidigeOntvanger() }, opties.headers || {});

  try {
    return await fetch(BASIS + pad, Object.assign({}, opties, { headers }));
  } catch (fout) {
    return { ok: false, status: 0, netwerkfout: true, melding: 'geen verbinding: ' + fout };
  }
}
```

- [ ] **Stap 7: verberg storingsknoppen die deze omgeving niet heeft**

Geef in `index.html` elke storingsknop een `data-proxy`-attribuut. Voor de vier infra-knoppen is
dat de proxy-naam, voor de magazijn-knoppen `magazijn-a` respectievelijk `magazijn-b`:

```html
    <button data-proxy="magazijn-a" onclick="post('/api/demo/storing/magazijn/a/traag')">Magazijn A (RVO) traag</button>
    <button data-proxy="magazijn-a" onclick="post('/api/demo/storing/magazijn/a/uit')">Magazijn A (RVO) uit</button>
    <button data-proxy="magazijn-b" onclick="post('/api/demo/storing/magazijn/b/traag')">Magazijn B (Belastingdienst) traag</button>
    <button data-proxy="magazijn-b" onclick="post('/api/demo/storing/magazijn/b/uit')">Magazijn B (Belastingdienst) uit</button>
    <button data-proxy="redis" onclick="post('/api/demo/storing/redis/uit')">Redis uit (scenario 12)</button>
    <button data-proxy="profiel" onclick="post('/api/demo/storing/profiel/uit')">Profielservice uit (scenario 9)</button>
    <button data-proxy="notificatie" onclick="post('/api/demo/storing/notificatie/uit')">Notificatie uit (scenario 10)</button>
    <button data-proxy="aanmeld" onclick="post('/api/demo/storing/aanmeld/uit')">Uitvraag/aanmeld uit (scenario 11)</button>
```

En voeg onderaan het `<script>`-blok toe:

```js
    // Niet elke omgeving heeft elke proxy: op ZAD ontbreken de magazijn-storingen, omdat de
    // magazijnen hun gedrag daar uit de simulator krijgen. Een knop tonen die gegarandeerd een
    // 400 geeft, kost tijdens een demo uitleg die niets toevoegt.
    fetch('/api/demo/omgeving')
      .then((respons) => (respons.ok ? respons.json() : null))
      .then((omgeving) => {
        if (!omgeving) return;

        const beschikbaar = new Set(omgeving.storingen);

        document.querySelectorAll('button[data-proxy]').forEach((knop) => {
          if (!beschikbaar.has(knop.dataset.proxy)) knop.hidden = true;
        });
      })
      .catch(() => {
        // Console onbereikbaar: laat alle knoppen staan. Ze falen dan zichtbaar, wat beter is dan
        // een leeg paneel zonder uitleg.
      });
```

- [ ] **Stap 8: draai de module en detekt**

Draai: `./mvnw clean test -pl demo/demo-console -am && ./mvnw detekt:check -pl demo/demo-console`
Verwacht: BUILD SUCCESS.

- [ ] **Stap 9: verifieer met de hand in de lokale stack**

```bash
docker compose --profile demo up -d
curl -s localhost:8095/api/demo/omgeving
```

Verwacht: `{"uitvraagBasis":"","storingen":["aanmeld","magazijn-a","magazijn-b","notificatie","profiel","redis"]}`.
Open daarna `http://localhost:8095/berichtenbox.html` en haal berichten op — de lijst moet vullen
zoals vóór deze wijziging.

- [ ] **Stap 10: commit**

```bash
git add demo/demo-console/src
git commit -m "feat(demo): pagina's lezen hun omgeving uit de console in plaats van te raden"
```

---

## Taak 3: Tempo — een server-side klok

Acceptatiecriterium 4 vraagt willekeurige berichten in een instelbaar tempo. Server-side, zodat de
stroom doorloopt als de demonstrateur zijn tab sluit en alle kijkers op ZAD dezelfde stroom zien.

**Bestanden:**
- Wijzig: `demo/demo-console/pom.xml`
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/tempo/TempoKlok.kt`
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/tempo/KlokProducer.kt`
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/tempo/TempoService.kt`
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/tempo/TempoResource.kt`
- Wijzig: `demo/demo-console/src/main/resources/META-INF/resources/index.html`
- Nieuw: `demo/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/tempo/TempoServiceTest.kt`

**Interfaces:**
- Gebruikt: `AanleverService.leverAan(opdrachten: List<AanleverOpdracht>): AanleverResultaat` en
  `DemoBerichtGenerator.genereer(aantal: Int, random: Random): List<AanleverOpdracht>` (bestaand).
- Levert: `TempoService.start(intervalSeconden: Int): TempoStatus`, `TempoService.stop(): TempoStatus`,
  `TempoService.status(): TempoStatus`, met `data class TempoStatus(val loopt: Boolean, val intervalSeconden: Int, val geleverd: Int)`.
  Taak 4 roept `stop()` aan.

- [ ] **Stap 1: voeg de scheduler-dependency toe**

In `demo/demo-console/pom.xml`, bij de overige `io.quarkus`-dependencies:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-scheduler</artifactId>
        </dependency>
```

- [ ] **Stap 2: schrijf de falende test**

`TempoServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.tempo

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.BadRequestException
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Verzette klok: de duurgrens is anders alleen te toetsen door een uur te wachten. */
private class TestKlok(var nu: Instant = Instant.parse("2026-08-26T10:00:00Z")) : Clock() {

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = nu
}

/** Voert de geplande taak niet zelf uit; de test tikt met de hand, zodat er niets te wachten valt. */
private class HandKlok : TempoKlok {

    var taak: (() -> Unit)? = null
    var gestopt = 0

    override fun start(intervalSeconden: Int, tik: () -> Unit) {
        taak = tik
    }

    override fun stop() {
        taak = null
        gestopt++
    }

    fun tik(keer: Int = 1) = repeat(keer) { taak?.invoke() }
}

class TempoServiceTest {

    private val klok = HandKlok()
    private val testKlok = TestKlok()
    private val aanleverService = mockk<AanleverService>()
    private val generator = mockk<DemoBerichtGenerator>()

    private val service = TempoService(klok, aanleverService, generator, testKlok)

    init {
        every { generator.genereer(any(), any()) } returns emptyList()
        every { aanleverService.leverAan(any()) } returns AanleverResultaat(1, 1, 0, 0)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, 3601, 86400])
    fun `een interval buiten de grenzen wordt geweigerd`(interval: Int) {
        // BadRequestException en geen require(): DemoFoutMapper maakt van een gewone
        // IllegalArgumentException een 500, en een bedieningsfout hoort een 400 te zijn.
        assertThrows(BadRequestException::class.java) { service.start(interval) }

        assertFalse(service.status().loopt)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 5, 3600])
    fun `een interval binnen de grenzen start de stroom`(interval: Int) {
        val status = service.start(interval)

        assertTrue(status.loopt)
        assertEquals(interval, status.intervalSeconden)
    }

    @Test
    fun `elke tik levert een bericht aan`() {
        service.start(5)

        klok.tik(3)

        verify(exactly = 3) { aanleverService.leverAan(any()) }
        assertEquals(3, service.status().geleverd)
    }

    @Test
    fun `een tweede start vervangt de lopende stroom in plaats van te stapelen`() {
        service.start(5)
        service.start(10)

        klok.tik()

        assertEquals(10, service.status().intervalSeconden)
        assertEquals(1, service.status().geleverd)
        verify(exactly = 1) { aanleverService.leverAan(any()) }
    }

    @Test
    fun `stop zonder lopende stroom is geen fout`() {
        val status = service.stop()

        assertFalse(status.loopt)
    }

    @Test
    fun `de stroom stopt vanzelf bij het maximum aantal berichten`() {
        // Op een gedeelde omgeving klikt iemand de SSO-sessie weg terwijl de stroom doorloopt.
        service.start(1)

        klok.tik(TempoService.MAX_BERICHTEN + 1)

        assertFalse(service.status().loopt)
        assertEquals(TempoService.MAX_BERICHTEN, service.status().geleverd)
    }

    @Test
    fun `de stroom stopt vanzelf na de maximale duur`() {
        service.start(1)
        klok.tik()

        testKlok.nu = testKlok.nu.plus(TempoService.MAX_DUUR).plus(Duration.ofSeconds(1))
        klok.tik()

        assertFalse(service.status().loopt)
        assertEquals(1, service.status().geleverd)
    }
}
```

- [ ] **Stap 3: draai de test en stel vast dat hij faalt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=TempoServiceTest`
Verwacht: compilatiefout — `TempoKlok` en `TempoService` bestaan niet.

- [ ] **Stap 4: schrijf de seam rond de scheduler**

`TempoKlok.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.tempo

import io.quarkus.scheduler.Scheduler
import jakarta.enterprise.context.ApplicationScoped

/**
 * Plant en annuleert de tik-taak. Aparte laag zodat [TempoService] zonder draaiende Quarkus te
 * toetsen is: de scheduler-API is een keten van fluent-aanroepen die zich slecht laat mocken, en
 * deze module houdt zijn tests bewust pure JVM.
 */
interface TempoKlok {

    fun start(intervalSeconden: Int, tik: () -> Unit)

    fun stop()
}

@ApplicationScoped
class SchedulerTempoKlok(private val scheduler: Scheduler) : TempoKlok {

    // Eigen administratie i.p.v. de scheduler bevragen: unscheduleJob op een niet-geplande taak
    // is per versie verschillend (stil of een fout), en de stop-knop mag nooit zelf omvallen.
    private var gepland = false

    override fun start(intervalSeconden: Int, tik: () -> Unit) {
        stop()

        scheduler.newJob(JOB)
            .setInterval("${intervalSeconden}s")
            .setTask { tik() }
            .schedule()

        gepland = true
    }

    override fun stop() {
        if (gepland) {
            scheduler.unscheduleJob(JOB)

            gepland = false
        }
    }

    private companion object {

        const val JOB = "demo-tempo"
    }
}
```

`KlokProducer.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.tempo

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** Eén klokbron, zodat de duurgrens van de stroom te toetsen is zonder een uur te wachten. */
@ApplicationScoped
class KlokProducer {

    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
```

- [ ] **Stap 5: schrijf de tempo-service**

`TempoService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.tempo

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

data class TempoStatus(val loopt: Boolean, val intervalSeconden: Int, val geleverd: Int)

/**
 * Een doorlopende stroom nieuwe berichten, één per tik. Draait server-side en niet in de browser:
 * op de gedeelde omgeving zien alle kijkers dan dezelfde stroom, en het sluiten van een tab legt
 * hem niet stil.
 *
 * De bovengrenzen zijn er omdat diezelfde eigenschap ook de keerzijde is — een vergeten stroom
 * blijft anders een weekend lang berichten pompen in een omgeving waar niemand kijkt.
 */
@ApplicationScoped
class TempoService(
    private val klok: TempoKlok,
    private val aanleverService: AanleverService,
    private val generator: DemoBerichtGenerator,
    private val clock: Clock,
) {

    private var interval = 0
    private var geleverd = 0
    private var gestartOp: Instant? = null

    @Synchronized
    fun start(intervalSeconden: Int): TempoStatus {
        // BadRequestException en geen require(): DemoFoutMapper vertaalt alleen een
        // WebApplicationException naar zijn eigen status, dus een require() zou een
        // bedieningsfout als 500 tonen.
        if (intervalSeconden !in MIN_INTERVAL..MAX_INTERVAL) {
            throw BadRequestException(
                "interval moet tussen $MIN_INTERVAL en $MAX_INTERVAL seconden liggen, was: $intervalSeconden",
            )
        }

        klok.stop()

        interval = intervalSeconden
        geleverd = 0
        gestartOp = clock.instant()

        klok.start(intervalSeconden) { tik() }

        return status()
    }

    @Synchronized
    fun stop(): TempoStatus {
        klok.stop()

        gestartOp = null

        return status()
    }

    @Synchronized
    fun status(): TempoStatus = TempoStatus(gestartOp != null, interval, geleverd)

    @Synchronized
    internal fun tik() {
        val start = gestartOp ?: return

        if (geleverd >= MAX_BERICHTEN || Duration.between(start, clock.instant()) > MAX_DUUR) {
            stop()

            return
        }

        aanleverService.leverAan(generator.genereer(1, Random.Default))

        geleverd++
    }

    companion object {

        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 3600
        const val MAX_BERICHTEN = 500

        val MAX_DUUR: Duration = Duration.ofMinutes(60)
    }
}
```

- [ ] **Stap 6: schrijf de resource**

`TempoResource.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.tempo

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/tempo")
@Produces(MediaType.APPLICATION_JSON)
class TempoResource(private val tempoService: TempoService) {

    @GET
    fun status(): TempoStatus = tempoService.status()

    @POST
    @Path("/start")
    fun start(@QueryParam("interval") @DefaultValue("10") interval: Int): TempoStatus =
        tempoService.start(interval)

    @POST
    @Path("/stop")
    fun stop(): TempoStatus = tempoService.stop()
}
```

- [ ] **Stap 7: draai de test en stel vast dat hij slaagt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=TempoServiceTest`
Verwacht: PASS, tien tests (vier plus drie parameterisaties plus zes losse).

- [ ] **Stap 8: zet de knoppen in het paneel**

In `index.html`, in het blok "Vullen", na de bestaande opvoer-knop:

```html
    <label>Stroom: elke <input id="tempoInterval" type="number" value="10" min="1" max="3600"> seconden</label>
    <button onclick="post('/api/demo/tempo/start?interval=' + document.getElementById('tempoInterval').value)">Stroom starten</button>
    <button onclick="post('/api/demo/tempo/stop')">Stroom stoppen</button>
    <button onclick="get('/api/demo/tempo')">Stroom-status</button>
```

- [ ] **Stap 9: draai de module en detekt**

Draai: `./mvnw clean test -pl demo/demo-console -am && ./mvnw detekt:check -pl demo/demo-console`
Verwacht: BUILD SUCCESS.

- [ ] **Stap 10: verifieer met de hand**

```bash
docker compose --profile demo up -d
curl -s -X POST 'localhost:8095/api/demo/tempo/start?interval=2'
sleep 7 && curl -s localhost:8095/api/demo/tempo
curl -s -X POST 'localhost:8095/api/demo/tempo/stop'
curl -s -X POST 'localhost:8095/api/demo/tempo/start?interval=0'
```

Verwacht: na 7 s `"geleverd"` rond 3, daarna `"loopt":false`, en de laatste aanroep een HTTP 400
met een melding over de grenzen.

- [ ] **Stap 11: commit**

```bash
git add demo/demo-console/pom.xml demo/demo-console/src
git commit -m "feat(demo): willekeurige berichten in een instelbaar tempo"
```

---

## Taak 4: Herstelknop

Acceptatiecriterium 5: na afloop van een demo terug naar de begintoestand, zonder handmatig
databasewerk.

**Bestanden:**
- Nieuw: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/herstel/HerstelService.kt`
- Wijzig: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/DemoResource.kt`
- Wijzig: `demo/demo-console/src/main/resources/META-INF/resources/index.html`
- Nieuw: `demo/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/herstel/HerstelServiceTest.kt`

**Interfaces:**
- Gebruikt: `TempoService.stop()` (taak 3), `StoringService.reset()` (taak 1),
  `MagazijnDatabase.leegAlles(): Map<String, Int>`, `Basisdataset.laad()`,
  `AanleverService.leverAan(...)`.
- Levert: `HerstelService.herstel(): HerstelResultaat` met
  `data class HerstelResultaat(val geleegd: Map<String, Int>, val vulling: AanleverResultaat)`.

- [ ] **Stap 1: schrijf de falende test**

`HerstelServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.herstel

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HerstelServiceTest {

    private val tempoService = mockk<TempoService>()
    private val storingService = mockk<StoringService>()
    private val magazijnDatabase = mockk<MagazijnDatabase>()
    private val basisdataset = mockk<Basisdataset>()
    private val aanleverService = mockk<AanleverService>()

    private val service = HerstelService(tempoService, storingService, magazijnDatabase, basisdataset, aanleverService)

    private fun alleStappenSlagen() {
        every { tempoService.stop() } returns TempoStatus(false, 0, 0)
        every { storingService.reset() } just Runs
        every { magazijnDatabase.leegAlles() } returns mapOf("magazijn-a" to 20, "magazijn-b" to 20)
        every { basisdataset.laad() } returns emptyList()
        every { aanleverService.leverAan(any()) } returns AanleverResultaat(40, 40, 0, 0)
    }

    @Test
    fun `herstel doorloopt de stappen in de juiste volgorde`() {
        // De volgorde draagt betekenis: een lopende stroom zou tijdens het legen blijven vullen,
        // en storingen zouden de basisvulling laten mislukken.
        alleStappenSlagen()

        service.herstel()

        verifyOrder {
            tempoService.stop()
            storingService.reset()
            magazijnDatabase.leegAlles()
            aanleverService.leverAan(any())
        }
    }

    @Test
    fun `herstel rapporteert wat er geleegd en gevuld is`() {
        alleStappenSlagen()

        val resultaat = service.herstel()

        assertEquals(mapOf("magazijn-a" to 20, "magazijn-b" to 20), resultaat.geleegd)
        assertEquals(40, resultaat.vulling.geslaagd)
    }

    @Test
    fun `een falende stap breekt af met de fout in plaats van stil door te gaan`() {
        // Half herstellen is erger dan niet herstellen: de bediener denkt dan dat de omgeving
        // schoon is terwijl er storingen aan staan.
        alleStappenSlagen()
        every { storingService.reset() } throws IllegalStateException("Toxiproxy onbereikbaar")

        assertThrows(IllegalStateException::class.java) { service.herstel() }

        verify(exactly = 0) { magazijnDatabase.leegAlles() }
        verify(exactly = 0) { aanleverService.leverAan(any()) }
    }
}
```

- [ ] **Stap 2: draai de test en stel vast dat hij faalt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=HerstelServiceTest`
Verwacht: compilatiefout — `HerstelService` bestaat niet.

- [ ] **Stap 3: schrijf de service**

`HerstelService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.herstel

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService

data class HerstelResultaat(val geleegd: Map<String, Int>, val vulling: AanleverResultaat)

/**
 * De omgeving terug naar de toestand van vlak na de eerste basisvulling — de knop aan het eind van
 * een demo. Eén handeling, want de vier losse stappen in de verkeerde volgorde laten een halve
 * toestand achter: een lopende stroom vult tijdens het legen door, en storingen die aan blijven
 * staan laten de basisvulling mislukken.
 */
@ApplicationScoped
class HerstelService(
    private val tempoService: TempoService,
    private val storingService: StoringService,
    private val magazijnDatabase: MagazijnDatabase,
    private val basisdataset: Basisdataset,
    private val aanleverService: AanleverService,
) {

    fun herstel(): HerstelResultaat {
        tempoService.stop()
        storingService.reset()

        val geleegd = magazijnDatabase.leegAlles()
        val vulling = aanleverService.leverAan(basisdataset.laad())

        return HerstelResultaat(geleegd, vulling)
    }
}
```

- [ ] **Stap 4: ontsluit hem via `DemoResource`**

Voeg `herstelService: HerstelService` toe aan de constructor van `DemoResource` en daaronder:

```kotlin
    @POST
    @Path("/herstel")
    fun herstel(): HerstelResultaat = herstelService.herstel()
```

- [ ] **Stap 5: draai de test en stel vast dat hij slaagt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=HerstelServiceTest`
Verwacht: PASS, drie tests.

- [ ] **Stap 6: zet de knop in het paneel**

In `index.html`, bovenaan het blok "Beheer":

```html
    <button onclick="post('/api/demo/herstel', true)">Herstel demo (stoppen, resetten, legen, vullen)</button>
```

De `true` hergebruikt de bestaande bevestigingsvraag; deze knop wist immers alle berichten.

- [ ] **Stap 7: draai de module en detekt**

Draai: `./mvnw clean test -pl demo/demo-console -am && ./mvnw detekt:check -pl demo/demo-console`
Verwacht: BUILD SUCCESS.

- [ ] **Stap 8: commit**

```bash
git add demo/demo-console/src
git commit -m "feat(demo): een knop die de omgeving terugbrengt naar de begintoestand"
```

---

## Taak 5: Legen over schema's en het logboek

Op ZAD delen beide magazijnen één database en scheidt alleen het schema ze. En zonder het logboek
mee te legen blijft de vorige demo zichtbaar in het LDV terwijl de berichten weg zijn.

**Bestanden:**
- Wijzig: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/legen/MagazijnDatabase.kt`
- Nieuw: `demo/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/legen/LegenSqlTest.kt`
- Wijzig: `demo/demo-console/src/main/resources/application.properties`

**Interfaces:**
- Levert: `internal object LegenSql` met `DOMEIN`, `LOGBOEK_BESTAAT` en `LOGBOEK`.
  `MagazijnDatabase.leegAlles()` en `.aantallen()` houden hun bestaande vorm.

- [ ] **Stap 1: schrijf de falende test**

`LegenSqlTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.legen

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegenSqlTest {

    @Test
    fun `de domein-truncate noemt alle vier de tabellen ongekwalificeerd`() {
        // Ongekwalificeerd is de kern: op ZAD bepaalt currentSchema van de datasource in welk
        // magazijn-schema de TRUNCATE landt. Een schemaprefix zou beide datasources naar dezelfde
        // tabellen sturen.
        listOf("berichten", "bijlagen", "bericht_status", "publicatie_deliveries").forEach { tabel ->
            assertTrue(LegenSql.DOMEIN.contains(" $tabel") || LegenSql.DOMEIN.contains("($tabel"), "mist $tabel")
        }

        assertFalse(LegenSql.DOMEIN.contains("."), "geen schemaprefix: currentSchema bepaalt het schema")
    }

    @Test
    fun `de logboek-controle kijkt in het schema van de sessie`() {
        // pg_tables zonder schemaname-filter zou het logboek van het ándere magazijn zien en dan
        // een TRUNCATE proberen op een tabel die in dit schema niet bestaat.
        assertTrue(LegenSql.LOGBOEK_BESTAAT.contains("current_schema()"))
        assertTrue(LegenSql.LOGBOEK_BESTAAT.contains("logboek_dataverwerkingen"))
    }

    @Test
    fun `de logboek-truncate is ongekwalificeerd`() {
        assertTrue(LegenSql.LOGBOEK.startsWith("TRUNCATE logboek_dataverwerkingen"))
        assertFalse(LegenSql.LOGBOEK.contains("."), "geen schemaprefix")
    }
}
```

- [ ] **Stap 2: draai de test en stel vast dat hij faalt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=LegenSqlTest`
Verwacht: compilatiefout — `LegenSql` bestaat niet.

- [ ] **Stap 3: pas `MagazijnDatabase` aan**

Vervang de KDoc en de leeg-logica:

```kotlin
/**
 * De SQL van het legen, apart zodat de invarianten toetsbaar zijn zonder database: alle statements
 * zijn ongekwalificeerd, want `currentSchema` van de datasource bepaalt in welk magazijn-schema ze
 * landen. Lokaal zijn dat twee databases met schema `public`, op ZAD één database met twee schema's.
 */
internal object LegenSql {

    const val DOMEIN = "TRUNCATE berichten, bijlagen, bericht_status, publicatie_deliveries RESTART IDENTITY CASCADE"

    // De LDV-wrapper maakt zijn tabel lui aan met CREATE TABLE IF NOT EXISTS, dus vóór het eerste
    // export-moment bestaat hij niet en zou een kale TRUNCATE het hele legen laten falen.
    const val LOGBOEK_BESTAAT =
        "SELECT count(*) FROM pg_tables WHERE schemaname = current_schema() AND tablename = 'logboek_dataverwerkingen'"

    const val LOGBOEK = "TRUNCATE logboek_dataverwerkingen RESTART IDENTITY"
}
```

En in de klasse:

```kotlin
    fun leegAlles(): Map<String, Int> =
        bronnen.mapValues { (_, bron) ->
            val aantal = telBerichten(bron)

            voerUit(bron, LegenSql.DOMEIN)
            leegLogboek(bron)

            aantal
        }

    // Het logboek staat in %prod ín het magazijn-schema. Blijft het staan, dan toont het LDV na een
    // herstel nog de verwerkingen van de vorige demo terwijl de berichten weg zijn — en juist dat
    // logboek is wat we in een demo laten zien.
    private fun leegLogboek(bron: JavaxDataSource) {
        if (queryEnkeleInt(bron, LegenSql.LOGBOEK_BESTAAT) > 0) {
            voerUit(bron, LegenSql.LOGBOEK)
        }
    }
```

- [ ] **Stap 4: draai de test en stel vast dat hij slaagt**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dtest=LegenSqlTest`
Verwacht: PASS, drie tests.

- [ ] **Stap 5: zet het schema op de datasources**

In `application.properties`, onder elke datasource:

```properties
# Op ZAD delen beide magazijnen één database en één user; alleen het schema scheidt ze, precies
# zoals de magazijnen zelf dat doen met DB_SCHEMA. currentSchema zet het search_path van de sessie,
# zodat de ongekwalificeerde TRUNCATE in het juiste schema landt. Lokaal zijn het twee databases,
# dus daar blijft public de juiste waarde.
quarkus.datasource.magazijn-a-db.jdbc.additional-jdbc-properties.currentSchema=${MAGAZIJN_A_DB_SCHEMA:public}
quarkus.datasource.magazijn-b-db.jdbc.additional-jdbc-properties.currentSchema=${MAGAZIJN_B_DB_SCHEMA:public}
```

- [ ] **Stap 6: verifieer met de hand tegen de lokale stack**

```bash
docker compose --profile demo up -d
curl -s -X POST localhost:8095/api/demo/basisvulling
curl -s localhost:8095/api/demo/status
curl -s -X POST localhost:8095/api/demo/legen
curl -s localhost:8095/api/demo/status
```

Verwacht: na de basisvulling een aantal groter dan nul per magazijn, na het legen nul. Controleer
daarna dat het logboek leeg is:

```bash
docker compose exec postgres-a psql -U berichtenmagazijn -d berichtenmagazijn \
  -c "select count(*) from logboek_dataverwerkingen"
```

Verwacht: `0`. Bestaat de tabel nog niet, dan geeft psql "does not exist" — ook goed, want dan had
het legen niets te doen en mag het niet gefaald zijn.

- [ ] **Stap 7: commit**

```bash
git add demo/demo-console/src
git commit -m "feat(demo): legen werkt op schema's en ruimt het logboek mee op"
```

---

## Taak 6: README en achterhaalde toelichtingen

Acceptatiecriterium 1 vraagt letterlijk om de stappen in de README van de engine. Daarnaast staan er
nu twee toelichtingen in de module die na deze wijziging niet meer kloppen.

**Bestanden:**
- Nieuw: `demo/demo-console/README.md`
- Wijzig: `demo/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/DemoFoutMapper.kt`
- Wijzig: `demo/demo-console/src/main/resources/application.properties`
- Wijzig: `demo/README.md`

- [ ] **Stap 1: schrijf de README**

`demo/demo-console/README.md`:

```markdown
# Demo-console

Bedieningspaneel voor de demo van het Federatief Berichtenstelsel: magazijnen legen en vullen,
storingen aanzetten, en de ondernemer-weergave van de Berichtenbox tonen. Wegwerpcode — geen
JaCoCo-gate, geen NL Design System, geen toegankelijkheidstraject.

## Lokaal starten

```bash
./mvnw -B package -DskipTests -pl demo/demo-console -am -Dquarkus.container-image.build=true
docker compose --profile demo up -d
open http://localhost:8095
```

De eerste regel bouwt het image dat compose verwacht. Bouw je ook de twee services opnieuw, volg
dan `../../docs/demo-runbook.md`; die beschrijft de volledige stack inclusief Podman,
stub-generatie en de scenario's.

Vul altijd een lege omgeving: het magazijn kent eigen bericht-ID's toe, dus twee keer vullen zonder
legen levert het dubbele aantal berichten op.

## Op ZAD

(Deze sectie is in taak 10 herschreven toen de deployment `demo` verviel ten gunste van `test` en de
storingsknoppen uit fase 2 vielen. `demo/demo-console/README.md` is leidend.)

## De knoppen

| Knop | Wat het doet |
|---|---|
| Herstel demo | Stroom stoppen, storingen resetten, legen, basisvulling — de knop aan het eind van een demo |
| Magazijnen legen | `TRUNCATE` op de berichten-, bijlage-, status- en outbox-tabellen van beide magazijnen, plus het logboek |
| Status | Aantal berichten per magazijn |
| Basisvulling laden | De vaste dataset uit `src/main/resources/dataset/basis.json` |
| Opvoeren | Een burst van *n* willekeurige berichten |
| Stroom starten / stoppen | Eén willekeurig bericht per interval; stopt vanzelf na 500 berichten of 60 minuten |
| Storingen | Zet een Toxiproxy traag of uit; "Alles normaal" herstelt elke instantie |
| Cache verlopen | Wist de sessiecache in Redis |
| Foutieve aanlevering, Ontdubbeling | Losse scenario's; zie het runbook |

## Configuratie

Alles gaat via env-vars met een lokale default, zodat de module zonder omgeving start.

| Variabele | Default | Waarvoor |
|---|---|---|
| `MAGAZIJN_A_URL`, `MAGAZIJN_B_URL` | `http://localhost:8090`, `:8091` | Aanleveren |
| `MAGAZIJN_A_DB_URL`, `MAGAZIJN_B_DB_URL` | localhost:5432, :5433 | Legen |
| `MAGAZIJN_A_DB_SCHEMA`, `MAGAZIJN_B_DB_SCHEMA` | `public` | Schema per magazijn; op ZAD delen beide dezelfde database |
| `TOXIPROXY_ADMIN_URL` | `http://localhost:8474` | Alle Toxiproxy-instanties tegelijk |
| `TOXIPROXY_<PROXY>_URL` | de waarde hierboven | Eén instantie apart; op ZAD staat elke stroom op een eigen adres |
| `UITVRAAG_BASIS` | leeg | Browser-zichtbaar adres van de uitvraag-API; leeg = afleiden uit de browser-locatie |
| `UITVRAAG_URL` | `http://localhost:8086` | Adres dat de console zélf aanroept voor de ontdubbeling-webhook |
| `REDIS_HOSTS` | `redis://localhost:6379` | Cache-verval-knop |
| `DEMO_MAGAZIJN_STUBS` | `12` | Aantal stub-magazijnen voor de veel-magazijnen-schuif |
```

- [ ] **Stap 2: corrigeer de toelichting in `DemoFoutMapper`**

De KDoc zegt nu dat de console "alleen op loopback luistert". Dat klopt niet meer zodra hij op ZAD
staat. Vervang die alinea:

```kotlin
 * De melding gaat onverkort naar de client. Lokaal luistert de console alleen op loopback; op de
 * gedeelde omgeving staat hij achter Keycloak-SSO, dus in beide gevallen leest alleen een bediener
 * mee die de tekst nodig heeft. Geen RFC 9457: die vorm hoort bij de productie-API's, niet bij dit
 * paneel.
```

- [ ] **Stap 3: corrigeer de profiel-opmerking in `application.properties`**

Vervang `# Wegwerp-console: draait uitsluitend lokaal/demo, altijd onder profiel dev.`:

```properties
# Wegwerp-console. Lokaal zet compose het dev-profiel voor het debug-logniveau hieronder; op de
# gedeelde omgeving draait hij onder het default prod-profiel, waar alleen dat logniveau wegvalt.
```

- [ ] **Stap 4: werk `demo/README.md` bij**

In de tabel, de regel voor `demo-console/`:

```markdown
| `demo-console/` | Maven-module: bedieningspaneel voor demo's — magazijnen legen, vullen, storingen aanzetten. Heeft een eigen image en een ZAD-component in de deployment `demo`; zie `demo-console/README.md` |
```

En in de regel voor `environment/`, verbreed de omschrijving:

```markdown
| `environment/` | FSC-federatieharness (peers, PKI, contract-bootstrap) én de ZAD-runbooks, waaronder `zad-demo/` voor de gedeelde demo-omgeving |
```

- [ ] **Stap 5: controleer de links**

Draai: `grep -o '](\.\./[^)]*)' demo/demo-console/README.md`
Loop de gevonden paden na met `ls`; elk pad moet bestaan.

- [ ] **Stap 6: commit**

```bash
git add demo/demo-console/README.md demo/demo-console/src demo/README.md
git commit -m "docs(demo): README bij de console en twee achterhaalde toelichtingen"
```

---

## Taak 7: Het console-image in CI

Zonder deze taak ontstaat het image pas bij een push naar main en valt een kapotte
jib-configuratie pas ná de merge op.

**Bestanden:**
- Wijzig: `.github/workflows/deploy.yml`
- Wijzig: `.github/scripts/test-uitrol-poort.sh`

- [ ] **Stap 1: voeg de bouw-job toe**

In `deploy.yml`, direct na `build-contract-bootstrap`:

```yaml
  # De demo-console levert een eigen image en een eigen ZAD-component, maar rolt niet mee met een
  # preview: hij draait in de preview-loze deployment `demo`, die met de hand wordt bijgewerkt via
  # deploy-demo.yml. Daarom aan `run` en niet aan `deploy`, net als build-contract-bootstrap — zo
  # valt een kapotte jib-configuratie op vóór de merge zonder de previews open te zetten.
  build-democonsole:
    if: github.event_name == 'push' || needs.changes.outputs.run == 'true'
    needs: [meta, changes]
    runs-on: ubuntu-latest
    timeout-minutes: 20
    concurrency:
      group: build-democonsole-${{ github.event.pull_request.number || github.ref }}
      cancel-in-progress: true
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false
      - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Build + push image (jib)
        env:
          GROUP: ${{ needs.meta.outputs.owner }}
          TAG: ${{ needs.meta.outputs.tag }}
          GHCR_USER: ${{ github.actor }}
          QUARKUS_CONTAINER_IMAGE_PASSWORD: ${{ secrets.GITHUB_TOKEN }}
        run: |
          ./mvnw -B package -DskipTests \
            -pl demo/demo-console -am \
            -Dquarkus.container-image.build=true \
            -Dquarkus.container-image.push=true \
            -Dquarkus.container-image.registry="$REGISTRY" \
            -Dquarkus.container-image.group="$GROUP" \
            -Dquarkus.container-image.tag="$TAG" \
            -Dquarkus.container-image.username="$GHCR_USER"
```

- [ ] **Stap 2: hang hem aan de uitrol-poort**

In de `needs:`-lijst van `uitrol-poort`, na `build-contract-bootstrap`:

```yaml
      - build-democonsole
```

`uitrol-poort.sh` verzamelt bouwresultaten op het voorvoegsel `build`, dus het script zelf hoeft
niet mee te veranderen; de nieuwe job komt vanzelf in de diagnose terecht.

- [ ] **Stap 3: laat de fixtures de werkelijkheid spiegelen**

Draai eerst: `grep -n "build-contract-bootstrap" .github/scripts/test-uitrol-poort.sh`

Voeg bij élke gevonden regel een `build-democonsole`-tegenhanger toe. In `fixture()` betekent dat:

```bash
  read -r -a b <<<"${3:-success success success success}"

  local -a namen=(
    "deploy-preview-uitvraag=${p[0]}" "deploy-preview-externe-stubs=${p[1]}" "deploy-preview-magazijnen=${p[2]}"
    "deploy-test-uitvraag=${t[0]}" "deploy-test-externe-stubs=${t[1]}" "deploy-test-magazijnen=${t[2]}"
    "build=${b[0]}" "build-externe-stubs=${b[1]}" "build-contract-bootstrap=${b[2]}" "build-democonsole=${b[3]}"
  )
```

En in de handgeschreven needs-blokken een regel `build-democonsole=success`.

- [ ] **Stap 4: draai de scriptsuites**

Draai:
```bash
.github/scripts/test-uitrol-poort.sh
.github/scripts/test-wijzigingsfilter.sh
```
Verwacht: beide groen, met het aantal asserties minstens gelijk aan vóór de wijziging.

- [ ] **Stap 5: controleer de workflow-syntaxis**

Draai: `docker run --rm -v "$PWD:/repo:ro" -w /repo rhysd/actionlint:latest -color`
Verwacht: geen bevindingen. Is actionlint niet beschikbaar, dan volstaat
`python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/deploy.yml'))"` als
minimumcontrole.

- [ ] **Stap 6: commit**

```bash
git add .github/workflows/deploy.yml .github/scripts/test-uitrol-poort.sh
git commit -m "ci: bouw het demo-console-image op elke code-PR"
```

- [ ] **Stap 7: open de PR voor fase 1**

```bash
git push -u origin feature/demo-op-zad
gh pr create --draft --base main \
  --title "feat: demo lokaal herhaalbaar maken (fase 1 van #936)" \
  --body-file <(cat <<'BODY'
Fase 1 van `docs/plans/2026-08-26-demo-op-zad-plan.md`: alles wat lokaal werkt en te reviewen is
zonder ZAD. Fase 2 (de ZAD-deployment) volgt in een eigen PR.

Closes MinBZK/MijnOverheidZakelijk#936
BODY
)
```

Geen reviewer toevoegen; de PR blijft draft tot de opdrachtgever hem vrijgeeft. Blijft het issue
open tot fase 2 klaar is, haal dan de sluitregel weg en zet hem in de PR van fase 2.

---

# Fase 2 — ZAD

> **Tweemaal herzien.** Dit plan beschreef eerst een eigen, preview-loze deployment `demo` in alle
> drie de projecten. Dat verviel: de demo draait op de bestaande keten in `test` en de console wordt
> daar één component bij, omdat alleen een component ín dezelfde deployment als de magazijnen hun
> `test-database`-secret erft.
>
> De tweede herziening is de storingsinjectie. Bij de uitvoering bleek de opzet uit het ontwerp
> onbruikbaar zodra previews meetellen; de nabrander in het ontwerp legt uit waarom en welke route
> het wél oplost. De storingsknoppen zijn daarom uit fase 2 gehaald, samen met de cache-verval-knop
> die op dezelfde netwerkregel-beperking stuit.

## Taak 8: Het console-component aanmaken in `mpfm-w3h/test`

ZAD past component-configuratie (`env_vars`, `aliases`, poorten) alleen bij creatie toe, niet bij
een re-POST. Het component moet dus in één keer compleet aangemaakt worden.

De volledige stappen staan als runbook in `demo/environment/zad-demo/README.md`, met de commando's
en de reden per keuze; hier alleen de vorm.

- [x] `keycloak` en `authorization-wall` als projectdienst selecteren, met een `restrict-access`-
      besluit: iedereen met een rijksaccount, of alleen wie een rol draagt.
- [x] `democonsole` aanmaken: poort 8095, diensten `postgresql-database`, `publish-on-web` en
      `authorization-wall`, en de aliassen voor de magazijn-URL's, de database en de uitvraag.
      `UITVRAAG_BASIS` (browser, mét `/api/v1`) en `UITVRAAG_URL` (server-side) delen op ZAD hun
      host: de uitvraag staat in een ander project, dus cluster-interne DNS zou een netwerkregel
      vragen die geen preview volgt.
- [x] `MAGAZIJN_A_DB_SCHEMA`/`_B_DB_SCHEMA` als env-vars, exact gelijk aan de `DB_SCHEMA` van
      `magazijna` respectievelijk `magazijnb`. Wijken ze af, dan leegt de console een leeg schema en
      meldt nul verwijderde berichten zonder te klagen.
- [x] De zes `TOXIPROXY_*_URL` leeg zetten en `SESSIECACHE_BEREIKBAAR=false`, zodat het paneel de
      knoppen weglaat die deze omgeving niet kan bedienen.
- [x] `QUARKUS_HTTP_CORS_ENABLED` en `QUARKUS_HTTP_CORS_ORIGINS` op `uitvraag` in `mpfb-8wh`. Eén
      reguliere expressie over alle console-origins van dit project, want previews komen en gaan.
      Zonder backslashes: een geëscapete variant laat de SOPS-stap van Operations Manager falen en
      blokkeert dan élke uitrol van dat project.
- [x] `keycloak` óók aan het component binden. `authorization-wall` noemt hem bij zijn voorwaarden
      maar trekt de binding niet mee; zonder die stap rendert er geen oauth2-proxy-sidecar en staat
      het paneel open op het internet.
- [x] De profielservice-stub het `fbs-demo-profiel`-image geven. Zonder de persona-voorkeuren valt
      elke ontvanger terug op de gedeelde catch-all, die voor iedereen hetzelfde ene magazijn
      teruggeeft — dan bevraagt de uitvraag maar één magazijn en toont de demo geen federatie.

## Taak 9: De console meenemen in de uitrol

- [x] `democonsole` toevoegen aan de componentlijst van `deploy-test-magazijnen` én
      `deploy-preview-magazijnen` in `deploy.yml`, met `build-democonsole` in hun `needs`.
- [x] `^demo/demo-console/` uit `DEMO_BUITEN_UITROLPOORT` halen. De console rolt nu mee, dus een
      consolewijziging hóórt een preview te kopen — anders draait de preview een console van een
      oudere tag en bewijst hij niets over de wijziging.
- [x] `.github/scripts/test-uitrol-poort.sh` en `test-wijzigingsfilter.sh` draaien; de fixtures voor
      demo-console verhuizen van "geen uitrol" naar "wél uitrol".

## Taak 10: Runbook, verificatie en documentatie

- [x] `demo/environment/zad-demo/README.md` — de eenmalige OM-stappen, en wat er bewust niet
      meekomt. Die map valt binnen `DEMO_BUITEN_UITROLPOORT`, dus runbook-wijzigingen kopen geen
      previews.
- [x] `demo/environment/zad-demo/verify-zad.md` — vier stappen. De vierde is de schemacontrole:
      status noteren, legen, status opnieuw. Nul verwijderde berichten terwijl de Berichtenbox wél
      vult, wijst op een verkeerd `MAGAZIJN_*_DB_SCHEMA`.
- [x] `demo/demo-console/README.md`, `docs/demo-runbook.md` en `demo/README.md` van beoogd naar
      bestaand, en de ZAD-sectie van `CLAUDE.md` uitbreiden met `democonsole` en met de drie
      ZAD-eigenschappen die bepalen wat een component wél en niet kan.
- [x] `docs/plans/2026-07-21-demo-platform-design.md` zet ZAD-deployment van het demo-platform nog
      onder "Bewust buiten scope"; die passage krijgt een verwijzing hierheen.

## Wat hierna nog open staat

- **De netwerkregels per preview** — gedaan. Cluster-intern verkeer van de console naar een ander
  project volgt geen preview tenzij de deploy-workflow de regel per deployment bijschrijft en
  `cleanup-preview.yml` hem weer opruimt; dat doet `.github/scripts/cross-domain-preview.sh` nu, en
  daarmee werkt de cache-verval-knop.
- **De storingsknoppen** — open. Vier Toxiproxy-componenten over twee projecten, plus de wijziging
  die de console zijn proxies zelf laat aanmaken via de admin-API. Leunde op de netwerkregels
  hierboven, en die staan nu.

## Zelfcontrole van dit plan

- **Dekking van het ontwerp.** Topologie → taken 8 en 9. Console in `mpfm-w3h` → taak 8. Toegang
  (Keycloak, authorization-wall) → taak 8. Geen FSC → vastgelegd in het ontwerp, geen taak nodig.
  Register → taak 1. Tempo → taak 3. Herstel → taak 4. Schema's en logboek → taak 5. UI en CORS →
  taken 2 en 8. Profiel `prod` → taak 6. CI → taken 7 en 9. Documentatie → taken 6 en 10.
  Verificatie per acceptatiecriterium → taken 2, 3, 5 (handmatig) en taak 10.
  Toxiproxy achter eigen ingress en de bijbehorende netwerkregels → vervallen; zie "Wat hierna nog
  open staat".
- **Namen.** `ToxiproxyAdressen.namen()/adres()/unieke()` en `ToxiproxyRegister.namen()/client()/instanties()`
  worden in taken 1 en 2 gelijk gebruikt; taak 2 raakt alleen het register. `TempoService.stop()` uit taak 3 wordt in taak 4 aangeroepen met dezelfde signatuur.
  `LegenSql.DOMEIN/LOGBOEK_BESTAAT/LOGBOEK` komen alleen in taak 5 voor. De proxy-namen `profiel`,
  `notificatie`, `aanmeld`, `redis` zijn identiek in `application.properties` (taak 1), het
  omgevings-endpoint (taak 2) en de env-vars (taak 8) — daar staan ze op ZAD leeg.
