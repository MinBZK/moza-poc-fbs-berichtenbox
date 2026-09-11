# Security-headers: één bron van waarheid, en de baseline compleet

**Status:** Uitgevoerd

## Context

Bij het voorbereiden van een padspecifieke frame-header voor het bijlage-endpoint bleek dat
elke JAX-RS-response van beide diensten de security-headers **twee keer** droeg. Gemeten op de
draad, tegen een draaiende `berichtenuitvraag`:

```
X-Frame-Options        -> [DENY, DENY]
Content-Security-Policy -> [frame-ancestors 'none', frame-ancestors 'none']
Cache-Control          -> [no-store, no-store]
API-Version            -> [v1]
```

Twee lagen plaatsten dezelfde headers: de declaratieve `quarkus.http.header.*`-config in
`application.properties` (bedoeld om óók `/openapi.json` en `/q/*` te dekken) en het JAX-RS
`SecurityHeadersFilter` in `fbs-common` (bedoeld als defense-in-depth). RESTEasy voegt zijn
headers toe aan wat de HTTP-laag al plaatste in plaats van ze te vervangen.

Dat is niet alleen onnet. Een browser doorsnijdt twee `Content-Security-Policy`-headers en
houdt de strengste over, en bij tegenstrijdige `X-Frame-Options`-waarden verschilt het gedrag
per browser. Zolang deze duplicatie bestaat, is een bewust versoepelde policy op één pad
onmogelijk: hij zou stil geen effect hebben.

Daarnaast bleek de CSP incompleet ten opzichte van de baseline die de NCSC
ICT-beveiligingsrichtlijnen voor webapplicaties (juli 2024) in **U/PW.03 maatregel 04**
voorschrijven. Die noemt `default-src`, `base-uri`, `form-action` én `frame-ancestors`
(elk `none of self`); wij zetten alleen `frame-ancestors`.

## Wat is gemeten

Drie mechanismen getoetst met een `@QuarkusTest` die de headers van de draad plukt:

| Mechanisme | Uitkomst |
|---|---|
| `quarkus.http.header."X".value` | Werkt, maar kent **één pad per headernaam** (`.path`), dus een tweede waarde voor een ander pad is niet uit te drukken. |
| `quarkus.http.filter.<n>.matches` + `.header."X"` | Matcht wél op regex — een eigen `X-Probe-Marker` kwam netjes door — maar **voegt toe**: met een algemene en een specifieke filter leverde één pad `[DENY, SAMEORIGIN, DENY]`. |
| Vert.x `headersEndHandler` + `headers().set(...)` | Vervangt, draait als laatste, dekt élk pad. |

Alleen de derde kan wat nodig is. Dat is geen voorkeur maar een meting.

## Wat er is gebouwd

- **`fbs-common/SecurityHeaders`** — framework-vrij object: welke headers, welke waarde, per
  pad. Puur, dus volledig unit- en mutation-testbaar.
- **`SecurityHeadersRegistratie` in beide diensten** — registreert via `@Observes Filters` een
  route-filter dat een `headersEndHandler` ophangt en de headers met `set` plaatst.
- **`quarkus.http.header.*` verwijderd** uit `berichtenuitvraag` en `berichtenmagazijn`.
- **`SecurityHeadersFilter` en `CacheControlFilter` verwijderd** uit `fbs-common`, inclusief
  hun tests. Hun taak is overgenomen; ze naast de nieuwe laag laten staan zou de duplicatie
  terugbrengen die dit plan juist opheft.

### CSP per pad

| Pad | `Content-Security-Policy` |
|---|---|
| API-paden en `/openapi.json` | `default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'` |
| Onder het non-application root-pad (`/q`) | `frame-ancestors 'none'` |

`default-src 'none'` kan op de API omdat een API-response geen subresources laadt. Achter `/q`
staan Swagger UI en de dev-UI: dat zijn HTML-pagina's die hun eigen script en stylesheet laden,
dus daar zou `default-src 'none'` een lege pagina opleveren zónder foutmelding. Het magazijn
heeft `quarkus.swagger-ui.always-include=true`, dus dat geldt daar ook in productie. Die paden
dragen geen berichten of identificatienummers, dus de winst van de strengere policy is er klein
en het risico groot. De clickjacking-bescherming blijft er wel op staan.

### Cache-Control

Blijft `no-store`, maar alleen als de response er zelf geen heeft — zo houdt een endpoint dat
bewust cacheable is zijn eigen waarde. Dat was de reden dat `CacheControlFilter` ooit apart
stond van `SecurityHeadersFilter`; die eigenschap is meeverhuisd, niet verdwenen.

Wel een stille gedragsverandering: de oude `quarkus.http.header."Cache-Control".value` was
**onvoorwaardelijk**, dus een response met een eigen waarde droeg er twee. Nu wint de eigen
waarde. Geen enkel productie-endpoint zet er één, dus voor de API verandert er niets. Quarkus'
eigen static handlers voor de Swagger-UI-assets zetten wél een eigen `Cache-Control` en houden
die nu — onschadelijk voor paden zonder gegevens, maar het is een verschil en geen toeval.

### Waarom de bedrading per dienst staat

Eerste opzet had `SecurityHeadersRegistratie` óók in `fbs-common`. Dat brak
`fbs-berichtensessiecache`: die library erft `fbs-common` inclusief de jandex-index, en
start in zijn tests een Quarkus **zonder** HTTP-laag. ArC probeerde de observer te
registreren en viel om op `ClassNotFoundException: io.quarkus.vertx.http.runtime.filters.Filters`
— de Vert.x-runtime stond er als `provided` niet op.

Dat is dezelfde val die
[#1054](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1054) beschrijft voor de
LDV-wrapper: een `provided` dependency achter een geïndexeerde bean treft élke consumer, ook
de consumers die die laag niet hebben. De les is niet "zet de dependency dan op compile",
maar: een bibliotheek die door niet-HTTP-consumers wordt gebruikt hoort geen HTTP-bedrading
te bevatten. De waarden (`SecurityHeaders`) blijven daarom gedeeld; de bedrading staat per
dienst en is daar identiek.

## Valkuil die dit opleverde

`quarkus.http.non-application-root-path` komt terug zoals hij geconfigureerd is, en de
standaardwaarde is **relatief**: `q`, niet `/q`. Wie `/q/health` daarmee vergelijkt, vindt geen
match en geeft elk beheerpad de strenge policy — met een lege Swagger UI tot gevolg en geen
enkele foutmelding. `SecurityHeaders.beheerpadRoot` zet de waarde om naar een absoluut pad,
relatief aan `quarkus.http.root-path`.

De grens loopt bovendien op een segment en niet op een prefix: `/qux` begint met `/q` maar is
een ander pad, en zou anders stilzwijgend de losse policy krijgen.

## Verificatie

- `SecurityHeadersTest` in `fbs-common` (30 tests, puur) — waarden, de padgrens, de
  relatieve-root-omzetting.
- `SecurityHeadersRegistratieTest` in beide diensten (6 tests, MockK op
  `Filters`/`RoutingContext`) — vervangen in plaats van aanvullen, de
  Cache-Control-uitzondering, en dát het plaatsen uitgesteld gebeurt.
- `SecurityHeadersQuarkusTest` in beide diensten — op de draad: elke header precies één keer,
  de juiste CSP per pad, en via `EigenHeadersTestResource` een resource die zelf een
  afwijkende header zet zodat het vervángen end-to-end vastligt.
- `AanleverResourceIntegrationTest` pinde de oude CSP-waarde; die assertie is meeverhuisd
  naar de nieuwe.

Volledige suites: `fbs-common` groen, `berichtenuitvraag` 281 tests groen,
`berichtenmagazijn` 462 tests groen, detekt 0 bevindingen, JaCoCo-gates gehaald.

### Geen enkel pad verliest een header

Nagelopen in de Quarkus-sources in plaats van aangenomen. De oude config hing per header een
route op `httpRouteRouter` met `ROUTE_ORDER_HEADERS = Integer.MIN_VALUE`; de nieuwe observer
registreert op diezelfde router een pad-loze catch-all. De short-circuits die een response
vroeg afsluiten draaien allemaal later: 413 op `-2`, host-validatie op 400, CORS/auth op
300/200/100. Die foutresponses dragen de headers dus ook. Wat ónder de router afbreekt — een
TLS-handshake, een kapotte request-line — kreeg voordien net zo min iets.

`context.normalizedPath()` kan niet null zijn: Vert.x valt terug op `"/"`, en dat valt aan de
strenge kant. Elk pad behalve `/q/**` werd strénger; `/q/**` bleef gelijk.

Daarom staat `PRIORITEIT` hoog (10.000) en niet net-boven-nul: draait er ooit een filter met
een hogere prioriteit dat de response afsluit zonder door te geven, dan wordt de
`headersEndHandler` nooit opgehangen en gaat die response zonder headers de deur uit.

### Randgeval dat de review opleverde

`beheerpadRoot("/app", "/")` leverde `"/app/"` op, waarna élk pad daaronder als beheerpad gold
en de hele API de losse CSP kreeg — de omgekeerde fout van een gemiste Swagger UI, en de
gevaarlijke van de twee. De guard in `isBeheerpad` ving dat niet, want die ziet alleen de
sámengestelde root. Nu vroeg afgevangen in `beheerpadRoot`, met een test die zonder de fix
faalt (geverifieerd). Geen live impact: beide diensten draaien op `quarkus.http.root-path=/`.

**Mutation testing** (pitest 1.20.4, tijdelijk toegevoegd, niet gecommit): 18 mutanten, 16
gedood. De twee overlevers zijn door de Kotlin-compiler gegenereerde
`Intrinsics.checkNotNull`-aanroepen — geen gedrag, niet te doden.

Vier mutaties met de hand op de `@QuarkusTest`-laag, die pitest niet kan aansturen:

| Mutatie | Gedood door |
|---|---|
| `headers.set` → `headers.add` | draadtest (ná toevoeging van `EigenHeadersTestResource`; dáárvóór niet — zolang de registratie de enige schrijver is, zijn toevoegen en vervangen niet te onderscheiden) |
| relatieve-root-omzetting weggehaald | alle drie de testklassen |
| Cache-Control altijd overschrijven | registratie-test + draadtest |
| beheerpad krijgt ook de strenge CSP | alle drie de testklassen |

Die eerste regel is de opbrengst van het muteren: de draadtest zag er compleet uit en dekte het
verschil niet.

## Vervolg

Hierop volgt een tweede PR die de frame-headers op het bijlage-download-endpoint versmalt naar
`SAMEORIGIN` / `frame-ancestors 'self'`, zodat een berichtenbox die de keten server-side
aanroept een veilig te tonen bijlage in een ingesloten viewer kan laten zien. Die versmalling
is pas mogelijk nu er per pad één header met één waarde staat.
