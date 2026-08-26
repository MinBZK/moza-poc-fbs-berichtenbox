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

## Begrippen

Een paar termen komen hieronder steeds terug.

| Term | Betekenis in dit document |
|---|---|
| fan-out | Het aantal magazijnen dat de uitvraag voor één ondernemer bevraagt: de opt-ins uit zijn profiel, doorsneden met het register. |
| substream | De voortgangsregels van één magazijn binnen één ophaalronde — gestart, daarna voltooid met uitkomst `OK`, `TIMEOUT` of `FOUT`. |
| pad-prefix | Het eerste stuk van de URL — `/magazijn/<OIN>` — waarmee de simulator bepaalt welk magazijn bedoeld is. |
| index (`i`) | Volgnummer 1…98 van een gesimuleerd magazijn. Bepaalt zijn OIN (`0000000900000000{i:04d}`), zijn naam ("Demo-magazijn i") en zijn gedrag. Alleen een rekengrootheid; hij komt niet in URL's voor. |
| log-normale spreiding | Responstijden met een lange staart: de meeste rond de mediaan, af en toe een forse uitschieter. Realistischer dan een vaste vertraging. |
| circuit breaker | Beveiliging in de uitvraag die een magazijn na herhaalde storingen een tijdje overslaat in plaats van er telkens op te wachten. |

## Aantallen

| Laag | Aantal | Onderbouwing |
|---|---|---|
| Echte magazijnen | **2** (A, B — ongewijzigd) | Dragen aanleveren, bijlagen, notificatie-outbox, LDV, retentie en FSC. Kosten per stuk zijn hoog (op ZAD ~335–363 Mi + eigen `postgresql-database` + eigen ingress). Een derde voegt geen nieuw gedrag toe: variatie in snelheid en uitval komt straks uit de simulator. |
| Gesimuleerde magazijnen | **98** | Eén service + één database, ongeacht n. Kosten zijn constant in het aantal. |
| **Register totaal** | **100** | |

Realisme-ijkpunt: een ondernemer met één vestiging raakt realistisch 15–20 afzenders
(Belastingdienst, KVK, RVO, UWV, SVB, RDW, Kadaster, CBS, NVWA, ILT, ACM, DUO, Justis, plus
gemeente, provincie, waterschap en omgevingsdienst). Met vestigingen in meerdere gemeenten loopt dat
naar 25–45. Het landelijke register is wezenlijk groter — alleen al 342 gemeenten — dus 100 toont de
orde van grootte van de fan-out, niet de omvang van het register. Zeg dat in de demo hardop.

Het aantal gesimuleerde magazijnen en de fan-out per persona zijn instellingen: één getal in het
generatiescript en een profiel-mapping. Het aantal **echte** magazijnen is dat niet — dat is een
deployment erbij (container, database, ingress en, zodra zo'n magazijn federatief meedoet, een eigen
FSC-peer). Vandaar dat de twee echte er twee blijven.

## Besluit: een eigen simulator

We bouwen één service die n magazijnen bedient, elk op een eigen pad-prefix, met één PostgreSQL
eronder. Drie redenen.

**Toestand.** Mappen en leesstatus zijn niet decoratief maar precies wat we willen tonen. Dat vraagt
opslag, en dus een echte implementatie in plaats van een antwoordmachine.

**Kosten die niet meeschalen.** Eén service en één database, of er nu tien of honderd magazijnen in
het register staan. Elke andere vorm betaalt per magazijn.

**Het contract blijft bewaakt.** De simulator genereert uit dezelfde `berichtenmagazijn-api.yaml`,
dus zijn build faalt zodra spec en implementatie uiteenlopen. Bijkomend voordeel: het levert een
tweede, onafhankelijke implementatie van die spec op. Voor een federatief stelsel is dat geen
bijzaak — het toont dat het contract implementeerbaar is door iemand die onze domeincode niet heeft.

### Overwogen alternatieven

| Alternatief | Waarom afgevallen |
|---|---|
| Doorgaan met WireMock, met stateful scenarios | Scenario-transitions dekken geen per-bericht-toestand; een vrije-tekst `map` en filtering per ontvanger zijn er niet in uit te drukken. |
| n instanties van het echte berichtenmagazijn | 98 JVM's en 98 databases — en zodra ze federatief meedoen ook een FSC-peer per magazijn (manager, controller, txlog, inway, outway, eigen PKI). Bij tien magazijnen zijn dat al zestig componenten. |
| Het echte magazijn multi-tenant maken | Raakt productiecode op de plekken waar het pijn doet: autorisatie, LDV per organisatie, migraties, retentie. |

## Module-opzet

Nieuwe Maven-module **`demo/magazijn-simulator`** — een eigen module in de reactor, naast
`demo/demo-console` en de twee services van het stelsel. Package
`nl.rijksoverheid.moz.fbs.magazijnsimulator`. Quarkus + Kotlin + Panache + PostgreSQL 18 + Flyway,
conform de bestaande conventies (surrogate PK per tabel, FK op surrogate PK, RESTRICT, `bytea`
zonder `@Lob`).

De simulator is een onderdeel van wat de issues de **simulatie-engine** noemen (groep-issue #787,
en #936 voor het draaibaar maken ervan) — niet het geheel: de demo-console en de scripts in `demo/`
horen er net zo goed bij. Vandaar de specifiekere modulenaam; wie "simulatie-engine" zegt, bedoelt
de simulator plus de bediening eromheen.

**Waarom onder `demo/`.** De simulator hoort nooit in productie te draaien. Onder voorbehoud van
het teambesluit bij spike MinBZK/MijnOverheidZakelijk#1005 — valt dat anders uit, dan landt de
simulator op `services/magazijn-simulator` — is `demo/` een module-wortel naast `services/` en
`libraries/`:
`demo-console` staat er al, de simulator komt ernaast. De richting van de koppeling is een afspraak:
een module uit het stelsel mag niet van een demo-module afhangen, andersom wel.

Let op dat "demo" niet betekent "wordt niet uitgerold": de simulator krijgt een eigen ZAD-component
en dus een eigen image. `.github/scripts/wijzigingsfilter.sh` sluit daarom alleen de aantoonbaar
niet-uitgerolde delen van `demo/` van bouwen en deployen uit; `demo/magazijn-simulator/` valt daar
buiten en houdt zijn build. Zie `demo/README.md`.

### Spec-hergebruik (optie A)

De generator staat al geparametriseerd in de parent-`pluginManagement`; een module voedt hem met
twee properties. De simulator-pom krijgt:

```xml
<api.spec.file>${project.basedir}/../../services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml</api.spec.file>
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
GET   /magazijn/00000009000000000007/api/v1/berichten      →  magazijn met die OIN, match op /api/v1/berichten
PATCH /magazijn/00000009000000000007/api/v1/berichten/{id} →  idem, match op /api/v1/berichten/{id}
POST  /magazijn/00000009000000000007/api/v1/aanleveringen  →  idem, match op /api/v1/aanleveringen
```

**Het prefix is de OIN, niet een verzonnen kortcode.** Fase 6 gebruikte `/m07`; dat vroeg om een
aparte `prefix`-kolom die met de gegenereerde register-regels in de pas moest blijven, en het brak
boven de 99 magazijnen (`/m{i:02d}`). De OIN is de identiteit die toch al door de hele keten stroomt
— `magazijnId == oin.waarde` — en staat al als sleutel in het register, dus de configuratieregel
wordt zelfbeschrijvend:

```properties
magazijnen."00000009000000000007".url=http://magazijn-simulator:8092/magazijn/00000009000000000007
```

Sleutel en OIN-segment zijn dan per constructie gelijk; drift tussen generator en simulator kan niet
meer ontstaan. De prijs is een langere URL in de logs, en dat is hem waard.

**Met `/magazijn/` ervoor.** Zonder dat woord moet het filter aan de *vorm* van het eerste segment
zien of er een magazijn bedoeld is — twintig cijfers, dus `^\d{20}$` — en dat is een gok over wat
een padsegment betekent. Met een vast woord ervoor is de vraag letterlijk te beantwoorden: begint
het pad met `/magazijn/`, dan hoort het tweede segment een OIN te zijn en is een niet-bestaande OIN
een 404 in plaats van iets dat langs het filter glipt. Het houdt bovendien de wortel vrij voor
paden die géén magazijn zijn — `/beheer`, en `/q/*` van Quarkus zelf. Voor mensen die de logs lezen
is het meegenomen dat er staat wat het is; dat is niet de reden, wel een prettige bijvangst. De
kosten zijn één segment extra in de register-URL.

Het filter matcht het eerste segment op `magazijn`, zoekt het magazijn bij het tweede segment op,
vult een `@RequestScoped MagazijnContext` en herschrijft de URI met
**`setRequestUri(baseUri, requestUri)`**.

Die twee-argument-variant is essentieel. De resources bouwen hun HAL `_links` uit
`UriInfo.baseUriBuilder`; door het prefix in de `baseUri` te laten staan blijven die links de OIN
bevatten. Met de één-argument-variant verdwijnt het prefix en wijzen de links naar het verkeerde
magazijn. Dit is de scherpste valkuil in het ontwerp — het gedrag van Quarkus REST op dit punt moet
in stap 1 geverifieerd worden en met een test vastgepind.

Een pad zonder `/magazijn/`-wortel, zonder OIN of met een onbekende OIN → 404
`application/problem+json`. Bewust geen default-magazijn: een verkeerd geconfigureerd register moet
luidruchtig falen en niet stil bij het eerste magazijn uitkomen.

De uitvraag-kant hoeft niets: `MagazijnClientFactory` bouwt de client met
`baseUri(http://magazijn-simulator:8092/magazijn/<OIN>)` en behoudt dat subpad — al bewezen in
fase 6.

## Datamodel

```
magazijn        id, oin UNIQUE, naam,
                gedrag_modus, latency_p50_ms, latency_p95_ms, foutkans, fout_status
bericht         id, magazijn_db_id FK, bericht_id UUID, afzender,      -- UNIQUE (magazijn_db_id, bericht_id)
                ontvanger_type, ontvanger_waarde, onderwerp, inhoud,
                publicatietijdstip, tijdstip_ontvangst, verwijderd_op
bericht_status  id, bericht_db_id FK UNIQUE, gelezen bool, map varchar(128), gewijzigd_op
bijlage         id, bericht_db_id FK, bijlage_id UUID, naam, mime_type, inhoud bytea
```

Elke repository filtert op het magazijn uit `MagazijnContext`; er is geen query die zonder die
discriminator draait. Test-cleanup in child-eerst-volgorde (`bericht_status` → `bijlage` →
`bericht` → `magazijn`), zoals bij het echte magazijn. Bij elke migratie hoort een
rollback-script onder `src/main/resources/db/rollback/V*.sql`, conform de projectconventie.

Twee dingen verdienen toelichting.

**`bericht_id` is uniek binnen een magazijn, niet daarbuiten.** Elk magazijn deelt zijn eigen
`berichtId` uit; twee organisaties kunnen dezelfde UUID kiezen zonder dat iemand daar iets over te
zeggen heeft. De simulator hoort dat te kunnen — de natuurlijke sleutel is dus
`(magazijn_db_id, bericht_id)`, precies zoals in het echte magazijn.

Dat wringt met de sessiecache, die berichten opslaat onder `bericht:v1:<berichtId>` zonder magazijn
erin: bij een botsing verdringt het ene bericht het andere en komt een `PATCH` of `DELETE` bij het
verkeerde uit. Dat is een gebrek in de cache, geen eis aan de simulator, en het staat als
MinBZK/MijnOverheidZakelijk#1004 op de backlog. Een globale `UNIQUE` in de simulator zou het gebrek
alleen verstoppen.

Praktisch: de seed-generator deelt UUID's uit die over alle magazijnen verschillen, zodat een demo
er niet per ongeluk overheen valt. Zodra #1004 opgepakt wordt, is de botsing met een seed-optie
opzettelijk na te bootsen — dat is precies het soort scenario waarvoor de simulator er is.

**`bericht_status` is een aparte tabel, ook al is er hooguit één rij per bericht.** Een bericht
heeft precies één ontvanger, dus die 1:1 is de bedoelde vorm en de `UNIQUE` op de foreign key legt
hem vast; het echte magazijn doet het niet anders. De reden om er toch geen kolommen op `bericht`
van te maken zit in de spec: die laat het veld `status` wég zolang de ontvanger niets heeft gezet,
en pas als er iets gezet is staat er `gelezen` plus `gewijzigdOp` in. Een ontbrekende rij is dat
onderscheid, één op één. Met kolommen op `bericht` zou "nog niets gezet" en "op ongelezen gezet"
allebei uit nullbare velden moeten volgen, en dan is het aan de code om het verschil te onthouden.

Wat hiermee werkt en met WireMock niet:

- **Mappen** — `PATCH {gelezen, map}` schrijft naar `bericht_status`; de volgende `GET` geeft de map
  terug, op alle 98 magazijnen, met de spec-semantiek (ontbrekend óf `null` veld = niet wijzigen).
- **Leesstatus** — persistent, dus de Berichtenbox toont over de hele fan-out consistente state, ook
  na een nieuwe sessie.
- **Soft-delete**, **filtering op `X-Ontvanger`** (persona's lekken niet in elkaar), **paginering**
  op `GET /berichten` — die laatste wordt zo voor het eerst over veel magazijnen echt uitgeoefend.
- **Bijlagen** met echte bytes en MIME-type, zodat het `BijlageContentTypeFilter`-pad in de uitvraag
  ook bij de gesimuleerde magazijnen iets doet.

### Niet in de eerste versie

Het onderstaande laten we liggen om de simulator klein te houden — niet omdat het er nooit in hoort.
Per stuk wat het later zou kosten, in de volgorde waarin het het makkelijkst alsnog landt:

| Onderdeel | Later toevoegen |
|---|---|
| Bijlage-groottelimieten | Goedkoop: een validatie op de aanlever-endpoint. |
| Retentie | Goedkoop: één periodieke query op `publicatietijdstip`. |
| Notificatie-outbox | Middel: een tabel plus poller. Interessant zodra we push-gedrag van veel magazijnen tegelijk willen tonen. |
| Autorisatiediepte | Middel, en pas zinvol zodra de AuthZEN-PEP (#10) er staat. |
| LDV | Duur, en inhoudelijk twijfelachtig: het logboek is per organisatie, dus honderd gesimuleerde magazijnen zouden honderd logboeken suggereren die er niet zijn. |
| FSC-inway | Duur; zie de ZAD-sectie. |

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
| `WEIGERT` | 4xx met een `problem+json`-body | `FOUT`, maar **géén** circuit-tik |
| `MALFORMED` | 200 met een body die het schema schendt | `FOUT`, maar **géén** circuit-tik |

De laatste twee zijn er niet voor de sier. De uitvraag onderscheidt bewust
*beschikbaarheids*-storingen (timeout, 5xx, netwerk — die tellen mee voor de circuit breaker) van
een magazijn dat wél antwoordde maar iets onbruikbaars zei (4xx of een body die niet deserialiseert
— die tellen níét mee). Met alleen de eerste vier modi wordt die tweede tak in de demo nooit geraakt,
terwijl juist die in fase 6 een echte fout opleverde: stub-responses zonder `bijlagen`-veld die als
`MALFORMED` binnenkwamen. Twee extra rijen in een enum dekken een pad dat anders onzichtbaar blijft.

**Gevolg voor de rest van de keten.** Omdat het gedrag op elke endpoint geldt, kan ook het markeren
als gelezen, het verplaatsen naar een map of het *aanleveren* van een nieuw bericht traag zijn of
falen. Dat laatste raakt de demo-bediening: een magazijn dat op `STUK` staat weigert ook nieuwe
berichten, dus wie eerst een storing aanzet en daarna wil vullen, krijgt een fout. Bedoeld gedrag,
maar het moet niet verrassen — vullen doe je vóór de storing, of via `POST /beheer/seed`, dat buiten
de simulatie valt. Dat is realistisch — in het echte
stelsel is een schrijfactie net zo goed een aanroep naar een andere organisatie — maar het vergroot
het testoppervlak, en het stelt eisen aan de foutafhandeling in de Berichtenbox die er nu misschien
niet zijn. Dat is geen bezwaar tegen dit ontwerp: het maakt werk zichtbaar dat er anders ook was.
Wie het in eerste instantie wil beperken, zet een vlag op de `magazijn`-rij die het gedrag tot
leesacties beperkt; dat is geen ander ontwerp.

**Threads in de simulator.** Een vertraagd antwoord houdt een worker-thread van de *simulator* vast
(Panache is blocking). Dit gaat over het magazijn-eind, niet over de uitvraag. Bij een fan-out van
100 met seconden vertraging komt de Quarkus-default van ~200 threads in zicht, zeker bij meerdere
gelijktijdige sessies. Drie uitwegen, in volgorde van voorkeur: `@RunOnVirtualThread` op de endpoints
(Java 21; een wachtende virtual thread kost geen platform-thread, en wachten is hier precies wat we
doen), de vertraging reactief vóór de blocking DB-call leggen, of `quarkus.thread-pool.max-threads`
meeschalen. Meetpunt in stap 6.

## Beheer-API

Buiten de gedeelde spec, op een eigen `/beheer`-pad zodat de gegenereerde interfaces schoon blijven.

**De magazijnen komen niet uit dit pad.** Het generatiescript levert één artefact met de hele set —
OIN, naam en gedrag per index — en de simulator leest dat bij het starten in en brengt zijn
`magazijn`-tabel ermee in overeenstemming. Datzelfde script schrijft de register-regels voor de
uitvraag, dus beide kanten komen uit één bron en kunnen niet uit elkaar lopen. Zou het inrichten via
een beheer-aanroep gaan, dan is er een opstartvolgorde (tot die aanroep geeft elk pad 404 terwijl het
register van de uitvraag geldig oogt) én een tweede waarheid die kan driften. `/beheer` is er
uitsluitend voor wijzigingen *tijdens* een demo.

| Endpoint | Waarvoor |
|---|---|
| `PUT /beheer/magazijnen/{oin}/gedrag` | gedrag live bijstellen |
| `POST /beheer/seed` | bulk n × m berichten in één transactie |
| `POST /beheer/legen` | terug naar de begintoestand |

`seed` is geen luxe: 45 magazijnen × 20 berichten via losse HTTP-aanleveringen duurt minuten, een
bulk-insert seconden. Hij levert ook bijlagen mee — een deel van de berichten krijgt er één, met een
echt MIME-type en een paar kilobyte inhoud. Zonder dat blijft het bijlage-pad in de uitvraag
alsnog ongebruikt, terwijl dat juist een van de redenen was om geen antwoordmachine meer te
gebruiken.

**Twintig berichten per magazijn is niet toevallig.** De uitvraag haalt per magazijn één pagina op en
het magazijn levert er standaard twintig; daarboven ziet de ondernemer niets. Dat is een echt gat en
staat als MinBZK/MijnOverheidZakelijk#996 op de backlog. Zolang dat er is, houdt de seed zich aan
twintig per magazijn — anders demonstreren we onbedoeld het gat in plaats van het gedrag dat we
willen tonen. Wie het gat juist wél wil laten zien, zet er bewust meer in.

`legen` zet ook het **gedrag** terug naar de deterministische verdeling, niet alleen de berichten.
Anders staat een magazijn dat tijdens de vorige demo op `STUK` is gezet er de volgende keer nog zo
bij, en is "terug naar de begintoestand" uit #936 een halve waarheid.

Vullen kán daarnaast gewoon via `POST /magazijn/<OIN>/api/v1/aanleveringen` — dezelfde spec, dus de
bestaande aanlever- en generatorcode van de demo-console werkt met alleen een andere base-URL. Samen
dekken
`seed` en `legen` de acceptatiecriteria van #936 over herhaalbaar vullen en leegmaken.

**`/beheer` hoort niet open te staan.** De WireMock-admin-API van de huidige ZAD-stubs is publiek en
zonder authenticatie bereikbaar (`GET https://profiel-test-mpfpsm-lcl.rig…/__admin/mappings`
antwoordt met 200; geverifieerd 2026-08-21). Die fout niet herhalen.

De schoonste vorm is het beheerpad helemaal niet publiceren. De enige beoogde aanroeper is de
demo-console, en binnen één ZAD-project bereiken componenten elkaar intern. Dat werkt alleen als de
demo-console en de simulator in hetzelfde project landen: cross-project verkeer loopt op ZAD over de
publieke ingress-URL's. Waar de demo-console terechtkomt is een open punt in #936; dit ontwerp vraagt
alleen dat die keuze bewust valt in plaats van per ongeluk.

Komt `/beheer` toch publiek te staan, dan hoort er authenticatie voor. Een gedeeld token in een
header — verplicht buiten `%dev`/`%test` en afgedwongen bij het starten, zoals
`RedisVerbindingValidator` dat doet — is de vorm die we zelf in de hand hebben. Of ZAD een
SSO-voorziening biedt die we ervoor kunnen gebruiken heb ik niet kunnen vaststellen: de drie
projectspecs gebruiken alleen `publish-on-web`, `persistent-storage`, `postgresql-database`,
`attachments`, `temp-storage` en `cross-domain-access`. Navragen bij het ZAD-team; is er SSO, dan
verdient die de voorkeur boven een gedeeld token.

## Persona's en fan-out

De fan-out is (opted-in afzender-OIN's uit het profiel) ∩ register. Vier persona's, waarvan de
verzamelingen **genest** zijn: elke grotere persona bevat de set van de kleinere. Daardoor is het
verschil in wachttijd puur het gevolg van de extra magazijnen en niet van een andere mengeling.

| Persona | Identificatie | Fan-out | Samenstelling |
|---|---|---|---|
| Bakkerij Pietersen | bestaande BSN-persona | **3** | A + B + index 1 |
| Installatiebedrijf De Vries | KVK 90000002 | **15** | A + B + index 1–13 |
| Grootbedrijf B.V. | KVK 90000001 (bestaat al) | **45** | A + B + index 1–43 |
| Landelijk Concern N.V. | KVK 90000003 | **100** | A + B + index 1–98 |

De vierde persona bevraagt het volledige register en is bewust extreem: geen enkele echte ondernemer
raakt 100 magazijnen. Hij bestaat om het gedrag van de keten in de breedte zichtbaar te maken, niet
om realisme te tonen.

**Het generatiescript bewaakt de ondergrens.** De persona's zijn vaste indexbereiken, dus met een
kleinere n bestaan ze niet meer: bij `DEMO_MAGAZIJN_STUBS=10` valt er van de 15-, 45- en
100-persona niets te bouwen. Het script hoort n daarom te toetsen aan de grootste persona en te
weigeren met een leesbare melding, in plaats van stilletjes een profiel met dertien scopes te
schrijven waarvan er tien bestaan. Wie bewust klein wil draaien, verkleint de persona-set mee.

**De namen liggen nog niet vast.** Er lopen elders standaard-persona's — in de proeftuin, en het werk
dat Swie eraan doet. Die zijn leidend zodra ze er zijn; nieuwe verzinnen zou de derde set opleveren.
Het ontwerp hangt alleen aan de *omvang* van de fan-out, niet aan namen of identificatienummers, dus
overnemen kost niets zolang het vóór stap 5 gebeurt. Actie: de vier groottes koppelen aan bestaande
persona's in plaats van eigen namen te bedenken.

### Gedragsverdeling over de 98

Deterministisch uit de index, in deze precedentievolgorde — geen seed nodig, elke omgeving krijgt
dezelfde verdeling:

1. `i ∈ {28, 97}` → **UIT** (2)
2. `i ∈ {33, 66, 98}` → **STUK** (3)
3. `i ∈ {22, 71}` → **WEIGERT** respectievelijk **MALFORMED** (1 + 1)
4. `i mod 20 == 0` → **HAPERT** (index 20, 40, 60, 80 — 4)
5. `i mod 5 == 0`, voor zover niet hierboven → **TRAAG** (15)
6. rest → **NORMAAL** (72)

Aandelen: 73 % normaal, 15 % traag, 4 % hapert, 3 % stuk, 2 % uit, 1 % weigert, 1 % malformed.
Index 22 valt binnen de 45-persona en index 71 alleen binnen de grootste, zodat de kleinste twee
persona's hun schone contrast houden en de niet-storing-tak vanaf het grote scenario zichtbaar wordt.

De twee echte magazijnen staan op normaal en blijven dat: met 98 gesimuleerde magazijnen die elk
gedrag kunnen vertonen, hoeft er op de echte geen storing meer nagebootst te worden.

De verdeling is zo gelegd dat elke persona een zinvol beeld geeft:

| Persona | traag | hapert | stuk | uit | weigert / malformed |
|---|---|---|---|---|---|
| 3 | – | – | – | – | – |
| 15 | 5, 10 | – | – | – | – |
| 45 | 5, 10, 15, 25, 30, 35 | 20, 40 | 33 | 28 | 22 (weigert) |
| 100 | 15 | 4 | 3 | 2 | 22, 71 |

Verwacht beeld per persona — te meten, niet aangenomen:

- **3** — alles binnen, vrijwel direct. De referentie.
- **15** — compleet, maar de lijst is pas klaar als de twee trage magazijnen geantwoord hebben. Toont
  dat de gebruiker op zijn traagste leverancier wacht.
- **45** — eerste berichten direct, daarna druppelsgewijs; één magazijn in timeout, één stuk (na drie
  rondes overgeslagen door de circuit breaker), twee die wisselend falen en één dat netjes antwoordt
  met een weigering. De lijst is dus zowel traag als onvolledig, en per magazijn is zichtbaar waaróm
  — inclusief het verschil tussen "onbereikbaar" en "wel bereikbaar, maar geen antwoord dat we
  kunnen gebruiken".
- **100** — hetzelfde beeld met een langere staart: vijftien trage magazijnen bepalen wanneer de
  lijst compleet heet, vijf leveren niets (drie stuk, twee die niet reageren), vier falen wisselend
  en twee antwoorden onbruikbaar. Toont dat de wachttijd van de ondernemer die van zijn traagste
  leverancier is.

## Grenzen en meetpunten

Dit ontwerp gaat ervan uit dat **alle** magazijnen in de fan-out ook daadwerkelijk bevraagd worden.
De begrenzing die de uitvraag op het aantal gelijktijdige magazijn-aanroepen legt, staat als aparte
backlog-issue en speelt in dit document geen rol.

| Grens | Waarde | Hoe we hem meten |
|---|---|---|
| Verbindingen vanuit de uitvraag | één client per magazijn, opgebouwd bij het starten | opstarttijd en geheugengebruik van de uitvraag bij n = 50 / 100 / 250 |
| Threads in de simulator | ~200 (Quarkus-default) | fan-out 100 met vertraagde magazijnen, meerdere sessies tegelijk |
| Register-configuratie | 2 regels per magazijn | 200 regels bij n = 100; triviaal |
| Doorlooptijd van een ophaalronde | het traagste magazijn bepaalt wanneer de lijst compleet is | tijd tot het eerste bericht en tijd tot compleet, per persona |
| Berichten per magazijn | 20 — de uitvraag haalt één pagina op | staat als MinBZK/MijnOverheidZakelijk#996 op de backlog; de seed blijft er tot die tijd onder |

**Hoe we meten.** De uitvraag zendt per magazijn een `MAGAZIJN_BEVRAGING_GESTART`- en een
`..._VOLTOOID`-event over de SSE-stream. Een klein script dat die stream meeleest en per event een
tijdstempel wegschrijft, levert precies de twee getallen die we willen: het eerste `VOLTOOID` met
uitkomst `OK` is "tijd tot het eerste bericht", het laatste event van de ronde is "tijd tot
compleet". Dat script hoort bij stap 6 en komt naast `demo/smoke.sh` te staan, zodat de meting
herhaalbaar is en niet met een stopwatch gebeurt.

Verwacht dat de demo hier optimalisatiepunten oplevert; dat is een doel en geen bijwerking. Wat stap
6 vindt hoort als issue op de backlog, niet stilzwijgend in dit document. Twee daarvan staan er al —
#996 hierboven en MinBZK/MijnOverheidZakelijk#997 over de onvolledige lijst bij veel aangesloten
organisaties.

## Wat vervalt

| Weg | Erbij |
|---|---|
| `magazijn-stubs`-service in compose (WireMock, poort 8092) | `magazijn-simulator` op dezelfde poort 8092 + `postgres-simulator` op 5435 |
| `demo/generated/magazijn-stubs-mappings/` | `POST /beheer/magazijnen`-payload uit de generator |
| `VeelMagazijnenService` + `WireMockAdminClient` in demo-console | demo-console roept `/beheer/…` aan |
| 503-overlay als enige gedragsknop | gedragsmodus per magazijn |
| Toxiproxy-proxies `magazijn-a` en `magazijn-b` | vervallen — storingsgedrag komt uit de simulator |

Toxiproxy zelf blijft wél staan. De vier andere proxies (`redis`, `profiel`, `notificatie`,
`aanmeld`) dragen de storingsscenario's uit fase 3 en 4 — cache weg, profielservice uit, downstream
onbereikbaar — en die komen niet uit de simulator. Alleen de twee magazijn-proxies worden overbodig;
de uitvraag adresseert de echte magazijnen daarna rechtstreeks.

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
| `/beheer` | Niet publiceren als de demo-console in hetzelfde project draait; anders publieke ingress mét token. Zie de Beheer-API-sectie. |

De simulator hoeft **niet** door FSC. Dat is een aparte afweging die in dit ontwerp niet meespeelt:
FSC blijft op de twee echte magazijnen. Eén gesimuleerd magazijn als extra FSC-dienst op een
bestaande inway is later een goedkope toevoeging (één dienst, één contract, één grant-hash), maar
n grant-hashes en n handmatige env-vars in Operations Manager schalen niet.

## Foutafhandeling

- Onbekende of ontbrekende OIN in het pad → 404 problem+json, met de OIN in `detail`; een pad
  zonder `/magazijn/`-wortel eveneens 404.
- Aanleveren bij een magazijn dat op `STUK` of `UIT` staat faalt net als elke andere aanroep; het
  gedrag geldt op de hele API. Alleen `/beheer` valt erbuiten.
- Ontbrekende of ongeldige `X-Ontvanger` → 400, zoals de spec voorschrijft.
- `POST /beheer/*` zonder geldig token buiten dev/test → 401, en bij boot een fail-fast als het
  token niet gezet is.
- `seed` met een onbekend magazijn-prefix → 400 met de lijst van bekende prefixes.
- Gedragsmodus `STUK`/`UIT` geldt ook voor `/beheer`? Nee — het beheerpad staat buiten de simulatie,
  anders is een kapot gezet magazijn niet meer te repareren.

## Testen

De categorieën hieronder zijn een startpunt, geen afvinklijst: bij de implementatie komen er zaken
bij die net zo goed getest horen te worden.

- **Unit** — pad-prefix-filter (geldige OIN, onbekende OIN, ontbrekende `/magazijn/`-wortel, iets
  dat geen 20 cijfers is, en de OIN die in de `baseUri` blijft staan), gedragskiezer
  (deterministische verdeling voor i = 1…98: precies 2 uit, 3 stuk, 1 weigert, 1 malformed, 4 hapert, 15 traag,
  72 normaal), status-patch-semantiek (ontbrekend veld, expliciet `null`, overschrijven van een map),
  en de n-validatie van het generatiescript tegen de grootste persona.
- **Integratie (`@QuarkusTest`, Dev Services)** — twee magazijnen naast elkaar: bericht aanleveren in
  het eerste magazijn, `PATCH gelezen+map`, `GET` daar toont de wijziging en `GET` bij het tweede
  magazijn níét; paginering over
  meerdere pagina's; soft-delete; bijlage-bytes met MIME-type. Cardinaliteiten leeg / één / meerdere
  via `@ParameterizedTest`, conform de teststrategie.
- **Contract** — `swagger-request-validator` tegen `berichtenmagazijn-api.yaml`, zodat de simulator
  aantoonbaar dezelfde spec dient als het echte magazijn. Dit is de kern van de belofte en verdient
  een expliciete test in plaats van vertrouwen op de generator.
- **Keten** — de bestaande `demo/smoke.sh` uitbreiden met een gesimuleerd magazijn: aanleveren →
  ophalen via de uitvraag → markeren als gelezen → opnieuw ophalen toont `gelezen: true`.
- **Invarianten die stil kunnen breken** — `seed` levert `berichtId`'s die ook over magazijnen heen
  verschillen, zolang MinBZK/MijnOverheidZakelijk#1004 openstaat; `legen` zet het gedrag terug naar
  de deterministische verdeling en niet alleen de berichten; een magazijn op `WEIGERT` of
  `MALFORMED` laat de circuit breaker van de uitvraag ongemoeid terwijl `STUK` hem wél opent. Alle
  drie zijn ze onzichtbaar tot ze misgaan, vandaar expliciet.
- **Coverage** — dezelfde 90 %-JaCoCo-gate als de andere modules. Dat de simulator demo-gereedschap
  is, is geen reden om er minder van te eisen: hij draagt straks het gedrag van honderd magazijnen,
  en een fout erin lijkt op een fout in de keten. Let op de bekende valkuil: de
  `quarkus-jacoco`-extensie telt alleen `@QuarkusTest`-dekking, dus pure unit-tests dragen niet bij
  aan de drempel — integratietests zijn dus nodig, niet optioneel. Blijkt 90 % onhaalbaar, dan is dat
  een eigen afweging en zetten we hem bewust lager in plaats van hem stilzwijgend weg te laten.
  Dat `demo/demo-console` vandaag helemaal geen gate heeft, is een gat op zich; dat staat als
  MinBZK/MijnOverheidZakelijk#1006 op de backlog en dekt beide modules. Detekt geldt onverkort.

## Stappen

1. **Module + spec + pad-prefix.** Lege module, generatie uit optie A, `@PreMatching`-filter,
   `MagazijnContext`. Verificatie: `GET /magazijn/<OIN>/api/v1/berichten` levert een lege,
   spec-valide `BerichtenLijst` en de `_links` dragen diezelfde OIN.
2. **Persistentie.** Flyway-migratie mét rollback-script, entities, repositories met discriminator,
   alle zes de operaties uit de spec. Verificatie: de integratietests hierboven.
3. **Gedrag per magazijn.** Modi, vertraging, foutkans. Verificatie: unit-test op de verdeling plus
   een `@QuarkusTest` die een `STUK`-magazijn een 503 ziet geven.
4. **Beheer-API + token.** Inrichten, seed, legen, gedrag. Verificatie: 100 magazijnen × 20 berichten
   geseed in < 10 s; 401 zonder token onder `%prod`.
5. **Generator en compose omzetten.** WireMock-stub-service en `VeelMagazijnenService` eruit,
   simulator erin, vier persona's in de profiel-stub, de twee magazijn-proxies uit Toxiproxy.
   Verificatie: `demo/smoke.sh` groen, de vier persona's leveren fan-out 3 / 15 / 45 / 100.
6. **Meten en vastleggen.** Meetscript op de SSE-stream; tijd tot het eerste bericht en tijd tot
   compleet, per persona; opstarttijd en geheugengebruik van de uitvraag bij n = 50 / 100 / 250.
   Uitkomsten terug in dit document en in #938.
7. **ZAD.** Component, database, register-attachment, persona's in het stubs-image.

Stap 1 t/m 5 leveren de lokale demo; stap 6 levert de onderbouwing die #938 vraagt. Stap 7 is
**geblokkeerd door #936**, en niet slechts ervan afhankelijk: zonder bediening en zonder
Berichtenbox op ZAD kan die stap technisch slagen — de simulator draait, de fan-out klopt — terwijl
er voor een stakeholder niets te zien is. Plan hem dus ná #936, of accepteer expliciet dat stap 7
alleen de keten oplevert en niet de demo.

Elke stap wordt een sub-issue onder MinBZK/MijnOverheidZakelijk#938, zodat het werk op het bord staat
en niet alleen in dit document.

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
2. Waar landt de demo-console op ZAD? Hetzelfde project als de simulator → `/beheer` blijft intern;
   een ander project → publieke ingress met token, of SSO als ZAD dat biedt. Hangt aan #936.
3. Welke persona's nemen we over uit de proeftuin en de standaard-persona's? Vóór stap 5.
4. Is n = 100 haalbaar binnen de opstarttijd en het geheugengebruik van de uitvraag, of ligt het
   plafond lager? Stap 6 beslist; het getal in dit document is een voorstel, geen meting.
5. Gaat het gedrag ook op schrijfacties gelden, of eerst alleen op leesacties? Zie "Gedrag per
   magazijn"; het verschil zit in het testoppervlak en in wat de Berichtenbox moet opvangen.
6. Blijft de module `magazijn-simulator` heten, of wil het team de term "simulatie-engine" uit #787
   in de modulenaam terugzien? Nu beslissen is goedkoop; na de eerste code kost het een
   package-rename.
7. Waar landt demo-code in de repository? Spike #1005 heeft `demo/` als module-wortel ingericht en
   de simulator begint daar; het teambesluit bij die spike moet dat nog bekrachtigen.
