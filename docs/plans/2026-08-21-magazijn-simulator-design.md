**Status:** Concept

# Magazijn-simulator — veel magazijnen met echte state — ontwerp

Issue: MinBZK/MijnOverheidZakelijk#938 ("Bepalen met hoeveel magazijnen en met hoeveel realisme we
demonstreren"). Hangt samen met #936 (demo draaibaar op laptop én ZAD) en #950 (magazijnen in
onderhoud). De statusvelden die de simulator moet dragen zijn dezelfde die #484 federatief wil
vastleggen.

**Overkoepelend ontwerp:** `docs/plans/2026-07-21-demo-platform-design.md`. Dit ontwerp vervangt
de stub-aanpak uit `2026-07-24-demo-platform-fase-6-veel-magazijnen-design.md`.

## Context

De demo draait nu met twee echte magazijnen (A en B, Quarkus + eigen PostgreSQL) plus n
WireMock-stubs die sinds fase 6 op pad-prefix `/mNN` uit één container komen. Dat fundament staat en
werkt: het aantal is instelbaar (`DEMO_MAGAZIJN_STUBS`), de fan-out per persona loopt via de
profiel-scopes, en de demo-console kan live "k van n" actief zetten.

Twee dingen zijn niet goed genoeg.

**WireMock kan geen state dragen.** De spec kent `PATCH /berichten/{berichtId}` met
`{gelezen, map}`, en `BerichtStatusInfo` geeft die twee terug op de lijst en het detail. Een
WireMock-mapping antwoordt met een vaste body: een `PATCH` verdwijnt in het niets en de volgende
`GET` toont weer de oude toestand. Mappen en leesstatus — precies wat we over veel magazijnen
willen tonen — zijn daarmee alleen op de twee echte magazijnen demonstreerbaar. De stubs ondersteunen
ook geen paginering, geen filtering op `X-Ontvanger` en geen echte bijlage-bytes. En omdat ze niets
met de spec delen, kunnen ze er stil uit lopen.

**Het aantal is om de verkeerde reden begrensd.** Eerdere afwegingen lieten de leesbaarheid van de
Berichtenbox-lijst meewegen. Dat is geen argument: de UI mag het aantal magazijnen niet bepalen.
Wat het aantal wél begrenst staat onder "Grenzen en meetpunten".

## Aantallen

| Laag | Aantal | Onderbouwing |
|---|---|---|
| Echte magazijnen | **2** (A, B — ongewijzigd) | Dragen aanleveren, bijlagen, notificatie-outbox, LDV, retentie en FSC. Kosten per stuk zijn hoog (op ZAD ~335–363 Mi + eigen `postgresql-database` + eigen ingress). Een derde voegt geen nieuw gedrag toe: variatie in snelheid en uitval doet Toxiproxy al. |
| Gesimuleerde magazijnen | **98** | Eén service + één database, ongeacht n. Kosten zijn constant in het aantal. |
| **Register totaal** | **100** | |

Realisme-ijkpunt: een ondernemer met één vestiging raakt realistisch 15–20 afzenders
(Belastingdienst, KVK, RVO, UWV, SVB, RDW, Kadaster, CBS, NVWA, ILT, ACM, DUO, Justis, plus
gemeente, provincie, waterschap en omgevingsdienst). Met vestigingen in meerdere gemeenten loopt dat
naar 25–45. Het landelijke register is wezenlijk groter — alleen al 342 gemeenten — dus 100 toont de
orde van grootte van de fan-out, niet de omvang van het register. Zeg dat in de demo hardop.

Alle drie de getallen zijn instellingen, geen code.

## Waarom een eigen simulator

| Alternatief | Waarom niet |
|---|---|
| WireMock met stateful scenarios | Scenario-transitions dekken geen per-bericht-state; een vrije-tekst `map` en filtering per ontvanger zijn er niet in uit te drukken. |
| n instanties van het echte berichtenmagazijn | 98 JVM's en 98 databases. |
| Het echte magazijn multi-tenant maken | Raakt productiecode op de plekken waar het pijn doet: autorisatie, LDV per organisatie, migraties, retentie. |
| **Nieuwe module `services/magazijn-simulator`** | Eén service, één database, n magazijnen op pad-prefix. |

Bijvangst die het extra waard maakt: de simulator is een **tweede, onafhankelijke implementatie van
`berichtenmagazijn-api.yaml`**. Voor een federatief stelsel is dat geen bijzaak — het toont dat de
spec implementeerbaar is door iemand die onze domeincode niet heeft. En omdat hij uit dezelfde spec
genereert, faalt de build zodra spec en simulator uiteenlopen. Dat is precies wat WireMock nooit kon.

## Module-opzet

Nieuwe Maven-module `services/magazijn-simulator`, package
`nl.rijksoverheid.moz.fbs.magazijnsimulator`. Quarkus + Kotlin + Panache + PostgreSQL 18 + Flyway,
conform de bestaande conventies (surrogate PK per tabel, FK op surrogate PK, RESTRICT, `bytea`
zonder `@Lob`).

### Spec-hergebruik (optie A)

De generator staat al geparametriseerd in de parent-`pluginManagement`; een module voedt hem met
twee properties. De simulator-pom krijgt:

```xml
<api.spec.file>${project.basedir}/../berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml</api.spec.file>
<api.base.package>nl.rijksoverheid.moz.fbs.magazijnsimulator</api.base.package>
```

Overwogen en niet gekozen: de spec naar een eigen `libraries/`-module tillen. Dat lost geen
robuustheidsprobleem op — de spec is een statisch bronbestand, dus er valt geen build-order te
regelen en een verplaatste module faalt luidruchtig — maar het kost wel een import-rewrite door de
hele magazijn-module (het gedeelde package kan niet `…fbs.berichtenmagazijn.api` blijven heten) en
het breekt de gepubliceerde spec-URL in `apis.json`. Het enige echte nadeel van optie A is dat de
koppeling niet in het dependency-graaf zichtbaar is; dat dekken we af met een comment bij
`api.spec.file` in `services/berichtenmagazijn/pom.xml` en een regel in de bestandstabel van
CLAUDE.md. Verhuizen kan alsnog zodra er een derde consument is — dan is het argument er ook echt.

Twee generator-runs leveren twee kopieën van de DTO's in twee packages op. Dat is hier onschadelijk:
de simulator is een apart deployable dat nooit in-process objecten met het magazijn deelt.

### Magazijnkeuze op pad-prefix

De gegenereerde interfaces dragen paden relatief aan `quarkus.rest.path=/api/v1`; daar verandert
niets aan. Een `@PreMatching ContainerRequestFilter` doet het werk:

```
GET   /m07/api/v1/berichten          →  magazijn m07, match op /api/v1/berichten
PATCH /m07/api/v1/berichten/{id}     →  magazijn m07, match op /api/v1/berichten/{id}
POST  /m07/api/v1/aanleveringen      →  magazijn m07, match op /api/v1/aanleveringen
```

Het filter leest het eerste segment, matcht `^m\d+$`, zoekt het magazijn op, vult een
`@RequestScoped MagazijnContext` en herschrijft de URI met **`setRequestUri(baseUri, requestUri)`**.

Die twee-argument-variant is essentieel. De resources bouwen hun HAL `_links` uit
`UriInfo.baseUriBuilder`; door het prefix in de `baseUri` te laten staan blijven die links
`/m07/…` bevatten. Met de één-argument-variant verdwijnt het prefix en wijzen de links naar het
verkeerde magazijn. Dit is de scherpste valkuil in het ontwerp — het gedrag van Quarkus REST op dit
punt moet in stap 1 geverifieerd worden en met een test vastgepind.

Onbekend of ontbrekend prefix → 404 `application/problem+json`. Bewust geen default-magazijn: een
verkeerd geconfigureerd register moet luidruchtig falen en niet stil op magazijn 1 uitkomen.

De uitvraag-kant hoeft niets: `MagazijnClientFactory` bouwt de client met
`baseUri(http://magazijn-simulator:8092/m07)` en behoudt dat subpad — al bewezen in fase 6.

## Datamodel

```
magazijn        id, oin UNIQUE, prefix UNIQUE, naam,
                gedrag_modus, latency_p50_ms, latency_p95_ms, foutkans, fout_status
bericht         id, magazijn_db_id FK, bericht_id UUID, afzender,
                ontvanger_type, ontvanger_waarde, onderwerp, inhoud,
                publicatietijdstip, tijdstip_ontvangst, verwijderd_op,
                UNIQUE(magazijn_db_id, bericht_id)
bericht_status  id, bericht_db_id FK, gelezen bool, map varchar(128), gewijzigd_op
bijlage         id, bericht_db_id FK, bijlage_id UUID, naam, mime_type, inhoud bytea
```

Elke repository filtert op het magazijn uit `MagazijnContext`; er is geen query die zonder die
discriminator draait. Test-cleanup in child-eerst-volgorde (`bericht_status` → `bijlage` →
`bericht` → `magazijn`), zoals bij het echte magazijn.

Wat hiermee werkt en met WireMock niet:

- **Mappen** — `PATCH {gelezen, map}` schrijft naar `bericht_status`; de volgende `GET` geeft de map
  terug, op alle 98 magazijnen, met de spec-semantiek (ontbrekend óf `null` veld = niet wijzigen).
- **Leesstatus** — persistent, dus de Berichtenbox toont over de hele fan-out consistente state, ook
  na een nieuwe sessie.
- **Soft-delete**, **filtering op `X-Ontvanger`** (persona's lekken niet in elkaar), **paginering**
  op `GET /berichten` — die laatste wordt zo voor het eerst over veel magazijnen echt uitgeoefend.
- **Bijlagen** met echte bytes en MIME-type, zodat het `BijlageContentTypeFilter`-pad in de uitvraag
  ook bij de gesimuleerde magazijnen iets doet.

Bewust buiten de simulator, en dat is wat hem klein houdt: LDV, FSC-inway, notificatie-outbox,
retentie-cron, autorisatiediepte en bijlage-groottelimieten.

## Gedrag per magazijn

Het gedrag staat in de `magazijn`-rij en geldt voor **elke** endpoint van dat magazijn — dus ook
voor `PATCH` en bijlage-download, iets wat een WireMock-mapping-overlay per definitie niet dekte.

| Modus | Effect | Wat de uitvraag registreert |
|---|---|---|
| `NORMAAL` | ~50 ms | `OK` |
| `TRAAG` | log-normaal rond p50/p95 | `OK`, late substream |
| `HAPERT` | foutkans per call | wisselend `OK` / `FOUT` |
| `STUK` | `fout_status` (503) | `FOUT`, na 3× `CIRCUIT_OPEN` |
| `UIT` | vertraging boven de query-timeout van 10 s | `TIMEOUT` |

Een vertraagd antwoord houdt een worker-thread vast (Panache is blocking). Bij een fan-out van 100
met seconden vertraging raakt de default-pool van 200 in zicht, zeker bij meerdere gelijktijdige
sessies. Ofwel `quarkus.thread-pool.max-threads` meeschalen, ofwel de vertraging reactief vóór de
blocking DB-call leggen. Meetpunt, geen blokkade.

## Beheer-API

Buiten de gedeelde spec, op een eigen `/beheer`-pad zodat de gegenereerde interfaces schoon blijven.

| Endpoint | Waarvoor |
|---|---|
| `POST /beheer/magazijnen` | n magazijnen inrichten (oin, prefix, naam, gedrag) |
| `PUT /beheer/magazijnen/{prefix}/gedrag` | gedrag live bijstellen |
| `POST /beheer/seed` | bulk n × m berichten in één transactie |
| `POST /beheer/legen` | reset zonder handmatig databasewerk |

`seed` is geen luxe: 45 magazijnen × 50 berichten via losse HTTP-aanleveringen duurt minuten, een
bulk-insert seconden. Vullen kán daarnaast gewoon via `POST /mNN/api/v1/aanleveringen` — dezelfde
spec, dus de bestaande aanlever- en generatorcode van de demo-console werkt met alleen een andere
base-URL. Samen dekken `seed` en `legen` de acceptatiecriteria van #936 over herhaalbaar vullen en
leegmaken.

**`/beheer` moet dicht.** De WireMock-admin-API van de huidige ZAD-stubs is publiek en zonder
authenticatie bereikbaar (`GET https://profiel-test-mpfpsm-lcl.rig…/__admin/mappings` antwoordt met
200; geverifieerd 2026-08-21). Die fout niet herhalen: een gedeeld token in een header, verplicht
buiten `%dev`/`%test`, afgedwongen bij boot zoals `RedisVerbindingValidator` dat doet. Alternatief om
te onderzoeken: `/beheer` op de Quarkus management-interface, die op ZAD niet via `publish-on-web`
naar buiten komt. Token-auth werkt hoe dan ook en is de basis.

## Persona's en fan-out

De fan-out is (opted-in afzender-OIN's uit het profiel) ∩ register. Vier persona's, waarvan de
verzamelingen **genest** zijn: elke grotere persona bevat de set van de kleinere. Daardoor is het
verschil in wachttijd puur het gevolg van de extra magazijnen en niet van een andere mengeling.

| Persona | Identificatie | Fan-out | Samenstelling |
|---|---|---|---|
| Bakkerij Pietersen | bestaande BSN-persona | **3** | A + B + m01 |
| Installatiebedrijf De Vries | KVK 90000002 | **15** | A + B + m01–m13 |
| Grootbedrijf B.V. | KVK 90000001 (bestaat al) | **45** | A + B + m01–m43 |
| Landelijk Concern N.V. | KVK 90000003 | **100** | A + B + m01–m98 |

De vierde persona bevraagt het volledige register en is bewust extreem: geen enkele echte ondernemer
raakt 100 magazijnen. Hij bestaat om het capaciteitsgedrag van de keten zichtbaar te maken, niet om
realisme te tonen.

### Gedragsverdeling over de 98

Deterministisch uit de index, in deze precedentievolgorde — geen seed nodig, elke omgeving krijgt
dezelfde verdeling:

1. `i ∈ {28, 97}` → **UIT** (2)
2. `i ∈ {33, 66, 98}` → **STUK** (3)
3. `i mod 20 == 0` → **HAPERT** (m20, m40, m60, m80 — 4)
4. `i mod 5 == 0`, voor zover niet hierboven → **TRAAG** (15)
5. rest → **NORMAAL** (74)

Aandelen: 76 % normaal, 15 % traag, 4 % hapert, 3 % stuk, 2 % uit. De twee echte magazijnen staan
op normaal; hun gedrag stuur je met Toxiproxy.

De verdeling is zo gelegd dat elke persona een zinvol beeld geeft:

| Persona | traag | hapert | stuk | uit | > bulkhead (20)? |
|---|---|---|---|---|---|
| 3 | – | – | – | – | nee |
| 15 | m05, m10 | – | – | – | nee |
| 45 | m05, m10, m15, m25, m30, m35 | m20, m40 | m33 | m28 | **ja** |
| 100 | 15 | 4 | 3 | 2 | **ja, ruim** |

Verwacht beeld per persona — te meten, niet aangenomen:

- **3** — alles binnen, vrijwel direct. De referentie.
- **15** — compleet, maar de lijst is pas klaar als de twee trage magazijnen geantwoord hebben. Toont
  dat de gebruiker op zijn traagste leverancier wacht.
- **45** — eerste berichten direct, daarna druppelsgewijs; één magazijn in timeout, één stuk (na drie
  rondes `CIRCUIT_OPEN`), twee die wisselend falen. En: 45 > 20 permits, dus een deel wordt
  geweigerd. Partiële lijst, de rest komt bij vernieuwen.
- **100** — gedomineerd door bulkhead-weigering: ~20 magazijnen beantwoord, ~80 "tijdelijk niet
  beschikbaar".

## Grenzen en meetpunten

| Grens | Waarde | Meten |
|---|---|---|
| Bulkhead | 20 permits, gedeeld over sessies | zie hieronder |
| REST-clients in de uitvraag | n clients bij boot, elk met eigen pool | boot-tijd en RSS bij n = 50 / 100 / 250 |
| Worker-threads in de simulator | ~200 (default) | fan-out 100 × vertraagd, meerdere sessies |
| Register-configuratie | 2 regels per magazijn | 200 regels bij n=100; triviaal |

De demo draait op de **productiewaarde `berichtensessiecache.magazijn-bulkhead.max-concurrent=20`**.
De huidige demo-override naar 60 in `compose.yaml` vervalt: die maskeerde precies het gedrag dat
#938 wil kunnen tonen. Wie het wachttijd-verhaal zónder weigering wil demonstreren zet de waarde
tijdelijk boven de fan-out; dat blijft één env-var.

**Openstaande vraag met productie-impact.** `MagazijnAggregatieBulkhead` doet `tryAcquire` zonder
wachttijd: bij een volle bulkhead volgt onmiddellijk `OVERBELAST`, er wordt niet gewacht. Bij een
fan-out boven 20 krijgt de gebruiker dus structureel een onvolledige lijst zonder dat er iets stuk
is. Erger nog: `MagazijnClientFactory` bouwt zijn clients met `associate` (LinkedHashMap) en
`Multi.createBy().merging()` subscribet in die volgorde, dus vermoedelijk winnen **steeds dezelfde
eerste 20** de permits en zijn de overige magazijnen voor die gebruiker permanent onzichtbaar — in
plaats van een wisselende deelverzameling. Dat is met de 100-persona voor het eerst reproduceerbaar
aan te tonen. Vaststellen met een test; is het zo, dan hoort er een apart issue onder #349 te komen
over permits meeschalen met de maximale fan-out, dan wel wachtrijen met een begrensde wachttijd in
plaats van weigeren.

## Wat vervalt

| Weg | Erbij |
|---|---|
| `magazijn-stubs`-service in compose (WireMock, poort 8092) | `magazijn-simulator` op dezelfde poort 8092 + `postgres-simulator` op 5435 |
| `demo/generated/magazijn-stubs-mappings/` | `POST /beheer/magazijnen`-payload uit de generator |
| `VeelMagazijnenService` + `WireMockAdminClient` in demo-console | demo-console roept `/beheer/…` aan |
| 503-overlay als enige gedragsknop | gedragsmodus per magazijn |

De WireMock-stubs op 8081/8082 blijven: die bedienen de `%test`-profielen, niet de demo.
`demo/genereer-magazijnen.py` blijft bestaan maar schrijft voortaan register-regels naar de
simulator plus één inricht-payload, in plaats van n mapping-bestanden.

## ZAD

| Onderdeel | Aanpak |
|---|---|
| Simulator-component | Nieuw component in `mpfm-w3h` (magazijnen), image via jib zoals de andere services, plus de platform-service `postgresql-database`. Kosten ~200–350 Mi, constant in n. |
| Register-entries | Gegenereerde `.properties` als ZAD-`attachments`-entry met `provide-as: file`, plus `SMALLRYE_CONFIG_LOCATIONS` op de uitvraag. Precedent: `logius-internal-ca-root-cert` staat er al zo op. n env-vars is bij 100 magazijnen geen optie. Previews erven via `clone-from: test`. |
| Profiel-persona's | De vier persona-mappings mee laten bakken in het `fbs-externe-stubs`-image; generator draaien vóór `docker build` in CI, met n uit een repo-variable. |
| https | `ConfigMagazijnregister` eist https buiten dev/test; de ZAD-ingress levert dat. |
| `/beheer` | Token verplicht buiten dev/test (zie boven). |

De simulator hoeft **niet** door FSC. Dat is een aparte afweging die in dit ontwerp niet meespeelt:
FSC blijft op de twee echte magazijnen. Eén gesimuleerd magazijn als extra FSC-dienst op een
bestaande inway is later een goedkope toevoeging (één dienst, één contract, één grant-hash), maar
n grant-hashes en n handmatige env-vars in Operations Manager schalen niet.

## Foutafhandeling

- Onbekend pad-prefix → 404 problem+json met het prefix in `detail`.
- Ontbrekende of ongeldige `X-Ontvanger` → 400, zoals de spec voorschrijft.
- `POST /beheer/*` zonder geldig token buiten dev/test → 401, en bij boot een fail-fast als het
  token niet gezet is.
- `seed` met een onbekend magazijn-prefix → 400 met de lijst van bekende prefixes.
- Gedragsmodus `STUK`/`UIT` geldt ook voor `/beheer`? Nee — het beheerpad staat buiten de simulatie,
  anders is een kapot gezet magazijn niet meer te repareren.

## Testen

- **Unit** — pad-prefix-filter (geldig, onbekend, ontbrekend, prefix in de `baseUri`), gedragskiezer
  (deterministische verdeling voor i = 1…98: precies 2 uit, 3 stuk, 4 hapert, 15 traag, 74 normaal),
  status-patch-semantiek (ontbrekend veld, expliciet `null`, overschrijven van een map).
- **Integratie (`@QuarkusTest`, Dev Services)** — twee magazijnen naast elkaar: bericht aanleveren in
  m01, `PATCH gelezen+map`, `GET` in m01 toont de wijziging en `GET` in m02 níét; paginering over
  meerdere pagina's; soft-delete; bijlage-bytes met MIME-type. Cardinaliteiten leeg / één / meerdere
  via `@ParameterizedTest`, conform de teststrategie.
- **Contract** — `swagger-request-validator` tegen `berichtenmagazijn-api.yaml`, zodat de simulator
  aantoonbaar dezelfde spec dient als het echte magazijn. Dit is de kern van de belofte en verdient
  een expliciete test in plaats van vertrouwen op de generator.
- **Keten** — de bestaande `demo/smoke.sh` uitbreiden met een gesimuleerd magazijn: aanleveren →
  ophalen via de uitvraag → markeren als gelezen → opnieuw ophalen toont `gelezen: true`.
- **Coverage** — de simulator is demo-gereedschap, net als `demo-console`, en krijgt daarom géén
  90 %-JaCoCo-gate. Wel echte tests; detekt geldt onverkort.

## Stappen

1. **Module + spec + pad-prefix.** Lege module, generatie uit optie A, `@PreMatching`-filter,
   `MagazijnContext`. Verificatie: `GET /m01/api/v1/berichten` levert een lege, spec-valide
   `BerichtenLijst` en de `_links` bevatten `/m01`.
2. **Persistentie.** Flyway-migratie, entities, repositories met discriminator, alle zes de
   operaties uit de spec. Verificatie: de integratietests hierboven.
3. **Gedrag per magazijn.** Modi, vertraging, foutkans. Verificatie: unit-test op de verdeling plus
   een `@QuarkusTest` die een `STUK`-magazijn een 503 ziet geven.
4. **Beheer-API + token.** Inrichten, seed, legen, gedrag. Verificatie: 100 magazijnen × 20 berichten
   geseed in < 10 s; 401 zonder token onder `%prod`.
5. **Generator en compose omzetten.** WireMock-stub-service en `VeelMagazijnenService` eruit,
   simulator erin, vier persona's in de profiel-stub, bulkhead terug naar 20. Verificatie:
   `demo/smoke.sh` groen, de vier persona's leveren fan-out 3 / 15 / 45 / 100.
6. **Meten en vastleggen.** Tijd tot eerste bericht en tijd tot compleet per persona; boot-tijd en
   geheugen van de uitvraag bij n = 50 / 100 / 250; is de bulkhead-weigering deterministisch?
   Uitkomsten terug in dit document en in #938.
7. **ZAD.** Component, database, register-attachment, persona's in het stubs-image.

Stap 1 t/m 5 leveren de lokale demo; stap 6 levert de onderbouwing die #938 vraagt; stap 7 de
ZAD-helft, die verder op #936 leunt.

## Bewust buiten scope

- **De UI.** Het aantal magazijnen mag geen UI-vraagstuk zijn; hoe de Berichtenbox 100 substreams
  toont is een eigen afweging in #936.
- **FSC voor de gesimuleerde magazijnen** (zie ZAD).
- **Load- en stresstesten** (#14): dit ontwerp maakt gelijktijdigheid demonstreerbaar, niet meetbaar
  onder belasting.
- **Onderhoudstoestand als apart begrip** (#950): `UIT` en `STUK` dekken het gedrag; of een magazijn
  in onderhoud een eigen signaal krijgt is een spec-vraag, geen simulator-vraag.

## Openstaande beslissingen

1. Honoreert Quarkus REST de `baseUri` uit `setRequestUri(baseUri, requestUri)` voor `UriInfo`? Zo
   niet, dan moeten de HAL-links langs een andere weg hun prefix terugkrijgen. **Blokkeert stap 1.**
2. `/beheer` achter een token, of op de management-interface? Token is de basis; de
   management-interface is een verbetering als ZAD hem afschermt.
3. Blijft de bulkhead-weigering bij dezelfde eerste 20 magazijnen hangen? Zo ja: apart issue onder
   #349.
4. Is n = 100 haalbaar binnen de boot-tijd en het geheugen van de uitvraag, of ligt het plafond
   lager? Stap 6 beslist; het getal in dit document is een voorstel, geen meting.
