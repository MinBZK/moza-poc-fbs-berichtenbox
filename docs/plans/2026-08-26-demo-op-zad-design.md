**Status:** Fase 1 (lokaal) uitgevoerd. Fase 2 uitgevoerd voor de console; de storingsinjectie is
verworpen — zie de nabrander onder "Storingsinjectie".

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
| Waar | Als component in de bestaande deployment `test`; de demo bouwt op de keten die er staat |
| Instelbaar tempo | Server-side klok in de demo-console, met start/stop en harde bovengrenzen |
| Storingsinjectie | Elke Toxiproxy achter zijn eigen `publish-on-web`-ingress, vóór zijn upstream |
| Uitrollen | Eigen workflow met alleen `workflow_dispatch`, niet aan `push: main` |

## Topologie op ZAD

De demo draait op de keten die er al staat: de bestaande uitvraag, de twee bestaande magazijnen en
de externe stubs in de deployment `test`. Er komt één component bij.

| Project | Deployment `test` | Nieuw |
|---|---|---|
| `mpfb-8wh` (uitvraag) | `redis`, `uitvraag` | — |
| `mpfm-w3h` (magazijnen) | `magazijna`, `magazijnb`, **`democonsole`** (achter authorization-wall) | 1 |
| `mpfpsm-lcl` (externe-stubs) | `profiel`, `notificatie` | — |

### Waarom in `test` en niet in een eigen deployment

**Een eigen deployment zou op zichzelf beter zijn**, en het is goed om te weten waaróm we die
toch niet nemen.

Drie dingen pleiten ervoor. Een deployment somt zijn componenten expliciet op en previews worden
aangemaakt met `clone-from: test`, dus alles wat in `test` staat wordt meegekloond naar elke
`pr-<n>` — precies de reden waarom de FSC-peers wél in eigen deployments staan. `test` rolt
bovendien bij elke merge naar main opnieuw uit, dus de demo-omgeving kan halverwege een presentatie
herstarten. En de legen-knop wist de database van `test`, terwijl nieuwe previews juist daarvan
klonen.

Wat de doorslag geeft is het legen. De console moet de magazijn-schema's met een directe
`TRUNCATE` kunnen leegmaken, en de `postgresql-database`-dienst is deployment-gebonden: alleen een
component ín dezelfde deployment als de magazijnen erft hun `test-database`-secret, en daarmee
dezelfde server, database en user. In een eigen deployment krijgt de console een eigen, lege
database. De enige manier om daar omheen te komen is de verbindingsgegevens van `test` met de hand
overschrijven in de `user-env-vars` van de console — een gedupliceerd secret dat het platform zelf
beheert en roteert. Breekt dat, dan meldt de console "0 berichten verwijderd" zonder te klagen:
stil falen, precies de vorm waar het verificatie-runbook al een stap tegen bevat.

De routes om zónder JDBC te legen vallen alle af. Een reset-endpoint in het magazijn zet
demo-logica in productiecode, wat een uitgangspunt van dit ontwerp uitsluit. Per bericht
`DELETE` via de bestaande API is soft-delete, vereist `X-Ontvanger` per bericht, zet geen identity
terug en laat rijen staan — geen reset.

Daar staat één nadeel tegenover: elke openstaande PR krijgt de console mee, ongeveer 250 Mi. Een
handmatig gekopieerde credential die stil kapot gaat weegt zwaarder dan dat geheugen.

Dat sluit ook aan bij het uitgangspunt van het ticket: de demo bouwt op de bestaande keten en
groeit van daaruit, in plaats van er een tweede keten naast te zetten.

### Gevolgen die hierbij horen

Drie dingen volgen rechtstreeks uit deze keuze en horen bekend te zijn bij wie de demo gebruikt:

- **De legen-knop wist de database van `test`.** Previews klonen die data, dus één druk op de knop
  verandert ook waar een nieuwe preview mee begint. Onomkeerbaar, en er zit een knop op.
- **Previews erven de console.** Ongeveer 250 Mi per openstaande PR.
- **De demo herstart bij elke merge naar main.** Daar is niets aan te doen zolang de demo in `test`
  woont; het is iets om te weten, niet om op te lossen.

### Waarom de console in `mpfm-w3h` staat

Dit is de enige harde plaatsingseis; al het andere is met netwerkregels op te lossen.

De `postgresql-database`-dienst is een **platformdienst van ZAD**, geen component dat wij zelf
draaien: het platform provisioneert de database op een gedeelde instantie en levert de
verbindingsgegevens als secret. In de gerenderde manifests van `mpfm-w3h/test` staat dan ook geen
enkele postgres-pod — alleen `test-database-secret.sops.yaml`. Dat is iets anders dan
`magazijna-fscpg` in de deployment `fsc-magazijna`: dát is wél zelf-gehost (`postgres:17` met
`persistent-storage`), met een eigen image-pin en eigen volume.

Die dienst is `binding=ServiceBinding.DEPLOYMENT`. In `mpfm-w3h/test` delen
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
uitvraag → https://toxiproxy-profiel-test-mpfpsm-lcl.rig… → [router termineert TLS]
         → toxiproxy:18089 → http://test-profiel:8080
```

De aanroeper ziet een `https://`-URL, dus geen enkele TLS-bewaker merkt er iets van. Er verdwijnt
ook niets versleutelds dat er nu wel is: `magazijna-service.yaml` is een ClusterIP op poort 8090
met `tls: standard` op de ingress, dus de router termineert vandaag al en praat plain naar de pod.

De prijs is dat een ZAD-component precies één poort publiceert — `ingress.yaml.jinja` rendert één
`service_port` — dus het wordt één Toxiproxy-component per stroom. Met de magazijnen buiten scope
zijn dat er vier: drie met ingress (`profiel`, `notificatie`, `aanmeld`) en één interne voor Redis,
die geen ingress nodig heeft omdat `RedisVerbindingValidator` al een gedocumenteerde klep kent die
op ZAD aanstaat. Vier containers van ~32 Mi.

**Deze storingsinjectie is met de verhuizing naar `test` duurder geworden en staat daarom open.**
In een eigen deployment raakte hij niemand anders; in `test` erft élke preview de vier containers,
en lopen de downstream-URL's van de uitvraag en de magazijnen daar permanent door een extra hop —
ook wanneer er niemand demonstreert. Of dat de storingsknoppen waard is, is een aparte beslissing.
De console werkt zonder: legen, vullen, tempo, herstel en de Berichtenbox-weergave hangen er niet
van af.

> **Nabrander bij de uitvoering: deze opzet werkt niet, en de storingsknoppen zijn er daarom
> uitgelaten.** Drie eigenschappen van ZAD, geverifieerd in `RijksICTGilde/RIG-Cluster` en op de
> OM-API:
>
> - De inhoud van een attachment wordt ongewijzigd gemount; `$DEPLOYMENT_NAME`-substitutie bestaat
>   alleen voor aliassen. Een `proxies.json` noemt dus een vaste upstream, die in een preview de
>   verkeerde is.
> - `command` staat niet in `AddComponentRequest`/`UpdateComponentRequest` en kent `zadctl` niet.
>   Het startcommando dat Toxiproxy naar `proxies.json` wijst is dus UI-handwerk, per component.
> - Een `cross-domain-access`-regel noemt altijd één concrete peer-deployment; blijft die open, dan
>   wordt de regel bij het genereren overgeslagen (`merge.py`/`resolve.py`). De console kan de
>   admin-API's in een preview dus niet bereiken.
>
> Het gevolg zonder maatregelen is de slechtste soort: een preview zou zijn keten-verkeer
> stilzwijgend door de Toxiproxy-instanties van `test` sturen en dáár de profiel-stub, de
> notificatie-stub en de uitvraag aanspreken.
>
> Er is wél een route die het oplost, en die vervangt dit ontwerp zodra de storingsknoppen aan de
> beurt zijn: Toxiproxy start met zijn eigen default-`CMD` (`-host=0.0.0.0`, geverifieerd op de
> image-config van 2.12.0) en dus zónder proxies, en de **console maakt de proxies zelf aan** via
> de admin-API. Listen en upstream komen dan uit console-configuratie, en die komt uit aliassen —
> die kennen `$DEPLOYMENT_NAME` wél. Dat schrapt de attachments én het `command`. Wat blijft, zijn
> de netwerkregels, en die zijn per preview bij te schrijven met
> `PATCH …/services/cross-domain-access/config/deployment/{d}/{inbound,outbound}` vanuit de
> deploy-workflow.
>
> Dezelfde netwerkregel-beperking raakt de cache-verval-knop: Redis staat in `mpfb-8wh`, de console
> in `mpfm-w3h`. Het paneel verbergt die knop op grond van `SESSIECACHE_BEREIKBAAR=false`.

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

### FSC

Dit ontwerp verandert niets aan de federatie. De demo draait op `test`, en wat daar vandaag door de
FSC-keten loopt blijft dat doen; wat er rechtstreeks loopt eveneens. De peers `fsc-logius` en
`fsc-magazijna` staan in hun eigen, preview-loze deployments en worden niet aangeraakt.

### Kosten

Eén component erbij: de console, ongeveer **250 Mi**, plus één ingress. Geen tweede stack, geen
extra database — `test` heeft de zijne al en de console deelt hem.

Die 250 Mi tellen per deployment die de console draagt, en previews klonen `test`. Bij vier
openstaande PR's is dat dus ruwweg een gigabyte, tijdelijk en meebewegend met wat er openstaat.

Komen de storingsknoppen er later bij, dan is dat vier containers van ~32 Mi plus drie ingressen,
opnieuw per deployment inclusief previews.

## Wat er in de code verandert

In `berichtenmagazijn`, `berichtenuitvraag`, `fbs-common`, `fbs-magazijnregister` en
`fbs-berichtensessiecache` verandert geen regel. Wat die services op ZAD anders doen komt volledig
uit runtime-configuratie: de aliassen van het nieuwe console-component en twee CORS-properties op de
uitvraag. Alle codewijzigingen zitten in `demo/demo-console`.

### Toxiproxy: van één client naar een register

Lokaal draait één Toxiproxy met zes proxies; op ZAD vier instanties met elk één proxy.
`ToxiproxyClient` is nu één `@RegisterRestClient(configKey = "toxiproxy")` met één URL.

Nieuw is een `ToxiproxyRegister`: een `@ConfigMapping` van proxy-naam naar admin-URL, dat via
`RestClientBuilder` per URL een client bouwt en cachet.

```properties
demo.toxiproxy."profiel".url=${TOXIPROXY_PROFIEL_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."notificatie".url=${TOXIPROXY_NOTIFICATIE_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."aanmeld".url=${TOXIPROXY_AANMELD_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."redis".url=${TOXIPROXY_REDIS_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."magazijn-a".url=${TOXIPROXY_MAGAZIJN_A_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
demo.toxiproxy."magazijn-b".url=${TOXIPROXY_MAGAZIJN_B_URL:${TOXIPROXY_ADMIN_URL:http://localhost:8474}}
```

Lokaal wijzen alle zes naar dezelfde instantie, dus daar verandert het gedrag niet. Op ZAD draaien
er vier; `magazijn-a` en `magazijn-b` worden expliciet uitgeschakeld door hun env-var leeg te
zetten (`TOXIPROXY_MAGAZIJN_A_URL=`, `_B_URL=`) — de configuratiesleutel zelf blijft altijd bestaan,
want de env-var-fallback in `application.properties` zit op de waarde, niet op het al-dan-niet-
zetten ervan. `ToxiproxyConfig.Instantie.url()` is daarom `Optional<String>` in plaats van een kale
`String`: smallrye-config behandelt een expliciet leeg gezette env-var als "niet gezet"
(`SRCFG00040`) en zou een niet-optionele `String`-mapping laten weigeren te booten. Een lege of
afwezige waarde filtert `ToxiproxyAdressen` weg (zie `ToxiproxyAdressen.perProxy`), waarna een knop
voor die proxy een 400 geeft met de beschikbare namen in plaats van een 500. Het register vervangt
daarmee de `INFRA_PROXIES`-allowlist in `StoringResource`: die lijst ís nu het register.

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
  "uitvraagBasis": "https://uitvraag-test-mpfb-8wh.rig…/api/v1",
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

Aan de uitvraag-kant hoort daarbij op `test`: `QUARKUS_HTTP_CORS_ENABLED=true` en
`QUARKUS_HTTP_CORS_ORIGINS` met de console-origin. Methods en headers blijven ongezet; Quarkus
spiegelt die uit het preflight-verzoek, zoals compose het lokaal al doet.

### De console draait op ZAD onder `prod`

In `application.properties` staat nu dat de console "uitsluitend lokaal/demo, altijd onder profiel
dev" draait. Het enige dat aan `%dev` hangt is een logniveau, dus onder het default `prod`-profiel
werkt de module ongewijzigd. Die opmerking wordt bijgewerkt; de compose-stack blijft `dev` zetten.

## CI/CD en configuratie

### Het console-image

`demo/demo-console/` valt binnen `DEMO_BUITEN_UITROLPOORT`, dus een PR die alleen die module raakt
krijgt `deploy=false` en werkt geen preview bij. Nu de console een component van `test` wordt, is
dat een keuze die fase 2 opnieuw moet wegen — zie "Uitrollen gaat mee met de bestaande workflow".

Het image wordt daarom gebouwd door een eigen job `build-democonsole`, gehangen aan `run` en niet
aan `deploy` — exact het patroon van `build-contract-bootstrap`, dat om dezelfde reden bestaat. Zo
bouwt een demo-console-PR zijn image wél (en valt een kapotte jib-configuratie op vóór de merge)
zonder de previews open te zetten. De job komt in de `needs`-lijst van `uitrol-poort`, en
`test-uitrol-poort.sh` groeit navenant mee.

Dit vult een deel in van de `TODO(#938)` in `wijzigingsfilter.sh`, die opmerkt dat de build-matrix
van `deploy.yml` alleen de twee services noemt en een demo-module met eigen image daar ook een
regel vraagt.

### Uitrollen gaat mee met de bestaande workflow

Er komt géén aparte uitrolworkflow. De console is een component van `mpfm-w3h/test`, dus hij hoort
in de componentlijst van de bestaande `deploy-test-magazijnen`-stap in `deploy.yml`, naast
`magazijna` en `magazijnb`. Elke push naar main werkt hem daarmee bij, net als de rest van de
keten.

Voor previews geldt hetzelfde langs `deploy-preview-magazijnen`. Dat betekent wel dat een
demo-console-wijziging voortaan een preview raakt, terwijl `demo/demo-console/` vandaag in
`DEMO_BUITEN_UITROLPOORT` staat en dus `deploy=false` oplevert. Fase 2 moet die uitsluiting
heroverwegen: óf het pad eruit halen zodat een consolewijziging een preview bijwerkt, óf hem laten
staan en accepteren dat de console in een preview op de tag van de laatste bredere uitrol blijft
hangen. De eerste optie is eerlijker, de tweede goedkoper.

### Eenmalige creatie van het component

ZAD past component-configuratie (`env_vars`, `aliases`, poorten) alleen bij creatie toe, niet bij
een re-POST op een bestaand component. Het `democonsole`-component moet dus één keer met zijn
volledige configuratie aangemaakt worden; daarna doet `deploy.yml` alleen nog tag-updates.

Dat is aanzienlijk minder werk dan een eigen deployment zou vragen — één component in plaats van
negen, en geen `upsert`-script voor deployments die nog niet bestaan. Een runbook blijft wel nodig,
want een deel gebeurt in `RijksICTGilde/rig-cluster-projects` en niet in deze repository:

- `keycloak` bij de projectdiensten van `mpfm-w3h` en `authorization-wall` in de `uses-services`
  van `democonsole`
- de aliassen van de console: de DB-verbinding via `$DATABASE_SERVER_HOST` en verwanten, de
  magazijn-URL's, `UITVRAAG_BASIS` (publiek, mét `/api/v1`) en `UITVRAAG_URL` (intern)
- de `user-env-vars` `MAGAZIJN_A_DB_SCHEMA` en `MAGAZIJN_B_DB_SCHEMA`, gelijk aan de `DB_SCHEMA`
  van `magazijna` respectievelijk `magazijnb` in dezelfde deployment
- `QUARKUS_HTTP_CORS_ENABLED` en `QUARKUS_HTTP_CORS_ORIGINS` op `uitvraag`

Dat runbook hoort onder `demo/environment/`, want dat pad staat in `DEMO_BUITEN_UITROLPOORT`; een
nieuwe map ernaast zou elke runbook-wijziging als uitrol-relevant laten tellen.

### Image-pin voor Toxiproxy

`ghcr.io/shopify/toxiproxy:2.12.0` staat nu alleen in `compose.yaml`. Zodra hij ook op ZAD draait
komt hij als `TOXIPROXY_IMAGE`-env in `deploy.yml`, en dan hoort hij in de guard:
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
| `demo/README.md` | De tabel noemt `demo-console` als bedieningspaneel; daar komt bij dat het een ZAD-component in `test` heeft. |
| `demo/environment/zad-demo/README.md` + `verify-zad.md` | Nieuw. De handmatige OM-stappen voor het console-component en de verificatie erna. |
| `CLAUDE.md` | De ZAD-sectie somt per project de componenten van `test` op; `democonsole` komt daarbij, met de reden waarom de demo op de bestaande keten draait. |
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

**Fase 2 — ZAD.** Het `democonsole`-component eenmalig aanmaken in `mpfm-w3h/test` met zijn
aliassen en env, `keycloak` en `authorization-wall` aanzetten, de console toevoegen aan de
componentlijsten van `deploy-test-magazijnen` en `deploy-preview-magazijnen`, de CORS-properties op
de uitvraag zetten, het runbook schrijven en de documentatie bijwerken. Levert criterium 2 en
criterium 5 op de gedeelde omgeving.

De storingsknoppen zijn geen onderdeel van fase 2 zolang de beslissing daarover openstaat (zie
"Storingsinjectie" hierboven).

## Openstaande afhankelijkheden

- **De storingsknoppen op ZAD: doen of niet doen.** Vier Toxiproxy-containers plus drie ingressen,
  per deployment inclusief previews, en een permanente extra hop in het verkeerspad van `test`. De
  rest van de demo werkt zonder. Te beslissen vóór fase 2 begint.
- **De uitsluiting van `demo/demo-console/` in `DEMO_BUITEN_UITROLPOORT`.** Nu de console een
  component van `test` wordt, bepaalt die uitsluiting of een consolewijziging een preview bijwerkt.
  Zie "Uitrollen gaat mee met de bestaande workflow".
- De schemanamen van de magazijnen in `test` moeten bekend zijn voordat de console geconfigureerd
  wordt; ze staan versleuteld in hun `user-env-vars`.
- **Bevestiging dat de legen-knop de database van `test` mag wissen.** Previews klonen die data.
  Dit is geen technische blokkade maar een afspraak die één keer hardop gemaakt moet zijn.
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
