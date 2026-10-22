# Fan-out-meting stopt als het legen niet bevestigd is — implementatieplan

**Status:** Uitgevoerd

**Doel:** `demo/meet-fanout.sh` begint geen ronde zonder aantoonbaar geleegde sessiecache
([MinBZK/MijnOverheidZakelijk#1073](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1073)).
Tot nu toe kon een inlogmuur het legen stil laten mislukken. Het issue gaat ervan uit dat elke ronde
ná de eerste dan het cache-pad meet; zie "De premisse van het issue gaat niet op" voor waarom dat
voor de huidige code niet klopt, en wat er wél warm blijft.

## Context

`vervalcache()` riep `POST $CONSOLE/api/demo/sessie/verlopen` aan met `curl -sS -f`. `-f` faalt
alleen op HTTP ≥ 400. Een inlogmuur die een aanroep zonder geldige sessie redirect (302/303) of de
inlogpagina meteen met 200 teruggeeft, komt daar ongehinderd doorheen. De oauth2-proxy op ZAD geeft
nu 403 (zie `inlogmuur.js`), maar dat is configuratie van de muur en geen eigenschap waar de meting
op mag leunen.

Het bedieningspaneel antwoordt na het wissen met `{"gewisteKeys": N}` (`application/json`). Dat
veld leest het paneel zelf al voor zijn samenvatting (`bediening.js`), en geen inlogpagina draagt
het. Het is dus al het "iets in het antwoord dat de meting kan herkennen" waar het issue naar vraagt.

## Ontwerp

Het legen telt pas als twee dingen kloppen: het paneel bevestigt elk legen, en de gemeten uitvraag
ziet na het legen geen sessie meer — getoetst één keer per run, op de ondernemer van de vorige ronde.

- **Het paneel bevestigt: 2xx én een JSON-object met een geheel getal `gewisteKeys`.** Een
  statuscode alleen is niet genoeg: een inlogpagina met 200 haalt elke statuscontrole. Een 204, een
  leeg antwoord of ander JSON telt ook als niet bevestigd.
  - Gekozen boven een nieuwe header of een marker-veld in het paneel (de optie uit het issue): het
    veld bestaat al, het paneel leunt er zelf op, en een extra marker zou een tweede contract naast
    het eerste zijn. Het paneel hoeft dus niet te veranderen.
  - Gekozen boven `-L` of `--fail-with-body`: de eerste volgt de redirect naar een inlogpagina die
    met 200 antwoordt, de tweede verandert niets aan de 3xx-behandeling.
  - Het JSON-ontleden gaat via `python3`, dat het script al vereist. Een regex op
    `"gewisteKeys":[0-9]+` zou breken op een andere serialisatie (spaties, indentatie).
  - "Niet bevestigd" krijgt een eigen exitcode (3). Een traceback geeft 1 en een ontbrekende
    `python3` 127; zonder onderscheid zou de melding dan naar de inlogmuur wijzen.
- **De uitvraag ziet geen sessie meer:** `GET $UITVRAAG/api/v1/berichten?paginaGrootte=1` met de
  `X-Ontvanger` van de vorige ronde moet `409` geven met `type` `urn:fbs:fout:nog-niet-opgehaald`, wat
  de uitvraag zonder `:status`-key teruggeeft. Op `type` en niet op `title`/`detail`: alleen de codes
  liggen in de API vast. Dit bewijst wat de bevestiging van het paneel niet kan: dat het paneel
  dezelfde Redis leegde als waar de uitvraag uit leest. `UITVRAAG` en `CONSOLE` hebben elk een eigen
  default, en een console wijst naar de Redis van zijn eigen deployment.
  - Eén keer per run, bij het tweede legen, en op de ondernemer van de eerste ronde: die had zeker
    een sessie, dus "geen sessie meer" bewijst iets, en het paneel wist alle sessies tegelijk. Elke
    toets is een verwerking "lezen" in het Logboek Dataverwerkingen van de uitvraag; een toets per
    ronde zou per run twaalf zulke regels achterlaten voor wat één regel al bewijst. Een eerdere versie
    toetste per ronde de ondernemer van díe ronde, en bewees daarmee pas iets bij zijn tweede ronde.
  - `paginaGrootte=1`: gaat de toets mis (200), dan wordt er zo min mogelijk van de ondernemer gelezen.
  - Dat een geleegde sessie `nog-niet-opgehaald` geeft, is gedrag van de uitvraag en geen vastgelegd
    contract: de spec omschrijft de code als "nog geen ophaalronde geweest". Verandert dat, dan stopt
    de meting luid; het commentaar bij de toets zegt dat.
  - Gekozen boven een eerdere versie die vanaf het tweede legen eiste dat er minstens één key
    gewist was. Die toetste alleen de Redis van het paneel, sloeg aan op keys van een open
    berichtenbox, en leunde op een sessie-TTL die lokaal maar twee minuten is.
- **curl:** `-q` als eerste argument (zonder die vlag leest curl de `~/.curlrc` van wie meet, en een
  `location`, `fail` of `include` daarin verandert wat er binnenkomt), `--max-time` en
  `--max-filesize 1M` (een paneel dat niet antwoordt of blijft sturen, zou de meting anders laten
  hangen of het geheugen vullen). `-q` en `--max-time` ook op de gezondheidscontrole, zodat die
  dezelfde route neemt; een proxy of CA-bundel hoort via de omgeving (`SSL_CERT_FILE` lezen curl én
  Python, `CURL_CA_BUNDLE` alleen curl).
- **Het cookie:** gaat via een file descriptor naar curl (`-H @<(printf …)`), niet als argument — een
  argument staat voor iedere gebruiker van de machine in `ps`. Alleen over https, behalve naar
  loopback, en zonder regeleinde (dat zou curl als extra header versturen); anders stopt het script
  vóór de eerste aanroep.
- **Melding per manier van mislukken**, steeds met het adres en de afsluiting dat de meting stopt:
  - geen (volledig) antwoord, met curl's exitcode — curl's eigen foutregel staat erboven, en bij
    een afgekapt antwoord kan het paneel wél gewist hebben, vandaar "niet bevestigd" en niet "niet
    geleegd";
  - 3xx, met de redirect zonder query — die van een inlogpagina is lang en zegt de lezer niets, het
    pad (`/oauth2/start`, Keycloak) wel. Een redirect naar hetzelfde pad op een ander adres
    (http → https) betekent dat `CONSOLE` niet klopt; een inlogmuur stuurt daar nooit heen;
  - 4xx/5xx met de statuscode, en met de uitleg die het paneel zelf meegeeft (`{"fout": ...}` van
    `DemoFoutMapper`, op één regel en ingekort);
  - 2xx zonder bevestiging, met het `Content-Type` (een `text/html` maakt een inlogpagina meteen
    herkenbaar);
  - 2xx waarvan `python3` het antwoord niet kon controleren, met de exitcode;
  - de uitvraag ziet nog een sessie (200), kent nog een mislukte ronde, haalt nog op, bereikt haar
    opslag niet, of antwoordt anders dan `nog-niet-opgehaald` — elk met een eigen uitleg, en met de
    naam van de ondernemer en niet zijn identificatienummer. Dat laatste geldt nu ook voor de
    meldingen van `meet()`, die bij een fout vóór de stroom ook het `type` noemen.
  - Tekst uit een antwoord (de uitleg van het paneel, het `type`, het Content-Type, de redirect)
    gaat alleen met afdrukbare tekens naar de terminal: anders kan een server met escape-codes een
    link vervalsen, het klembord vullen of een valse FOUT-regel tonen.
  - Bij de vormen die op een muur wijzen (3xx naar een ander pad, 401 en 403 zónder uitleg van het
    paneel, 2xx zonder bevestiging) een regel over `CONSOLE_COOKIE`: ontbreekt het, of is het
    verlopen.
- **Een meting die stopt, laat de vorige uitvoer staan:** de tabel groeit in de map van de run en
  gaat pas na de laatste ronde naar `UITVOER`, als die gezet is. Stoppen is bij een verlopen cookie
  het verwachte pad, en een halve tabel is niet van een hele te onderscheiden. De melding noemt waar
  de rondes tot dan toe staan. `UITVOER` heeft geen vaste standaard meer in `/tmp`: daar kan een
  andere gebruiker het pad vooraf als symlink of eigen bestand neerzetten.
- **De ruwe stromen blijven staan** (het bewijs om na te rekenen); het script meldt aan het eind dat
  de map weg kan zodra de uitkomst is vastgelegd.
- **Het cookie komt in geen enkele melding terug:** het is een geldige sessie op een rijksaccount.
- **Contract vastleggen aan de paneelkant:** KDoc op `SessieResource.verlopen()` dat het veld door het
  paneel (`bediening.js`) én de meting gelezen wordt. `SessieResourceTest` pint de veldnaam, en een
  tweede test toetst dat `meet-fanout.sh`, `bediening.js` en de knop in `index.html` dezelfde
  veldnaam en hetzelfde pad gebruiken als de resource. Die lezers hernoemt geen refactoring mee.

Meegenomen omdat het dezelfde soort fout is in hetzelfde script: `meet()` gaf zijn getallen met
tabs door aan `read`. Opeenvolgende tabs smelten daar samen, dus in een ronde zonder geslaagd
magazijn (`eerste` leeg) schoof `compleet` in de kolom van het eerste bericht — een goed ogend getal
op de verkeerde plek. Nu met `|`, net als bij het legen.

## De premisse van het issue gaat niet op

Een gevulde sessiecache maakt `_ophalen` niet korter. `haalBerichtenOp`
(`BerichtensessiecacheService`) zet alleen `:lock` en een BEZIG-`:status`, leest de cache niet, en
bevraagt daarna alle organisaties die de resolver voor die ontvanger teruggeeft. De Redis-reads
(`getPage`, `search`, `getById`) horen bij `GET /berichten`, `/_zoeken` en `/{berichtId}`. Zonder
legen doet ronde 2 dus dezelfde fan-out. Nagegaan in de code, niet gemeten.

Wat wél warm blijft tussen rondes, is in-process toestand van de uitvraag, en die kan het paneel niet
legen:

- de circuit breaker per magazijn (`berichtensessiecache.magazijn-circuit.*`: open na drie
  storingen, 30 s, over alle ondernemers heen). Die laat "compleet" bij de grote ondernemers het
  sterkst zakken (10,1 s → 2,7–3,0 s in de laptoptabel);
- de Profiel-resolver-cache (`profiel.resolver.cache.ttl-seconds=30`, `expireAfterWrite`: een
  treffer verlengt niet). Een ronde die binnen 30 s na de vorige van dezelfde ondernemer begint,
  slaat de Profiel-aanroep over.

Gevolgen voor deze wijziging:

- De code doet wat het issue vraagt: geen ronde zonder bevestigd legen. Het legen zorgt dat elke
  ronde vanuit dezelfde toestand begint, zonder `:lock` van een eerdere, afgebroken aanvraag.
- De teksten die deze wijziging toevoegt of aanraakt, ook `docs/demo-runbook.md`, beweren niet
  langer dat een niet-geleegde sessiecache "het cache-pad" laat meten.
- Of de meting die in-process toestand moet omzeilen, is een keuze over de meetmethode. Die raakt de
  vergelijkbaarheid met de eerdere metingen in `docs/plans/2026-08-21-magazijn-simulator-design.md`
  en hoort in een eigen issue; het issue is nog niet aangemaakt. Knoppen daarvoor: de Profiel-cache
  via `PROFIEL_CACHE_TTL_SECONDS=0` onder `%prod`/`%staging`/`%acceptatie` en via
  `PROFIEL_RESOLVER_CACHE_TTL_SECONDS=0` in de lokale stack (`%dev`); de circuit breaker alleen door
  meer dan 30 s tussen rondes, of via zijn eigen configuratie.

## Toetsing aan de overheidsstandaarden

Een aparte reviewronde toetste deze wijziging aan de API Design Rules en RFC 9457, het Logboek
Dataverwerkingen (LDV), de AVG/NeRDS-privacyrichtlijn en de BIO. Wat daaruit volgde en hier is
opgelost, staat in het ontwerp hierboven; per standaard in het kort:

- **API Design Rules / RFC 9457:** sturen op `type` (§3.1.1), `Accept` met `application/problem+json`,
  een eigen uitleg per code, `/api/v1` op één plek, en een nep-server die `BerichtenLijst` en
  `Problem` uit de spec volgt. Het demo-paneel is bewust geen ADR-API.
- **LDV:** de toets bij de uitvraag is een echte verwerking en hoort gelogd te worden; daarom één
  toets per run in plaats van één per ronde, en in de kop van het script en in `verify-zad.md` dat de
  meting alleen met demo-persona's mag draaien.
- **AVG/privacy:** het cookie (een persoonsgebonden credential) niet in `ps`, niet in de
  shell-geschiedenis (`read -rs` in `verify-zad.md`), alleen de `_oauth2_proxy`-paren; geen
  identificatienummer in meldingen; `paginaGrootte=1`; de ruwe stromen niet langer bewaren dan nodig.
- **BIO:** het cookie alleen over https (BIO2 5.14/8.24) en zonder regeleinde, niet in procesargumenten
  (5.17/8.12), tekst van servers zonder escape-codes en met een groottegrens (8.28), geen vast
  uitvoerpad in `/tmp`, en het CA-advies dat ook voor de Python-meting werkt.

Buiten deze wijziging, in de uitvraag zelf en daarom in een eigen PR: een verwachte weigering (zoals
de 409 `nog-niet-opgehaald`) krijgt in het logboek status `ERROR` terwijl LDV §3.2.2.3 daar `UNSET`
vraagt, en de logregel van `_ophalen` staat altijd op `UNSET`, ook als de ophaalronde mislukte
(§3.3.1).

## Tests

`demo/test-meet-fanout.sh` draait het échte script tegen een nep-server in Python (één proces voor
uitvraag en paneel: gezondheid, een korte SSE-stream met `ophalen-gereed`, `GET /berichten` met de
sessies per ondernemer in het geheugen, en het verlopen-endpoint in een te kiezen modus). Een echte
server en geen `curl`-stub: wat curl met een 3xx, een 401 of een eigen `.curlrc` doet, is precies wat
getoetst wordt.

- **Geslaagd** (de lokale demo-stack zonder muur): `gewisteKeys` 2, en 0 bij alleen het eerste legen
  (JSON over meerdere regels), twee rondes → exit 0, elke ronde legen, toetsen en ophalen in die
  volgorde, uitvoerbestand met alle rijen. Ook een ronde zonder geslaagd magazijn: `eerste_ms`
  leeg, `compleet_ms` in zijn eigen kolom.
- **Geslaagd met cookie:** de muur laat alleen door met het juiste cookie; een cookie met spatie
  komt heel aan.
- **Afgebroken bij het eerste legen**, per vorm: 302, 303 en 307 (relatieve `Location` met query),
  302 zonder `Location`, 301 naar hetzelfde pad op een ander adres, 401, 403, 200 met een
  HTML-inlogpagina over meerdere regels, 204, 200 met ander JSON, een JSON-lijst, `gewisteKeys` als
  string en als boolean, 500 zonder en mét uitleg van het paneel, 403 mét uitleg, en een afgekapt
  antwoord. Steeds: exit ≠ 0, de juiste reden met het adres, de cookie-hint alleen bij een muur, de
  vorige uitvoer ongemoeid, nul ophaalrondes, één poging tot legen.
- **Afgebroken in een latere ronde:** een cookie dat na drie rondes verloopt, met de muur als 302,
  403 en 200-inlogpagina (drie rondes gemeten, de vierde begint niet).
- **De toets bij de uitvraag:** één keer per run, op de ondernemer van de eerste ronde, met
  `paginaGrootte=1` en een `Accept` met problem+json. Ziet de uitvraag nog een sessie, kent ze een
  mislukte ronde, haalt ze op of bereikt ze haar opslag niet, dan stopt de meting na één ronde met
  de bijbehorende uitleg; de melding noemt de ondernemer, niet zijn BSN.
- **De ophaalronde wordt geweigerd:** de melding noemt het `type` en de ondernemer, niet zijn BSN.
- **Het cookie:** over http naar een ander adres dan loopback, of met een regeleinde, stopt de meting
  vóór de eerste aanroep; tijdens een meting staat het in geen enkele argumentenlijst (de suite
  bekijkt `ps` terwijl curl op een traag paneel wacht, en toetst dat ze curl daar ook echt zag).
- **Escape-codes:** in de uitleg van het paneel, het `type`, het Content-Type en een absolute
  redirect komen niet op de terminal, en een regeleinde in het `type` levert geen eigen FOUT-regel.
- **Groottegrens:** een antwoord van 2 MiB stopt met curl's exitcode 63.
- **Zonder `UITVOER`:** de tabel staat in de map van de run.
- **Niets op het adres:** stopt met curl's exitcode, curl's eigen foutregel blijft zichtbaar.
- **Kapotte `python3`:** stopt, maar zonder verwijzing naar de inlogmuur.
- **Eigen `.curlrc` met `location`, `fail` en `include`:** 302 en 403 houden hun eigen melding, een
  geslaagd legen blijft geslaagd.
- **Muur zonder (geldig) cookie:** stopt, en het cookie staat niet in de uitvoer.

De nep-server krijgt zijn testcookie via een bestand, zodat de toets op de argumentenlijst het
niet bij de nep-server zelf vindt. De suite zelf is afgeschermd van de omgeving van wie hem draait:
een lege `.curlrc` in `CURL_HOME`
(curl zoekt dan niet verder naar die in `$HOME`), `no_proxy` voor de nep-server, en de kapotte
`python3` als geëxporteerde functie, zodat een TMPDIR zonder uitvoerrecht het geval niet ongemerkt
uitschakelt.

Elke bewaking is tegengeproefd: met de oude `curl -f`, met `isinstance` in plaats van
`type(...) is int`, zonder `-q`, zonder de eigen exitcode, met alleen het eerste legen
gecontroleerd, met de `-w`-regel of de body verkeerd afgesplitst, zonder de uitleg van het paneel,
met `-s` in plaats van `-sS`, zonder de toets bij de uitvraag, met elke 409 of een 200 als goed bij
de uitvraag, met tabs in `meet()`, met de tabel rechtstreeks in `UITVOER`, zonder de tak voor een
redirect naar hetzelfde pad, met het cookie als argument, zonder de https-eis, met een toets per
ronde, zonder `paginaGrootte`, met het nummer in een melding van `meet()`, zonder `type` bij een
weigering van de ophaalronde, zonder schoning van de servertekst, zonder groottegrens, met een
regeleinde in het cookie en met een vaste standaard voor `UITVOER` wordt de suite rood.

CI: de job `harness-scripts` in `fsc-harness-overlays.yml` shellcheckt `demo/*.sh` al en draait
op een wijziging daarin; zijn testlus krijgt `demo/test-*.sh` erbij, en een benoemde controle dat
`demo/test-meet-fanout.sh` ook wérkelijk gedraaid heeft — de suites onder `lib/` houden de bestaande
"minstens één suite"-teller anders altijd boven nul, en een bestand dat bestaat maar uit de lus
geraakt is, draait niet. `ci-scripts.yml` is bedoeld voor de scripts die de CI zelf sturen, en past
daar niet.

Bewust niet in deze wijziging:

- een ondergrens voor het aantal asserties per suite in die job, zoals `ci-scripts.yml` die heeft.
  De bestaande suites onder `demo/environment/lib/` missen die ook; dat is een eigen wijziging aan
  de job;
- een HTTP-contracttest op `/api/demo/sessie/verlopen` in `PaneelContractTest`. Een `@Mock`-dubbel
  van `SessieService` vervangt hem voor élke `@QuarkusTest` van de module, ook voor de leeg- en
  herstelknoppen die op `null` (Redis weg) leunen. De vorm is met de hand tegen het echte paneel
  nagegaan, en de consistentietest pint veldnaam en pad;
- een test op `--max-time`: dat vraagt een instelbare grens alleen voor de test, en zonder de vlag
  hangt de meting zichtbaar in plaats van verkeerde getallen te geven;
- de cookie-hint alleen tonen als `$CONSOLE/oauth2/auth` 401 geeft, zoals `inlogmuur.js` doet. De
  hint is voorwaardelijk geformuleerd en de melding noemt het antwoord zelf;
- een SSE-stream zonder `ophalen-gereed` fataal maken. Die ronde telt al niet mee in "compleet", en
  het hoort bij `meet()`, niet bij het legen.

## Stappen

1. `vervalcache()` herschrijven: bevestiging van het paneel en toets bij de uitvraag; kop en
   toelichting van het script bijwerken (niet alleen 403); `meet()` met `|`; de tabel pas na de
   laatste ronde naar `UITVOER`.
2. `demo/test-meet-fanout.sh` met de nep-server.
3. `fsc-harness-overlays.yml`: testlus uitbreiden, benoemde controle dat de suite draaide.
4. `SessieResource`: KDoc; `SessieResourceTest` met de consistentietest.
5. `demo/environment/zad-demo/verify-zad.md`: wat de operator op ZAD ziet, de handmatige route
   zonder cookie weg (die kon niet werken: het script leegt zelf en stopt zonder bevestiging), een
   rij voor "ziet na het legen nog een sessie". `docs/demo-runbook.md`: de oude premisse eruit.

## Verificatie

- `bash demo/test-meet-fanout.sh`
- `shellcheck -x -S warning demo/meet-fanout.sh demo/test-meet-fanout.sh` (via de
  koalaman-container)
- `./mvnw clean test -pl demo/demo-console -am`
- Het script tegen een écht draaiend bedieningspaneel (jar met een lokale Redis), met de uitvraag
  uit een nep-server: bevestigt dat het werkelijke antwoord van het paneel als bevestiging herkend
  wordt, en dat een uitvraag met eigen sessie-opslag de meting vóór de tweede ronde stopt.
