# Demo-identiteiten als eigen dienst

**Status:** Uitgevoerd — de OM-stappen uit taak 5 moeten nog gedraaid worden (`proeftuin-component.sh apply`)

> **Voor uitvoerders:** de stappen hieronder staan in TDD-volgorde: eerst de falende test, dan de
> kleinste implementatie. Elke taak eindigt op een groene build en een commit.

**Doel:** de lijst met demo-identiteiten bereikbaar maken voor de berichtenbox van de proeftuin,
zonder de beheerknoppen van het bedieningspaneel bloot te stellen.

**Aanpak:** een eigen Maven-module `demo/demo-personas` met een eigen image, die uitsluitend
`GET /api/demo/personas` aanbiedt. De demo-console neemt die module als afhankelijkheid op, zodat
de lijst één bron heeft. Het nieuwe component draait op ZAD zonder `authorization-wall`; de console
houdt zijn muur.

**Stack:** Maven, Quarkus 3.x, Kotlin, jib. Geen database, geen Redis.

## Aanleiding

De berichtenbox van de proeftuin roept bij het kiezen van een keten-persona eerst
`/api/demo/personas` aan (`assets/javascript/berichtenbox-keten.js`, functie `aangeslotenPersona`).
Dat verzoek gaat via `BACKEND_DEMO` naar de demo-console, en daar staat een `authorization-wall`.
De nginx van de proeftuin proxyt server-side en heeft geen sessie, dus het antwoord is 403 en de
bezoeker krijgt "Er gaat iets mis met het ophalen van uw berichten bij de bronnen."

Dat gedrag aan hun kant is opzettelijk: kunnen zij niet vaststellen dát een testaccount in de keten
zit, dan mogen ze geen gegenereerde berichten tonen alsof het de post van de bezoeker is. Het is dus
aan ons om het endpoint bereikbaar te maken.

## Waarom een eigen dienst en niet een vlag

Onderzocht en verworpen:

- **Cluster-intern verkeer naar de console.** Werkt niet. De muur is een oauth2-proxy-sidecar ín de
  pod (`--upstream=http://localhost:8095`), en de Service publiceert uitsluitend de sidecar-poort
  4180. `service.yaml.jinja` slaat bovendien `application_port` over in zijn extra-poortenlus, dus
  8095 is langs geen weg als Service-poort te krijgen. Een netwerkregel kan niet om een sidecar heen.
- **De muur gedeeltelijk openzetten.** Kan niet: `authorization-wall` kent op ZAD één instelling
  (`banner`), geen skip-auth-routes.
- **Een vlag in de demo-console die de beheer-endpoints uitzet.** Werkt, maar de bescherming hangt
  dan aan een omgevingsvariabele. Staat die verkeerd, dan is `/api/demo/legen` — een TRUNCATE op
  beide magazijn-databases — publiek bereikbaar. Een eigen image kan die fout niet maken, want de
  code zit er niet in.

Wat hiermee publiek wordt: een lijst met fictieve testaccounts (label plus een nummer uit de
test-reeksen). Diezelfde nummers stuurt de browser al als `X-Ontvanger` naar de publiek bereikbare
uitvraag, dus het is geen nieuwe categorie gegevens. De beheer-endpoints blijven achter de muur.

## Bereikbaarheid

Het component krijgt `publish-on-web` en géén muur. Publiek en niet cluster-intern, omdat de
proeftuin in project `pm-5sj` staat: cross-project verkeer binnen de cluster bestaat daar niet
zonder netwerkregel, en zo'n regel noemt een vaste deployment en volgt dus geen preview.

`pm-5sj` wijst standaard naar onze `test`-deployment. Wie een preview wil beproeven, zet
`BACKEND_DEMO` daar met de hand om via `zadctl`. Onze eigen proeftuin-componenten krijgen een alias
op `$DEPLOYMENT_NAME`, zodat elke preview zijn eigen personadienst gebruikt.

## Bestandsindeling

| Pad | Verantwoordelijkheid |
|---|---|
| `demo/demo-personas/pom.xml` | Module + jib-image `fbs-demo-personas` |
| `demo/demo-personas/src/main/kotlin/.../demopersonas/PersonaConfig.kt` | `@ConfigMapping(prefix = "demo.personas")` |
| `demo/demo-personas/src/main/kotlin/.../demopersonas/DemoPersona.kt` | Verhuisd uit de console, ongewijzigd |
| `demo/demo-personas/src/main/kotlin/.../demopersonas/PersonaBron.kt` | Verhuisd, ongewijzigd |
| `demo/demo-personas/src/main/kotlin/.../demopersonas/Identificatiecheck.kt` | Verhuisd, ongewijzigd |
| `demo/demo-personas/src/main/kotlin/.../demopersonas/PersonaService.kt` | Verhuisd; de magazijn-kruiscontrole gaat eruit |
| `demo/demo-personas/src/main/kotlin/.../demopersonas/PersonaResource.kt` | `GET /api/demo/personas` |
| `demo/demo-personas/src/main/resources/META-INF/microprofile-config.properties` | De persona-lijst zelf, zodat beide images dezelfde waarden lezen |
| `demo/demo-console/.../personas/PersonaMagazijnCheck.kt` | Blijft in de console: verwijst elke persona naar een bekend magazijn |
| `compose.yaml` | Service `demo-personas` op 8098 |
| `demo/proxy/demo.conf.template` | `location /api/demo/personas` vóór `/api/demo/` |
| `.github/workflows/deploy.yml` | Bouwen en uitrollen |
| `.github/workflows/cleanup-preview.yml` | `fbs-demo-personas` in de opruimlus |
| `demo/environment/zad-demo/proeftuin-component.sh` | Component erbij, `BACKEND_DEMO` erheen |

De persona-lijst verhuist mee uit `demo/demo-console/src/main/resources/application.properties`
(regels met `demo.personas.*`) naar de `microprofile-config.properties` van de nieuwe module. Quarkus
leest configuratie uit afhankelijkheden, dus beide images zien dezelfde waarden zonder dat iemand ze
twee keer onderhoudt.

---

> **Bijgesteld na review:** het endpoint `/api/demo/personas` bestaat alleen in de personadienst.
> De demo-console zet het uit met `personadienst.endpoint=false` en levert de lijst mee in
> `/api/demo/omgeving` voor de twee pagina's die zij zelf serveert. Twee diensten die hetzelfde
> adres beantwoorden maken een verkeerd gerichte proxy onzichtbaar, want beide antwoorden zijn dan
> gelijk. De magazijn-kruiscontrole loopt niet meer als losse opstartcontrole maar via de naad
> `MagazijnKennis`, zodat één boot alle inrichtingsfouten meldt in plaats van één per herstart.
>
> **Afwijking bij het uitvoeren:** taak 1 en 2 zijn één commit geworden. De persona-configuratie
> verhuist mee naar de nieuwe module, en zodra die uit `application.properties` van de console weg
> is, weigert de console te starten tot hij de module als afhankelijkheid heeft. Twee commits zouden
> een rode tussenstand opleveren. De testhulp `TestPersonas` gaat als test-jar mee, omdat twee
> console-tests hun eigen dataset tegen dezelfde ingerichte persona's toetsen.

## Taak 1: de module met het endpoint

**Bestanden:**
- Aanmaken: `demo/demo-personas/pom.xml`, de bronnen uit de tabel hierboven
- Wijzigen: `pom.xml` (regel 25-27: `<module>demo/demo-personas</module>` erbij, alfabetisch vóór `demo/magazijn-simulator`)
- Test: `demo/demo-personas/src/test/kotlin/.../demopersonas/PersonaResourceTest.kt`

**Levert:** `PersonaService.alle(): List<DemoPersona>`, `PersonaService.metMagazijnen(): List<DemoPersona>`,
`PersonaDto(id, label, ontvanger, bron)` op `GET /api/demo/personas`. Die vorm is een contract met
de proeftuin: hun code zoekt `p.bron === "keten" && p.ontvanger === "KVK:" + nummer`.

- [ ] **Stap 1: schrijf de falende test**

```kotlin
@QuarkusTest
class PersonaResourceTest {

    @TestHTTPResource("/api/demo/personas")
    lateinit var url: URL

    @Test
    fun `de lijst draagt de vorm die een berichtenbox verwacht`() {
        // De proeftuin zoekt op `bron` en `ontvanger`; verschuift een van die twee namen, dan vindt
        // hij geen enkel testaccount meer en meldt hij dat de keten hem niet kent.
        val body = HttpClient.newHttpClient()
            .send(HttpRequest.newBuilder(url.toURI()).GET().build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(200, body.statusCode())
        assertTrue(body.body().contains(""""bron":"keten""""))
        assertTrue(body.body().contains(""""ontvanger":"KVK:90000014""""))
    }
}
```

- [ ] **Stap 2: draai hem en zie hem falen**

Draai: `./mvnw clean test -pl demo/demo-personas -am -Dquarkus.http.test-port=0`
Verwacht: rood, de module bestaat nog niet.

- [ ] **Stap 3: maak de module**

`pom.xml` naar het voorbeeld van `demo/demo-console/pom.xml`: parent `moza-poc-fbs-berichtenbox`,
`artifactId` `demo-personas`, afhankelijkheden `quarkus-rest-jackson`, `quarkus-kotlin`,
`quarkus-container-image-jib`, `quarkus-arc`. Bewust géén `fbs-common` (die trekt de LDV-stack mee),
geen database, geen rest-client.

`src/main/resources/application.properties`:

```properties
quarkus.application.name=fbs-demo-personas
quarkus.container-image.name=fbs-demo-personas
quarkus.http.port=8098
```

- [ ] **Stap 4: verhuis het domein**

`git mv` de vier bestanden uit `demo/demo-console/src/main/kotlin/.../democonsole/personas/`
(`DemoPersona.kt`, `PersonaBron.kt`, `Identificatiecheck.kt`, `PersonaService.kt`) naar de nieuwe
module en hernoem het package naar `nl.rijksoverheid.moz.fbs.demopersonas`. Verhuis hun tests mee.

Twee wijzigingen in `PersonaService`:

1. Hij leest niet langer `DemoConfig` maar een eigen mapping:

```kotlin
@ConfigMapping(prefix = "demo.personas")
interface PersonaConfig {

    /** Demo-identiteiten, gesleuteld op id: `demo.personas.<id>.label` enzovoort. */
    @WithParentName
    fun personas(): Map<String, PersonaInstelling>

    interface PersonaInstelling {
        fun label(): String
        fun type(): String
        fun waarde(): String
        fun bron(): String
        fun magazijnen(): Optional<List<String>>
    }
}
```

2. De kruiscontrole tegen `config.magazijnen()` gaat eruit — die configuratie hoort bij de console,
   niet bij de identiteiten. Taak 2 zet hem daar terug. Wat blijft: de validatie per persona
   (`DemoPersona.init`) en de controle op dubbele ontvangers.

Verplaats de `demo.personas.*`-regels uit de console-`application.properties` naar
`demo/demo-personas/src/main/resources/META-INF/microprofile-config.properties`. Neem ze letterlijk
over, inclusief de comments erboven.

- [ ] **Stap 5: schrijf de resource**

```kotlin
/** Eén persona: `label` in de keuzelijst, `ontvanger` als `X-Ontvanger`-header, `bron` als presentatie. */
data class PersonaDto(val id: String, val label: String, val ontvanger: String, val bron: String)

@Path("/api/demo/personas")
@Produces(MediaType.APPLICATION_JSON)
class PersonaResource(private val personaService: PersonaService) {

    @GET
    fun personas(): List<PersonaDto> = personaService.alle().map {
        PersonaDto(it.id, it.label, it.ontvanger, it.bron.wire)
    }
}
```

- [ ] **Stap 6: draai de tests, zie ze slagen**

Draai: `./mvnw clean test -pl demo/demo-personas -am -Dquarkus.http.test-port=0`
Verwacht: groen, inclusief de meeverhuisde unittests.

- [ ] **Stap 7: registreer de module en toets de grens**

Draai: `.github/scripts/demo-modules.sh` — de nieuwe module hoort in de uitvoer te staan.
Draai: `.github/scripts/demo-grens.sh` — moet groen blijven; het stelsel noemt de module nergens.

- [ ] **Stap 8: commit**

```bash
git add pom.xml demo/demo-personas demo/demo-console
git commit -m "feat(demo): de demo-identiteiten krijgen een eigen dienst"
```

---

## Taak 2: de console neemt de module af

**Bestanden:**
- Wijzigen: `demo/demo-console/pom.xml` (afhankelijkheid `demo-personas`)
- Wijzigen: `demo/demo-console/.../generator/GeneratorProducer.kt` (import)
- Wijzigen: `demo/demo-console/.../DemoConfig.kt` (`personas()` eruit)
- Aanmaken: `demo/demo-console/.../personas/PersonaMagazijnCheck.kt`
- Test: `demo/demo-console/.../personas/PersonaMagazijnCheckTest.kt`

**Gebruikt:** `PersonaService.metMagazijnen()` uit taak 1.

Twee dingen die een uitvoerder hier tegenkomt:

**Beans uit een afhankelijkheid worden niet vanzelf gevonden.** Quarkus ontdekt CDI-beans alleen in
een jar met een Jandex-index. Neem `quarkus-jandex` op in de nieuwe module, of zet
`quarkus.index-dependency.personas.group-id`/`.artifact-id` in de console. Zonder dat start de
console zonder `PersonaService` en faalt de injectie in `GeneratorProducer`.

**Het endpoint komt mee.** `PersonaResource` zit in dezelfde module, dus de console blijft
`/api/demo/personas` op zijn eigen origin aanbieden — achter zijn muur, waar het paneel hem leest.
Dat is gewenst: de keuzelijst van de ontdubbeling blijft werken zonder extra client.

**Configuratie uit een afhankelijkheid.** Quarkus leest `META-INF/microprofile-config.properties`
uit jars, maar `application.properties` alléén uit de applicatie zelf. Vandaar de splitsing: de
persona-lijst in de eerste, de poort en de applicatienaam van de dienst in de tweede.

`DemoConfig` draagt `@ConfigMapping(prefix = "demo")`. Elke `demo.*`-property moet op een member
vallen, anders faalt het booten met SRCFG00050. `personas()` moet daar dus wég zodra de nieuwe
mapping die sleutels claimt — en de module moet als afhankelijkheid mee, anders claimt niemand ze.

- [ ] **Stap 1: schrijf de falende test**

```kotlin
class PersonaMagazijnCheckTest {

    @Test
    fun `een persona die naar een onbekend magazijn wijst laat de console niet starten`() {
        // De generator verdeelt berichten over de magazijnen van een persona. Een OIN dat nergens
        // bestaat levert geen fout op bij het aanleveren maar een persona zonder post.
        val fout = assertThrows<IllegalArgumentException> {
            PersonaMagazijnCheck.vereisBekend(
                personas = listOf(persona(magazijnen = listOf("00000000000000100000", "99999999999999999999"))),
                bekendeMagazijnen = setOf("00000000000000100000"),
            )
        }

        assertTrue(fout.message!!.contains("99999999999999999999"))
    }

    @Test
    fun `een persona zonder magazijnen glijdt er doorheen`() {
        PersonaMagazijnCheck.vereisBekend(listOf(persona(magazijnen = emptyList())), setOf("00000000000000100000"))
    }
}
```

- [ ] **Stap 2: draai hem en zie hem falen**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dquarkus.http.test-port=0 -Dtest=PersonaMagazijnCheckTest`
Verwacht: rood op een onbekende `PersonaMagazijnCheck`.

- [ ] **Stap 3: schrijf de check en haak hem aan het opstarten**

Een `@Observes StartupEvent` in de console die `PersonaService.alle()` en `DemoConfig.magazijnen()`
samenbrengt. Fail-fast bij het booten, zoals de rest van de console met configuratiefouten omgaat.

- [ ] **Stap 4: draai de hele module**

Draai: `./mvnw clean test -pl demo/demo-console -am -Dquarkus.http.test-port=0`
Verwacht: groen. Let op `PersonaConfiguratieTest` en `DemoDatasetConsistentieTest`: die lezen de
persona-lijst en moeten nu uit de afhankelijkheid komen.

- [ ] **Stap 5: commit**

```bash
git add demo/demo-console
git commit -m "refactor(demo): de console leest de identiteiten uit de personadienst"
```

---

## Taak 3: de lokale stack

**Bestanden:**
- Wijzigen: `compose.yaml` (service `demo-personas`, en `demo-proxy` krijgt hem als upstream)
- Wijzigen: `demo/proxy/demo.conf.template`
- Wijzigen: `docs/demo-runbook.md`

- [ ] **Stap 1: voeg de service toe**

```yaml
  demo-personas:
    image: ghcr.io/minbzk/fbs-demo-personas:${DEMO_TAG:-lokaal}
    build: ...   # zoals demo-console lokaal gebouwd wordt
    profiles: [demo]
    ports:
      - "${DEMO_BIND:-127.0.0.1}:8098:8098"
```

- [ ] **Stap 2: splits het pad in de proxy**

In `demo/proxy/demo.conf.template`, vóór `location /api/demo/`:

```nginx
    # De identiteiten komen uit een eigen dienst, zodat de proeftuin ze kan lezen zonder bij het
    # bedieningspaneel te kunnen. Deze regel houdt lokaal dezelfde scheiding aan als op ZAD.
    location /api/demo/personas {
        proxy_pass ${PERSONAS_UPSTREAM};
    }
```

Zet `PERSONAS_UPSTREAM` in de env van `demo-proxy` en breid `NGINX_ENVSUBST_FILTER` uit met
`PERSONAS_UPSTREAM`. Zonder die uitbreiding belandt de variabele letterlijk in de nginx-config.

- [ ] **Stap 3: draai de stack en toets beide paden**

```bash
docker compose --profile demo up -d
curl -s http://127.0.0.1:8097/api/demo/personas | head -c 200   # via de proxy
curl -s http://127.0.0.1:8098/api/demo/personas | head -c 200   # rechtstreeks
```

Verwacht: twee keer dezelfde lijst. Toets ook dat het paneel op `/bediening/` nog een keuzelijst
met persona's toont — dat pad loopt nog steeds langs de console.

- [ ] **Stap 4: commit**

```bash
git add compose.yaml demo/proxy docs/demo-runbook.md
git commit -m "feat(demo): de lokale stack draait de personadienst naast de console"
```

---

## Taak 4: bouwen en uitrollen

**Bestanden:**
- Wijzigen: `.github/workflows/deploy.yml` (build-demo-images, twee componentenlijsten)
- Wijzigen: `.github/workflows/cleanup-preview.yml` (opruimlus)

- [ ] **Stap 1: bouw het image mee**

`build-demo-images` draait één Maven-aanroep voor de demo-modules. Voeg `demo/demo-personas` toe aan
de `-pl`-lijst van die stap; de comment erboven noemt twee modules en moet er drie noemen.

- [ ] **Stap 2: rol het component uit**

In beide magazijnen-blokken (`pr-<n>` én `test`):

```json
{"name": "demopersonas", "image": "${{ env.REGISTRY }}/${{ needs.meta.outputs.owner }}/fbs-demo-personas:${{ needs.meta.outputs.tag }}"}
```

- [ ] **Stap 3: ruim het package op bij het sluiten**

Voeg `fbs-demo-personas` toe aan de `for pkg in …`-lus in `cleanup-preview.yml`.

- [ ] **Stap 4: toets de workflows**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/deploy.yml'))"
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/cleanup-preview.yml'))"
.github/scripts/test-wijzigingsfilter.sh
```

- [ ] **Stap 5: commit**

```bash
git add .github/workflows
git commit -m "ci(demo): de personadienst rolt mee met elke deployment"
```

---

## Taak 5: ZAD-inrichting

**Bestanden:**
- Wijzigen: `demo/environment/zad-demo/proeftuin-component.sh`
- Wijzigen: `demo/environment/zad-demo/README.md` (hoofdstuk 7)
- Wijzigen: `demo/environment/zad-demo/verify-zad.md` (stap 7)

- [ ] **Stap 1: maak het component in het script**

`zadctl component add demopersonas --image <uit deploy.yml> --deployment <d> --port 8098
--service publish-on-web`. Géén `authorization-wall` en géén `keycloak`: dit component draagt
uitsluitend een leeslijst, en de muur zou precies het probleem terugbrengen dat deze dienst oplost.

Zet `BACKEND_DEMO` op het proeftuin-component naar de publieke URL van dit component in dezelfde
deployment, in plaats van naar `democonsole`:

```
BACKEND_DEMO: https://demopersonas-$DEPLOYMENT_NAME-mpfm-w3h.<basisdomein>
BACKEND_DEMO_HOST: demopersonas-$DEPLOYMENT_NAME-mpfm-w3h.<basisdomein>
```

Let op: aliassen worden alleen bij het aanmaken van een component toegepast. Het bestaande
`proeftuin`-component moet dus verwijderd en opnieuw aangemaakt worden, of de aliassen worden met
`zadctl alias add` bijgewerkt en het component herstart.

- [ ] **Stap 2: toets in plan-modus**

Draai: `demo/environment/zad-demo/proeftuin-component.sh plan`
Verwacht: drie aanroepen, en `BACKEND_DEMO` wijst naar `demopersonas`.

- [ ] **Stap 3: schrijf de verificatiestap**

In `verify-zad.md` bij stap 7: kies in de berichtenbox een keten-persona met een KVK-nummer
(`Garage Van Dijk B.V.`) en verwacht berichten in plaats van de melding "Er gaat iets mis met het
ophalen van uw berichten bij de bronnen." Blijft die melding staan, toets dan
`curl -s https://demopersonas-<deployment>-mpfm-w3h.<basisdomein>/api/demo/personas` — een 403 daar
betekent dat er per ongeluk een muur op staat.

- [ ] **Stap 4: commit**

```bash
git add demo/environment/zad-demo
git commit -m "docs(demo): de personadienst in de ZAD-inrichting"
```

---

## Verificatie na afloop

1. `./mvnw clean verify -pl demo/demo-personas -am` en `-pl demo/demo-console -am` groen.
2. Lokaal: de berichtenbox op `http://127.0.0.1:8097/` toont voor een keten-persona berichten.
3. Op een preview: `GET https://demopersonas-pr-<n>-mpfm-w3h.<basisdomein>/api/demo/personas` geeft
   200 met de lijst; `GET https://democonsole-pr-<n>-…/api/demo/legen` geeft nog steeds 403.
4. In `pm-5sj`: `BACKEND_DEMO` staat op onze `test`-deployment en de berichtenbox daar toont
   keten-berichten.

## Wat hierbuiten valt

- `/api/demo/omgeving` blijft in de console. De proeftuin vraagt het niet op; alleen onze eigen
  wegwerp-berichtenbox gebruikt het, en die draait op dezelfde origin als de console.
- De vraag of moza-poc zijn nginx op `/api/demo/personas` moet laten proxyen in plaats van op de
  hele `/api/demo/`-prefix. Met deze scheiding is dat niet meer nodig om veilig te zijn, alleen nog
  als extra slot. Voorstel apart bij hen neerleggen.
