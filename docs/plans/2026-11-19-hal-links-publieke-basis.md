# HAL-links zonder schema en host uit het verzoek

**Status:** Concept

Refs MinBZK/MijnOverheidZakelijk#1215. Vondst uit de security-review van #417 (open vraag 2 in de
afsluitcomment daar).

## Context

Het berichtenmagazijn en de magazijn-simulator bouwen hun HAL-links (`self`, `first`, `last`,
`prev`, `next`, de `self` van een bijlage, de `self` in het antwoord op aanleveren) op
`UriInfo.baseUriBuilder`. Schema en host komen dan uit het binnenkomende verzoek. Achter de
ZAD-ingress eindigt TLS bij de router en praat het component plain http, dus het schema is daar
`http`.

Gevolgen:

- een app op een https-origin blokkeert de links als mixed content; paginering en `self` volgen
  werkt niet;
- een client die een link volgt, stuurt zijn `Authorization: Bearer <sessieToken>` (tweede
  ingang) of zijn `X-Ontvanger` (eerste ingang) eerst in klare tekst naar poort 80;
- de host komt uit de `Host`-header. Wie die vervalst, vergiftigt alleen zijn eigen antwoord, maar
  het patroon hoort niet in een dienst die achter willekeurige proxies kan staan.

## Stap 1: gemeten op 2026-11-19

### Wat ZAD teruggeeft (`test` van mpfm-w3h)

Eerste ingang, met een demo-`X-Ontvanger`. De tweede ingang draait op ZAD nog niet (#416, #455).

| Dienst | Verzoek | `_links` |
|---|---|---|
| simulator, OIN `00000009000000000001` | `GET https://magazijnsimulator-test-…/magazijn/<OIN>/api/v1/berichten?pageSize=1`, `KVK:90000003` | `self`, `first`, `last`, `next` en de `self` per bericht allemaal `http://magazijnsimulator-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl/magazijn/<OIN>/api/v1/…` |
| simulator, zelfde OIN | `KVK:90000002` (geen berichten) | `self`, `first`, `last` op `http://…?page=0`; geen `next`/`prev` |
| magazijna | `GET https://magazijna-test-…/api/v1/berichten?pageSize=1`, `KVK:90000001` | `http://magazijna-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl/api/v1/berichten?page=0&pageSize=1` |

De verwachting uit #417 klopt dus voor beide diensten: schema `http`, host de publieke hostnaam.

### Wat de ingress meestuurt en wat Quarkus ermee doet

- Een verzoek met `X-Forwarded-Proto: https`, `X-Forwarded-Host: evil.example` en
  `Forwarded: proto=https;host=evil.example` geeft bij magazijna ongewijzigd `http://magazijna-…`.
  Quarkus negeert de headers: `quarkus.http.proxy.proxy-address-forwarding` staat in geen van beide
  diensten aan.
- De OpenShift-router van ZAD laat een meegestuurde `X-Forwarded-*` staan en zet zijn eigen waarde
  erachter (vastgesteld voor `X-Forwarded-For` bij het quotum van de tweede ingang, comment bij
  `simulator.rechtstreeks.vertrouwde-proxies` in #416). Quarkus neemt met
  `proxy-address-forwarding` de eerste waarde: die kiest de client zelf. Aanzetten laat de client
  dus schema én host kiezen, ook met `trusted-proxies`, want de router ís de vertrouwde proxy.
- Een verzoek met een vervalste `Host` (`Host: evil.example` naar het adres van magazijna) haalt de
  pod niet: de router routeert op host en geeft 404. Op ZAD is Host-vergiftiging dus niet
  uitvoerbaar; in compose, lokaal en achter een andere proxy wel.
- Of poort 80 van de ingress antwoordt (en dus of een token daar in klare tekst aankomt) is vanaf
  de ontwikkelcontainer niet te meten: uitgaand verkeer naar poort 80 is daar dicht.

### Wie de links volgt

| Afnemer | Volgt magazijn-links? |
|---|---|
| Berichtenuitvraag (sessiecache, `MagazijnPaginaLezer`) | Nee. Bouwt elke pagina zelf uit het magazijnregister met `page`/`pageSize`; `_links` wordt bij het deserialiseren genegeerd (`MagazijnBerichtenResponse`). |
| Berichten-app (#418) | Nee, nog niet. Bouwt het adres uit `magazijn_url` van het stelseldocument en haalt alleen de eerste pagina (`TODO(#1216)` in `ophalen.js` voor de volgende pagina's en het openen van een bericht). |
| Proeftuin-berichtenbox, demo-console | Praten met de uitvraag, niet met een magazijn. De uitvraag geeft al relatieve links (`/api/v1/berichten/…`), en de demo-console volgt die met een basis-URL. |

Niemand volgt vandaag een link van een magazijn. De eerste die het gaat doen is de Berichten-app.

### Wat het contract en de standaarden zeggen

- `Link.href` in `berichtenmagazijn-api.yaml` (gedeeld door de simulator) is
  `format: uri-reference`, niet `format: uri`. Een relatieve verwijzing valt dus al binnen het
  schema. De uitvraag-spec gebruikt hetzelfde schema en levert al absolute-path references.
- De NL API Design Rules (v2.2.x en de werkversie) schrijven geen absolute links voor. De
  paginering-module (in consultatie tot 4 december 2026) schrijft een `Link`-header (RFC 8288) voor
  en zegt niets over de vorm van de URI; HAL komt in geen van beide voor. Voorbeelden met
  `https://api.example.org/…` zijn non-normatief.
- HAL (draft-kelly-json-hal) gebruikt in zijn eigen voorbeelden relatieve `href`s (`/orders?page=2`).
- RFC 3986 §5 regelt het oplossen van een relatieve verwijzing tegen het adres van het opgevraagde
  document; elke HTTP-client en `new URL(href, adres)` in de browser doen dat.

## Stap 2: keuze

### A. Geconfigureerde publieke basis-URL per dienst/magazijn

Voor de simulator per OIN, gelijk aan `magazijn_url`; voor het echte magazijn een eigen property,
https buiten dev/test en fail-fast bij boot.

- \+ links zijn absoluut en https, onafhankelijk van het verzoek.
- − elke deployment heeft een eigen hostnaam (`test`, `pr-<n>`): een nieuwe env per component in
  `deploy.yml`, in de ZAD-runbooks en in `compose.yaml`, en die moet gelijk blijven aan het
  stelseldocument. Een ontwerpwijziging aan de deploy-configuratie.
- − fout voor een afnemer achter FSC. De uitvraag praat via zijn eigen outway; een absolute
  publieke URL stuurt een link-volger buiten FSC om (en op ZAD komt hij daar door de
  netwerkisolatie per deployment niet eens aan). De dienst kan het adres van andermans outway niet
  kennen.

### B. Relatieve `href`s (absolute-path reference)

`/api/v1/berichten?page=1&pageSize=20` in het magazijn,
`/magazijn/<OIN>/api/v1/berichten?page=1&pageSize=20` in de simulator.

- \+ schema en host komen nergens meer uit het verzoek: geen mixed content, geen klare-tekst-hop,
  geen Host-vergiftiging. De client lost op tegen het adres dat hij zelf aanriep, dus https als hij
  https aanriep.
- \+ werkt achter elke hop die het pad bewaart: de ZAD-router, de FSC-outway en -inway (de
  `endpoint_url` van magazijn-a is de root van de dienst), compose.
- \+ geen configuratie, niets om gelijk te houden met het stelseldocument, `deploy.yml` en de
  runbooks blijven ongewijzigd.
- \+ zelfde vorm als de uitvraag al levert.
- \+ valt binnen het contract: het schema blijft `uri-reference`. Wel verandert de waarde die een
  afnemer ziet; omdat niemand de links nu volgt, raakt dat in de praktijk geen afnemer.
- − een hop die een pad-prefix wegknipt (`/fbs/magazijn/...` naar `/...`) breekt de links. Bij A
  breken ze net zo, tenzij de geconfigureerde basis precies dat prefix draagt. Een FSC-`endpoint_url`
  met een pad erin is zo'n hop. Vandaag knipt geen hop: geen dienst zet `quarkus.http.root-path` en
  de `endpoint_url` van magazijn-a is de root van de dienst. Die voorwaarde staat in de KDoc van
  `HalBasis`.
- − een Java-client die een link volgt, moet `requestUri.resolve(href)` doen in plaats van de href
  direct te gebruiken.

### C. Forwarded-headers vertrouwen, alleen van een geconfigureerde proxy

- − op ZAD ís de router de vertrouwde proxy en laat hij de waarde van de client vooraan staan; met
  Quarkus' keuze voor de eerste waarde kiest de client alsnog schema en host. Eigen parsing (de
  laatste waarde, zoals `ClientAdres` voor het quotum) is nieuwe beveiligingscode voor een probleem
  dat B zonder code oplost.
- − overlapt met `vertrouwde-proxies`; `proxy-address-forwarding` aanzetten zou het quotum van de
  tweede ingang ondermijnen.

**Gekozen: B.** Het is het kleinst, het hangt nergens van af dat buiten de dienst gelijk moet
blijven, en het is de enige van de drie die ook klopt voor een afnemer achter FSC. Het raakt het
schema van het contract niet en de deploy-configuratie niet. Wat wél verandert is de linkvorm; die
zetten we als beschrijving bij `Link.href` in de spec, zodat een afnemer weet dat hij moet oplossen
tegen het adres van het verzoek.

## Structuur

Eén plek per dienst bepaalt de basis:

- **berichtenmagazijn:** `HalBasis` (nieuw, CDI-bean) levert een `UriBuilder` op
  `quarkus.http.root-path` + het base-path uit de spec (`ApiInfo.BASE_PATH`), zonder schema en
  host. Niet `quarkus.rest.path`: die ligt bij de build vast, en een runtime-override zou de links
  laten afwijken van de routering. Een test bewaakt dat beide gelijk zijn. `BerichtenResource` en
  `AanleverResource` gebruiken hem in plaats van `uriInfo.baseUriBuilder`; `UriInfo` verdwijnt uit
  beide.
- **simulator:** `MagazijnPad.basisUri(oin)` levert `/magazijn/<OIN>/api/v1` zonder `UriInfo`.
  De tweede ingang (#417) gebruikt dezelfde functie en compileert na een rebase pas als die
  aanroep is bijgewerkt.

## Stappen

1. `HalBasis` in het magazijn; resources omzetten; mapper-KDoc bijwerken.
2. `MagazijnPad.basisUri` zonder `UriInfo`; aanroepers en KDoc bijwerken.
3. Spec: beschrijving bij `Link.href` (`info.version` patch).
4. Tests per ingang (eerste ingang van magazijn en simulator):
   - vervalste `Host` plus `X-Forwarded-Proto: http`/`X-Forwarded-Host`: elke `href` begint met het
     base-path, zonder schema en host;
   - nul, één en meerdere pagina's: `next`/`prev` wel of niet aanwezig;
   - bestaande asserties die op `endsWith`/`containsString` steunden, worden exact.
5. Contracttests (`OpenApiContractTest`, `OphaalBeheerOpenApiContractTest`,
   `MagazijnSpecContractTest`) ongewijzigd groen.
6. Lokaal live: demo-stack, links bij magazijn en simulator ophalen.

## Verificatie

- `./mvnw clean verify -pl services/berichtenmagazijn,demo/magazijn-simulator -am`
- Demo-stack: `curl` op de eerste ingang van beide; links relatief.
- Na de merge op ZAD de meting uit stap 1 herhalen: `_links` begint met `/api/v1/` (magazijna) en
  `/magazijn/<OIN>/api/v1/` (simulator).
- De tweede ingang (#417) krijgt dezelfde test met vervalste `Host` zodra hij op deze stand is
  gebouwd.
