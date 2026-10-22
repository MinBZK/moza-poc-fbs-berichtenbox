# Fan-out-meting stopt als het legen niet bevestigd is — implementatieplan

**Status:** Uitgevoerd

**Doel:** `demo/meet-fanout.sh` begint geen ronde zonder aantoonbaar geleegde sessiecache
([MinBZK/MijnOverheidZakelijk#1073](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1073)).
Tot nu toe kon een inlogmuur het legen stil laten mislukken. Het issue gaat ervan uit dat elke ronde
ná de eerste dan het cache-pad meet; zie "Bevinding uit de review" voor waarom dat voor de
huidige code niet opgaat, en welke cache wél warm blijft.

## Context

`vervalcache()` roept `POST $CONSOLE/api/demo/sessie/verlopen` aan met `curl -sS -f`. `-f` faalt
alleen op HTTP ≥ 400. Een inlogmuur die een aanroep zonder geldige sessie redirect (302/303) of de
inlogpagina meteen met 200 teruggeeft, komt daar ongehinderd doorheen. De oauth2-proxy op ZAD geeft
nu 403 (zie `inlogmuur.js`), maar dat is configuratie van de muur en geen eigenschap waar de meting
op mag leunen.

Het bedieningspaneel antwoordt na het wissen met `{"gewisteKeys": N}` (`application/json`). Dat
veld leest het paneel zelf al voor zijn samenvatting (`bediening.js`), en geen inlogpagina draagt
het. Het is dus al het "iets in het antwoord dat de meting kan herkennen" waar het issue naar vraagt.

## Ontwerp

- **Bevestiging = 2xx én een JSON-object met een geheel getal `gewisteKeys`.** Een statuscode alleen
  is niet genoeg: een inlogpagina met 200 haalt elke statuscontrole. Een 204, een leeg antwoord of
  ander JSON telt ook als niet bevestigd.
  - Gekozen boven een nieuwe header of een marker-veld in het paneel (de optie uit het issue): het
    veld bestaat al, het paneel leunt er zelf op, en een extra marker zou een tweede contract naast
    het eerste zijn. Het paneel hoeft dus niet te veranderen.
  - Gekozen boven `-L` of `--fail-with-body`: de eerste volgt de redirect naar een inlogpagina die
    met 200 antwoordt, de tweede verandert niets aan de 3xx-behandeling.
  - Het JSON-ontleden gaat via `python3`, dat het script al vereist. Een regex op
    `"gewisteKeys":[0-9]+` zou breken op een andere serialisatie (spaties, indentatie).
  - "Niet bevestigd" krijgt een eigen exitcode (3). Een traceback geeft 1 en een ontbrekende
    `python3` 127; zonder onderscheid zou de melding dan naar de inlogmuur wijzen.
  - `curl -q`: zonder die vlag leest curl de `~/.curlrc` van wie meet, en een `location`, `fail` of
    `include` daarin verandert wat er binnenkomt.
  - `--max-time 60`: een paneel dat niet antwoordt, zou de meting anders zonder melding laten hangen.
- **Vanaf het tweede legen moet er iets gewist zijn.** Na elke ronde die de uitvraag bereikt, staat
  er een `:status`-key met de sessie-TTL (12 uur), ook bij een lege set organisaties. "0 gewist"
  betekent dan dat het paneel naar een andere Redis kijkt dan de uitvraag, bijvoorbeeld met
  `UITVRAAG` op ZAD en `CONSOLE` nog op de lokale default. Bij het allereerste legen is 0 geldig.
  - Eerst buiten de scope gelaten; na de review omgedraaid. Het issue vraagt om een legen dat
    *aantoonbaar* gelukt is, en een bevestiging van een paneel dat de gemeten sessie niet raakt, is
    dat niet.
- **Melding per manier van mislukken**, steeds met het adres en de afsluiting dat de meting stopt:
  - geen antwoord, met curl's exitcode (curl's eigen foutregel staat erboven);
  - 3xx, met de redirect zonder query — die van een inlogpagina is lang en zegt de lezer niets, het
    pad (`/oauth2/start`, Keycloak) wel;
  - 4xx/5xx met de statuscode, en met de uitleg die het paneel zelf meegeeft
    (`{"fout": ...}` van `DemoFoutMapper`, op één regel en ingekort);
  - 2xx zonder bevestiging, met het `Content-Type` (een `text/html` maakt een inlogpagina meteen
    herkenbaar);
  - 2xx waarvan `python3` het antwoord niet kon controleren, met de exitcode;
  - 0 gewist na een geslaagde ronde, met de vraag of paneel en uitvraag dezelfde Redis delen.
  - Bij de vormen die op een muur wijzen (3xx, 401 en 403 zónder uitleg van het paneel, 2xx zonder
    bevestiging) een regel over `CONSOLE_COOKIE`: ontbreekt het, of is het verlopen. Geeft het
    paneel zelf de fout, dan is het geen muur en ontbreekt die regel.
- **Het cookie komt in geen enkele melding terug:** het is een geldige sessie op een rijksaccount.
- **Contract vastleggen aan de paneelkant:** KDoc op `SessieResource.verlopen()` dat het veld door het
  paneel (`bediening.js`) én de meting gelezen wordt. `SessieResourceTest` pint de veldnaam, en een
  tweede test toetst dat `meet-fanout.sh` en `bediening.js` dezelfde veldnaam en (het script) hetzelfde
  pad gebruiken als de resource. Die twee lezers hernoemt geen refactoring mee.

## Bevinding uit de review: de premisse van het issue

De review vond dat een gevulde sessiecache `_ophalen` niet korter maakt. `haalBerichtenOp`
(`BerichtensessiecacheService`) neemt alleen een aparte `:lock`-key en bevraagt daarna altijd alle
organisaties; de Redis-reads (`getPage`, `search`, `getById`) horen bij `GET /berichten`. Zonder
legen doet ronde 2 dus dezelfde fan-out. Nagegaan in de code, niet gemeten.

Wat wél warm blijft tussen rondes, is de Profiel-resolver-cache in de uitvraag zelf
(`profiel.resolver.cache.ttl-seconds=30`, in-process). Rondes van één ondernemer lopen direct na
elkaar, dus ronde 2 en verder slaan de Profiel-aanroep over. Het paneel kan die cache niet legen.

Gevolgen voor deze wijziging:

- De code blijft zoals het issue vraagt: geen ronde zonder bevestigd legen. Het legen voorkomt nog
  steeds dat een ronde de sessie of de lock van een afgebroken vorige ronde erft.
- De teksten die deze wijziging toevoegt of aanraakt, beweren niet langer dat een niet-geleegde
  sessiecache "het cache-pad" laat meten.
- Of de meting de Profiel-cache moet omzeilen (`PROFIEL_CACHE_TTL_SECONDS=0` op de gemeten
  uitvraag, of meer dan 30 s tussen de rondes van één ondernemer) is een keuze over de meetmethode.
  Die raakt de vergelijkbaarheid met de eerdere metingen in
  `docs/plans/2026-08-21-magazijn-simulator-design.md`, en hoort in een eigen issue.
  `docs/demo-runbook.md` herhaalt de oude premisse en hoort daarin mee te gaan.

## Tests

`demo/test-meet-fanout.sh` draait het échte script tegen een nep-server in Python (één proces voor
uitvraag en paneel: gezondheid, een korte SSE-stream met `ophalen-gereed`, en het verlopen-endpoint
in een te kiezen modus). Een echte server en geen `curl`-stub: wat curl met een 3xx, een 401 of een
eigen `.curlrc` doet, is precies wat getoetst wordt.

- **Geslaagd** (de lokale demo-stack zonder muur): `gewisteKeys` 2, en 0 bij alleen het eerste legen
  (JSON over meerdere regels), twee rondes → exit 0, elke ronde één legen vóór één ophaalronde,
  uitvoerbestand met alle rijen.
- **Geslaagd met cookie:** de muur laat alleen door met het juiste cookie; een cookie met spatie
  komt heel aan.
- **Afgebroken bij het eerste legen**, per vorm: 302, 303 en 307 (relatieve `Location` met query),
  302 zonder `Location`, 401, 403, 200 met een HTML-inlogpagina over meerdere regels, 204, 200 met
  ander JSON, een JSON-lijst, `gewisteKeys` als string en als boolean, 500 zonder en mét uitleg van
  het paneel, en 403 mét uitleg van het paneel. Steeds: exit ≠ 0, het adres in de melding, de juiste
  reden, de cookie-hint alleen bij een muur, nul ophaalrondes, één poging tot legen.
- **Afgebroken in een latere ronde:** een cookie dat na drie rondes verloopt (drie rondes gemeten,
  de vierde begint niet), en een paneel dat steeds 0 wist (na de eerste ronde begint er geen tweede).
- **Niets op het adres:** stopt met curl's exitcode, curl's eigen foutregel blijft zichtbaar.
- **Kapotte `python3`:** stopt, maar zonder verwijzing naar de inlogmuur.
- **Eigen `.curlrc` met `location`, `fail` en `include`:** 302 en 403 houden hun eigen melding, een
  geslaagd legen blijft geslaagd.
- **Muur zonder (geldig) cookie:** stopt, en het cookie staat niet in de uitvoer.

De suite zelf is afgeschermd van de omgeving van wie hem draait: een lege `.curlrc` in `CURL_HOME`
(curl zoekt dan niet verder naar die in `$HOME`) en `no_proxy` voor de nep-server.

Elke bewaking is tegengeproefd: met de oude `curl -f`, met `isinstance` in plaats van
`type(...) is int`, zonder `-q`, zonder de eigen exitcode, met alleen het eerste legen
gecontroleerd, met de statusregel of de body verkeerd afgesplitst, zonder de nul-controle, zonder
de uitleg van het paneel en met `-s` in plaats van `-sS` wordt de suite rood.

CI: de job `harness-scripts` in `fsc-harness-overlays.yml` shellcheckt `demo/*.sh` al en draait
op een wijziging daarin; zijn testlus krijgt `demo/test-*.sh` erbij, en een benoemde controle dat
`demo/test-meet-fanout.sh` bestaat — de suites onder `lib/` houden de bestaande "minstens één
suite"-teller anders altijd boven nul. `ci-scripts.yml` is bedoeld voor de scripts die de CI zelf
sturen, en past daar niet.

Bewust niet in deze wijziging: een ondergrens voor het aantal asserties per suite in die job, zoals
`ci-scripts.yml` die heeft. De bestaande suites onder `demo/environment/lib/` missen die ook; dat is
een eigen wijziging aan de job, los van dit issue.

## Stappen

1. `vervalcache()` herschrijven; kop en toelichting van het script bijwerken (niet alleen 403).
2. `demo/test-meet-fanout.sh` met de nep-server.
3. `fsc-harness-overlays.yml`: testlus uitbreiden, benoemde controle op de suite.
4. `SessieResource`: KDoc; `SessieResourceTest` met de consistentietest.
5. `demo/environment/zad-demo/verify-zad.md`: wat de operator op ZAD ziet, en de handmatige route
   zonder cookie weg (die kon niet werken: het script leegt zelf en stopt zonder bevestiging).

## Verificatie

- `bash demo/test-meet-fanout.sh`
- `shellcheck -x -S warning demo/meet-fanout.sh demo/test-meet-fanout.sh` (via de
  koalaman-container)
- `./mvnw clean test -pl demo/demo-console -am`
- Het script tegen een écht draaiend bedieningspaneel (jar met een lokale Redis), met de uitvraag
  uit een nep-server: bevestigt dat het werkelijke antwoord van het paneel als bevestiging herkend
  wordt.
