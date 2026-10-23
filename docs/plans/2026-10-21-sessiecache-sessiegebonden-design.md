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
- **Activiteit** = elk verzoek van de gebruiker binnen die sessie, en een verlenging door het
  portaal (`_verlengen`). Een open `_volgen`-verbinding en haar hartslag tellen niet: inactiviteit
  is inactiviteit van de gebruiker (zie *Het portaal is de klok*).
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

### Het portaal is de klok

Besluit van de opdrachtgever. Het portaal opent straks sessies naar meer diensten dan FBS. Heeft
elke dienst een eigen inactiviteitstimer, dan logt de gebruiker op verschillende momenten uit, en
merkt hij dat pas bij zijn volgende klik in die ene dienst. Daarom bepaalt het portaal wanneer de
gebruiker inactief of uitgelogd is; de timers van FBS zijn alleen een vangnet.

- Het portaal roept `POST /sessies/huidige/_verlengen` aan bij activiteit van de gebruiker in
  welke dienst ook, hooguit eens per minuut. Berichtenverzoeken aan FBS verlengen zelf ook: dat
  zijn verzoeken van de gebruiker.
- Het portaal stuurt `DELETE /sessies/huidige` bij uitloggen en bij zijn eigen time-out.
- Bij `POST /sessies` geeft het portaal het plafond van zijn eigen sessie mee
  (`uiterlijkGeldigTot`); FBS neemt het vroegste van dat moment en start + `max-duur`.
- De defaults blijven (`inactiviteit` PT15M, `max-duur` PT12H). In een koppeling met een portaal
  staat `inactiviteit` ruimer dan de termijn van het portaal plus zijn verleng-interval, zodat FBS
  niet vóór het portaal verloopt (zie de operator-handleiding).
- Een open `_volgen`-verbinding houdt de sessie niet in leven. Een tabblad dat op een onbeheerde
  werkplek openstaat, hield de berichtgegevens anders bereikbaar tot het plafond van 12 uur (ASVS
  V3.3.2, BIO: inactiviteit van de gebruiker).

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

- `POST /api/v1/sessies` met `X-Ontvanger` en een optionele body met het plafond van de
  portaalsessie (`uiterlijkGeldigTot`) → `201`, body met de sessie-id, `geldigTot` (verloop bij
  inactiviteit) en `uiterlijkGeldigTot` (het harde plafond), en `Location: /api/v1/sessies/huidige`.
  De id is een geheim en staat in geen pad; `/sessies/huidige` draagt geen geheim. Boven de grens
  `429` met `Retry-After` tot de vroegst verlopende sessie.
- `GET /api/v1/sessies/huidige` → `200` met `geldigTot` en `uiterlijkGeldigTot`, zonder de sessie
  te verlengen. `POST /api/v1/sessies/huidige/_verlengen` → `200` met dezelfde body, na het
  verlengen. Beide `401` op een sessie die niet loopt.
- `DELETE /api/v1/sessies/huidige` met `X-Ontvanger` + `X-Sessie` → `204`. Idempotent: een
  onbekende of al verlopen sessie geeft ook `204`; alleen een ontbrekende of krom gevormde
  `X-Sessie` geeft `401`.
- `X-Sessie` staat in de spec als `securitySchemes.sessie` (`apiKey` in de header), met een
  `security`-blok op elke operatie die hem vereist. Elke `401` draagt
  `WWW-Authenticate: X-Sessie realm="berichtenuitvraag"`; bij de overstap naar een token wordt dat
  `Bearer realm="…", error="invalid_token"`.
- Alle `/api/v1/berichten…`-endpoints (lijst, detail, PATCH, DELETE, bijlage, `_ophalen`,
  `_volgen`, `_zoeken`) eisen `X-Ontvanger` + `X-Sessie`. Ontbreekt de sessie, is hij onbekend of
  verlopen, of hoort hij bij een andere ontvanger: `401` als `application/problem+json`. De
  aanroeper kan een verlopen sessie niet onderscheiden van een vervalste (geen informatie lekken).
- Uitgezonderd: `POST /api/v1/sessies`, `POST /api/v1/aanmeldingen` (magazijn → uitvraag, geen
  gebruiker) en `/api/v1/openapi.json`. Het `SessieFilter` weigert standaard: elk ander pad
  vereist een sessie, ook een endpoint dat later bijkomt. Een test leidt uit de spec af dat
  filter en `security`-blokken dezelfde verzameling beschrijven.
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
- **Sessies per ontvanger** (zoals gebouwd): een ZSET `$k:sessies` met als lid `sha256(id)` en
  als score het verloopmoment `min(laatste activiteit + inactiviteit, plafond)`, plus een HASH
  `$k:sessies:plafond` met het plafond per sessie. Zo is "is dit de laatste?" één atomaire vraag.
  Er is geen sessierecord met de ontvanger erin: de ontvanger zit gehasht in de sleutel (`$k`), en
  leesbaar alleen in `$k:ontvanger` naast de verloopindex (zie hieronder).
- **Verloopindex, stelselbreed:** per ontvanger het verloopmoment van zijn laatst-verlopende
  sessie, zodat de uitvraag verlopen sessies actief kan vinden (zie *Opruimen*). Het lid is de
  hash van de ontvanger, niet de ontvanger zelf: één stelselbrede sleutel mag geen lijst van BSN's
  worden. Die hash is een pseudoniem, geen anonimisering (zie *Verwerkingsregister en DPIA*). De
  index heeft een TTL van `max-duur` + `vangnet-marge` + `max-duur`, opnieuw gezet bij elke
  bijwerking (zie *Na de herreview*). De leesbare ontvanger staat per ontvanger apart, met de
  vangnet-TTL van de gegevens, en verdwijnt samen met zijn index-entry; de veegtaak leest hem voor
  de logregel, en alleen als hij bij het kenmerk van de entry hoort.
- **Grens:** hoogstens 5 lopende sessies per ontvanger (instelbaar). Daarboven weigert
  `POST /sessies` met `429` (problem+json); er wordt geen bestaande sessie verdrongen, want dat
  logt een echte gebruiker ongemerkt uit. Precedent: de connection-grens per ontvanger op
  `_volgen`.
- **Facade** krijgt `startSessie(ontvanger, uiterlijkGeldigTot?)`, `raakSessie(id, ontvanger)`
  (controleren + verlengen; faalt met een eigen exceptie die de uitvraag naar `401` vertaalt),
  `leesSessie(id, ontvanger)` (alleen controleren) en `beeindigSessie(id, ontvanger)`. Beide
  geven de geldigheid terug. Bestaande leesmethodes gaan pas door na `raakSessie`.
- **Plafond per sessie:** `$k:sessies:plafond` bewaart per sessie haar plafond, het vroegste van
  start + `max-duur` en het plafond dat de start meekreeg. Een meegegeven plafond dat volgens de
  Redis-klok niet in de toekomst ligt, weigert de start zonder iets te wissen.

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
- De hashes vallen niet onder de sessie-prefix. Gebouwd: elke schrijfactie zet de hash en de
  tombstone in een set per ontvanger (`$k:sleutels`), en het opruimscript wist die set in stukken
  van 1000 binnen één Lua-aanroep, zodat opruimen niet halverwege blijft steken.
- **Uitloggen tijdens een ophaalronde:** een ronde die na het opruimen afrondt, laat niets
  achter (geen list, geen GEREED-status, geen hashes). De ronde schrijft alleen zolang ze haar
  eigen lock houdt (zie *Na de review*); het opruimen wist de lock, dus ze schrijft niets weg. De
  SSE-stream van die ronde eindigt zonder `ophalen-gereed`. Dat geldt ook als de gebruiker direct
  opnieuw inlogt en een nieuwe ronde start: de oude ronde kan die niet overschrijven.
- **Aanmelden na uitloggen:** werkt al goed zolang `:status` mee wordt opgeruimd —
  `schrijfBericht` weigert zonder status (`GeenLijstInCache`, uitvraag antwoordt `202`).
- **Falen:** mislukt opruimen, dan logt de uitvraag een waarschuwing met alleen de foutklasse,
  zonder BSN en zonder ontvanger-hash (een hash van een BSN is terug te rekenen). Een
  correlation-id is er niet: de veegtaak draait buiten een verzoek. De ontvanger blijft in de
  verloopindex, een veeginterval naar achteren, dus een volgende veegronde probeert het opnieuw;
  de Redis-TTL is het laatste vangnet. Mislukt het uitloggen zelf, dan krijgt de gebruiker `503` en herhaalt de client het
  verzoek; een herhaling van een uitlog die al gewist had, geeft `204`.

### `_volgen`

De hartslag (`volg-hartslag`, PT20S) controleert alleen of de sessie nog loopt (`leesSessie`); hij
verlengt haar niet, en het openen van de stream evenmin. Eindigt de sessie (uitgelogd elders in
dezelfde sessie, verlopen), dan stuurt de stream bij de volgende hartslag `SessieVerlopen` en
sluit. De afnemer verbindt dan niet vanzelf opnieuw: een herstart van de stream die de sessie zo
toch levend houdt, is precies wat dit voorkomt. De opstartcontrole "hartslag < inactiviteit/2"
vervalt: de hartslag houdt de sessie niet meer open.

Eerder raakte de hartslag de sessie. Dat hield een open tabblad op een onbeheerde werkplek tot het
plafond van 12 uur ingelogd (zie *Het portaal is de klok*).

Na een verbroken verbinding verbindt de berichtenbox opnieuw, maar leest hij de lijst niet vanzelf
opnieuw: `GET /berichten` verlengt de sessie, en een herverbinding is geen activiteit van de
gebruiker. Een proxy die de stream elke paar minuten afbreekt, of een rolling deploy, hield een
onbeheerd tabblad anders alsnog tot het plafond in leven. De lijst wordt weer compleet bij de
volgende actie van de gebruiker; dat staat zo in de spec van `_volgen`.

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
- Faalt de controle ná een schrijfactie zelf, of blijft het antwoord op de schrijfactie zelf uit
  (een aanmelding of verwijdering die in Redis gelukt kan zijn), dan markeert de uitvraag de
  ontvanger alsnog in de index, zodat de veegtaak het oppakt. Faalt ook dat, dan volgt een
  foutregel met het alert-token `[ALERT opruimen_na_schrijven]`: dat is de enige route waarlangs de
  Redis-TTL gegevens zonder voorafgaande logregel wist.
- Blijft het antwoord op een uitlog uit (time-out), dan kan het wissen al gebeurd zijn terwijl een
  herhaalde uitlog niets meer ziet. De uitvraag legt het opruimen dan vast met uitkomst
  `onbevestigd` en antwoordt `503`. Een start die op een time-out stuit, kan net zo al resten
  gewist hebben en laat een sessie achter die niemand kent; een volgende start ziet die lopen en
  meldt niets. Daarom legt ook die start het opruimen vast als `onbevestigd` (`nieuwe-start`).

- **Bedoelde weigering:** een `429 te-veel-sessies` op de start, en een `400` door een fout van de
  afnemer (zoals een plafond in het verleden), krijgen een logregel zonder status `Error`
  (`LogboekContext.expectException`): `Error` betekent in het logboek dat er iets misging. De
  logregel van een start draagt `fbs.sessie.start.uitkomst` (`gestart` of `geweigerd`), zodat een
  inzage niet "sessie gestart" leest waar de start geweigerd werd.
- **Einde zonder effect:** de logregel van `DELETE /sessies/huidige` draagt
  `fbs.sessie.einde.uitkomst` (`onbekend`, `beeindigd` of `opgeruimd`). Zolang er geen
  authenticatie is, kan iedereen een einde voor een willekeurige ontvanger aanvragen; aan
  `onbekend` is te zien dat zo'n regel niets deed.
- **Opvragen en verlengen** (`GET /sessies/huidige`, `_verlengen`) krijgen geen eigen logregel,
  net als het verlengen door elk ander verzoek in de sessie: ze raken alleen de
  sessie-administratie, niet de berichtgegevens.
- **Gat in het logboek:** lukt het schrijven van een opruimregel niet, dan volgt
  `LDV_OPRUIMEN_ONTBREEKT` met een eigen `foutId`, de aanleiding, de uitkomst, het type ontvanger
  en de klasse van de oorzaak, zonder BSN. Naleggen kan dus niet; de operator meldt het gat aan de
  privacy officer, met tijdvenster en aantal.

### Verwerkingsregister en DPIA

De verwerkingsactiviteit `urn:nl:moz:fbs:verwerkingsactiviteit:berichtenuitvraag:sessie` hoort in
het verwerkingsregister (AVG art. 30) en in de DPIA, met:

- **Doel:** de tijdelijke kopie van de berichtgegevens van een ontvanger precies zo lang bewaren als
  hij een lopende inlogsessie heeft, en elk wissen ervan verantwoorden.
- **Bewaartermijnen:** 15 minuten na het laatste verzoek of de laatste verlenging (`inactiviteit`),
  hoogstens 12 uur na de start (`max-duur`, of het eerdere plafond van het portaal); de Redis-TTL
  van 13 uur (`max-duur` + `vangnet-marge`) is alleen een vangnet als het actief wissen faalt.
- **Grondslag:** dezelfde als die van de berichtenuitvraag zelf; de sessie-administratie en het
  logboek ervan zijn een noodzakelijk onderdeel van het tonen van de berichten, geen eigen doel.
  De concrete wettelijke grondslag (naar verwachting een taak van algemeen belang, AVG art.
  6(1)(e)) stelt de privacy officer vast.
- **Gegevenscategorieën:**
  - het identificatienummer van de ontvanger (BSN, RSIN, KVK-nummer of OIN; een BSN is een
    wettelijk identificatienummer, UAVG art. 46);
  - de metadata van zijn berichten: afzender, onderwerp, publicatietijdstip, leesstatus, map en de
    namen van bijlagen (de berichttekst blijft in het magazijn);
  - de sessie-administratie per ontvanger: een hash van elk sessie-id en per sessie het verloop en
    het plafond, dus wanneer iemand van die onderneming actief was;
  - de verloopindex (hieronder);
  - de logregels in het Logboek Dataverwerkingen: `uitvraag-sessie-start`, `uitvraag-sessie-einde`
    en `uitvraag-sessie-opruimen`, elk met de ontvanger als betrokkene. Zo legt het logboek per
    ontvanger vast wanneer er werd in- en uitgelogd en wanneer zijn gegevens gewist zijn; die
    regels blijven bewaard zolang het logboek ze bewaart, los van de termijnen hierboven.
- **Verloopindex:** één stelselbrede sorted set met per ontvanger zijn kenmerk en het verloopmoment
  van zijn laatste sessie. Het kenmerk is SHA-256 zonder geheim over het identificatienummer: een
  pseudoniem, geen anonimisering. Er zijn zo'n 10⁸ geldige BSN's, dus wie de index kan lezen, rekent
  met een woordenboek elk kenmerk in minuten terug. Behandel de index als persoonsgegeven. De index
  heeft een TTL van `max-duur` + `vangnet-marge` + `max-duur` (standaard 25 uur), die elke
  bijwerking opnieuw zet: draait de veegtaak niet meer, dan verdwijnt hij uiterlijk zo lang na de
  laatste sessieactiviteit in het stelsel. De leesbare ontvanger staat per ontvanger apart, met de
  vangnet-TTL, en verdwijnt samen met zijn index-entry.
- **Afweging: geen HMAC.** Een HMAC met een geheime sleutel zou het kenmerk alleen terug te rekenen
  maken voor wie de sleutel heeft. Dat vraagt sleutelbeheer: opslag, rotatie, en bij rotatie een
  index die niet meer klopt. Die sleutel zou bovendien naast dezelfde Redis staan als wat hij
  beschermt: de index staat daar naast de berichtgegevens en de leesbare ontvanger, met dezelfde
  toegang (TLS en authenticatie). Een geheim voegt daar weinig aan toe. Daarom SHA-256, en de index
  behandeld als persoonsgegeven.

### Logging en privacy

- Een sessie-id is een credential: nooit in logs, URL's, LDV of foutmeldingen.
- BSN niet in applicatie-logs (bestaande regel).

## Na de review

Wat er na de review van PR #393 is veranderd ten opzichte van het ontwerp hierboven, of daar
preciezer is gemaakt. De secties hierboven zijn waar nodig al bijgewerkt; dit is het overzicht.

**Bibliotheek**

- **Eigen lock per ophaalronde.** De lock draagt per ronde een eigen token. Een ronde schrijft
  haar resultaat (lijst, hashes, sleutelset, status) in één transactie, en alleen als ze haar
  lock nog houdt. Eindigde de laatste sessie intussen, of startte na een nieuwe inlog een nieuwe
  ronde, dan schrijft de oude ronde niets. Een ronde die langer duurt dan `aggregation-lock-ttl`
  slaat daardoor ook niets op.
- **Geen leesbaar BSN in de verloopindex.** Het lid is het kenmerk (de hash) van de ontvanger;
  de leesbare vorm staat apart in `$k:ontvanger`, met de vangnet-TTL. Is die al verlopen, dan
  meldt de veegtaak de opruiming als `Opruiming.ZonderOntvanger`, met het alert
  `LDV_OPRUIMEN_ONTBREEKT` in plaats van een logregel.
- **Veegtaak.** Elke opruiming gaat direct naar het logboek, niet pas na de batch. Een tick
  vraagt batches van 100 op zolang de batch vol is, binnen een half interval. Een ontvanger
  waarvan het opruimen mislukt of geen antwoord krijgt, schuift een interval (minstens 30 s) naar
  achteren. Drie mislukte rondes op rij geven `[ALERT sessie_veger_faalt]`; zie ook *Na de
  herreview*.
- **Plafond van het portaal en de hartslag.** `startSessie` neemt het plafond van het portaal mee,
  `leesSessie` leest zonder te verlengen, en de hartslag van `_volgen` verlengt niet meer (zie
  *Het portaal is de klok*).

**Uitvraag**

- `GET /sessies/huidige` en `POST /sessies/huidige/_verlengen`; `Location` op de 201;
  `Retry-After` op de 429; `WWW-Authenticate` op elke 401.
- Het `SessieFilter` weigert standaard en kent vier vereisten: geen sessie (`POST /sessies`,
  `/aanmeldingen`, `/openapi.json`), alleen de vorm (`DELETE /sessies/huidige`), een lopende sessie
  zonder verlengen (`GET /sessies/huidige`, `_volgen`) en verlengen (al het andere). Een
  ontbrekende of krom gevormde `X-Sessie` op `DELETE` geeft daardoor `401`, niet `400`.
- `LDV_OPRUIMEN_ONTBREEKT` draagt een `foutId`, de aanleiding, de uitkomst, het type ontvanger en
  de klasse van de oorzaak. Een 429 is in het logboek geen `Error`, en de logregel van een uitlog
  draagt `fbs.sessie.einde.uitkomst`.

**Na de herreview**

- **Alert per ontvanger.** `[ALERT sessie_veger_faalt]` gold alleen rondes die de kandidaten niet
  eens konden opvragen. Op een alleen-lezen Redis lukt dat wel en faalt elke veeg. De veger telt
  daarom per ontvanger de mislukte pogingen op rij; vanaf drie volgt hetzelfde alert, één regel
  voor alle ontvangers samen, daarna elke tiende ronde zolang er een vastzit. Wat hij zo onthoudt,
  is begrensd (1000 ontvangers, vergeten na vijf minuten zonder nieuwe mislukking) en staat alleen
  in het geheugen van de pod.
- **Minder foutId's per gat.** Een opruiming zonder bekende ontvanger gaf bij elk uitstel, op elke
  pod, een nieuwe `LDV_OPRUIMEN_ONTBREEKT`. Nu per reeks mislukkingen één, plus één bij de
  opruiming die ten slotte lukt.
- **Schrijven zonder antwoord.** Een aanmelding of verwijdering waarop Redis niet antwoordt, kan
  toch geschreven hebben. De facade markeert de ontvanger dan voor de veegtaak, net als na een
  mislukte opruimcontrole.
- **Ronde zonder lock.** Een ronde die haar lock al kwijt is voordat ze de magazijnen bevraagt
  (de laatste sessie eindigde terwijl de voorkeuren werden opgehaald), bevraagt er geen en eindigt
  zonder slotevent. Een ronde zonder magazijnen telt de opruimcontrole niet meer mee in de timeout
  van het opslaan, en krijgt die controle ook als het opslaan zonder antwoord bleef.
- **Leesbare ontvanger getoetst.** De veegtaak gebruikt `$k:ontvanger` alleen als die bij het
  kenmerk van de entry hoort. Anders gaat het opruimen door zonder betrokkene, met het alert, en
  komt er geen logregel met een verkeerde betrokkene.
- **TTL op de verloopindex.** Elke bijwerking zet de TTL van de index op `max-duur` +
  `vangnet-marge` + `max-duur`. Waarom dat geen gegevens zonder logregel laat verdwijnen: een
  schrijfactie zet de TTL van haar gegevens op `max-duur` + `vangnet-marge` vanaf haar eigen
  moment. Ze gebeurt in een sessie, en tussen de start van die sessie (die de index bijwerkt) en
  elke schrijfactie erin zit hoogstens `max-duur`; een schrijfactie na het einde van de laatste
  sessie zet de index zelf bij (`OPRUIMEN`, `MARKEER`). De index verloopt dus nooit vóór de
  gegevens waarvoor hij de takenlijst is. Alleen de vangnet-TTL zou dat niet garanderen: een
  aanmelding schrijft zonder de index bij te werken, tot een inactiviteitsduur na het laatste
  verzoek.
- **Logboek bij de start.** Zie *Logboek Dataverwerkingen*: `fbs.sessie.start.uitkomst`, geen
  `Error` op een 400 van de afnemer, en een onbevestigd opruimen bij een start zonder antwoord.
- **Spec.** `PATCH` en `DELETE /berichten/{id}` declareren de `503` die de sessiecontrole kan
  geven, en een test eist dat van elke operatie met een sessie. Na een herverbinding van `_volgen`
  leest de berichtenbox de lijst pas bij de volgende actie van de gebruiker (zie *`_volgen`*).
- **Grens per onderneming.** De grens van 5 sessies telt per ontvanger, dus voor alle medewerkers
  van een onderneming samen. Dat blijft een open punt in het issue, voor de overstap naar
  authenticatie per gebruiker.

**Wat het logboek wel en niet ziet**

Elk wissen door de sessiescripts komt in het logboek: ook het opruimen na een schrijfactie die
kruiste met het einde van de laatste sessie, via de verloopindex. Twee uitzonderingen, en beide
geven eerst een alert-token:

- de vangnet-TTL, die zonder logregel wist. Dat gebeurt alleen als het actief opruimen langer dan
  de vangnet-marge uitbleef, na `[ALERT opruimen_na_schrijven]` of `[ALERT sessie_veger_faalt]`
  (een veegtaak die de kandidaten niet kan opvragen, of een ontvanger die ronde na ronde niet op te
  ruimen is);
- een logregel die niet te schrijven was (`LDV_OPRUIMEN_ONTBREEKT`).

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
