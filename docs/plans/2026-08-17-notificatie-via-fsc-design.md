**Status:** Concept

# Notificatie-events via FSC — ontwerp

Issue: MinBZK/MijnOverheidZakelijk#784. Zusterissue #730 (profiel-bevraging via FSC) is
uitgevoerd als dienstpublicatie op de `logius`-peer.

## Context

Het berichtenmagazijn publiceert bericht-events als CloudEvents-webhook: `POST /events` met
`Content-Type: application/cloudevents+json`, antwoord `202`. Dat loopt nu rechtstreeks naar een
WireMock-stub (`compose.yaml`, host `:8084`), via `DownstreamClient` op een kale
`java.net.http.HttpClient`. Het doelmodel schrijft iets anders voor: `workspace.dsl:191` zegt
"CloudEvents webhook via FSC". Dit ontwerp haalt de implementatie naar dat model toe.

Waar #730 een **pull**-bevraging door de mesh bracht, bewijst dit een **push**: het magazijn is
hier de afnemer die uitgaand verkeer door zijn eigen outway stuurt.

## Rolverdeling

| Rol | Peer | Component |
|-----|------|-----------|
| Consumer (pusher) | `magazijn-a` (OIN `00000000000000100000`) | outway — **nieuw** |
| Provider (ontvanger) | `logius` (OIN `00000000000000001000`) | bestaande inway, tweede dienst |

De notificatiedienst komt op de bestaande `logius`-inway te staan, naast `profiel-service`. Dat is
een bewuste afwijking van het acceptatiecriterium "eigen OIN, cert uit test-CA": een derde
peer-stack kost een volledige compose-, PKI- en adresruimte-duplicatie, terwijl de eigenschap die
dit issue moet bewijzen — een magazijn dat uitgaand door FSC pusht — daar niet van afhangt. De
dienst is later naar een eigen peer te verhuizen; dat is dan een publicatie- en contractwijziging,
geen wijziging aan het magazijn.

Gevolg is dat het verkeer tussen deze twee peers voortaan **beide kanten op** loopt:

- `logius → magazijn-a` — `berichtenmagazijn` ophalen (bestond al, #782);
- `magazijn-a → logius` — `notificatieservice` pushen (nieuw).

Daarmee toont de harness meteen dat een peer niet in één rol vastzit: `magazijn-a` is provider én
consumer, `logius` andersom.

## Wat er niet klopte aan de issue-analyse

Het issue noemt de FBS-kant "config-only, #726-patroon". Dat gaat hier niet op.

De outway kiest de doel-inway op de header `Fsc-Grant-Hash`, en `fsc-outway serve` eist daarnaast
een `Fsc-Transaction-Id` in UUID-v7-vorm; zonder die headers antwoordt hij met "service not found"
respectievelijk "invalid uuid version, must be v7". In deze codebase worden die headers gezet door
`FscOutwayHeadersFilter` / `ProfielFscOutwayHeadersFilter`, en dat zijn **JAX-RS-clientfilters**.
`DownstreamClient` is bewust géén JAX-RS-client — aantal en URL's van downstreams komen uit config,
dus een `@RegisterRestClient` per stuk zou minder flexibel zijn. Alleen de URL naar de outway
ombuigen levert dus een `service not found`.

Er is daarom een codewijziging in `berichtenmagazijn` nodig. Klein, maar wel echt.

## Ontwerp — applicatiekant

### Grant-hash per downstream

`PublicatieConfig.Downstream` krijgt een optionele `grant-hash`
(`magazijn.publicatie.downstreams.<key>.grant-hash`). Leeg of afwezig = het huidige gedrag: geen
FSC-headers, rechtstreeks verkeer. Dat is niet alleen een migratiepad maar de blijvende situatie
voor downstreams die buiten de mesh liggen.

### Headercontract op één plek

`FscOutwayHeaders` levert het headerpaar voortaan als data (grant-hash + verse UUID-v7
transaction-id). De bestaande `zet(ClientRequestContext, ...)` gaat daarop leunen, zodat er één
bron van waarheid blijft en JAX-RS-callers ongemoeid blijven. `DownstreamClient` zet dezelfde
headers op zijn `HttpRequest.Builder`.

Het alternatief — `DownstreamClient` omzetten naar een JAX-RS-client zodat het bestaande filter
past — is verworpen: dat draait de bewuste keuze voor config-gedreven downstreams terug en raakt
de retry-, timeout- en foutafhandeling die daaromheen is gebouwd.

### SSRF-blocklist en de outway

`DownstreamClient.valideerUrl` hanteert een exacte loopback-whitelist (`localhost`, `127.0.0.1`,
`[::1]`), een TLS-eis buiten loopback (BIO 13.2.1) en een SSRF-blocklist op alles wat naar een
intern adres resolveert. Die drie zijn geschreven voor downstreams die over publiek internet naar
een federatieve dienstverlener gaan. Voor de outway kloppen ze niet:

- **lokaal** draait de demo-stack met `QUARKUS_PROFILE=dev`, waar de hele validatie al uitstaat —
  het federatiebewijs loopt dus sowieso;
- **op ZAD** wordt de outway aangesproken als `https://fsc-…-fscoutway:8443` (zie
  `logius/deploy/zad/verify-zad.md`). De TLS-eis is dan voldaan, maar die ClusterIP resolveert naar
  een RFC1918-adres en loopt op de blocklist stuk.

Besluit: **een downstream met een grant-hash slaat de SSRF-blocklist over**. De TLS-eis blijft
onverkort staan. De rechtvaardiging is dat de bestemming van zo'n call niet meer door onze URL
wordt bepaald maar door het FSC-contract: de outway routeert op de grant-hash, en een URL die naar
een ander intern adres wijst levert geen verkeer maar een fout. Dat is een strakkere garantie dan
de blocklist geeft, niet een zwakkere.

Wat er wél mee verdwijnt: een operator met config-toegang kan het magazijn met een verzonnen
grant-hash op een intern adres richten. Die bypass moet daarom zichtbaar zijn — bij boot logt het
magazijn één regel met een alert-token die opsomt welke downstreams door een outway lopen, in
dezelfde vorm als het bestaande `DOWNSTREAM_URL_VALIDATIE_UIT`.

De overwogen alternatieven: een aparte `via-outway`-vlag (een tweede knop die altijd samen met de
grant-hash gezet moet worden — twee dingen die uiteen kunnen lopen), en de validatie ongemoeid
laten (kleinste diff, maar dan beschrijft het ZAD-runbook een pad dat bij de eerste poging faalt).

## Ontwerp — harness

### Outway bij `magazijn-a`

De peer heeft er nog geen. Het adresschema in `federatie/README.md` reserveert `.5` al voor elke
peer, dus de outway komt op `127.20.2.5:8443`, gespiegeld aan `outway-logius`. Dat raakt: de
PKI-definitie (`pki/peers/magazijn-a/outway/csr.json`, met de ZAD-hostnamen erin zoals de andere
componenten die dragen), de drie compose-bestanden van de peer, en de federatie-overlay.

### Twee diensten op één inway

`logius/deploy/local/publish-service.sh` heeft de dienstnaam hardcoded. Die wordt overrulebaar via
`FSC_SERVICE_NAME` — dezelfde variabelenaam die `smoke-discover.sh` al gebruikt — met de huidige
waarde als default, zodat bestaande aanroepen niet wijzigen. `fsc_zet_upstream()` krijgt de dienst
als extra argument.

De upstream van `notificatieservice` is de bestaande WireMock notificatie-stub op host `:8084`, net
zoals `smoke-keten.sh` de inway van `magazijn-a` naar het échte magazijn op `:8090` laat wijzen.

### Rollen en contract

`peers.env` krijgt de notificatierollen: welke peer de dienst draagt, welke peers pushen, en de
dienstnaam. `contracts/fbs-contracten.sh` krijgt een tweede lus die per pusher een contract opzet
en het grant-hash naar hetzelfde `demo/generated/fsc-grants.env` schrijft. Dat bestand houdt
bewust één schrijver, inclusief het opruimen ervan bij een mislukte run — een grant-hash uit een
vorige federatie die blijft staan, levert stil `400 UNKNOWN_GRANT_HASH_IN_HEADER` op.

De contract-bootstrap zelf is generiek en hoeft niet te wijzigen: `bootstrap.sh` krijgt alle peers,
adressen en certificaten uit env.

### Demo-stack

`compose.podman-hostnet.yaml` geeft `berichtenmagazijn` dezelfde `env_file`-koppeling naar
`demo/generated/fsc-grants.env` die de uitvraag al heeft, plus een overrulebare `NOTIFICATIE_URL`.
Ontbreekt dat bestand — geen federatie op deze machine — dan start de stack zonder grant-hash en
valt het magazijn terug op rechtstreeks verkeer.

## Verificatie

`federatie/smoke-notificatie.sh`, naar het model van `smoke-keten.sh`:

1. **Data-pad** — een uniek gemerkt bericht aanleveren bij `magazijn-a`; na de outbox-poller staat
   de CloudEvent in de request-journal van de stub (`__admin/requests`), mét
   `Content-Type: application/cloudevents+json`. Het merk is uniek per run: zonder dat houdt een
   event uit een eerdere run de assert groen terwijl de keten stuk is.
2. **Verantwoording** — een nieuwe gedeelde transactie-id in beide txlogs: uitgaand bij
   `magazijn-a`, inkomend bij `logius`, op `service_name = notificatieservice`. Ging de push
   rechtstreeks, dan groeit geen van beide. Met nulmeting vooraf, om dezelfde reden als in
   `smoke-keten.sh`: een eerdere run laat rijen achter.
3. **Fire-and-forget intact** — de stub blijft `202` geven en de claim komt op afgeleverd te staan
   in plaats van in de retry-lus te blijven hangen.

De outbox-poller draait op 60s. De smoke wacht daarop met een bovengrens, en verlaagt de interval
niet: anders bewijst hij een configuratie die niemand draait.

Applicatiekant, op de bestaande `DownstreamHttpServer`-opzet: geparametriseerd over afwezig, leeg,
whitespace en gevuld grant-hash — headers exact doorgegeven, transaction-id parseert als UUID v7,
en bij een lege waarde géén van beide headers. Plus de SSRF-uitzondering: intern adres mét
grant-hash gaat door, intern adres zónder wordt geweigerd.

## Buiten scope

- **De aanmeld-downstream.** Technisch identiek en in het C4-model óók via FSC, maar `aanmeld`
  wijst niet naar een stub maar naar de échte uitvraag-webhook. Dat pad verdient een eigen
  dienstpublicatie en een eigen issue. De codewijziging hier maakt het daarna klein: één contract
  en één configregel.
- **Een eigen `notificatie`-peer.** Zie de afweging onder Rolverdeling.
- **Live ZAD-uitrol.** De runbooks worden bijgewerkt (outway-component bij `magazijn-a`,
  `CreateService notificatieservice`, env-vars op het gedeployde magazijn), maar het toepassen
  ervan vereist echte ZAD-toegang en blijft — net als bij elk eerder peer-plan — handmatig
  vervolgwerk.
- **`workspace.dsl`.** Regel 191 beschrijft dit pad al correct.

## Openstaand

De dienst heet `notificatieservice`, letterlijk zoals in de acceptatiecriteria. Dat wijkt af van
`profiel-service` en sluit aan bij `berichtenmagazijn`; de bestaande namen zijn onderling al niet
consistent, dus dit ontwerp volgt het issue in plaats van een derde schrijfwijze te introduceren.
