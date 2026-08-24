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

Aan onze kant bestaat al een tweede Berichtenbox: de wegwerp-UI in `services/demo-console` (`:8095`,
fase 2a). Die praat wél met de keten, en doet dat **rechtstreeks vanuit de browser**:
`berichtenbox.js` zet `X-Ontvanger` op elke `fetch` naar `http://<host>:8086/api/v1`, en de uitvraag
heeft daarvoor een CORS-allowlist die alleen in het compose-demo-profiel aan staat
(`QUARKUS_HTTP_CORS_ENABLED` + `QUARKUS_HTTP_CORS_ORIGINS`). In `application.properties` staat geen
CORS-regel; buiten de demo is de uitvraag dus CORS-loos.

Dat is het fundament waarop dit ontwerp voortbouwt: het pad browser → uitvraag is al gebouwd,
inclusief de SSE-voortgang en de bijlage-download. Wat ontbreekt is een tweede consument van dat pad
op een ander domein, en een identiteit die niet uit een keuzelijst in onze eigen console komt.

**De twee UI's blijven naast elkaar bestaan, met verschillende doelgroepen.** De proeftuin is wat een
stakeholder ziet; de wegwerp-UI hoort bij het bedieningspaneel en is onze eigen verificatie dat de
keten werkt zónder de proeftuin ertussen. Of die tweede kan vervallen zodra de proeftuin het
overneemt, staat als openstaand punt.

## Begrippen

| Term | Betekenis in dit document |
|---|---|
| proeftuin | De Eleventy-site `MinBZK/moza-poc`, publiek repo, apart gehost. |
| keten | Onze services samen: uitvraag, magazijnen, sessiecache, stubs. |
| koppelvlak | De verzameling keten-endpoints die de proeftuin aanroept, plus de personalijst. |
| persona | Een demo-identiteit: een label dat de proeftuin toont en een `X-Ontvanger`-waarde die de keten kent. |
| substream | De voortgangsregels van één magazijn binnen één ophaalronde. |
| opstelling | Lokaal (alles op de eigen machine) of online (proeftuin-omgeving tegen de keten op ZAD). |

## Besluit: de proeftuin praat rechtstreeks met de uitvraag

**Beslissing.** De Berichtenbox-pagina in de proeftuin roept de uitvraag-API rechtstreeks aan vanuit
de browser, met dezelfde spec-endpoints en dezelfde `X-Ontvanger`-header als de wegwerp-UI. Wij
breiden de CORS-allowlist van de uitvraag uit met de proeftuin-origin. Er komt geen tussenlaag.

**Waarom.**

- Het pad bestaat al en is bewezen: dezelfde `fetch`-aanpak, dezelfde header, dezelfde CORS-schakelaar.
  Een tweede consument kost dan een regel in een allowlist, geen nieuwe component.
- `_ophalen` is een SSE-stream. Een tussenlaag moet die streamend doorgeven, anders vervalt precies
  het gedrag dat we willen tonen — per-magazijn voortgang — tot een blokkerend verzoek dat pas
  antwoordt als het traagste magazijn klaar is. Een streamende proxy is echt werk en levert niets op.
- De acceptatiecriteria vragen dat de proeftuin degradatie *toont*. Hoe dichter de pagina op de
  echte stream zit, hoe minder er onderweg verloren gaat.

**Wat het kost.** De `X-Ontvanger`-waarde staat in de browser, en elke origin die de pagina serveert
moet in de allowlist staan. Het eerste is een demo-concessie die met #552 vervalt; het tweede is een
regel per omgeving. Zie "De identiteit" voor hoe we het eerste begrenzen.

### Overwogen alternatieven

| Alternatief | Waarom niet |
|---|---|
| BFF/proxy in `demo-console` | Extra hop in het datapad, en de SSE-stream moet er streamend doorheen. Lost alleen het identiteitsprobleem op, en dat lossen we goedkoper op. Zou de demo-console bovendien tot productieonderdeel van de demo-UI maken terwijl hij wegwerp is. |
| Build-time export van de keten naar de site | De proeftuin toont dan een momentopname. "Engine voert bericht op → zichtbaar in de proeftuin" is dan onhaalbaar; dat is acceptatiecriterium 2. |
| Server-side ophalen in Eleventy | Eleventy rendert statisch bij de build; hetzelfde bezwaar. Een Node-laag ernaast toevoegen maakt de proeftuin een applicatie in plaats van een site. |

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
de lijst op bij de keten:

```
GET /api/demo/personas
→ [ { "id": "bloom", "label": "Bloom B.V. — Adviseur", "ontvanger": "KVK:…" }, … ]
```

De pagina gebruikt `ontvanger` als headerwaarde en toont `label` in de keuzelijst. Het nummer staat
daarmee in ons repo en in de configuratie van de keten, niet in de publieke site. Het endpoint landt
in `demo-console` — het is demo-gereedschap en hoort niet in de spec van de uitvraag.

Deze constructie is expliciet tijdelijk. Zodra er eHerkenning of een andere authenticatie is (#552),
komt de ontvanger uit de sessie en vervalt zowel de lijst als de keuzelijst.

## Het koppelvlak

Geen proeftuin-specifieke endpoints en geen aparte antwoordvorm: de proeftuin leest de spec zoals die
er is. Dat houdt de contracttests die we al hebben (`swagger-request-validator`) geldig voor precies
het verkeer dat de proeftuin doet.

| Endpoint | Waarvoor | Bijzonderheid |
|---|---|---|
| `GET /api/v1/berichten/_ophalen` | ophaalronde starten, voortgang tonen | SSE. Via `fetch` met een reader, niet via `EventSource` — die kan geen `X-Ontvanger` meesturen. Precedent: `berichtenbox.js`. |
| `GET /api/v1/berichten` | de lijst, gepagineerd | HAL `_links`; `next` ontbreekt op de laatste pagina. |
| `GET /api/v1/berichten/_zoeken` | zoeken | |
| `GET /api/v1/berichten/{id}` | detail | |
| `PATCH /api/v1/berichten/{id}` | gelezen / map | Vraagt echte state in het magazijn; zie de samenhang met #938. |
| `DELETE /api/v1/berichten/{id}` | verwijderen | Soft-delete. |
| `GET …/bijlagen/{bijlageId}` | bijlage | Via `fetch`, niet via `<a href>`: een gewone link stuurt de header niet mee. |
| `GET /api/demo/personas` | demo-identiteiten | Nieuw, in `demo-console`. Het enige dat wij toevoegen. |

De organisatienamen komen uit de stream mee: `magazijn-bevraging-gestart` en
`magazijn-bevraging-voltooid` dragen `magazijnId` (de afzender-OIN) en `naam`. De proeftuin hoeft dus
geen eigen namenlijst te onderhouden — en de naam die hij toont is de naam uit het magazijnregister.

## Configuratie: één instelling, één build

`ketenBasisUrl` bepaalt de opstelling. Leeg of afwezig = de huidige gegenereerde mock; gezet = de
keten op dat adres.

De eis "geen aparte versie van de proeftuin" (#937) maakt dit een **runtime**-instelling, geen
build-time-variabele. Een `process.env` in `.eleventy.js` levert per omgeving een andere build op, en
dan is de proeftuin-online een ander artefact dan de proeftuin-lokaal. De vorm — een statisch
`keten.json` dat de container kan overschrijven, een `<meta>`-tag, of een querystring-override voor
tijdens een demo — is aan de proeftuin; het criterium is dat één build beide opstellingen bedient.

## De twee opstellingen

| | Lokaal | Online (ZAD) |
|---|---|---|
| Keten | `docker compose --profile demo up`; uitvraag op `:8086` | `berichtenuitvraag`, project `mpfb-8wh`, deployment `test` |
| Proeftuin | lokale Eleventy-server | de proeftuin-omgeving |
| `ketenBasisUrl` | `http://localhost:8086/api/v1` | de publieke ingress-URL van de uitvraag |
| CORS | origin erbij in het compose-demo-profiel | origin erbij in de OM-projectspec van `mpfb-8wh` |
| https | niet vereist | de ZAD-ingress levert het |

**Koppel online aan `test`, niet aan een preview.** De URL van een `pr-<n>`-deployment verandert per
PR; de baseline `test` is stabiel. Previews erven de configuratie via `clone-from: test`.

**Het leespad online is niet geblokkeerd door #936.** De uitvraag draait al op ZAD; er is dus een
werkende online koppeling mogelijk vóórdat de bediening daar staat. Wat wél op #936 wacht, is
acceptatiecriterium 2 in de online opstelling: engine-acties zijn pas online zichtbaar als de
demo-console (en daarmee `/api/demo/personas` en het opvoeren van berichten) daar draait. Tot die tijd
toont de online proeftuin echte keten-data uit een met de hand gevulde omgeving.

## Foutafhandeling in de proeftuin

Acceptatiecriterium 6 vraagt een begrijpelijke melding in plaats van een lege of kapotte pagina. De
keten levert daar genoeg voor; het is een kwestie van de gevallen uit elkaar houden.

| Situatie | Wat de keten doet | Wat de proeftuin toont |
|---|---|---|
| Keten onbereikbaar | `fetch` faalt zonder status | "De berichtenketen is niet bereikbaar" + het geprobeerde adres |
| Origin niet in de allowlist | in de browser niet te onderscheiden van onbereikbaar | zelfde melding; noem CORS als eerste verdenking in de handleiding |
| Ophaalronde al bezig | `409` vóór de stream | wachten en opnieuw proberen, geen foutmelding |
| Cache onbereikbaar | `503` vóór de stream | expliciete melding; dit is de Redis-uit-scenario van de engine |
| Eén of meer magazijnen stuk of traag | `magazijn-bevraging-voltooid` met `status: FOUT` of `TIMEOUT` | de lijst tóón je, met "3 van de 15 organisaties reageerden niet" erbij |
| Ophaalronde als geheel mislukt | `ophalen-fout` met `referentie` | melding met die referentie erin — dat is het support-anker in onze logs |
| Stream stopt zonder eindevent | geen `ophalen-gereed` | als mislukt behandelen; de spec zegt expliciet dat de status op `200` vaststaat zodra de stream loopt |

**Niet stilletjes terugvallen op de mock-dataset.** Een pagina die bij een storing de gegenereerde
berichten toont, liegt tegen de stakeholder en verbergt precies wat de engine wil laten zien.

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

**3. Waar `/beheer` landt.** Openstaande beslissing 2 in het simulator-ontwerp gaat over waar de
demo-console op ZAD terechtkomt, met als eis dat `/beheer` intern blijft. Dit ontwerp voegt er een
eis aan toe die de andere kant op wijst: `/api/demo/personas` moet juist wél publiek bereikbaar zijn,
want de proeftuin draait op een ander domein. De demo-console krijgt dus twee soorten pad — een
publiek leespad en een intern beheerpad — en die scheiding hoort in de deployment terug te komen,
niet alleen in de code.

**4. Het paginagat (#996).** De uitvraag haalt per magazijn één pagina van twintig berichten op. In
de wegwerp-UI valt dat nauwelijks op; in de proeftuin, waar de mock 120 berichten toont, wordt het
direct zichtbaar. Dat is geen bezwaar tegen de koppeling — het is precies waarom een echte koppeling
nuttig is — maar het hoort vóór een stakeholder-demo bekend te zijn.

Er is één volgorde-verschil dat het vermelden waard is: stap 7 van het simulator-ontwerp (ZAD) is
geblokkeerd door #936, dit ontwerp is dat voor het leespad niet. De online koppeling kan dus eerder
staan dan de honderd magazijnen, en toont dan de twee echte magazijnen. Dat is een prima
tussenstation en geen halve oplevering.

## Wat er verandert, per repo

| Waar | Wat |
|---|---|
| `services/demo-console` | `GET /api/demo/personas`; persona's uit configuratie |
| `compose.yaml` | proeftuin-origin in `QUARKUS_HTTP_CORS_ORIGINS` van de uitvraag |
| ZAD-projectspec `mpfb-8wh` | dezelfde twee CORS-variabelen voor de proeftuin-origin, op deployment `test` |
| `docs/ontwikkelen.md` | hoe je de proeftuin lokaal tegen de keten zet |
| `MinBZK/moza-poc` | Berichtenbox-pagina op het koppelvlak, foutstaten, `ketenBasisUrl`, README-stappen |

Aan de keten-services zelf verandert niets: geen nieuwe endpoints in de spec, geen demo-logica in het
gedragspad. Dat is dezelfde lijn als het demo-platform-ontwerp trekt.

## Testen

- **CORS-allowlist** — een test die vaststelt dat een preflight vanaf de proeftuin-origin slaagt en
  vanaf een willekeurige andere origin niet. Zonder zo'n test breekt de koppeling stil bij een
  configuratiewijziging, en ziet de proeftuin dat als "keten onbereikbaar".
- **Personalijst** — lege lijst, één persona, meerdere persona's; onbekende `id`; een persona met een
  `ontvanger`-waarde die niet aan het patroon van de header voldoet moet bij het starten falen, niet
  pas bij de eerste aanroep.
- **Contract** — bestaand. De proeftuin gebruikt geen enkel endpoint dat niet al door
  `swagger-request-validator` gedekt is; wat erbij komt is de personalijst.
- **Foutstaten in de proeftuin** — aan die kant, tegen voorbeeld-JSON: partial failure, `409`, `503`,
  stream zonder eindevent. Die vier zijn met een fixture te bouwen en hoeven niet op een draaiende
  keten te wachten.
- **Keten** — `demo/smoke.sh` uitbreiden is hier niet nodig; de koppeling voegt aan de keten niets
  toe dat de smoke-test nog niet dekt.

`demo-console` valt vandaag buiten de JaCoCo-gate. Het personalijst-endpoint komt daar dus zonder
dekkingseis binnen. Dat gat is geen vondst van dit ontwerp — het simulator-ontwerp signaleert het ook
— maar het is wel de tweede keer dat het opduikt, en dat verdient een eigen issue.

## Stappen

1. **Koppelvlak vastleggen.** Eén sessie met de proeftuin-ontwikkelaar: endpoints, foutvorm,
   persona-vorm, wie welke melding toont. Levert voorbeeld-JSON op waarmee de proeftuin verder kan
   zonder draaiende keten.
2. **Personalijst.** `GET /api/demo/personas` in `demo-console`, gevuld uit configuratie, afgestemd
   op `_data/personas.json`. Verificatie: de wegwerp-UI kan zijn eigen keuzelijst eruit halen.
3. **CORS lokaal.** Proeftuin-origin in het compose-demo-profiel, plus de test op de allowlist.
4. **Proeftuin op het koppelvlak.** Pagina, foutstaten, `ketenBasisUrl`. Verificatie: acceptatie-
   criteria 1, 2 en 3 in de lokale opstelling.
5. **Online leespad.** CORS-origin op `mpfb-8wh`/`test`, proeftuin-omgeving wijst erheen.
   Verificatie: het leesdeel van acceptatiecriterium 4.
6. **Online engine-acties.** Na #936: demo-console op ZAD, personalijst en het opvoeren van berichten
   online. Verificatie: acceptatiecriterium 2 in de online opstelling.
7. **Documentatie in beide repo's.** Acceptatiecriterium 5.

Stap 1 tot en met 4 leveren de lokale koppeling en zijn niet van ander werk afhankelijk. Stap 5 kan
daar direct achteraan. Alleen stap 6 wacht op #936.

Elke stap wordt een sub-issue onder #937, zodat het werk in beide repo's op één bord staat.

## Bewust buiten scope

- **Inloggen.** De persona-keuzelijst is een demo-constructie; echte authenticatie hoort bij #552.
- **Aanleveren vanuit de proeftuin.** De proeftuin leest; berichten opvoeren blijft de engine.
- **Vormgeving en toegankelijkheid van de pagina.** Dat is het vak van de proeftuin, inclusief NL
  Design System en WCAG. Wij leveren data en foutsemantiek.
- **De wegwerp-UI vervangen.** Zie de openstaande punten.
- **Notificaties richting de proeftuin.** Live nieuwe berichten pushen (CloudEvents naar de browser)
  is een eigen vraagstuk; dit ontwerp haalt op bij het laden en op verzoek.

## Openstaande beslissingen

1. Welke vorm krijgt `ketenBasisUrl` in de proeftuin — statisch bestand, meta-tag, querystring? Aan
   de proeftuin; het criterium is één build voor beide opstellingen.
2. Welke persona's en welke nummers? Afstemmen met stap 5 van het simulator-ontwerp en met het
   persona-werk dat elders loopt, zodat er geen derde set ontstaat.
3. Op welke origin draait de proeftuin online? Nodig voor de allowlist, en per omgeving anders.
4. Blijft de wegwerp-Berichtenbox in `demo-console` bestaan zodra de proeftuin de demo draagt? Hij
   kost weinig, maar twee UI's onderhouden is een keuze die bewust hoort te vallen.
5. Toont de proeftuin de voortgang per organisatie tijdens het ophalen, of alleen het eindresultaat?
   Dat bepaalt of trage magazijnen zichtbaar zijn of alleen merkbaar als wachttijd.
6. Blijft de personalijst in `demo-console` als de demo-console op ZAD in een ander project landt dan
   de uitvraag? Dan staat de proeftuin tegenover twee publieke adressen in plaats van één.
