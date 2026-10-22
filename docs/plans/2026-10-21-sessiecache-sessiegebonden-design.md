# Sessiecache gekoppeld aan de inlogsessie — ontwerp

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#943. Tussenstand in het issue:
<https://github.com/MinBZK/MijnOverheidZakelijk/issues/943#issuecomment-6312260142>.

## Aanleiding en doel

De sessiecache heet zo, maar kent geen sessie. Berichten van een ontvanger blijven staan tot een
sliding TTL van 12 uur verloopt (`berichtensessiecache.ttl`), ook nadat de gebruiker is
vertrokken. Er is geen pad om ze bij uitloggen te wissen, en geen begrip van "uitloggen".

Doel: de gegevens van een gebruiker bestaan precies zolang hij een lopende inlogsessie heeft, en
zijn weg zodra zijn laatste sessie eindigt. Dataminimalisatie, en een vers beeld na opnieuw
inloggen. Opruimen is nooit dataverlies: de magazijnen zijn de bron.

## Het sessiebegrip

Zo werkt het straks met echte authenticatie, en zo bouwen we het nu al:

- Een gebruiker logt in via een browser of een vakapplicatie. Elke inlog levert een **sessie**
  op (in OIDC: de `sid`). Een browser met meerdere tabs is één sessie; een tweede browser of een
  vakapplicatie is een tweede sessie.
- **De sessie hoort bij de gebruiker, niet bij een apparaat.** Een gebruiker kan meerdere sessies
  tegelijk hebben. Zijn gegevens blijven tot zijn **laatste** sessie eindigt.
- Een sessie eindigt door:
  - **uitloggen** — expliciet, ruimt direct op als het de laatste sessie was;
  - **15 minuten zonder activiteit** (`berichtensessiecache.sessie.inactiviteit=PT15M`);
  - **12 uur na het starten**, wat de activiteit ook is (`berichtensessiecache.sessie.max-duur=PT12H`).
  Wat het eerst komt, telt. Beide instelbaar.
- **Activiteit** = elk verzoek binnen die sessie, inclusief de hartslag van een open
  `_volgen`-verbinding.
- Een sessie start altijd met een **vers beeld**: is er bij de start geen andere lopende sessie
  van dezelfde gebruiker, dan worden eventuele resten (van een verlopen sessie) eerst opgeruimd.
- Een verzoek zonder geldige sessie wordt geweigerd. Geen terugval op het oude gedrag.

### Wat er straks verandert, en wat niet

Nu bestaat er geen inlogdienst. De uitvraag maakt daarom zelf de sessie en geeft de id uit.

Straks logt de gebruiker één keer in bij het portaal (OIDC-relying party, eHerkenning). Het
portaal roept de uitvraag server-side aan met een token voor de uitvraag (`aud`), bij voorkeur via
token exchange (RFC 8693). Dat token draagt de ontvanger en de `sid`, ondertekend door de
inlogdienst. FSC bewijst daaronder welke organisatie aanroept, niet welke gebruiker. Wat dan
verandert:

- `POST /sessies` blijft het moment waarop het portaal na het inloggen verbinding legt, maar de
  sessie-id komt uit het token in plaats van uit de uitvraag.
- `DELETE /sessies/huidige` blijft: de uitvraag is een resource server en krijgt geen
  back-channel logout van de inlogdienst. Het portaal krijgt die (of ziet de gebruiker zelf
  uitloggen) en meldt het door.
- `X-Ontvanger` en `X-Sessie` vervallen; de uitvraag leest beide uit het gevalideerde token.

De controle per verzoek en het opruimen blijven ongewijzigd. Daarom zijn starten, controleren en
beëindigen drie losse operaties op de `Sessiecache`-facade: de bron van de sessie kan wisselen
zonder dat de cache iets merkt.

### De demo

In de demo is geen inlog. Per tab geldt het kiezen van een persona als inloggen en het wisselen
naar een andere persona als uitloggen van de vorige. Meerdere persona's tegelijk bekijken = meerdere
tabs; elke tab bewaart zijn sessie-id in `sessionStorage` (overleeft herladen, is per tab). Voor de
uitvraag is dat niet te onderscheiden van echte sessies.

Verworpen: afmelden bij het sluiten van een tab (`pagehide` + `sendBeacon`). Een browser
onderscheidt sluiten niet van herladen, dus elke F5 zou uitloggen en bij de laatste tab alles
wissen. Een gesloten tab wacht gewoon de inactiviteitstermijn af.

## Ontwerp

### Uitvraag-API (OpenAPI-first, `berichtenuitvraag-api.yaml`)

- `POST /api/v1/sessies` met `X-Ontvanger` → `201`, body met de sessie-id en het uiterlijke
  verloopmoment. De id is een geheim: niet in een `Location`-header of een ander pad.
- `DELETE /api/v1/sessies/huidige` met `X-Ontvanger` + `X-Sessie` → `204`. Idempotent: een
  onbekende of al verlopen sessie geeft ook `204`.
- Alle `/api/v1/berichten…`-endpoints (lijst, detail, PATCH, DELETE, bijlage, `_ophalen`,
  `_volgen`, `_zoeken`) eisen `X-Ontvanger` + `X-Sessie`. Ontbreekt de sessie, is hij onbekend of
  verlopen, of hoort hij bij een andere ontvanger: `401` als `application/problem+json`. De
  aanroeper kan een verlopen sessie niet onderscheiden van een vervalste (geen informatie lekken).
- Uitgezonderd: `POST /api/v1/aanmeldingen` (magazijn → uitvraag, geen gebruiker) en
  `/api/v1/openapi.json`.
- `X-Ontvanger` blijft in de verzoeken en wordt tegen de sessie gecontroleerd. Zo spiegelt het
  hoe straks het token beide draagt.

### Gebruiker en ontvanger

Een sessie hoort bij de ingelogde **medewerker**: elke inlog is een eigen sessie, en één sessie
geldt voor precies één ontvanger. De gegevens in de tijdelijke opslag staan per **ontvanger**
(de onderneming, KVK), zoals nu. Zijn twee medewerkers van dezelfde onderneming ingelogd, dan
delen zij die gegevens; ze verdwijnen bij de laatste sessie voor die ontvanger.

Open punt, in overleg met afnemers: is een bericht voor de onderneming of voor een medewerker?
En hoe verhoudt een eHerkenning-machtiging, die voor één dienst geldt, zich tot berichten van
andere diensten? Verandert dat, dan verandert de sleutel van de gegevens, niet het sessiemodel.

### Sessie-opslag (in `fbs-berichtensessiecache`)

Sessiebeheer zit in de library, niet in de uitvraag: verlopen, verlengen en opruimen moeten
atomair zijn met de berichtgegevens in dezelfde Redis.

- **Sessie-id:** 128 bits uit een cryptografisch veilige generator, URL-veilig gecodeerd. In
  Redis staat alleen `sha256(id)`, zodat een dump geen bruikbare sessies oplevert.
- **Sessierecord** per sessie: de ontvanger, het startmoment en het uiterlijke verloopmoment
  `min(laatste activiteit + inactiviteit, start + max-duur)`. De ontvanger staat er voluit in,
  omdat het opruimen bij verlopen hem nodig heeft voor het logboek (zie *Logboek*); de
  bericht-hashes bewaren hem nu ook al, dus dit is geen nieuwe blootstelling.
- **Sessie-index per ontvanger:** welke sessies deze ontvanger heeft, met hun verloopmoment
  (bv. een ZSET met score = verloopmoment), zodat "is dit de laatste?" één atomaire vraag is.
- **Verloopindex, stelselbreed:** per ontvanger het verloopmoment van zijn laatst-verlopende
  sessie, zodat de uitvraag verlopen sessies actief kan vinden (zie *Opruimen*). Het lid is de
  hash van de ontvanger, niet de ontvanger zelf: de index heeft geen TTL en mag geen lijst van
  BSN's worden. De leesbare ontvanger staat per ontvanger apart, met de vangnet-TTL van de
  gegevens, en verdwijnt samen met zijn index-entry; de veegtaak leest hem voor de logregel.
- **Grens:** hoogstens 5 lopende sessies per ontvanger (instelbaar). Daarboven weigert
  `POST /sessies` met `429` (problem+json); er wordt geen bestaande sessie verdrongen, want dat
  logt een echte gebruiker ongemerkt uit. Precedent: de connection-grens per ontvanger op
  `_volgen`.
- **Facade** krijgt `startSessie(ontvanger)`, `raakSessie(id, ontvanger)` (controleren +
  verlengen; faalt met een eigen exceptie die de uitvraag naar `401` vertaalt) en
  `beeindigSessie(id, ontvanger)`. Bestaande leesmethodes gaan pas door na `raakSessie`.

### Levensduur van de berichtgegevens

- De `berichtensessiecache.ttl`-sliding-TTL verdwijnt. Wissen is een **actieve** stap van de
  uitvraag, bij het uitloggen van de laatste sessie én bij het verlopen ervan — zodat elk wissen
  in het logboek komt.
- De Redis-TTL op de gegevens (`:list`, `:status`, tombstones, `bericht:v4:*`-hashes) is alleen
  nog een vangnet en ligt **altijd ná** het verloopmoment van de laatste sessie. Elke schrijfactie
  zet hem op `max-duur + vangnet-marge` (`berichtensessiecache.sessie.vangnet-marge`, `PT1H`);
  omdat geen sessie langer leeft dan `start + max-duur`, ligt dat vanzelf voorbij elke sessie die
  er op dat moment is. Alleen een sessie die start terwijl er al een loopt, trekt de TTL van de
  bestaande gegevens één keer op. Leesverkeer verlengt niets meer — de sliding-TTL en
  `verlengSessie` verdwijnen.
- **Schrijven en uitloggen kruisen elkaar:** een ophaalronde of aanmelding die schrijft terwijl
  de laatste sessie net eindigt, controleert ná het schrijven atomair of er nog een lopende sessie
  is, en ruimt anders zelf op. Zo laat geen volgorde van gebeurtenissen gegevens achter.
- Daarmee verlopen de gegevens nooit terwijl een sessie loopt, en wist Redis ze alleen zelf als
  het actieve opruimen faalt.
- `aggregation-lock-ttl` (PT2M) blijft voor de lock en de BEZIG-status.

### Opruimen

Drie aanleidingen: het beëindigen van de laatste sessie; het verlopen van de laatste sessie; de
start van een sessie zonder andere lopende sessie (resten van een vorige keer).

- **Verlopen vinden:** een periodieke taak in de uitvraag (bv. elke 30 s) haalt uit de
  verloopindex de ontvangers waarvan de laatste sessie verlopen is en ruimt ze op. Meerdere pods
  mogen tegelijk vegen: per ontvanger controleert één atomaire stap of er echt geen lopende sessie
  meer is, ruimt op en haalt hem uit de index, zodat precies één pod opruimt en logt. Niet op
  keyspace-notificaties bouwen: die gaan verloren als er net geen luisteraar is.
- Weg: `:list`, `:status`, `:lock`, `:verwijderd:*`-tombstones, de sessie-index, de
  sessierecords en **alle** `bericht:v4:*`-hashes van die ontvanger, ook die uit eerdere
  ophaalrondes. Daarmee verdwijnen ook de RediSearch-entries.
- De hashes vallen niet onder de sessie-prefix en zijn nu alleen via de `:list` of een FT.SEARCH op
  het `ontvanger`-veld te vinden. Hoe de builder ze betrouwbaar en atomair vindt (bijhouden in een
  eigen set per ontvanger bij `store` en aanmelden, of anders) is aan de builder; eis is dat
  opruimen niet halverwege blijft steken en geen half-gevulde sessie achterlaat.
- **Uitloggen tijdens een ophaalronde:** een ronde die na het opruimen afrondt, laat niets
  achter (geen list, geen GEREED-status, geen hashes): de controle ná het schrijven ruimt het op.
  De SSE-stream van die ronde eindigt.
- **Aanmelden na uitloggen:** werkt al goed zolang `:status` mee wordt opgeruimd —
  `schrijfBericht` weigert zonder status (`GeenLijstInCache`, uitvraag antwoordt `202`).
- **Falen:** mislukt opruimen, dan logt de uitvraag een waarschuwing met correlation-id, zonder
  BSN en zonder ontvanger-hash (een hash van een BSN is terug te rekenen). De ontvanger blijft in
  de verloopindex, dus de volgende veegronde probeert het opnieuw; de Redis-TTL is het laatste
  vangnet. Mislukt het uitloggen zelf, dan krijgt de gebruiker `503` en herhaalt de client het
  verzoek; een herhaling van een uitlog die al gewist had, geeft `204`.

### `_volgen`

De hartslag (`volg-hartslag`, PT20S) wordt een `raakSessie`. Eindigt de sessie (uitgelogd elders
in dezelfde sessie, verlopen), dan stuurt de stream `SessieVerlopen` en sluit. De opstartcontrole
"hartslag < ttl/2" wordt "hartslag < inactiviteit/2".

### Logboek Dataverwerkingen

Veilige route: alles wat sessiegegevens aanmaakt of vernietigt, komt in het logboek, onder een
nieuwe verwerkingsactiviteit `urn:nl:moz:fbs:verwerkingsactiviteit:berichtenuitvraag:sessie` in
`ProcessingActivities`.

- Te loggen: sessie gestart, sessie beëindigd, en opruimen — met de aanleiding (uitgelogd,
  verlopen, nieuwe start) en de uitkomst (gelukt/mislukt). `dataSubjectId` = de ontvanger.
- Het opruimen bij verlopen gebeurt buiten een HTTP-verzoek; de logregel moet dus zonder de
  request-gebonden `@Logboek`-interceptor geschreven kunnen worden. Hoe is aan de builder.
- Alleen het vangnet (Redis-TTL na een mislukte opruiming) is niet te loggen; daarom gaat aan dat
  vangnet altijd een gelogde mislukte opruiming vooraf, of — bij de dubbele storing hieronder — een
  alert.
- **De verloopindex is de takenlijst voor het logboek.** Een script dat gegevens wist en dat zelf
  niet tot een logregel leidt, laat de ontvanger in de verloopindex staan met score "nu"; wie die
  entry daarna weghaalt, meldt de opruiming. Concreet: wist de controle ná een schrijfactie gegevens
  (geschreven nadat de laatste sessie al eindigde), dan zet hij de ontvanger wachtend in de index.
  De veegtaak legt het wissen dan vast (`verlopen`), of een uitlog of start die de wachtende taak
  eerder oppakt (`uitgelogd`, `nieuwe-start`). Zonder die markering zou een uitlog of start die
  niets meer te wissen vindt, de taak stil uit de index halen.
- Faalt de controle ná een schrijfactie zelf, dan markeert de uitvraag de ontvanger alsnog in de
  index, zodat de veegtaak het oppakt. Faalt ook dat, dan volgt een foutregel met het alert-token
  `[ALERT opruimen_na_schrijven]`: dat is de enige route waarlangs de Redis-TTL gegevens zonder
  voorafgaande logregel wist.
- Blijft het antwoord op een uitlog uit (time-out), dan kan het wissen al gebeurd zijn terwijl een
  herhaalde uitlog niets meer ziet. De uitvraag legt het opruimen dan vast met uitkomst
  `onbevestigd` en antwoordt `503`. Een start die op een time-out stuit, laat hooguit een sessie
  achter die niemand kent; die verloopt en de veegtaak meldt het opruimen.

### Logging en privacy

- Een sessie-id is een credential: nooit in logs, URL's, LDV of foutmeldingen.
- BSN niet in applicatie-logs (bestaande regel).

## Demo en frontends

- **Berichtenbox in de demo-console** (`demo/demo-console/.../berichtenbox.js`): sessie starten bij
  het kiezen van een persona, beëindigen bij wisselen, `X-Sessie` op elk verzoek. Een `401` start
  een nieuwe sessie en een nieuwe ophaalronde — in de demo staat dat voor "opnieuw inloggen".
- **Knop "Cache verlopen"** (`demo-console/.../sessie/SessieService.kt`): doet nu
  `KEYS berichtensessiecache:*` + `DEL` en mist daardoor de `bericht:v4:*`-hashes. Wordt: alle
  sessies en alle sessiegegevens weg, hashes inbegrepen. De frontends merken dat aan een `401`.
- **Demo-profiel:** het demo-profiel zette de TTL op PT2M; dat wordt een korte inactiviteitstermijn.
- **Proeftuin-berichtenbox (MinBZK/moza-poc):** eigen PR, gekoppeld aan deze. Sessie starten bij
  het kiezen van een persona (`personas.js`), beëindigen bij `ruimOpBijWisseling` en bij de
  "Uitloggen"-link, sessie-id per tab in `sessionStorage`, `X-Sessie` op alle uitvraag-aanroepen in
  `berichtenbox-keten.js`, en de bijlage-proxy (`server/keten-proxy.js`, nu via cookie
  `ontvanger`) geeft ook de sessie door. `401` → nieuwe sessie + opnieuw ophalen.
- **Uitrol:** geen achterwaartse compatibiliteit. Wij schrijven beide PR's; ze verwijzen naar
  elkaar en gaan na werktijd samen live, in deze volgorde: (1) de moza-poc-PR mergen, zodat hun
  main een image publiceert; (2) in deze PR de pin in `compose.yaml` op de digest van dat
  main-image zetten (niet op een PR- of preview-image, anders meldt `proeftuin-pin.sh` hem direct
  als afwijkend); (3) deze PR door de merge queue. Tot stap 1 test je samen via
  `compose.proeftuin-versie.yaml`. Tussen stap 1 en 3 draait ZAD nog op de oude pin en dus nog
  consistent.
- **Bruno:** `bruno/berichtenuitvraag/` krijgt requests voor starten en beëindigen, en een
  `sessie`-variabele die de bestaande requests meesturen.
- Docs bijwerken: `docs/operator-handleiding-uitvraag.md` (config-sleutels), `docs/ontwikkelen.md`,
  `docs/demo-runbook.md` waar het de cache-knop noemt, en de cache-regel in `CLAUDE.md`.

## Tests

Naast de gebruikelijke happy/unhappy-paden en de contracttest tegen de spec (ook de `401`):

- **Meerdere sessies:** één sessie beëindigen laat de andere werken en de gegevens staan; de
  laatste beëindigen ruimt alles op. Cardinaliteit 1, 2 en 3 sessies.
- **Gegevens:** opruimen met geen, één en meerdere berichten, en met hashes uit een eerdere
  ophaalronde; daarna vindt RediSearch niets meer voor die ontvanger.
- **Opnieuw inloggen** na uitloggen en na verlopen: vers beeld, geen resten.
- **Uitloggen tijdens een lopende ophaalronde:** de ronde schrijft na afloop niets weg.
- **Aanmelding na uitloggen:** geen nieuwe gegevens.
- **Verlopen:** inactiviteit en max-duur afzonderlijk, en welke eerst komt; activiteit verlengt
  niet voorbij max-duur.
- **Sessie van een andere ontvanger:** `401`, geen gegevens.
- **Falend opruimen:** waarschuwing gelogd, zonder BSN; de volgende veegronde probeert opnieuw;
  gegevens verlopen uiterlijk via het vangnet.
- **Verlopen opruimen:** de veegtaak ruimt een ontvanger op wiens laatste sessie verliep, laat
  een ontvanger met nog één lopende sessie staan, en ruimt bij twee pods precies één keer op.
- **Grens:** de zesde sessie voor een ontvanger geeft `429`; na het beëindigen van één kan het weer.
- **Logboek:** start, beëindigen en elk soort opruimen leveren een logregel onder de
  sessie-activiteit op, ook het opruimen buiten een verzoek.

## Doelen, niet-doelen, anti-doelen

- **Niet-doelen:** echte authenticatie (OIDC, eHerkenning, tokenvalidatie), een
  back-channel-logout-endpoint, autorisatie (AuthZEN), afmelden bij het sluiten van een tab,
  achterwaartse compatibiliteit.
- **Anti-doelen:** een tijdelijke omweg die bij echte authenticatie weer weg moet; een sessie-id
  in een URL of log; BSN in applicatie-logs; een half opgeruimde sessie; gegevens die
  verdwijnen zonder logregel.

## Aan de builder

Redis-sleutelindeling, Lua versus MULTI, hoe hashes per ontvanger gevonden worden, de exacte vorm
van de responsebody van `POST /sessies` (binnen de ADR), naamgeving van facade-methodes en
excepties, en of de sessiecontrole in een request-filter of per resource zit.
