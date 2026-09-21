# Nieuwe berichten live naar de open berichtenbox

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#939 — opvolger van #1025 (periodiek navragen vanuit de
proeftuin, MinBZK/moza-poc#151).

## Context

Een bericht dat tijdens een open sessie wordt aangemeld, staat binnen een seconde in de
sessiecache: het magazijn publiceert een CloudEvent op `POST /api/v1/aanmeldingen` en de uitvraag
schrijft het bij. Wat ontbrak, was de laatste stap naar de browser. #1025 dichtte dat met
periodiek `GET /berichten` vanuit de proeftuin; dat kost verkeer zolang er niets gebeurt, en een
nieuw bericht verschijnt pas bij de volgende ronde.

## Besluit: SSE, geen websocket

`GET /api/v1/berichten/_volgen` houdt een Server-Sent-Events-verbinding open.

- Het verkeer is eenrichting (keten → browser). Wat de browser terugdoet, gaat al over de gewone
  endpoints.
- Er is een precedent: `_ophalen` is SSE, de proeftuin leest het met `fetch` en een reader
  (`EventSource` kan `X-Ontvanger` niet meesturen), en zijn proxy's staan al ongebufferd.
- Een websocket vraagt een eigen upgrade-pad door de proeftuin-proxy, de ZAD-route en een
  eventuele FSC-inway, en een eigen authenticatieverhaal voor het handshake-verzoek. Dat levert
  hier niets op wat SSE niet al levert.

## Ontwerp

### Stroom

```
volgen-gestart → (bericht-bijgekomen | hartslag)* → sessie-verlopen
```

- **`volgen-gestart`** komt pas als de luisteraar geregistreerd is én deze pod aanmeldingen
  ontvangt. De afnemer leest dan de lijst; alles daarna krijgt hij als `bericht-bijgekomen`. Een
  bericht op het grensvlak kan in beide zitten, daarom ontdubbelt de afnemer op `berichtId`.
  Opnieuw verbinden na een haperende verbinding is daardoor dezelfde handeling als de eerste keer:
  de lijst is daarna compleet, zonder dubbelen.
- **`bericht-bijgekomen`** draagt een `BerichtSamenvatting` in dezelfde vorm als een element van
  `GET /berichten`, met de afzendernaam uit het register. De afnemer hoeft niets na te vragen.
- **`hartslag`** (default elke 20 s) verlengt de sessie en houdt proxies wakker die een stille
  verbinding afbreken (OpenShift-route 30 s). Blijft hij twee keer uit, dan verbindt de afnemer
  opnieuw.
- **`sessie-verlopen`** sluit de stroom af; de afnemer start een nieuwe ophaalronde.

Foutsemantiek vóór de stream is die van `GET /berichten` (409 / 503). Een storing daarna breekt
de stroom af; de afnemer verbindt opnieuw.

### Tussen pods: Redis pub/sub, één abonnement per pod

Een aanmelding en de open berichtenbox van dezelfde ontvanger landen niet op dezelfde pod. De pod
die de aanmelding verwerkt, publiceert `<cacheKey> <berichtId>` op
`berichtensessiecache:v3:aanmeldingen`; elke pod heeft daar één abonnement op en verdeelt in het
geheugen naar zijn luisteraars. De Quarkus-client opent per `subscribe` een eigen connection, dus
een abonnement per bezoeker zou één Redis-connection per open berichtenbox kosten.

Over het kanaal gaat geen berichtgegeven: de ontvangende pod leest het bericht zelf uit de cache
(via `getById`, dat meteen de eigenaar controleert). De `cacheKey` zelf is wel een pseudoniem van
de ontvanger — een SHA-256 zonder geheim, dus een BSN is eruit terug te rekenen — en het kanaal
hoort daarom dezelfde bescherming als de sessie-keys (TLS en authenticatie op Redis).

**Actief bewijzen met een probe.** De Vert.x-client levert de abonnementsbevestiging soms niet af
bij de wachtende aanroep (`No handler waiting for message: [subscribe, …]`); de subscribe-`Uni`
van Quarkus blijft dan hangen terwijl de berichten wél binnenkomen. Gemeten in de keten-E2E:
wisselend per run. De pod publiceert daarom een eigen probe op het kanaal en noemt het abonnement
pas actief als die terugkomt — precies de garantie waarop `volgen-gestart` leunt.

### Sessie in leven houden

Het aanmeld-pad schrijft alleen in een actieve sessie, en de lijst verlengt de sessie alleen bij
lezen. Een bezoeker die kijkt maar niets aanklikt, verloor zijn sessie dus. `verlengSessie`
verlengt op elke hartslag, maar pas wanneer de helft van de bewaartermijn verstreken is; dan gaan
lijst, status én alle berichthashes mee. Een lopende ophaling houdt haar korte vangnet-TTL. Om die
reden moet de hartslag onder de helft van `berichtensessiecache.ttl` liggen; de pod start anders
niet.

### Plafonds en maximale duur

- `berichtenuitvraag.volgen.max-connections` (default 2000) per pod, en
  `berichtenuitvraag.volgen.max-connections-per-ontvanger` (default 5), zodat één aanroeper het
  pod-plafond niet voor iedereen opmaakt. Daarboven een 503 met `Retry-After`: de lijst blijft
  bruikbaar, alleen het vanzelf binnenkomen valt weg. De plek wordt vóór de cache-lookup
  gereserveerd en teruggegeven als de stream niet tot stand komt.
- `berichtensessiecache.volg-max-duur` (default 1 uur). Daarna eindigt de stream zonder
  `sessie-verlopen` en verbindt de afnemer opnieuw. Zonder die grens houdt een vergeten tabblad
  de sessie, en daarmee de berichten in Redis, onbeperkt vast; en zodra er authenticatie komt,
  wordt de toegang zo bij elke nieuwe connection opnieuw gecontroleerd.

### `API-Version` op de stream

De `ApiVersionFilter` is een `ContainerResponseFilter`, en die komt bij een streaming `Multi` niet
aan de beurt: de headers zijn dan al verstuurd. Een test op `_volgen` liet dat zien, en `_ophalen`
bleek hetzelfde gat te hebben. Beide SSE-resources zetten de header nu zelf met `@ResponseHeader`;
de foutantwoorden vóór de stream houden hem via het filter.

## Open punten

- **Absolute maximale sessie-leeftijd.** De maximale duur begrenst één stream, niet de sessie:
  een berichtenbox die telkens opnieuw verbindt, houdt de sessie in leven. Dat gold al voor het
  periodiek navragen uit #1025. Een grens vanaf de ophaalronde, los van de verlenging, is een
  eigen afweging voor de sessiecache als geheel.
- **LDV bij lange streams.** `@Logboek` legt het openen van de stream vast; de interceptor sluit de
  registratie af voordat de stream loopt. Berichten die uren later over dezelfde stream gaan, staan
  daardoor niet apart in het logboek. Bij `_ophalen` speelt hetzelfde, maar die duurt seconden. Hoe
  dit te loggen is (per doorgegeven bericht, of één registratie bij het einde) leggen we voor aan
  het LDV-team, net als moza-logboekdataverwerking#71.

## Wat buiten dit werk valt

- **De proeftuin.** Het verbinden, ontdubbelen, opnieuw verbinden en terugvallen op het periodiek
  navragen gebeurt in `MinBZK/moza-poc` en krijgt daar een eigen PR.
- **Status- of mapwijzigingen uit een ander tabblad.** Die gaan niet over deze stroom; de lijst
  toont ze bij de volgende lees.
- **Opschalen.** Het plafond begrenst per pod; hoeveel verbindingen een pod werkelijk draagt, hoort
  bij het ticket over het aantal magazijnen en de load-test die daar nog ontbreekt.

## Verificatie

- Library: `SessieVolgerTest` (volgorde, filteren per ontvanger, meerdere luisteraars, hartslag,
  verlopen sessie, wegvallend abonnement, leesfout, afmelden, hartslag-validatie),
  `RedisAanmeldingenIntegrationTest` (tussen twee "pods" tegen echte Redis, onleesbare
  kanaalberichten, afmelden, `verlengSessie` voor en na de helft van de TTL),
  `BlockingSessiecacheTest` (gating vóór de stream, aanmelden best-effort).
- Uitvraag: `VolgenSseTest` (wire-vorm per gebeurtenis, foutstatussen vóór de stream, ongeldige
  `X-Ontvanger`, afgebroken stroom), `VolgSseContractTest` (spec ↔ code, beide richtingen),
  `VolgenPlafondTest` (per pod, per ontvanger, plek teruggeven), `OpenApiContractTest` (409 en
  503 vóór de stream tegen het Problem-schema), `RouteDekkingTest`.
- Keten: `UitvraagKetenE2eTest` — ophalen, `_volgen` openen, een CloudEvent op de webhook, en het
  bericht verschijnt op de stroom zonder dat het magazijn opnieuw bevraagd wordt.
