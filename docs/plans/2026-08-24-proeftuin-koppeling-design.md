**Status:** Concept

# Berichtenbox in de proeftuin koppelen aan de PoC-keten — ontwerp

Issue: MinBZK/MijnOverheidZakelijk#937 ("Berichtenbox in de proeftuin koppelen aan de PoC-keten").
Hangt samen met #936 (demo draaibaar op laptop én ZAD), #938 (aantal magazijnen en realisme) en
#552 (authenticatie en autorisatie). Alle drie hangen onder #787.

**Overkoepelend ontwerp:** `docs/plans/2026-07-21-demo-platform-design.md`. Dit ontwerp raakt het
magazijn-simulator-ontwerp (`2026-08-21-magazijn-simulator-design.md`) op vier punten; die staan
onder "Samenhang met de magazijn-simulator".

## Context

De proeftuin (`MinBZK/moza-poc`, Eleventy, publiek repo) toont een Berichtenbox op
`https://proef.moza.rijksapp.dev/moza/berichtenbox/`. Die pagina draait op een gegenereerde dataset:
`_data/berichtenboxData.js` bouwt bij elke build met een vaste seed 120 berichten over veertien
verzonnen magazijnen, plus twee voorgevulde mappen. Er komt niets uit de keten en de simulatie-engine
is er niet zichtbaar.

Aan onze kant bestaat al een tweede Berichtenbox: de wegwerp-UI in `demo/demo-console` (`:8095`,
fase 2a). Die praat wél met de keten, en doet dat rechtstreeks vanuit de browser: `berichtenbox.js`
zet `X-Ontvanger` op elke `fetch` naar `http://<host>:8086/api/v1`, en de uitvraag heeft daarvoor een
CORS-allowlist die alleen in het compose-demo-profiel aan staat (`QUARKUS_HTTP_CORS_ENABLED` +
`QUARKUS_HTTP_CORS_ORIGINS`). In `application.properties` staat geen CORS-regel; buiten de demo is de
uitvraag dus CORS-loos.

**De proeftuin is geen kale statische site.** Hij wordt uitgeleverd als container
(`container/Containerfile`) met nginx ervoor, en die nginx heeft al een same-origin reverse proxy met
per-API instelbare backends: `BACKEND_ORIGIN` als algemene bestemming en `BACKEND_PROFIEL` voor de
Profiel-service, allebei runtime-env. De SSE-variant is er ook al — `location /chat` zet
`proxy_buffering off`, `Connection ""` en een lange `proxy_read_timeout` voor `/chat/stream`. Voor
`eleventy --serve` bestaat daarnaast `server/proxy.js` (`npm run proxy`): een dev-proxy op `/api` met
`PROXY_TARGET` én een per-request-override via `?target=` of de header `x-proxy-target`.

Dat is het fundament waarop dit ontwerp voortbouwt: aan onze kant is het datapad browser → uitvraag
bewezen, inclusief SSE-voortgang en bijlage-download; aan de proeftuin-kant bestaat de laag die dat
verkeer same-origin kan afhandelen al, in beide opstellingen. Wat ontbreekt is de bedrading tussen
die twee, plus een identiteit die niet uit een keuzelijst in onze eigen console komt.

**De proeftuin wordt de Berichtenbox van de demo, lokaal én online.** Dat maakt "één build voor
beide opstellingen" een harde eis in plaats van een nette bijkomstigheid — en het maakt de
wegwerp-Berichtenbox overbodig. Twee UI's op hetzelfde koppelvlak betekent elke wijziging twee keer
doen, of de tweede laten rotten; een rottende tweede UI is erger dan geen, want hij wijst bij een
storing de verkeerde kant op. De verificatie waarvoor hij zogenaamd nodig was — werkt de keten zónder
de proeftuin ertussen? — doen `demo/smoke.sh` (levert aan bij A én B, toetst de ophaal-stream op
geslaagde bevraging van beide) en de Bruno-collecties beter: scriptbaar, zonder browser, en
bruikbaar in CI. `berichtenbox.{html,css,js}` in de demo-console (ruim 800 regels) verdwijnt daarom
zodra stap 4 laat zien dat de proeftuin het overneemt. Het bedieningspaneel (`index.html`,
`/beheer`) is iets anders en blijft: dat is de engine, niet de Berichtenbox.

## Begrippen

| Term | Betekenis in dit document |
|---|---|
| proeftuin | De Eleventy-site `MinBZK/moza-poc`, publiek repo, apart gehost, uitgeleverd als container met nginx. |
| keten | Onze services samen: uitvraag, magazijnen, sessiecache, stubs. |
| koppelvlak | De verzameling keten-endpoints die de proeftuin aanroept, plus de personalijst. |
| persona | Een demo-identiteit: een label dat de proeftuin toont en een `X-Ontvanger`-waarde die de keten kent. |
| same-origin proxy | De nginx (container) of `server/proxy.js` (dev) van de proeftuin, die `/api/...` server-side doorzet naar de keten. De browser ziet één origin, dus geen CORS. |
| BFF | Backend for Frontend: een eigen serverlaag per frontend die de achterliggende API's samenvoegt en omvormt. Meer dan een proxy — hij heeft eigen logica en een eigen contract. |
| substream | De voortgangsregels van één magazijn binnen één ophaalronde. |
| opstelling | Lokaal (alles op de eigen machine) of online (proeftuin-omgeving tegen de keten op ZAD). |

## Besluit: de proeftuin bereikt de keten same-origin, via zijn eigen proxy

**Beslissing.** De Berichtenbox-pagina roept de keten aan op zijn eigen origin, onder het pad
`/api/v1/...`. De proxy van de proeftuin zet dat server-side door naar de uitvraag: in de container
via een nginx-`location` naar een runtime-env-backend, in dev-mode via `server/proxy.js` met
`PROXY_TARGET`. Endpoints, paden en de `X-Ontvanger`-header blijven ongewijzigd; er komt geen
tussenlaag met eigen logica bij, en aan de keten verandert niets.

**Waarom.**

- De laag bestaat al, in beide opstellingen, mét het SSE-precedent (`/chat/stream`). Wat we nodig
  hebben is een `location`-blok en een env-var, geen nieuwe component.
- Same-origin maakt het CORS-vraagstuk grotendeels leeg. Dat is niet alleen minder configuratie: het
  haalt de belangrijkste stille faalwijze weg. Een origin die niet in de allowlist staat is in de
  browser niet te onderscheiden van "keten onbereikbaar", en met preview-deployments aan beide kanten
  zou die allowlist bij elke PR kunnen breken.
- De bestemming is per omgeving en per deployment instelbaar zonder rebuild — precies wat de eis
  "geen aparte versie van de proeftuin" (#937) vraagt.
- Het pad blijft één-op-één. `proxy_pass $backend$request_uri` doet geen rewrite, dus
  `/api/v1/berichten` in de browser is `/api/v1/berichten` op de uitvraag. De contracttests die we
  hebben (`swagger-request-validator`) dekken daarmee precies het verkeer dat de proeftuin doet.

**Wat het kost.** Twee dingen. De `X-Ontvanger`-waarde staat nog steeds in de browser — een
demo-concessie die met #552 vervalt; zie "De identiteit" voor hoe we hem begrenzen. En de proxy moet
de SSE-stream ongebufferd doorlaten, anders vervalt de per-magazijn voortgang tot één blokkerend
verzoek dat pas antwoordt als het traagste magazijn klaar is. Dat is bij nginx een kwestie van de
instellingen uit `location /chat` overnemen; bij `http-proxy-middleware` (dev) is streamen het
standaardgedrag. In beide gevallen is het een expliciet verificatiepunt bij oplevering, geen aanname.

### Overwogen alternatieven

| Alternatief | Waarom niet |
|---|---|
| Rechtstreeks browser → uitvraag met CORS-allowlist | Werkt (de wegwerp-UI doet het), maar levert een allowlist die per origin en per preview-deployment onderhouden moet worden en die stil breekt. Blijft de terugvaloptie, zie "CORS als terugvaloptie". |
| Een BFF in `demo-console` | Een proxy zonder eigen logica hebben we al; een laag mét eigen logica maakt de wegwerp-console tot productieonderdeel van de demo-UI en zet een tweede contract naast de spec. |
| Build-time export van de keten naar de site | De proeftuin toont dan een momentopname. "Engine voert bericht op → zichtbaar in de proeftuin" is dan onhaalbaar; dat is acceptatiecriterium 2. |
| Server-side ophalen in Eleventy | Eleventy rendert statisch bij de build; hetzelfde bezwaar. |

## Twee bronnen naast elkaar: keten of gegenereerde dataset

De gegenereerde dataset verdwijnt niet. De proeftuin houdt beide bronnen en kiest er expliciet één:

- **Keten** — de koppeling uit dit ontwerp. Toont echte magazijnen, echte voortgang, echte uitval.
- **Gegenereerde dataset** — de huidige `_data/berichtenboxData.js`. Werkt zonder netwerk, zonder
  keten en zonder hosting.

De keuze hoort zichtbaar te zijn waar de demo hem nodig heeft: als eigenschap van de proefpersoon
(sommige persona's wijzen naar de keten, andere naar de dataset), zodat één demo beide naast elkaar
kan tonen, en als schakelaar voor het geval de keten of de hosting het laat afweten. Dan gaat de demo
gewoon door.

**Het verschil met stil terugvallen.** Een gekozen bron is eerlijk: de bezoeker of de demonstrateur
weet wat hij ziet. Een pagina die bij een storing ongevraagd op de dataset terugvalt, is dat niet — die
toont verzonnen berichten alsof ze uit de keten komen, en verbergt precies het gedrag dat de engine wil
laten zien. Schakelen mag, automatisch en onaangekondigd schakelen niet. Bij een storing in de
keten-modus hoort de melding uit "Foutafhandeling", eventueel met een knop die zichtbaar naar de
dataset overstapt.

## De identiteit: id in de proeftuin, waarde uit de keten

De proeftuin heeft geen inlogkoppeling, dus er is een demo-identiteit nodig. Twee dingen sturen de
vorm.

**`moza-poc` is een publiek repo.** Een identificatienummer dat daar in de git-historie belandt, is
niet meer terug te nemen. Dat geldt ook voor fictieve BSN's: een BSN-vormige waarde met een geldige
elfproef is niet als "verzonnen" te herkennen door wie hem later tegenkomt. Daarom komt er geen
enkel identificatienummer in dat repo.

**Het hoeft ook niet.** `X-Ontvanger` accepteert `^(BSN|RSIN|KVK|OIN):[0-9]+$`. De persona's van de
proeftuin zijn bedrijven — `_data/personas.json` draagt al `kvkNummer` en `rsinNummer` — dus een
zakelijke Berichtenbox kan volledig op `KVK:` draaien. BSN-persona's zijn in deze koppeling niet nodig.

**Vorm.** De proeftuin kent alleen de persona-`id` (`"bloom"`). Bij het laden van de pagina haalt hij
de lijst op bij de keten, over dezelfde proxy:

```
GET /api/demo/personas
→ [ { "id": "bloom", "label": "Bloom B.V. — Adviseur", "ontvanger": "KVK:…", "bron": "keten" }, … ]
```

De pagina gebruikt `ontvanger` als headerwaarde, toont `label` in de keuzelijst en leest in `bron`
welke van de twee bronnen bij die persona hoort. Het nummer staat daarmee in ons repo en in de
configuratie van de keten, niet in de publieke site. Het endpoint landt in `demo-console` — het is
demo-gereedschap en hoort niet in de spec van de uitvraag.

Deze constructie is expliciet tijdelijk. Zodra er eHerkenning of een andere authenticatie is (#552),
komt de ontvanger uit de sessie en vervalt zowel de lijst als de keuzelijst.

## Het koppelvlak

Geen proeftuin-specifieke endpoints en geen aparte antwoordvorm: de proeftuin leest de spec zoals die
er is.

| Endpoint | Waarvoor | Bijzonderheid |
|---|---|---|
| `GET /api/v1/berichten/_ophalen` | ophaalronde starten, voortgang tonen | SSE. Via `fetch` met een reader, niet via `EventSource` — die kan geen `X-Ontvanger` meesturen. Precedent: `berichtenbox.js`. Vereist een ongebufferde proxy. |
| `GET /api/v1/berichten` | de lijst, gepagineerd | Leest de sessiecache; geen nieuwe ophaalronde. HAL `_links`; `next` ontbreekt op de laatste pagina. |
| `GET /api/v1/berichten/_zoeken` | zoeken | |
| `GET /api/v1/berichten/{id}` | detail | |
| `PATCH /api/v1/berichten/{id}` | gelezen / map | Vraagt echte state in het magazijn; zie de samenhang met #938. |
| `DELETE /api/v1/berichten/{id}` | verwijderen | Soft-delete. |
| `GET …/bijlagen/{bijlageId}` | bijlage | Via `fetch`, niet via `<a href>`: een gewone link stuurt de header niet mee. |
| `GET /api/demo/personas` | demo-identiteiten | Nieuw, in `demo-console`. Het enige dat wij toevoegen. |

De organisatienamen komen uit de stream mee: `magazijn-bevraging-gestart` en
`magazijn-bevraging-voltooid` dragen `magazijnId` (de afzender-OIN) en `naam`. De proeftuin hoeft dus
geen eigen namenlijst te onderhouden — en de naam die hij toont is de naam uit het magazijnregister.

## Nieuwe berichten zichtbaar maken

Dit hoort bij de koppeling, niet erbuiten: acceptatiecriterium 2 is "engine voert een bericht op →
zichtbaar in de proeftuin", en dat is alleen overtuigend als de pagina het uit zichzelf laat zien.

**Het aanmeld-pad bestaat al, op één stap na.** Een magazijn publiceert een nieuw bericht als
CloudEvent naar `POST /api/v1/aanmeldingen` op de uitvraag (in compose loopt dat pad langs Toxiproxy
naar de échte uitvraag-webhook). De uitvraag schrijft het bericht meteen in de sessiecache van de ontvanger, mits
die een actieve sessie heeft; het is idempotent op het CloudEvents-`id`. Een nieuw bericht staat dus
binnen een seconde na het opvoeren in de cache, zónder nieuwe ophaalronde. Wat ontbreekt is
uitsluitend de laatste hop: uitvraag → browser.

**Nu: de proeftuin ververst zichzelf.** Zolang de Berichtenbox open staat, haalt hij periodiek
`GET /api/v1/berichten` op en vergelijkt de lijst. Dat kost geen enkel nieuw endpoint, leest de cache
(dus geen magazijn-verkeer), en houdt de sessie warm — elke succesvolle lees verlengt de sliding TTL,
die in het demo-profiel op `PT2M` staat. Nieuwe berichten meldt de pagina zichtbaar ("2 nieuwe
berichten") in plaats van ze stil in de lijst te schuiven; dat is wat je bij een demo wilt kunnen
aanwijzen.

**Later: de aanmelding doorzetten tot in de browser.** Een SSE-endpoint op de uitvraag waarop de
browser wacht tot er iets in de sessiecache verandert, haalt de vertraging en het polling-verkeer weg. Dat is wél een toevoeging aan
de spec en aan het gedragspad van een productiedienst, dus het hoort een eigen afweging te krijgen —
inclusief de vraag wat er met dat endpoint gebeurt als de sessie verloopt of de cache wegvalt. Het
verversen hierboven is de stap die de demo nú compleet maakt; de aanmelding doorzetten is de nette
eindvorm.

Wat hier níét onder valt: notificaties buiten de pagina om (e-mail, push naar een toestel). Dat is de
notificatiedienst en een eigen vraagstuk.

## Configuratie: één build, per omgeving instelbaar

De bestemming van de keten is een **runtime**-instelling. Een `process.env` in `.eleventy.js` levert
per omgeving een andere build op, en dan is de proeftuin-online een ander artefact dan de
proeftuin-lokaal — precies wat #937 uitsluit.

| Opstelling | Waar de bestemming staat |
|---|---|
| Container (ZAD, en lokaal in compose) | env-var op de container, à la `BACKEND_PROFIEL`; nginx zet `/api/v1/` en `/api/demo/` door |
| `eleventy --serve` | `PROXY_TARGET` op `server/proxy.js` |
| Tijdens een demo, ad hoc | de bestaande per-request-override van de dev-proxy (`?target=`), of een gelijkwaardige override in de container-opstelling |

**Ook naar een preview kunnen wijzen.** De baseline `test` van `mpfb-8wh` is de vanzelfsprekende
default: die URL is stabiel en previews erven de configuratie via `clone-from: test`. Maar het moet
instelbaar blijven — een `pr-<n>`-deployment van de keten tegen de proeftuin aanzetten is precies wat
je wilt kunnen doen om een wijziging te beproeven vóór hij op main staat. Omdat de bestemming een
env-var per deployment is, kost dat niets: een preview van de proeftuin kan naar een preview van de
keten wijzen zonder dat een van beide opnieuw gebouwd wordt.

Let op bij de container: `NGINX_ENVSUBST_FILTER` in de `Containerfile` staat vandaag op
`^(BACKEND_ORIGIN|NGINX_LOCAL_RESOLVERS)$`. Een nieuwe backend-variabele die daar niet in staat, wordt
niet gesubstitueerd en belandt letterlijk in de nginx-config. Dat geldt nu al voor `BACKEND_PROFIEL`
en `BACKEND_API_2` uit de template.

## De twee opstellingen

| | Lokaal | Online (ZAD) |
|---|---|---|
| Keten | `docker compose --profile demo up`; uitvraag op `:8086`, demo-console op `:8095` | `berichtenuitvraag`, project `mpfb-8wh`, deployment `test` (of een preview, zie boven) |
| Proeftuin | dezelfde container, als service in ons `demo`-profiel | project `pm-5sj`, component `proef` |
| Bestemming | env-var wijst naar `http://berichtenuitvraag:8086` / `http://demo-console:8095` binnen het compose-netwerk | de publieke adressen van uitvraag en demo-console |
| https | niet vereist | de ZAD-ingress levert het |

**Lokaal is een container, geen installatie.** De proeftuin publiceert twee packages: per PR een
preview (`ghcr.io/minbzk/moza-poc/preview:pr-<n>-<sha>`) en op elke push naar `main` een stabiel
image (`ghcr.io/minbzk/moza-poc:latest` plus een onveranderlijke `sha-<7>`), met daarnaast
release-tags uit `release.yml`. Een `sha-`-tag pinnen in `compose.yaml` onder het bestaande
`demo`-profiel maakt hem meteen opneembaar; `latest` verschuift stil onder de demo door en is
daarom niet de juiste keuze. Wie de demo draait heeft dan geen Node, geen `npm ci` en geen Eleventy
op zijn machine nodig — `docker compose --profile demo up` start de hele demo inclusief Berichtenbox. Dat de proeftuin binnen hetzelfde compose-netwerk zit, maakt de bestemming
bovendien een containernaam in plaats van een host-poort. `npm run dev` blijft er gewoon naast staan
voor wie aan de proeftuin zelf werkt.

**Wat er vandaag al doorheen komt.** De nginx van de proeftuin heeft al een `location /api/` die
naar `BACKEND_ORIGIN` doorzet. Het leespad (`/api/v1/...`, inclusief de SSE-stream en de
`X-Ontvanger`-header) is dus te beproeven zonder één regel in `moza-poc` te wijzigen — en daarmee de
bufferingsval die dit ontwerp als het grootste risico aanwijst. Wat wél een wijziging daar vraagt is
`/api/demo/personas`: dat pad valt onder dezelfde `location /api/` en zou bij de uitvraag uitkomen.
Een tweede bestemming is bovendien nu onmogelijk, want `NGINX_ENVSUBST_FILTER` laat alleen
`BACKEND_ORIGIN` door — zie "Configuratie".

**Het leespad online is niet geblokkeerd door #936.** De uitvraag draait al op ZAD; een werkende
online koppeling kan er dus zijn vóórdat de bediening daar staat. Wat wél op #936 wacht, is
acceptatiecriterium 2 in de online opstelling: engine-acties zijn pas online zichtbaar als de
demo-console (en daarmee `/api/demo/personas` en het opvoeren van berichten) daar draait. Tot die tijd
toont de online proeftuin echte keten-data uit een met de hand gevulde omgeving.

## CORS als terugvaloptie

Met een same-origin proxy is CORS niet nodig. Voor een opstelling waarin de pagina tóch rechtstreeks
op de uitvraag uitkomt — de wegwerp-UI doet dat vandaag, en een proeftuin-dev die zonder proxy werkt
kan het willen — blijft de allowlist bestaan. Twee dingen daarbij:

- **Wildcards kunnen**, maar het zijn regexes, geen globs: `quarkus.http.cors.origins` behandelt een
  waarde die met `/` begint en eindigt als reguliere expressie (de slashes worden gestript, de rest
  gaat door `Pattern.compile`). Eén regel dekt dan alle preview-origins van de proeftuin. De match is
  een volledige match op de hele origin, dus ankers zijn niet nodig — de val zit in een te gulle `.*`
  en in onontsnapte punten: `/https://.*\.rijksapp\.dev/` laat élke deployment in dat domein toe,
  niet alleen die van de proeftuin.
- **Het blijft het demo-profiel.** De uitvraag heeft buiten compose geen CORS-configuratie en dat
  hoort zo te blijven; een allowlist die per ongeluk in `application.properties` belandt, geldt ook
  in productie.

## Foutafhandeling in de proeftuin

Acceptatiecriterium 6 vraagt een begrijpelijke melding in plaats van een lege of kapotte pagina. De
keten levert daar genoeg voor; het is een kwestie van de gevallen uit elkaar houden. **Het tonen is
werk van het proeftuin-team**; onze taak is dat de gevallen ondubbelzinnig op tafel liggen. Deze tabel
gaat daarom letterlijk mee in het overdracht-issue en in de PR op de proeftuin, met voorbeeld-JSON per
regel.

| Situatie | Wat de keten doet | Wat de proeftuin toont |
|---|---|---|
| Keten onbereikbaar | `fetch` faalt zonder status, of de proxy geeft `502`/`504` | "De berichtenketen is niet bereikbaar" + de ingestelde bestemming |
| Ophaalronde al bezig | `409` vóór de stream | wachten en opnieuw proberen, geen foutmelding |
| Cache onbereikbaar | `503` vóór de stream | expliciete melding; dit is het Redis-uit-scenario van de engine |
| Eén of meer magazijnen stuk of traag | `magazijn-bevraging-voltooid` met `status: FOUT` of `TIMEOUT` | de lijst tóón je, met "3 van de 15 organisaties reageerden niet" erbij |
| Ophaalronde als geheel mislukt | `ophalen-fout` met `referentie` | melding met die referentie erin — dat is het support-anker in onze logs |
| Stream stopt zonder eindevent | geen `ophalen-gereed` | als mislukt behandelen; de spec zegt expliciet dat de status op `200` vaststaat zodra de stream loopt |
| Origin niet toegestaan (alleen zónder proxy) | in de browser niet te onderscheiden van onbereikbaar | zelfde melding; noem CORS als eerste verdenking in de handleiding |

De gedeeltelijke uitval is de belangrijkste regel in die tabel: dat is het scenario waarvoor de
koppeling bestaat. De engine zet een magazijn traag of uit, en de bezoeker ziet in de proeftuin dat
één organisatie ontbreekt — niet een spinner die nooit stopt.

## Samenhang met de magazijn-simulator (#938)

Het simulator-ontwerp en dit ontwerp spreken elkaar nergens tegen. Ze raken elkaar op vier punten,
waarvan er drie een openstaande beslissing van het andere document invullen.

**1. De persona's.** Openstaande beslissing 3 in het simulator-ontwerp vraagt welke persona's we
overnemen "uit de proeftuin en de standaard-persona's". Dit ontwerp maakt dat concreet:
`_data/personas.json` in de proeftuin is de bestaande set, met `id`, `label`, `kvkNummer` en
`rsinNummer`. De simulator hangt alleen aan de *omvang* van de fan-out (3 / 15 / 45 / 100), dus die
groottes koppelen aan bestaande proeftuin-persona's kost niets zolang het vóór stap 5 van dat
ontwerp gebeurt. De verzonnen KVK-nummers `90000001`–`90000003` uit dat document vervallen dan.

**2. De magazijnnamen.** Het simulator-ontwerp geeft de 98 gesimuleerde magazijnen de naam
"Demo-magazijn *i*". Dat werkt in een bedieningspaneel, maar niet in de proeftuin: daar staat de naam
uit het register in de berichtenlijst, naast Belastingdienst en Kamer van Koophandel. De
mock-dataset van de proeftuin toont vandaag elf echte instanties plus drie gemeentes. Het
generatiescript moet dus echte organisatienamen leveren — gemeentes zijn de voor de hand liggende
bron voor de lange staart. Dit raakt alleen het script, niet het ontwerp eromheen.

**3. Wat er publiek mag staan.** Het simulator-ontwerp wil `/beheer` — het beheerpad van de
*simulator* — helemaal niet publiceren, en houdt daarvoor demo-console en simulator liefst in
hetzelfde ZAD-project, waar componenten elkaar intern bereiken. Dit ontwerp zet daar één eis
tegenover: `/api/demo/personas` moet wél van buiten bereikbaar zijn, want de proeftuin draait in een
ander project en op ZAD isoleert de tenant-NetworkPolicy per deployment — cross-project verkeer loopt
over de publieke route.

Die twee botsen niet, want het zijn verschillende componenten. Waar ze wél botsen is *binnen* de
demo-console: `/api/demo/personas` moet publiek, terwijl onder datzelfde prefix `POST /api/demo/legen`
staat (TRUNCATE op beide magazijn-databases), plus `random`, `storing/*` en `ontdubbeling`. Lokaal is
dat afgedekt doordat compose de console alleen op `127.0.0.1` publiceert; op ZAD bestaat die knop niet.

**Vorm die daaruit volgt.** Op ZAD is publiek-of-niet een eigenschap van een *component*, niet van een
pad. Eén artefact, twee keer uitgerold als twee componenten die alleen in configuratie verschillen:
één met een publieke route die uitsluitend het leespad aanzet, één zonder route met de bedienings- en
storingsacties. Twee dingen horen daarbij:

- **Het leespad krijgt een eigen prefix**, los van `/api/demo/`. Zolang publiek en destructief onder
  hetzelfde prefix zitten, is elke routeringsregel een handmatige uitzonderingslijst en is één fout
  genoeg.
- **De code doet dezelfde scheiding.** Het publieke component zet de beheer-endpoints uit via
  configuratie, zodat een fout in de routering geen TRUNCATE-endpoint blootlegt. Een tweede HTTP-poort
  binnen het artefact kan ook — Quarkus biedt daarvoor `ManagementInterface`, waarmee je routes op
  `quarkus.management.port` hangt — maar dat werkt met Vert.x-routes, en onze endpoints zijn JAX-RS
  (`@Path("/api/demo")`). Dat zou een herschrijving betekenen voor een scheiding die de
  component-splitsing plus een configuratievlag ook levert.

Deze eis is scherper dan die van het simulator-ontwerp en vervangt hem niet: `/beheer` van de
simulator blijft ongepubliceerd, en het enige dat in het demo-project publiek hoort te staan is de
personalijst.

**4. De onvolledige lijst (#996, #1038).** De uitvraag haalt per magazijn één pagina van twintig
berichten op (#996), en bij veel aangesloten organisaties valt er nog meer buiten de lijst (#1038,
waarin #997 is opgegaan). In de wegwerp-UI valt dat nauwelijks op; in de proeftuin, waar de mock 120
berichten over veertien organisaties toont, wordt het direct zichtbaar — en met de simulator erbij
(45 of 100 magazijnen) wordt #1038 het opvallendst van de twee.

Dit is een volgorde-afhankelijkheid, geen bezwaar: landen #996 en #1038 vóór de koppeling een
stakeholder bereikt, dan is er niets om te weten. Beide staan vandaag open en in refine, dus tot dat
moment hoort de beperking bekend te zijn bij wie de demo geeft. Dat een echte koppeling dit zichtbaar
maakt terwijl een mock-dataset het verbergt, is precies het argument vóór koppelen.

Er is één volgorde-verschil dat het vermelden waard is: stap 7 van het simulator-ontwerp (ZAD) is
geblokkeerd door #936, dit ontwerp is dat voor het leespad niet. De online koppeling kan dus eerder
staan dan de honderd magazijnen, en toont dan de twee echte magazijnen. Dat is een prima
tussenstation en geen halve oplevering.

## Wat er verandert, per repo

| Waar | Wat |
|---|---|
| `demo/demo-console` | `GET /api/demo/personas`; persona's uit configuratie, inclusief `bron`. Later: `berichtenbox.{html,css,js}` weg, `/beheer` blijft |
| `compose.yaml` | proeftuin-container als service in het `demo`-profiel, met de bestemming naar uitvraag en demo-console |
| ZAD-projectspec `mpfb-8wh` | niets, zolang de proeftuin via zijn eigen proxy binnenkomt; de uitvraag moet publiek bereikbaar zijn |
| `docs/ontwikkelen.md` | hoe je de demo mét proeftuin start, en hoe je hem op een preview van de keten richt |
| `MinBZK/moza-poc` | proxy-`location`s voor `/api/v1/` en `/api/demo/`, Berichtenbox-pagina op het koppelvlak, bronkeuze, foutstaten, verversen, README-stappen |

Aan de keten-services zelf verandert niets: geen nieuwe endpoints in de spec, geen demo-logica in het
gedragspad. Dat is dezelfde lijn als het demo-platform-ontwerp trekt.

## Testen

- **Personalijst** — lege lijst, één persona, meerdere persona's; onbekende `id`; een persona met een
  `ontvanger`-waarde die niet aan het patroon van de header voldoet moet bij het starten falen, niet
  pas bij de eerste aanroep.
- **Proxy-doorgifte** — met de proeftuin-container in compose: `X-Ontvanger` komt aan, en de
  SSE-stream levert de per-magazijn events *tijdens* de ronde, niet in één klap aan het eind. Dat
  laatste is de test die de bufferingsval vangt.
- **Verversen** — een bericht opvoeren tijdens een open sessie verschijnt binnen het pollinterval,
  zonder nieuwe ophaalronde. Dekt meteen dat de aanmeld-webhook doet wat we aannemen.
- **CORS-allowlist** — alleen zolang de terugvaloptie bestaat: preflight vanaf een toegestane origin
  slaagt, vanaf een willekeurige andere niet, en een regex-patroon matcht niet buiten het domein.
- **Contract** — bestaand. De proeftuin gebruikt geen enkel endpoint dat niet al door
  `swagger-request-validator` gedekt is; wat erbij komt is de personalijst.
- **Foutstaten in de proeftuin** — aan die kant, tegen voorbeeld-JSON: partial failure, `409`, `503`,
  stream zonder eindevent. Die vier zijn met een fixture te bouwen en hoeven niet op een draaiende
  keten te wachten.

`demo-console` valt vandaag buiten de JaCoCo-gate. Het personalijst-endpoint komt daar dus zonder
dekkingseis binnen. Dat gat is geen vondst van dit ontwerp — het simulator-ontwerp signaleert het ook
— maar het is wel de tweede keer dat het opduikt, en dat verdient een eigen issue.

## Stappen

1. **Koppelvlak vastleggen en overdragen.** Eén sessie met de proeftuin-ontwikkelaar: endpoints,
   foutvorm, persona-vorm, bronkeuze, wie welke melding toont. Levert een overdracht-issue op de
   proeftuin op met de foutentabel en voorbeeld-JSON, zodat dat werk kan starten zonder draaiende
   keten.
2. **Personalijst.** `GET /api/demo/personas` in `demo-console`, gevuld uit configuratie, afgestemd
   op `_data/personas.json`. Verificatie: de wegwerp-UI kan zijn eigen keuzelijst eruit halen.
3. **PR op de proeftuin.** Wij openen hem zelf, met de proxy-`location`s, de bronkeuze en een
   Berichtenbox die op het koppelvlak praat. Zo staat het voorstel in code in plaats van in een
   document, en houdt het proeftuin-team de regie over vormgeving en review.
4. **Proeftuin in compose, wegwerp-Berichtenbox eruit.** Stabiele image-tag afspreken, container als
   service in het `demo`-profiel, bestemming naar uitvraag en demo-console. Verificatie:
   acceptatiecriteria 1, 2 en 3 in de lokale opstelling, zonder Node op de machine. Zodra die groen
   zijn: `berichtenbox.{html,css,js}` uit de demo-console, `/beheer` blijft. Niet eerder — pas als de
   proeftuin het aantoonbaar overneemt.
5. **Verversen.** Periodiek lezen plus zichtbare melding van nieuwe berichten. Verificatie: engine
   voert een bericht op, de pagina toont het uit zichzelf.
6. **Online leespad.** Bestemming op de proeftuin-omgeving naar `mpfb-8wh`/`test`, plus de
   instelbaarheid richting een preview. Verificatie: het leesdeel van acceptatiecriterium 4.
7. **Online engine-acties.** Na #936: demo-console op ZAD, personalijst en het opvoeren van berichten
   online. Verificatie: acceptatiecriterium 2 in de online opstelling.
8. **Documentatie in beide repo's.** Acceptatiecriterium 5.

Stap 1 tot en met 5 leveren de lokale koppeling en zijn niet van ander werk afhankelijk. Stap 6 kan
daar direct achteraan. Alleen stap 7 wacht op #936.

Elke stap wordt een sub-issue onder #937, zodat het werk in beide repo's op één bord staat.

## Bewust buiten scope

- **Inloggen.** De persona-keuzelijst is een demo-constructie; echte authenticatie hoort bij #552.
- **Aanleveren vanuit de proeftuin.** De proeftuin leest; berichten opvoeren blijft de engine.
- **Vormgeving en toegankelijkheid van de pagina.** Dat is het vak van de proeftuin, inclusief NL
  Design System en WCAG. Wij leveren data, foutsemantiek en voorbeeld-JSON.
- **Notificaties buiten de pagina om.** E-mail of push naar een toestel is de notificatiedienst, niet
  deze koppeling. Nieuwe berichten binnen een open Berichtenbox vallen er wél onder; zie "Nieuwe
  berichten zichtbaar maken".
- **Het bedieningspaneel vervangen.** `/beheer` in de demo-console blijft; dat is de engine. Alleen
  de wegwerp-Berichtenbox ernaast verdwijnt, en dat staat in stap 4 in plaats van hier.

## Openstaande beslissingen

1. Welke persona's en welke nummers? Afstemmen met stap 5 van het simulator-ontwerp en met het
   persona-werk dat elders loopt, zodat er geen derde set ontstaat.
2. Welke image-tag van de proeftuin pinnen we in compose — een `sha-`-tag of een release-tag? Beide
   bestaan al, dus dit blokkeert niets; het is een keuze tussen "volgt main op de voet" en "volgt de
   release-cadans". Wat blijft staan is de afhankelijkheid die ontstaat door de wegwerp-Berichtenbox
   op te ruimen: zonder eigen UI staat of valt de lokale demo met een image uit een ander repo. Zet
   de tag daarom achter een env-var, zodat omzetten één regel is.
3. Toont de proeftuin de voortgang per organisatie tijdens het ophalen, of alleen het eindresultaat?
   Dat bepaalt of trage magazijnen zichtbaar zijn of alleen merkbaar als wachttijd.
4. Wat is het pollinterval bij het verversen, en stopt het als het tabblad niet zichtbaar is?
5. Welk prefix krijgt het publieke leespad, en hoe heten de twee componenten? Nodig om personas
   publiek te kunnen zetten zonder `POST /api/demo/legen` mee te publiceren; zie punt 3 hierboven.
   Dat de proeftuin daardoor tegenover twee bestemmingen staat (uitvraag en demo-console) is met de
   proxy geen bezwaar: twee `location`s, geen twee origins in een allowlist.
