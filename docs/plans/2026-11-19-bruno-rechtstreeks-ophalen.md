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
  start. Een ZAD-omgeving krijgt ze dus niet, tenzij iemand ze daar met de hand zet. De README
  waarschuwt daarvoor.

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
  jose4j (de Verifier). De code toetst zich ook tegen jose 6.2.13, dezelfde versie als de app
  (`npm test`, `lib/rechtstreeks.test.mjs`): wat de ene maakt, moet de andere lezen.
- Uit de Berichten-app komt de volgorde van `openSessie` (`js/activeren.js`). Niet overgenomen is de
  niet-exporteerbare WebCrypto-sleutel: Bruno heeft geen kluis, en de sleutels van de app en de
  sessie leven één run in het geheugen van het script.
- `@usebruno/cli` en `jose` staan vast in `bruno/berichtenmagazijn/package.json` en de lockfile.
  Dependabot houdt ze bij met een cooldown van veertien dagen.

### 4. Wat de requests tonen en toetsen

Na het ontsleutelen vervangt het post-response-script de body door de payload (`res.setBody`).
Bruno toont dan de payload, en de `assert`-blokken lopen erop. Status, `Content-Type`
`application/jose` en `Cache-Control: no-store` toetsen ze altijd; elke request toetst daarnaast de
vorm van de payload. Het sessietoken en de AES-sleutel staan in runtime-variabelen (`bru.setVar`),
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
  PostgreSQL op podman: 6 requests, 32 asserts en 2 tests groen.
- Mutaties in een kopie van de collectie (`bru run` of `npm test`): de ontvanger in het credential,
  het algoritme-id in de Concat KDF, `typ` uit de JWE, `aud` van de nonce-handtekening, een leeg
  antwoord, een ander bericht-id, de realm in `WWW-Authenticate`, de lengte in de KDF, de
  header-, curve-, IV- en `kid`-controle. Twaalf mutaties, twaalf gevangen.
- Niet in CI: geen workflow draait `bru run`. Dat vraagt een simulator met database in de job en een
  sleutel per run; dat is een keuze voor de opdrachtgever.
