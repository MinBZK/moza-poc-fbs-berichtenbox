# Fan-out-meting stopt als het legen niet bevestigd is — implementatieplan

**Status:** Uitgevoerd

**Doel:** `demo/meet-fanout.sh` begint geen ronde zonder aantoonbaar geleegde sessiecache
([MinBZK/MijnOverheidZakelijk#1073](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1073)).
Tot nu toe kon een inlogmuur het legen stil laten mislukken, en dan meet elke ronde ná de eerste het
cache-pad: de fan-out lijkt gratis.

## Context

`vervalcache()` roept `POST $CONSOLE/api/demo/sessie/verlopen` aan met `curl -sS -f`. `-f` faalt
alleen op HTTP ≥ 400. Een inlogmuur die een aanroep zonder geldige sessie omleidt (302/303) of de
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
  - `curl -q`: zonder die vlag leest curl de `~/.curlrc` van wie meet, en een `location` of `fail`
    daarin verandert welke reden de melding noemt.
- **Melding per manier van mislukken**, steeds met het adres en de afsluiting dat de meting stopt:
  - niet bereikbaar (curl-exitcode ≠ 0; curl's eigen melding staat erboven);
  - 3xx, met het doorverwijsadres zonder query — die van een inlogpagina is lang en zegt de lezer
    niets, het pad (`/oauth2/start`, Keycloak) wel;
  - 4xx/5xx met de statuscode;
  - 2xx zonder bevestiging, met het `Content-Type` (een `text/html` maakt een inlogpagina meteen
    herkenbaar);
  - 2xx waarvan `python3` het antwoord niet kon controleren, met de exitcode.
  - Bij de vormen die op een muur wijzen (3xx, 401, 403, 2xx zonder bevestiging) een regel over
    `CONSOLE_COOKIE`: ontbreekt het, of is het verlopen.
- **Geen extra controle op `gewisteKeys = 0`.** Ná een geslaagde ronde zou er altijd iets te wissen
  moeten zijn, en nul wijst dan op een paneel dat naar een andere Redis kijkt dan de uitvraag. Dat
  valt buiten dit issue (dat gaat over de muur) en een tweede, inhoudelijke controle verdient een
  eigen afweging; nul is bij de allereerste ronde bovendien gewoon geldig.
- **Het cookie komt in geen enkele melding terug:** het is een geldige sessie op een rijksaccount.
- **Contract vastleggen aan de paneelkant:** KDoc op `SessieResource.verlopen()` dat het veld door het
  paneel (`bediening.js`) én de meting gelezen wordt, en een unittest die de veldnaam vastpint. Een
  hernoeming faalt de meting luid, maar dan met een melding die naar de muur wijst; de test wijst
  naar de juiste plek.

## Tests

`demo/test-meet-fanout.sh` draait het échte script tegen een nep-server in Python (één proces voor
uitvraag en paneel: gezondheid, een korte SSE-stream met `ophalen-gereed`, en het verlopen-endpoint
in een te kiezen modus). Een echte server en geen `curl`-stub: de fout zat juist in een aanname over
wat curl met een 3xx doet, en een stub zou die aanname herhalen.

- **Geslaagd** (de lokale demo-stack zonder muur): `gewisteKeys` 2 en 0, twee rondes → exit 0, elke
  ronde één legen vóór één ophaalronde, uitvoerbestand met alle rijen.
- **Geslaagd met cookie:** de muur laat alleen door met het juiste cookie; een cookie met spatie
  komt heel aan.
- **Afgebroken**, per vorm: 302, 303 en 307 (relatief `Location` met query), 401, 403, 200 met een
  HTML-inlogpagina, 204, 200 met ander JSON, een JSON-lijst, `gewisteKeys` als string en als
  boolean, 500, en een adres waar niets luistert. Steeds: exit ≠ 0, het adres in de melding, de
  juiste reden, de cookie-hint alleen bij een muur, nul ophaalrondes.
- **Kapotte `python3`:** stopt, maar zonder verwijzing naar de inlogmuur.
- **Eigen `.curlrc` met `location` en `fail`:** 302 en 403 houden hun eigen melding.
- **Muur zonder (geldig) cookie:** stopt, en het cookie staat niet in de uitvoer.

Elke bewaking is tegengeproefd: met de oude `curl -f`, met `isinstance` in plaats van
`type(...) is int`, zonder `-q` en zonder de eigen exitcode wordt de suite rood.

CI: de job `harness-scripts` in `fsc-harness-overlays.yml` shellcheckt `demo/*.sh` al en draait
op een wijziging daarin; zijn testlus krijgt `demo/test-*.sh` erbij. `ci-scripts.yml` is bedoeld
voor de scripts die de CI zelf sturen, en past daar niet.

Bewust niet in deze wijziging: een ondergrens voor het aantal asserties per suite in die job, zoals
`ci-scripts.yml` die heeft. De bestaande suites onder `demo/environment/lib/` missen die ook; dat is
een eigen wijziging aan de job, los van dit issue.

## Stappen

1. `vervalcache()` herschrijven; kop en toelichting van het script bijwerken (niet alleen 403).
2. `demo/test-meet-fanout.sh` met de nep-server.
3. `fsc-harness-overlays.yml`: testlus uitbreiden.
4. `SessieResource`: KDoc; `SessieResourceTest`.
5. `demo/environment/zad-demo/verify-zad.md`: de 403-zin breder maken (omleiden, 200-inlogpagina).

## Verificatie

- `bash demo/test-meet-fanout.sh`
- `shellcheck -x -S warning demo/meet-fanout.sh demo/test-meet-fanout.sh` (via de
  koalaman-container)
- `./mvnw clean test -pl demo/demo-console -am`
- Het script tegen een écht draaiend bedieningspaneel (dev-mode met Redis), met de uitvraag uit de
  nep-server: bevestigt dat het werkelijke antwoord van het paneel als bevestiging herkend wordt.
