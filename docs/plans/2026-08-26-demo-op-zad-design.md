**Status:** Fase 1 (lokaal) uitgevoerd; fase 2 (ZAD) blijft concept.

# Demo draaibaar op de laptop én op ZAD — ontwerp

Issue: MinBZK/MijnOverheidZakelijk#936 ("Demo van de berichtenbox draaibaar maken op de laptop én
online (ZAD)"). Hangt samen met #938 (magazijn-simulator) en #1005 (scheiding van
demonstratiecode, uitgevoerd in #237).

Dit ontwerp draait één besluit om. `docs/plans/2026-07-21-demo-platform-design.md` zette
ZAD-deployment van het demo-platform expliciet buiten scope. Het issue stelt vast dat de demo
zowel lokaal als op ZAD beschikbaar moet zijn; dat besluit is genomen en staat hier niet meer
ter discussie.

## Context

De demo-console kan vandaag al legen (`POST /api/demo/legen`), vullen met een vaste basisset
(`/basisvulling`) en willekeurige berichten opvoeren (`/random?aantal=n`). Wat ontbreekt is een
instelbaar tempo, een README bij de engine, en bovenal: de hele demo draait uitsluitend lokaal.
Op ZAD staan wel de uitvraag, twee magazijnen en de externe stubs, maar geen enkele UI en geen
bediening. Een stakeholder kan er dus niets zien.

Het legen gaat via directe JDBC op de magazijn-databases — bewust "vieze" kennis van het
magazijn-schema in de wegwerp-console, in plaats van een reset-endpoint in productiecode. Dat
uitgangspunt blijft hier overeind.

## Besluiten

| Vraag | Keuze |
|---|---|
| Wat werkt er op ZAD | Alles behalve de storingsknoppen op de magazijnen en de veel-magazijnen-schuif; die wachten op #938 |
| Toegang | Alles achter Keycloak-SSO via de `authorization-wall`-dienst |
| Waar | Een eigen, preview-loze deployment `demo` in elk van de drie projecten |
| Instelbaar tempo | Server-side klok in de demo-console, met start/stop en harde bovengrenzen |
| Storingsinjectie | Elke Toxiproxy achter zijn eigen `publish-on-web`-ingress, vóór zijn upstream |
| Uitrollen | Eigen workflow met alleen `workflow_dispatch`, niet aan `push: main` |

## Topologie op ZAD

Een preview-loze deployment `demo` in elk van de drie projecten, naar het model van
`fsc-logius` / `fsc-magazijna`.

| Project | Deployment `demo` | Nieuwe componenten |
|---|---|---|
| `mpfb-8wh` (uitvraag) | `redis`, `uitvraag`, `toxiproxy-aanmeld` (ingress), `toxiproxy-redis` (intern) | 2 |
| `mpfm-w3h` (magazijnen) | `magazijna`, `magazijnb`, `democonsole` (achter authorization-wall) | 1 |
| `mpfpsm-lcl` (externe-stubs) | `profiel`, `notificatie`, `toxiproxy-profiel` (ingress), `toxiproxy-notificatie` (ingress) | 2 |

### Waarom een eigen deployment en geen componenten in `test`

Een deployment somt zijn componenten expliciet op, en previews worden aangemaakt met
`clone-from: test`. Alles wat in `test` staat, wordt dus meegekloond naar elke `pr-<n>` — precies
de reden waarom de FSC-peers in eigen deployments staan. Componenten per preview weghalen kan wel
(`DELETE /api/v2/projects/{p}/components/{c}`), maar dat werkt op projectniveau en verwijdert het
component overal; `sleep-mode` werkt per deployment en niet per component.

Daar komt bij dat `test` bij elke merge naar main opnieuw uitrolt. Een demo-URL die halverwege een
presentatie herstart is geen demo-URL. De `demo`-deployment wordt daarom bewust met de hand
bijgewerkt.

De cross-project-URL's templaten al op `$DEPLOYMENT_NAME`, dus `demo` lost vanzelf op naar
`magazijna-demo-mpfm-w3h.…` en dergelijke. Dat scheelt configuratiewerk.

### Waarom de console in `mpfm-w3h` staat

Dit is de enige harde plaatsingseis; al het andere is met netwerkregels op te lossen.

De `postgresql-database`-dienst is `binding=ServiceBinding.DEPLOYMENT`. In `mpfm-w3h/test` delen
`magazijna` en `magazijnb` daardoor één secret `test-database`, dus één server, één database en
één user. Wat ze scheidt is `DB_SCHEMA`, dat per component in de versleutelde `user-env-vars`
staat — `magazijna-user-secret.sops.yaml` en `magazijnb-user-secret.sops.yaml` dragen allebei een
`DB_SCHEMA` met verschillende waarde. Van daaruit doet `application.properties` de rest:
`%prod.quarkus.flyway.schemas`, `hibernate-orm.database.default-schema`, de
`currentSchema`-JDBC-property en de `${DB_SCHEMA}`-prefix op de LDV-tabel.

Een `democonsole`-component in dezelfde deployment erft hetzelfde `demo-database`-secret en
daarmee dezelfde user. Die user bezit beide schema's — Flyway heeft de tabellen ermee aangemaakt —
dus `TRUNCATE` mag. De console leegt de magazijnen langs exact dezelfde JDBC-route als lokaal,
zonder reset-endpoint in productiecode en zonder een tweede variant van het scenario.

### Storingsinjectie: elke Toxiproxy achter zijn eigen ingress

Toxiproxy is een TCP-proxy: hij termineert geen TLS en kan geen SNI vervalsen. Vijf van de zes
lokale proxy-stromen zitten achter een TLS-eis die de code zelf afdwingt buiten dev/test
(`ConfigMagazijnregister`, `ProfielServiceEndpointValidator`, `DownstreamClient`). Een interne
`http://…toxiproxy:18090` laat de betreffende service dus fail-fast weigeren te starten.

De oplossing is de proxy niet bij de aanroeper te zetten maar vóór zijn upstream, achter een
TLS-terminerende ingress:

```
uitvraag → https://toxiproxy-profiel-demo-mpfpsm-lcl.rig… → [router termineert TLS]
         → toxiproxy:18089 → http://demo-profiel:8080
```

De aanroeper ziet een `https://`-URL, dus geen enkele TLS-bewaker merkt er iets van. Er verdwijnt
ook niets versleutelds dat er nu wel is: `magazijna-service.yaml` is een ClusterIP op poort 8090
met `tls: standard` op de ingress, dus de router termineert vandaag al en praat plain naar de pod.

De prijs is dat een ZAD-component precies één poort publiceert — `ingress.yaml.jinja` rendert één
`service_port` — dus het wordt één Toxiproxy-component per stroom. Met de magazijnen buiten scope
zijn dat er vier: drie met ingress (`profiel`, `notificatie`, `aanmeld`) en één interne voor Redis,
die geen ingress nodig heeft omdat `RedisVerbindingValidator` al een gedocumenteerde klep kent die
op ZAD aanstaat. Vier containers van ~32 Mi.

### Overwogen alternatieven voor de storingsinjectie

| Alternatief | Waarom afgevallen |
|---|---|
| Eén Toxiproxy bij de aanroeper, plus een nieuwe onveilige klep die de https-eis uitschakelt | Zet demo-gedreven verandering in security-code van het stelsel, en de klep blijft daarna staan. Botst met het uitgangspunt dat de bestaande services geen demo-logica in hun gedragspad krijgen. |
| Een `demo`-Quarkus-profiel dat de TLS-eis overslaat | Dan bewijst de ZAD-omgeving niet meer dat de `%prod`-configuratie boot, wat nu wél zo is. Een profiel dat "prod min uitzonderingen" is, drift stil uit elkaar. |
| Toxiproxy achter de FSC-inway, zodat elke URL https blijft | Elegant waar het kan, maar op ZAD loopt maar één van de zes stromen door een inway: alleen magazijn A heeft een peer (`fsc-magazijna`). Magazijn B, de profiel-dienst en de notificatiedienst hebben er geen, en `AANMELD_URL` wijst naar de directe uitvraag-ingress. Het overal laten werken vraagt drie nieuwe FSC-peers van zes componenten elk. |

### Netwerkregels

Elke hop die over een publieke ingress loopt heeft geen regel nodig: egress naar 443 staat in de
tenant-baseline. Elke Toxiproxy staat in dezelfde deployment als zijn upstream, dus die hop is
eveneens vrij. Wat overblijft zijn vijf `cross-domain-access`-paren, allemaal vanaf de console —
een outbound-regel bij de console en een inbound-regel bij de tegenpartij:

- `democonsole` → de vier `toxiproxy-*`-admin-API's (8474)
- `democonsole` → `redis` (6379, voor de cache-verval-knop)

De admin-poorten worden bewust niet gepubliceerd: wie daarbij kan, kan de demo stukmaken.

### Toegang

`keycloak` bij de projectdiensten van `mpfm-w3h`, `authorization-wall` op `democonsole`. Dat zet
een oauth2-proxy-sidecar vóór het component en routeert al het inkomende verkeer over poort 4180;
bezoekers loggen in met hun rijksaccount. Eén wall dekt zowel het bedieningspaneel als de
Berichtenbox-weergave, want die komen uit hetzelfde component.

De uitvraag blijft onbewaakt — de Berichtenbox-pagina roept hem cross-origin aan.

### Geen FSC in de demo-deployment

De peers `fsc-logius` en `fsc-magazijna` zijn singletons: één deployment, één aanmelding van de
federatie-OIN bij de directory. Ze in een tweede deployment betrekken vraagt een tweede
gepubliceerde dienst met eigen contract en grant-hash. De `demo`-deployment roept de magazijnen
daarom rechtstreeks over https aan, net als de lokale demo-stack, die FSC ook als losse keten
heeft. FSC in de demo is een vervolgstap.

### Kosten

De `test`-stacks vragen samen 1139 Mi aan requests (uitvraag 213, magazijnen 698, externe stubs
228). De `demo`-deployment spiegelt dat en telt de console (~250 Mi) en vier Toxiproxy's
(~128 Mi) erbij op: ruwweg **1,5 Gi extra requests**, plus een tweede `postgresql-database` en
vier ingressen.

Of ODCN die ruimte heeft is niet uit de projectspecs af te lezen. Dat is een vraag aan het
ZAD-team, en het antwoord moet er zijn voordat fase 2 begint.

## Wat er in de code verandert

In `berichtenmagazijn`, `berichtenuitvraag`, `fbs-common`, `fbs-magazijnregister` en
`fbs-berichtensessiecache` verandert geen regel. Wat die services op ZAD anders doen komt volledig
uit runtime-configuratie: gewijzigde aliassen in de `demo`-deployment en twee CORS-properties op de
uitvraag. Alle codewijzigingen zitten in `demo/demo-console`.

### Toxiproxy: van één client naar een register

Lokaal draait één Toxiproxy met zes proxies; op ZAD vier instanties met elk één proxy.
`ToxiproxyClient` is nu één `@RegisterRestClient(configKey = "toxiproxy")` met één URL.

Nieuw is een `ToxiproxyRegister`: een `@ConfigMapping` van proxy-naam naar admin-URL, dat via
`RestClientBuilder` per URL een client bouwt en cachet.

```properties
demo.toxiproxy."profiel".url=${TOXIPROXY_PROFIEL_URL:http://localhost:8474}
demo.toxiproxy."notificatie".url=${TOXIPROXY_NOTIFICATIE_URL:http://localhost:8474}
demo.toxiproxy."aanmeld".url=${TOXIPROXY_AANMELD_URL:http://localhost:8474}
demo.toxiproxy."redis".url=${TOXIPROXY_REDIS_URL:http://localhost:8474}
demo.toxiproxy."magazijn-a".url=${TOXIPROXY_MAGAZIJN_A_URL:http://localhost:8474}
demo.toxiproxy."magazijn-b".url=${TOXIPROXY_MAGAZIJN_B_URL:http://localhost:8474}
```

Lokaal wijzen alle zes naar dezelfde instantie, dus daar verandert het gedrag niet. Op ZAD draaien
er vier; `magazijn-a` en `magazijn-b` worden expliciet uitgeschakeld door hun env-var leeg te
zetten (`TOXIPROXY_MAGAZIJN_A_URL=`, `_B_URL=`) — de configuratiesleutel zelf blijft altijd bestaan,
want de env-var-fallback in `application.properties` zit op de waarde, niet op het al-dan-niet-
zetten ervan. Een lege waarde filtert `ToxiproxyAdressen` weg (zie
`ToxiproxyAdressen.perProxy`), waarna een knop voor die proxy een 400 geeft met de beschikbare
namen in plaats van een 500. Het register vervangt daarmee de `INFRA_PROXIES`-allowlist in
`StoringResource`: die lijst ís nu het register.

`StoringService.reset()` gaat over alle geregistreerde instanties in plaats van over één. De
bestaande controle — een Toxiproxy zonder proxies betekent dat de keten nergens doorheen loopt —
blijft, maar per instantie.

### Tempo: een server-side klok

`TempoService` met `start(intervalSeconden)`, `stop()` en `status()`, ontsloten via
`POST /api/demo/tempo/start`, `POST /api/demo/tempo/stop` en `GET /api/demo/tempo`. Elke tik levert
één gegenereerd bericht aan via de bestaande `AanleverService` en `DemoBerichtGenerator`.

Uitvoering met de programmatische API van `quarkus-scheduler` (`Scheduler.newJob(...)`): één
dependency erbij in een wegwerp-module, en geen zelfgebouwd threadbeheer of afsluitlogica.

Drie grenzen, want dit draait op een gedeelde omgeving achter een SSO-sessie die iemand wegklikt:

- interval tussen 1 en 3600 seconden; daarbuiten een 400;
- automatische stop na 500 berichten of 60 minuten, wat het eerst komt;
- één stroom tegelijk — een tweede `start` vervangt de lopende in plaats van te stapelen.

`POST /api/demo/random?aantal=n` blijft ongewijzigd voor een burst.

### Eén herstel-knop

`POST /api/demo/herstel` doet in volgorde: tempo stoppen, storingen resetten, legen, basisvulling.
Dat is het laatste acceptatiecriterium in één handeling. De losse endpoints blijven bestaan.

### Legen werkt op schema's in plaats van op databases

Lokaal zijn het twee databases, op ZAD één database met twee schema's. Dat is op te lossen zonder
één regel in `MagazijnDatabase` te wijzigen, door het schema aan de datasource te hangen:

```properties
quarkus.datasource.magazijn-a-db.jdbc.additional-jdbc-properties.currentSchema=${MAGAZIJN_A_DB_SCHEMA:public}
quarkus.datasource.magazijn-b-db.jdbc.additional-jdbc-properties.currentSchema=${MAGAZIJN_B_DB_SCHEMA:public}
```

`currentSchema` zet het `search_path` van de sessie, dus de ongekwalificeerde
`TRUNCATE berichten, bijlagen, bericht_status, publicatie_deliveries` landt vanzelf in het juiste
schema. Op ZAD krijgen beide datasources dezelfde URL, user en wachtwoord uit het
`demo-database`-secret en verschillen ze alleen in schemanaam.

Die schemanamen moeten gelijk zijn aan de `DB_SCHEMA` van de magazijnen in dezelfde deployment.
Lopen ze uiteen, dan leegt de console een leeg schema en meldt hij "0 berichten verwijderd" zonder
te klagen. Het verificatie-runbook krijgt daarom een stap die na het legen de telling per magazijn
opvraagt en vergelijkt met de basisvulling.

Daarbij hoort ook het legen van `logboek_dataverwerkingen`. Die tabel staat in `%prod` ín het
magazijn-schema, dus zonder deze stap blijft het logboek van de vorige demo staan terwijl de
berichten weg zijn — en juist het logboek wil je in een demo laten zien. Het moet een apart,
tolerant statement zijn: de wrapper maakt de tabel lui aan met `CREATE TABLE IF NOT EXISTS`, dus
vóór het eerste export-moment bestaat hij niet en zou een gewone `TRUNCATE` het hele legen laten
falen. Vandaar een guard op `to_regclass(...) IS NOT NULL`.

### De UI leidt zijn API-adres niet meer af uit de browser

`berichtenbox.js` bevat `const BASIS = http://${window.location.hostname}:8086/api/v1`. Op ZAD
bestaat er geen poort 8086 en is het schema https.

Nieuw is `GET /api/demo/omgeving`:

```json
{
  "uitvraagBasis": "https://uitvraag-demo-mpfb-8wh.rig…/api/v1",
  "storingen": ["profiel", "notificatie", "aanmeld", "redis"]
}
```

De pagina haalt dat één keer op bij het laden. De default van `uitvraagBasis` blijft leeg, waarop
de JS terugvalt op het huidige gedrag; lokaal verandert er dus niets, ook niet voor wie de demo op
een VM-adres opent. `storingen` komt uit hetzelfde register en stuurt welke knoppen de pagina
toont, zodat de magazijn-storingsknoppen op ZAD vanzelf verdwijnen — zonder aparte build of aparte
pagina.

`uitvraagBasis` komt uit een eigen sleutel `UITVRAAG_BASIS` en niet uit het bestaande
`UITVRAAG_URL`. Die twee zijn wezenlijk verschillend: `UITVRAAG_URL` is het adres dat de console
zelf server-side aanroept voor de ontdubbeling-webhook en mag container-interne DNS zijn
(`http://berichtenuitvraag:8086`), terwijl `uitvraagBasis` in een browser terechtkomt en dus het
publieke adres moet zijn. Ze samentrekken breekt lokaal meteen.

Aan de uitvraag-kant hoort daarbij op de `demo`-deployment: `QUARKUS_HTTP_CORS_ENABLED=true` en
`QUARKUS_HTTP_CORS_ORIGINS` met de console-origin. Methods en headers blijven ongezet; Quarkus
spiegelt die uit het preflight-verzoek, zoals compose het lokaal al doet.

### De console draait op ZAD onder `prod`

In `application.properties` staat nu dat de console "uitsluitend lokaal/demo, altijd onder profiel
dev" draait. Het enige dat aan `%dev` hangt is een logniveau, dus onder het default `prod`-profiel
werkt de module ongewijzigd. Die opmerking wordt bijgewerkt; de compose-stack blijft `dev` zetten.

## CI/CD en configuratie

### Het console-image

`demo/demo-console/` valt binnen `DEMO_BUITEN_UITROLPOORT`, dus een PR die alleen die module raakt
krijgt `deploy=false`. Dat blijft zo: er komt geen preview van de demo, en de `demo`-deployment
rolt niet mee met een PR.

Het image wordt daarom gebouwd door een eigen job `build-democonsole`, gehangen aan `run` en niet
aan `deploy` — exact het patroon van `build-contract-bootstrap`, dat om dezelfde reden bestaat. Zo
bouwt een demo-console-PR zijn image wél (en valt een kapotte jib-configuratie op vóór de merge)
zonder de previews open te zetten. De job komt in de `needs`-lijst van `uitrol-poort`, en
`test-uitrol-poort.sh` groeit navenant mee.

Dit vult een deel in van de `TODO(#938)` in `wijzigingsfilter.sh`, die opmerkt dat de build-matrix
van `deploy.yml` alleen de twee services noemt en een demo-module met eigen image daar ook een
regel vraagt.

### Een eigen workflow om de demo uit te rollen

`.github/workflows/deploy-demo.yml`, met alleen `workflow_dispatch` en een invoerveld voor de
image-tag (default de laatste `main-<sha7>`). Drie stappen met `zad-actions/deploy` tegen de
deployment `demo` in de drie projecten.

Bewust niet in `deploy.yml` en niet aan `push: main`: automatisch uitrollen bij elke merge herstart
de demo-omgeving, wat precies het probleem is dat de eigen deployment moest voorkomen. En
`deploy.yml` heeft een fijn afgeregelde poort-machinerie (`gate`, `uitrol-poort`, de kruiscontrole);
daar een vierde uitrol-as doorheen vlechten kost meer dan het oplevert.

### Eenmalige creatie via een runbook-script

ZAD past component-configuratie (`env_vars`, `aliases`, poorten) alleen bij creatie toe, niet bij
een re-POST op een bestaand component. Vandaar dezelfde tweedeling als bij de FSC-peers:

- `demo/environment/zad-demo/deploy/` krijgt `upsert-demo.sh` (validate/plan/apply tegen de
  v2-API), met `README.md` en `verify-zad.md`, voor de eenmalige creatie van de `demo`-deployments,
  hun componenten, aliassen en poorten. Onder `environment/` en niet als eigen map onder `demo/`,
  omdat `^demo/environment/` in `DEMO_BUITEN_UITROLPOORT` staat: een nieuwe map naast die
  uitsluiting zou elke runbook-wijziging als uitrol-relevant laten tellen en drie previews kopen.
  `demo/README.md` beschrijft `environment/` vandaag als de FSC-federatieharness; die omschrijving
  wordt verbreed naar de ZAD- en federatie-runbooks.
- `deploy-demo.yml` doet daarna uitsluitend tag-updates.

Wat in dat runbook staat en niet in deze repo kan, omdat het in `RijksICTGilde/rig-cluster-projects`
leeft: `keycloak` bij de projectdiensten van `mpfm-w3h`, `authorization-wall` op `democonsole`, de
vijf `cross-domain-access`-paren en de `demo`-aliassen.

### Image-pin voor Toxiproxy

`ghcr.io/shopify/toxiproxy:2.12.0` staat nu alleen in `compose.yaml`. Zodra hij ook op ZAD draait
komt hij als `TOXIPROXY_IMAGE`-env in `deploy-demo.yml`, en dan hoort hij in de guard:
`pin-consistency.yml` krijgt `shopify/toxiproxy` naast `redis/redis-stack-server`. Dependabot ziet
alleen `compose.yaml`, dus zonder die uitbreiding levert een bump stille drift.

De pin in de OM-projectspec valt buiten het bereik van de guard (andere repository), net als bij
Redis vandaag; het runbook noemt hem als handmatig na te lopen punt.

### Configuratie in Operations Manager

| Component | Sleutels |
|---|---|
| `uitvraag` (demo) | `PROFIEL_SERVICE_URL`, `REDIS_HOSTS`, `QUARKUS_HTTP_CORS_ENABLED`, `QUARKUS_HTTP_CORS_ORIGINS` |
| `magazijna` / `magazijnb` (demo) | `NOTIFICATIE_URL`, `AANMELD_URL`, `DB_SCHEMA`, `MAGAZIJN_OIN` |
| `democonsole` | `UITVRAAG_BASIS`, `UITVRAAG_URL`, `MAGAZIJN_A_URL`/`_B_URL`, de DB-aliassen + `MAGAZIJN_A_DB_SCHEMA`/`_B_DB_SCHEMA`, alle zes `TOXIPROXY_*_URL` (`TOXIPROXY_MAGAZIJN_A_URL=` en `_B_URL=` expliciet leeg — leeg schakelt die proxy uit, zie hierboven), `REDIS_HOSTS`, `MAGAZIJN_STUBS_ADMIN_URL` en `DEMO_MAGAZIJN_STUBS` (horen bij de veel-magazijnen-schuif; buiten scope voor fase 2, zie "Bewust buiten scope") |
| vier `toxiproxy-*` | `proxies.json` als attachment, één proxy per instantie |

De DB-aliassen van de console verwijzen naar dezelfde platformvariabelen als die van de magazijnen:

```yaml
MAGAZIJN_A_DB_URL: jdbc:postgresql://$DATABASE_SERVER_HOST:5432/$DATABASE_DB
MAGAZIJN_A_DB_USER: $DATABASE_SERVER_USER
MAGAZIJN_A_DB_PASSWORD: $DATABASE_PASSWORD
```

## Documentatie

| Bestand | Wat |
|---|---|
| `demo/demo-console/README.md` | Nieuw. De README waar het eerste acceptatiecriterium om vraagt: wat de console is, lokaal starten, de ZAD-URL, en een tabel met de knoppen. Verwijst door naar het runbook in plaats van het te dupliceren. |
| `docs/demo-runbook.md` | Sectie over de ZAD-demo: URL, inloggen via SSO, en wat daar bewust anders is. |
| `demo/README.md` | De tabel noemt `demo-console` als bedieningspaneel; daar komt bij dat het ook een ZAD-component heeft. |
| `demo/environment/zad-demo/deploy/README.md` + `verify-zad.md` | Nieuw. Achtergrond en verificatiestappen bij `upsert-demo.sh`. |
| `CLAUDE.md` | De ZAD-sectie noemt per project de deployments `test` en `pr-<n>`; daar komt `demo` bij, met de reden waarom die preview-loos is. |
| `docs/plans/2026-07-21-demo-platform-design.md` | Onder "Bewust buiten scope" staat nog dat ZAD-deployment van het demo-platform niet gebeurt. Dat krijgt een verwijzing naar dit ontwerp. |

## Tests

`demo-console` valt buiten de JaCoCo-gate maar niet buiten detekt (`maxIssues: 0`, geen baseline).
Zijn tests zijn bewust pure JVM; daar rekent de demo-shard van `test.yml` op.

| Nieuw of gewijzigd | Wat |
|---|---|
| `ToxiproxyRegisterTest` | Proxy naar admin-URL; onbekende proxy geeft 400 met de beschikbare namen. `@ParameterizedTest` over nul, één en meerdere instanties — een register van één verbergt "geeft de enige terug" in plaats van "kiest per sleutel". |
| `StoringServiceTest` | `reset()` gaat over alle instanties; de "geen enkele proxy"-controle geldt per instantie. |
| `TempoServiceTest` | Grenswaarden 0, 1, 3600, 3601; tweede `start` vervangt de lopende stroom; `stop` zonder lopende stroom; auto-stop op aantal én op duur, met een injecteerbare klok. |
| `HerstelServiceTest` | De vier stappen in volgorde; een falende stap laat de rest niet stil overslaan. |
| `OmgevingResourceTest` | Lege default laat de UI terugvallen op het huidige gedrag; gezette config komt ongewijzigd door; `storingen` spiegelt het register. |
| `DemoConsolePropertiesTest` | Beide datasources dragen een `currentSchema` uit env met default `public`. |
| `LegenSqlTest` | De opgebouwde statements: de vier domeintabellen ongekwalificeerd, en de LDV-tabel achter de `to_regclass`-guard. |

Bewust géén Testcontainers-test in deze module. Het schema-gedrag is configuratie, en het bewijs
dat een `TRUNCATE` in `magazijna` landt en niet in `public` vraagt een echte PostgreSQL. Die
toevoeging zou `demo-console` Docker-afhankelijk maken en de aanname onder de test-scoping breken.
Het gedrag wordt in plaats daarvan handmatig geverifieerd volgens het schema hieronder.

## Verificatie per acceptatiecriterium

| Criterium | Bewijs |
|---|---|
| Lokaal te starten, stappen in de README | De README volgen op een schone kloon; daarna een gevulde Berichtenbox. |
| Bereikbaar op ZAD met dezelfde scenario's | Na SSO-login alle knoppen die op ZAD bestaan één keer doorlopen. |
| Leegmaken en opnieuw vullen | Legen geeft een lege lijst; vullen geeft *n* berichten; nogmaals vullen zonder legen geeft 2*n*. Die laatste stap bewijst dat het magazijn eigen ID's toekent en dat legen echt nodig is. |
| Willekeurige berichten in instelbaar tempo | Starten met interval 5 s en de toename tellen; 0 en 3601 geven 400; de stroom stopt vanzelf op de bovengrens. |
| Terug naar de begintoestand zonder databasewerk | Eén druk op herstel; daarna zijn de aantallen per magazijn gelijk aan die direct na de eerste basisvulling, staan alle proxies aan en is het logboek leeg. |

## Fasering

**Fase 1 — lokaal.** Toxiproxy-register, tempo-service, herstel-knop, omgevings-endpoint en
UI-aanpassing, `currentSchema` op de datasources, het legen van de LDV-tabel, de README, en de
`build-democonsole`-job. Volledig in deze repository en volledig te reviewen zonder ZAD. Levert de
acceptatiecriteria 1, 3, 4 en 5 lokaal.

**Fase 2 — ZAD.** `upsert-demo.sh` met runbook, de configuratie in Operations Manager,
`deploy-demo.yml`, de pin-guard en de documentatie-aanpassingen. Levert criterium 2 en criterium 5
op de gedeelde omgeving.

## Openstaande afhankelijkheden

- Bevestiging van het ZAD-team dat ODCN ~1,5 Gi extra requests aankan. Stellen tijdens fase 1,
  beantwoord vóór fase 2.
- De schemanamen van de magazijnen in de `demo`-deployment moeten bekend zijn voordat de console
  geconfigureerd wordt; ze staan versleuteld in de `user-env-vars` van `test`.
- **Het verbergmechanisme dekt niet alle knoppen die op ZAD zonder werkende backend staan.**
  `index.html` verbergt alleen knoppen met een `data-proxy`-attribuut wanneer die proxy niet in
  het register voorkomt (zie hierboven). De veel-magazijnen-knoppen en de ontdubbelingsknop dragen
  dat attribuut niet en blijven dus zichtbaar en falend op ZAD, ook al staan ze — via de
  veel-magazijnen-schuif — buiten scope voor fase 2. Fase 2 moet hier een keuze maken: het
  mechanisme uitbreiden naar een generieke "backend ontbreekt"-check, of deze knoppen een eigen
  verbergconditie geven.

## Bewust buiten scope

- **Storingsknoppen op de magazijnen en de veel-magazijnen-schuif op ZAD.** De simulator uit #938
  laat de magazijnen hun gedrag zelf variëren en vervangt de WireMock-stubs. ZAD-plumbing voor die
  stubs nu bouwen — gegenereerde mappings als attachments, opnieuw bij elke wijziging van *n* — is
  werk dat integraal weggegooid wordt. Beide blijven lokaal werken zoals ze werken.
- **FSC in de demo-deployment.** Zie hierboven; vraagt een tweede gepubliceerde dienst per peer.
- **Productiekwaliteit van de demo-console.** Ongewijzigd: geen JaCoCo-gate, geen NL Design System,
  geen toegankelijkheidstraject. De module is expliciet wegwerp.
