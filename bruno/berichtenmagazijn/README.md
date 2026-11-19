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
App Manager maakt er een nieuwe. Het pre-request-script van `3-sessie-starten` ondertekent het
credential daarom zelf, met een sleutel die je één keer maakt:

```bash
cd bruno/berichtenmagazijn
npm ci --ignore-scripts      # @usebruno/cli en jose, vast in package-lock.json
npm run sleutel              # maakt .env (gitignored) en toont de instellingen voor de simulator
```

De private key staat daarna in `bruno/berichtenmagazijn/.env`. Die sleutel is **alleen voor
lokaal gebruik**: niet inchecken, niet delen, en zet de instellingen nooit op een gedeelde of
ZAD-omgeving. Hij staat in geen enkel ingecheckt bestand, en een simulator vertrouwt hem alleen als
je hem met de instellingen hieronder start. Het credential hoort bij de demo-persona Garage Van
Dijk B.V. (`KVK:90000014`, `rechtstreeksOntvanger`).

Start de simulator met die instellingen (vanuit de root van de repo, met de database uit compose):

```bash
docker compose --profile demo up -d postgres-simulator
./mvnw install -pl demo/magazijn-simulator -am -DskipTests
./mvnw quarkus:dev -pl demo/magazijn-simulator $(npm --prefix bruno/berichtenmagazijn run -s sleutel 2>/dev/null)
```

Bij de start logt de simulator `Rechtstreeks ophalen: 1 App Manager(s) vertrouwd`. Draai dan de
map, in deze volgorde:

```bash
cd bruno/berichtenmagazijn
npx bru run rechtstreeks --env lokaal --sandbox developer   # of: npm run rechtstreeks
```

`npx` start hier de vastgezette CLI uit `node_modules`; haal hem nooit los op met
`npx @usebruno/cli@latest`.

| Request | Wat het laat zien |
|---|---|
| `1-bericht-klaarzetten` | levert via de eerste ingang een bericht aan voor de ontvanger, zodat de lijst iets toont |
| `2-challenge-opvragen` | kaal verzoek; toont header en payload van de JWS. De handtekening is niet te controleren: de simulator ondertekent met een wegwerpsleutel die in geen stelseldocument staat |
| `3-sessie-starten` | bouwt de hele `CreateSession`: app-sleutelpaar, credential, `app_nonce_signature` (ES256), AES-sleutel van 32 bytes, JWE met `ECDH-ES` en `A256GCM`. Ontsleutelt het antwoord (`dir`) |
| `4-berichtenlijst-ophalen` | `Authorization: Bearer {{rechtstreeksSessieToken}}`; toont de ontsleutelde lijst |
| `5-bericht-ophalen` | het klaargezette bericht, ontsleuteld |
| `6-ongeldig-token` | foutpad: `401` met `WWW-Authenticate: Bearer realm="<OIN>", error="invalid_token"` |

Na een ontsleuteld antwoord toont Bruno de payload in plaats van de JWE; de asserts lopen op die
payload. Het sessietoken en de AES-sleutel staan in runtime-variabelen
(`rechtstreeksSessieToken`, `rechtstreeksAesSleutel`) en nooit in een bestand. Een sessie duurt
hooguit vijftien minuten en een challenge zestig seconden: draai bij een `401` opnieuw vanaf
request 2.

De JOSE-code toetst zichzelf tegen jose als tweede implementatie, zonder simulator:

```bash
npm test
```
