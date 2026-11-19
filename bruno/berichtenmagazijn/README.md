# Bruno-collectie berichtenmagazijn

Voorbeeld-requests voor de API's van het magazijn, uit `berichtenmagazijn-api.yaml` en
`rechtstreeks-ophalen-api.yaml`. Open de map in [Bruno](https://www.usebruno.com/) en kies
environment `lokaal`.

| Map | Wat | Tegen |
|---|---|---|
| `aanleveren/`, `ophalen-beheer/`, `specificatie/` | eerste ingang: aanleveren, ophalen en beheren met `X-Ontvanger` | het magazijn (`baseUrlMagazijn`, poort 8090) |
| `rechtstreeks/` | tweede ingang: challenge, magazijnsessie, berichten van de ontvanger uit de sessie | de magazijn-simulator (`baseUrlSimulator`, poort 8092) |

## Rechtstreeks ophalen

De tweede ingang draait nu alleen in `demo/magazijn-simulator`, onder
`/magazijn/<OIN>/api/v1`. Het echte magazijn volgt (MinBZK/MijnOverheidZakelijk#1215); dan wijst
alleen `baseUrlSimulator` ergens anders heen. De environment `zad` noemt hem niet: de tweede
ingang is daar nog niet bereikbaar.

De requests doen wat de app van de ondernemer doet (FSC voor Burgers 4.3.4.2): een challenge
vragen, een Verifiable Presentation versleuteld aanbieden, en de versleutelde antwoorden
ontsleutelen. De cryptografie staat in `lib/rechtstreeks.js`, op `node:crypto`. Daarom draaien de
requests alleen in de **developer**-sandbox van Bruno: de safe-sandbox laadt geen lokale module. In
de app kies je bij de collectie de JavaScript-sandbox *Developer Mode*; `bru run` krijgt
`--sandbox developer`. Die modus geeft de scripts van de collectie toegang tot Node: open er alleen
een collectie mee die je vertrouwt.

Het credential komt van een **lokale App Manager** die alleen op je eigen machine bestaat, en niet
van de demo-App Manager (`demo/app-manager`). Bij die demo kies je een persona in de browser, en
een simulator vertrouwt zijn sleutel alleen als je hem met de hand instelt; elke herstart van de
App Manager maakt er een nieuwe. Het pre-request-script van `4-sessie-starten` ondertekent het
credential daarom zelf, met een sleutel die je één keer maakt:

```bash
cd bruno/berichtenmagazijn
npm ci --ignore-scripts      # @usebruno/cli en jose, vast in package-lock.json
npm run sleutel              # maakt .env (gitignored) en toont de instellingen voor de simulator
```

De private key staat daarna in `bruno/berichtenmagazijn/.env`. Die sleutel is **alleen voor
lokaal gebruik**: niet inchecken, niet delen, en zet de instellingen nooit op een gedeelde of
ZAD-omgeving. Dat is een afspraak, geen afdwinging: de simulator neemt de instellingen in elk
profiel aan. Wel staat de sleutel in geen enkel ingecheckt bestand, en `geldig-tot` ligt negentig
dagen vooruit, zodat een vergeten instelling vanzelf vervalt. Het credential hoort bij de
demo-persona Garage Van Dijk B.V. (`KVK:90000014`, `rechtstreeksOntvanger`); request 2 zet een
bericht klaar voor Grootbedrijf B.V. (`KVK:90000001`, `rechtstreeksAndereOntvanger`).

Start de simulator met die instellingen (vanuit de root van de repo, met de database uit compose):

```bash
docker compose --profile demo up -d postgres-simulator
./mvnw install -pl demo/magazijn-simulator -am -DskipTests
./mvnw quarkus:dev -pl demo/magazijn-simulator $(npm --prefix bruno/berichtenmagazijn run -s sleutel 2>/dev/null)
```

Bij de start logt de simulator `Rechtstreeks ophalen: 1 App Manager(s) vertrouwd`. Staat er
`geen App Manager vertrouwd`, draai dan `npm run sleutel` los: de `2>/dev/null` hierboven verbergt
zijn foutmelding. Draai daarna de map; de requests hangen in deze volgorde van elkaar af:

```bash
cd bruno/berichtenmagazijn
npm run rechtstreeks     # bru run rechtstreeks --env lokaal --sandbox developer
```

`npm run` start de vastgezette CLI uit `node_modules`, en faalt als die er niet is. Gebruik geen
kale `npx bru`: zonder `npm ci` in deze map haalt `npx` het npm-pakket `bru` op, dat iets heel anders
is. En nooit `npx @usebruno/cli@latest`.

| Request | Wat het laat zien |
|---|---|
| `1-bericht-klaarzetten` | levert via de eerste ingang een bericht aan voor de ontvanger, zodat de lijst iets toont |
| `2-bericht-voor-ander-klaarzetten` | hetzelfde voor een andere demo-persona; requests 6 en 8 toetsen dat de sessie dat bericht niet ziet |
| `3-challenge-opvragen` | kaal verzoek; toont header en payload van de JWS. De handtekening is niet te controleren: de simulator ondertekent met een wegwerpsleutel die in geen stelseldocument staat |
| `4-sessie-starten` | bouwt de hele `CreateSession`: app-sleutelpaar, credential, `app_nonce_signature` (ES256), AES-sleutel van 32 bytes, JWE met `ECDH-ES` en `A256GCM`. Ontsleutelt het antwoord (`dir`) |
| `5-nonce-opnieuw` | foutpad: hetzelfde verzoek nog eens geeft `401`, want een nonce is één keer bruikbaar |
| `6-berichtenlijst-ophalen` | `Authorization: Bearer {{rechtstreeksSessieToken}}`; toont de ontsleutelde lijst, met alleen berichten van de ontvanger uit de sessie |
| `7-bericht-ophalen` | het klaargezette bericht, ontsleuteld |
| `8-bericht-van-ander` | foutpad: het bericht van request 2 geeft `404`, gelijk aan dat van een bericht dat niet bestaat |
| `9-ongeldig-token` | foutpad: `401` met `WWW-Authenticate: Bearer realm="<OIN>", error="invalid_token"` |
| `10-zonder-token` | foutpad: `401` met alleen de realm |

Elke request van de tweede ingang toetst ook de headers die het contract op elk antwoord eist,
foutantwoorden inbegrepen: `API-Version` en de CORS-headers.

Requests 1 en 2 schrijven bij elke run een bericht in de database van de simulator, met
`Bruno-test:` in het onderwerp. Gebruikt de demo dezelfde database, dan staan die berichten daar
ook; start voor een demo met een schone database.

Na een ontsleuteld antwoord toont Bruno de payload in plaats van de JWE; de asserts lopen op die
payload. Het sessietoken en de AES-sleutel staan in runtime-variabelen
(`rechtstreeksSessieToken`, `rechtstreeksAesSleutel`) en nooit in een bestand. Een sessie duurt
hooguit vijftien minuten en een challenge zestig seconden: draai bij een `401` opnieuw vanaf
request 3.

De JOSE-code toetst zichzelf tegen jose als tweede implementatie, zonder simulator:

```bash
npm test
```

## Afhankelijkheden

`@usebruno/cli` pint een deel van zijn afhankelijkheden exact, ook versies met bekende advisories.
Dependabot kan die niet ophogen. Daarom staan `axios`, `form-data`, `js-yaml` en `yaml` onder
`overrides` in `package.json`, als ondergrens (`^`). Een security fix binnen dezelfde major komt
dan via de lockfile-update van Dependabot. Vraagt de fix een nieuwe major, dan pas je de override
met de hand aan: Dependabot wijzigt `overrides` zelf niet.

Een override wint zonder melding, ook van een hogere versie die een nieuwe CLI zelf vraagt.
`npm test` faalt dan (`lib/overrides.test.mjs`, op de lockfile). Geen workflow draait die test:
**draai `npm test` op elke Dependabot-PR voor deze map** voor je hem goedkeurt. Bekijk bij een bump
van de CLI ook of de overrides nog nodig zijn. Wat overblijft (`npm audit`: 12 meldingen, waarvan 4 high) zijn `@faker-js/faker` 9, waarvan de
fix een nieuwe major vraagt, en moderate meldingen. Dat risico nemen we: de CLI draait alleen op een
ontwikkelmachine, tegen localhost, met een collectie uit deze repo. Bruno geeft geen invoer van
buiten aan faker. Dependabot kan deze meldingen niet oplossen; ze horen in het dashboard als
aanvaard te staan, met deze reden.
