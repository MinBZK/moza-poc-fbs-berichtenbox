# Bruno-requests voor rechtstreeks ophalen

**Status:** Uitgevoerd

Refs MinBZK/MijnOverheidZakelijk#1215. Gestapeld op #418 (de Berichten-app haalt rechtstreeks op).

## Context

Het ontwerp (`2026-10-27-rechtstreeks-ophalen-design.md`, Verificatie) belooft een `.bru` per
nieuw endpoint van de tweede ingang: `challenges` als kaal verzoek, `sessies` en de
`berichten`-paden met een pre-request-script. PR 3 (#416) en PR 4 (#417) leverden ze niet; de
opdrachtgever koos voor een aparte PR na #418, omdat daar de app-kant van dezelfde cryptografie al
in JavaScript staat.

De paden staan in `rechtstreeks-ophalen-api.yaml` en draaien nu alleen in `demo/magazijn-simulator`,
onder `/magazijn/<OIN>/api/v1`.

## Ontwerpkeuzes

### 1. Waar de collectie komt: `bruno/berichtenmagazijn/rechtstreeks/`

Niet een nieuwe collectie `bruno/magazijn-simulator/`.

- De regel is "per service met een OpenAPI-spec", en de spec hoort bij het magazijn. De simulator
  is een tweede implementatie van die spec, geen eigen API.
- De hook `bruno-collectie-herinnering.sh` koppelt `services/berichtenmagazijn/.../*-api.yaml` aan
  `bruno/berichtenmagazijn/`. Een collectie elders zou hij als ontbrekend blijven melden.
- Zodra het echte magazijn de tweede ingang heeft (TODO(#1215) in de spec), wijst alleen de
  variabele `baseUrlSimulator` ergens anders heen; de requests blijven staan.

Een eigen variabele `baseUrlSimulator` (`http://localhost:8092/magazijn/<OIN>/api/v1`) naast
`baseUrlMagazijn`, omdat beide ingangen nu op verschillende diensten draaien.

### 2. Waar het credential vandaan komt: een lokale App Manager in het script

In de echte flow haalt de app het credential bij de demo-App Manager (4.4.1, `demo/app-manager`).
Bruno kan die flow zonder browser doorlopen: `tegen-app-manager.mjs` in de Berichten-app doet het
met fetch, door de doorverwijzing handmatig te volgen en de cookie en `Origin` van de keuzepagina mee
te sturen. Dat lost het probleem alleen niet op. De demo-App Manager tekent in ontwikkelmodus met
een wegwerpsleutel die elke start anders is, en de simulator vertrouwt een App Manager alleen via
`simulator.rechtstreeks.app-managers."<OIN>".sleutels."<kid>"`. Ook via de App Manager moet een
ontwikkelaar dus een sleutel met de hand bij de simulator instellen. Daarbovenop zouden er vier
requests zijn tegen een dienst zonder OpenAPI-spec, die een browser nabootsen.

Daarom ondertekent het pre-request-script het credential zelf:

- `npm run sleutel` (`lib/maak-sleutel.mjs`) maakt één keer een P-256-sleutel in
  `bruno/berichtenmagazijn/.env`. Dat bestand valt onder de `.env`-regel van `.gitignore` en krijgt
  modus `0600`. Het script toont de publieke helft als `-D`-instellingen voor `quarkus:dev`.
- Eigen OIN en kid (`00000000000000001199`, `bruno-lokaal`), los van de demo-App Manager
  (`00000000000000001100`).
- Geen sleutel en geen vertrouwde publieke sleutel in een ingecheckt bestand, ook niet onder
  `%dev`. Een simulator vertrouwt hem alleen als hij met die instellingen op de commandoregel
  start. Een ZAD-omgeving krijgt ze dus niet, tenzij iemand ze daar met de hand zet: de simulator
  neemt ze in elk profiel aan, dus dat is een afspraak en geen afdwinging. De README waarschuwt
  ervoor, en `geldig-tot` ligt negentig dagen vooruit, zodat een vergeten instelling vervalt.

### 3. De scriptruntime: developer-sandbox, `node:crypto`, geen jose

- Bruno-CLI **4.3.0** (22 oktober 2026; de nieuwste, en ouder dan twee weken). `bruno.json`
  `"version": "1"` is de versie van het bestandsformaat, niet die van Bruno.
- De safe-sandbox laadt geen lokale module (`require('./lib/...')` faalt). De developer-sandbox wel,
  en die heeft `node:crypto` en `crypto.subtle`. Daarom staat `--sandbox developer` in elk
  commando.
- **jose werkt niet in Bruno.** De developer-sandbox laadt npm-modules als CommonJS in een
  `vm`-context. jose 6 is alleen ESM (`Cannot use import statement outside a module`), en
  `import()` heeft daar geen callback (`A dynamic import callback was not specified`). jose 5 heeft
  nog CommonJS, maar dan staan er twee hoofdversies in de repo, en Dependabot zou de oude telkens
  naar 6 willen tillen.
- Daarom staat de JOSE-code in `lib/rechtstreeks.js` op `node:crypto`. Alleen wat het magazijn
  accepteert: ES256, `ECDH-ES` met Concat KDF en `A256GCM`, `dir` met `A256GCM`. Dat volgt het
  voorbeeld van `TestApp` in `fbs-rechtstreeks`: een tweede implementatie naast jose (de app) en
  jose4j (de Verifier). De code toetst zich ook tegen jose (`npm test`,
  `lib/rechtstreeks.test.mjs`): wat de ene maakt, moet de andere lezen. Nu 6.2.13, net als de app;
  door de langere cooldown hier kan een bump een week later komen dan bij de app.
- Uit de Berichten-app komt de volgorde van `openSessie` (`js/activeren.js`). Niet overgenomen is de
  niet-exporteerbare WebCrypto-sleutel: Bruno heeft geen kluis, en de sleutels van de app en de
  sessie leven één run in het geheugen van het script.
- `@usebruno/cli` en `jose` staan vast in `bruno/berichtenmagazijn/package.json` en de lockfile.
  Dependabot houdt ze bij met een cooldown van veertien dagen, in één PR per ronde.
- De CLI pint `axios`, `form-data`, `js-yaml` en `yaml` exact, op versies met bekende advisories;
  een security update van Dependabot kan die niet ophogen. `overrides` geven een ondergrens
  (`^1.21.0`, `^4.0.6`, `^4.3.2`, `^2.8.3`), geen exacte versie. Dependabot wijzigt `overrides`
  niet, maar hoogt de lockfile op binnen de range; met een exacte waarde kon een security fix
  binnen dezelfde major dus niet landen. Een fix in een nieuwe major vraagt handwerk. De
  lockfile is opgebouwd met `npm install --before=2026-11-05`, zodat ook geen transitieve versie
  jonger is dan twee weken. `lib/overrides.test.mjs` faalt als een override lager uitkomt dan wat
  een pakket in de boom vraagt (exact of als range, ook genest), of een andere major oplegt; een
  override wint anders zonder melding. De test leest de lockfile, maar geen workflow draait hem: de README vraagt hem op elke
  Dependabot-PR met de hand. `npm audit` gaat van 18
  meldingen (8 high) naar 12 (4 high). Wat blijft, is `@faker-js/faker` 9 (de fix vraagt een nieuwe
  major onder `@usebruno/requests`) en moderate meldingen: aanvaard, omdat de CLI alleen op een
  ontwikkelmachine tegen localhost draait met een collectie uit deze repo.
- In commando's `npm run rechtstreeks`, niet `npx bru`: zonder lokale installatie haalt `npx` het
  npm-pakket `bru` op, dat iets anders is.

### 4. Wat de requests tonen en toetsen

Na het ontsleutelen vervangt het post-response-script de body door de payload (`res.setBody`).
Bruno toont dan de payload, en de `assert`-blokken lopen erop. Elke request van de tweede ingang
toetst status, `Content-Type`, `API-Version` en de CORS-headers die het contract op elk antwoord
eist; de geslaagde ook `Cache-Control: no-store` en de vorm van de payload. Naast het hoofdpad
staan de foutpaden die het contract benadrukt: een tweede poging met dezelfde nonce (401), een
bericht van een andere ontvanger (404, met een tweede demo-persona, Grootbedrijf B.V.), met
dezelfde status, `type`, `title` en `detail` (op het id na) als een bericht dat niet bestaat, een onbekend token en geen token (401, RFC 6750).
De lijst wordt getoetst op alleen berichten van de ontvanger uit de sessie, en het totaal tegen dat
van de eerste ingang (via `bru.sendRequest` in het script). Beide ingangen tellen in de simulator via
dezelfde query: dat toetst dat de tweede niet anders telt, niet de telling zelf. Niet opgenomen: het `400`-pad waarbij de
nonce bruikbaar blijft, en `406`; de tests van de simulator dekken ze. Het sessietoken en de AES-sleutel staan in runtime-variabelen (`bru.setVar`),
die Bruno niet in een bestand schrijft. In het antwoordvenster van `sessies` staat het token niet,
alleen dat het de vorm uit de spec heeft.

De challenge-handtekening wordt **niet** gecontroleerd. De simulator ondertekent met een
wegwerpsleutel per start (`MagazijnVerifiers`, TODO(#1215)), en het stelseldocument noemt bij
organisaties geen `public_keys`. Het script toetst de header (`ES256`, een `kid`), de payload en de
tijdelijke sleutel (P-256), en meldt in de console dat de handtekening niet gecontroleerd is. De
app (#418) doet hetzelfde met TODO(#1216).

### 5. Omgevingen

`lokaal` krijgt `baseUrlSimulator`, `simulatorOin` en `rechtstreeksOntvanger` (`KVK:90000014`,
demo-persona Garage Van Dijk B.V.). `zad` krijgt ze niet: de tweede ingang staat daar nog niet open
(#416, #455).

## Verificatie

- `npm test` in `bruno/berichtenmagazijn`: de JOSE-code tegen jose.
- `bru run rechtstreeks --env lokaal --sandbox developer` tegen een simulator in ontwikkelmodus, met
  PostgreSQL op podman: 10 requests, 73 asserts en 7 tests groen. `npm test`: 40 tests.
- Mutaties in een kopie van de collectie (`bru run` of `npm test`), onder meer de ontvanger in het
  credential, het algoritme-id en de lengte in de Concat KDF, `typ` uit de JWE, `aud` van de
  nonce-handtekening, base64url voor de AES-sleutel, de scope, een vaste app-sleutel, een leeg
  antwoord, een ander bericht-id, de realm, de header-asserts, de controles op header, curve, IV,
  `kid`, `typ`, `alg` en het aantal delen, een lekkend totaal, een bericht van een andere
  ontvanger, een afwijkende 404 (`detail` of een extra veld), en overrides die een gevraagde versie
  terugzetten (exact, als range, genest, transitief) of een andere major opleggen. 39 mutaties, 39
  gevangen.
- Niet in CI: geen workflow draait `bru run`. Dat vraagt een simulator met database in de job en een
  sleutel per run; dat is een keuze voor de opdrachtgever.
