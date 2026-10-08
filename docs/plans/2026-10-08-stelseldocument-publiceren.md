# Deelnemende magazijnen publiceren als ondertekend stelseldocument

**Status:** Concept

Issue: MinBZK/MijnOverheidZakelijk#1212.

## Context

Een app die berichten rechtstreeks bij de magazijnen ophaalt, moet zelf weten welke magazijnen
meedoen en hoe ze te bereiken zijn. Die lijst bestaat al: het magazijnregister. Dit plan
publiceert hem daarnaast als stelseldocument, ondertekend door de stelselbeheerder, zodat een app
de herkomst en de integriteit kan controleren. Wij vervullen de rol van stelselbeheerder.

De vorm volgt de conceptstandaard
[BK Connect draft-00](https://vorijk.nl/standaard/connect/draft-bk-connect-00.html), gelezen op
2026-10-08. Die eist dat het document JSON is, publiek bereikbaar, ondertekend door de
stelselbeheerder en voorzien van versienummering (4.1.1.2), en dat elke handtekening een JWS is
met ECDSA of EdDSA (3.1). De structuur is vrij: de velden `oin`, `name`, `discovery_url` en
`public_key` zijn een voorbeeld (4.1.1.1). Verplicht is de inhoud — sleutels en een verwijzing
naar de vindbaarheidsconfiguratie van elke organisatie — niet de veldnaam. Pad, JWS-vorm,
sleuteldistributie en geldigheidsduur laat de standaard aan een toepassingsprofiel (2.2.2).

De naam van de route waar dit document bij hoort, ligt nog niet vast. Module, paden, config en
ZAD-project heten daarom naar de rol (stelselbeheer) en niet naar de route.

### Wat het document wel en niet levert

Tot de magazijnen een eigen sleutel voeren (#1215) is het document een **ondertekende
adreslijst**: het zegt welke organisaties meedoen en waar hun magazijn staat. Een app mag er geen
vertrouwen in een magazijn aan ontlenen; de verbinding met een magazijn is alleen zo betrouwbaar
als de TLS eronder. De spec-beschrijving en het toepassingsprofiel zeggen dat met zoveel woorden.

### Een conceptstandaard volgen

Draft-00 is een werkversie en staat niet op de lijst van Forum Standaardisatie; volgen is een
keuze, geen plicht. We volgen hem omdat de beproeving juist deze inrichting naast de bestaande wil
leggen. Het toepassingsprofiel draagt de peildatum van de gelezen tekst. Een brekende volgende
draft leidt tot een nieuwe versie van het profiel, en de afwijkingen hieronder gaan als
terugkoppeling naar de auteurs.

## Verhouding tot FSC

Er zijn straks drie plekken die zeggen wie meedoet: de FSC-directory, de register-config en het
stelseldocument. Ze dienen een ander publiek.

- **FSC-directory en -contracts** regelen verkeer tussen organisaties. Toegang vergt mTLS onder de
  trust anchor van de group en is dus onbereikbaar voor een browser-app.
- **Het stelseldocument** is de publieke afgeleide voor apps van ondernemers.
- **Het magazijnregister is leidend.** Het document wordt eruit opgebouwd en bevat niets wat daar
  niet in staat; de OIN koppelt een regel in het document aan een peer in FSC.

`docs/vergelijking-fbs-vorijk.md` concludeerde eerder dat FSC-contracts en het OIN-stelsel de
trust-basis al leveren. Dat geldt tussen organisaties; voor een app zonder FSC-toegang is een
eigen publiek anker nodig. Die passage wordt in deze PR bijgewerkt.

## Structuur

Nieuwe service `services/stelselregister`, package `nl.rijksoverheid.moz.fbs.stelselregister`,
poort 8094, image `fbs-stelselregister`. Afhankelijk van `fbs-common` en `fbs-magazijnregister`.
Geen database, geen Redis en geen LDV: het document bevat alleen publieke organisatiegegevens.

```
services/stelselregister/
  src/main/resources/openapi/stelselregister-api.yaml
  src/main/kotlin/.../stelselregister/
    SecurityHeadersRegistratie.kt        eigen kopie, zoals elke dienst
    StelselregisterApiVersionProvider.kt
    stelseldocument/
      Stelseldocument.kt                 payload-model + opbouw uit het register
      StelseldocumentOndertekenaar.kt    bouwt en ondertekent; kent geen HTTP
      Ondertekensleutel.kt               keystore laden + valideren (sleutel én keten)
      UitgegevenStelseldocument.kt       het geldende exemplaar, periodiek ververst
      StelseldocumentConfig.kt
      StelseldocumentResource.kt         implementeert de gegenereerde interface
      SleutelsetResource.kt              /.well-known/jwks.json, handgeschreven
      StelseldocumentGezondheid.kt       readiness: er is een onverlopen document
docs/stelseldocument-toepassingsprofiel.md
demo/environment/stelselregister/pki/    test-root + ondertekencertificaat genereren
bruno/stelselregister/
```

Opbouwen en ondertekenen staan los van het serveren. Krijgt het register later een beheerde bron,
dan kan ondertekenen naar de beheerhandeling verhuizen zonder de publieke dienst te herschrijven.

### Contract

| Pad | Antwoord |
|---|---|
| `GET /api/v1/stelseldocument` | JWS compact, `application/jose` |
| `GET /.well-known/jwks.json` | `application/jwk-set+json`, de publieke sleutels |
| `GET /.well-known/security.txt` | `302` naar het centrale bestand van het NCSC (RFC 9116) |
| `GET /openapi.json` | de spec, zonder authenticatie |

`/api/v1/stelseldocument` is een singleton: er is één document, geen collectie. HAL-`_links` zijn
niet van toepassing, want de body is een JWS.

De twee `/.well-known`-paden vallen buiten de API: ze staan niet in de gegenereerde interface en
dragen geen `API-Version`. De spec noemt ze in `info.description`; de JWKS krijgt een eigen
schematest tegen RFC 7517.

**JWS-header** — precies deze velden:

| Veld | Waarde |
|---|---|
| `alg` | `ES256` |
| `typ` | `stelseldocument+jwt` |
| `kid` | RFC 7638-thumbprint van de ondertekensleutel |
| `x5c` | certificaatketen van het ondertekencertificaat, zonder de root |

Geen `jku`, `jwk`, `x5u` of `crit`. De handtekening is R‖S (64 bytes), niet DER.

**Payload:**

```json
{
  "iss": "00000000000000001000",
  "iat": 1791460800,
  "exp": 1791547200,
  "version": "<hash van de inhoud>",
  "environment": "demo",
  "organizations": [{ "oin": "…", "name": "…", "magazijn_url": "https://…" }],
  "app_managers": [],
  "document_types": [{ "name": "bericht" }]
}
```

- `organizations` komt uit `Magazijnregister.alle()`, gesorteerd op OIN. `grantHash` gaat er niet
  in: dat is routeringsinformatie van onze eigen outway.
- `magazijn_url` is het publieke adres van het magazijn, nooit dat van een outway. De dienst
  weigert te starten als een regel buiten dev/test geen `https` draagt.
- `version` identificeert de inhoud (`organizations`, `app_managers`, `document_types`); `iat`
  ordent twee exemplaren.
- `environment` onderscheidt een demo- of previewdocument van een echt: het register bevat hier
  fictieve OIN's naast herkenbare organisatienamen.

Het schema van header en payload staat onder `components.schemas` in de spec. OpenAPI 3.0 kan een
schema niet aan een `application/jose`-body hangen; de contracttest decodeert de JWS en valideert
de delen zelf.

**Verificatie door een app** (staat in de spec-beschrijving en in het toepassingsprofiel):

1. `alg` is exact `ES256` en `typ` exact `stelseldocument+jwt`; weiger al het andere.
2. Valideer de keten in `x5c` tot de vastgelegde root, inclusief geldigheid van elk certificaat.
3. Controleer de handtekening met de sleutel uit het ondertekencertificaat.
4. Weiger een document waarvan `exp` voorbij is, waarvan `iss` niet de verwachte stelselbeheerder
   is, of waarvan `iat` ouder is dan het laatst geaccepteerde exemplaar.

**Headers:**

| | Document | JWKS |
|---|---|---|
| `Cache-Control` | `public, max-age=300` | `public, max-age=3600` |
| `ETag` | zwak, uit `version` + `kid` + `iat` | zwak, uit de `kid`'s |
| `If-None-Match` → 304 | ja | ja |

CORS: `Access-Control-Allow-Origin: *` zonder credentials, methodes `GET, HEAD, OPTIONS`, ook op
`/openapi.json`. `Access-Control-Expose-Headers: ETag, API-Version`, en omdat `If-None-Match` een
preflight uitlokt `Access-Control-Allow-Headers: If-None-Match` met een `Access-Control-Max-Age`.

Fouten zijn `application/problem+json` met `API-Version`: 404 (onbekend pad), 405, 406 (een
`Accept` die `application/jose` uitsluit), 500 en 503 (geen geldig document beschikbaar).

## Ontwerpkeuzes

- **Eigen service, eigen ZAD-project.** Stelselbeheer is een andere rol dan uitvragen, en het
  document moet bereikbaar blijven als de uitvraag er niet is. Een eigen project (`mpfs-rab`)
  houdt de ondertekensleutel buiten de andere projecten en laat het project publiek staan: de
  toegangsmuur van ZAD is projectbreed.
- **Vertrouwen via een certificaatketen.** Een app legt de root vast, niet de ondertekensleutel.
  De JWKS is een gemak voor wie de sleutel wil zien, geen bron van vertrouwen: hij komt van
  dezelfde origin als het document. De root gaat buiten de dienst om naar de app (in de build),
  met zijn vingerafdruk in het toepassingsprofiel en de operator-handleiding. Nu een eigen
  test-root; een PKIoverheid-certificaat met de OIN in `subject.serialNumber` past in dezelfde
  vorm.
- **Rotatie zonder dat apps breken.** Een nieuw ondertekencertificaat onder dezelfde root is voor
  een app onzichtbaar. De JWKS toont tijdens de overgang de oude en de nieuwe sleutel. Intrekken
  van een gelekte sleutel loopt via een kort levend ondertekencertificaat en een nieuwe uitgifte;
  de root blijft offline.
- **Geldigheid van 24 uur, elk uur ververst.** Een verlopen of verouderd document is daarmee
  herkenbaar, en een verwijderd magazijn verdwijnt binnen een dag uit elke app. `exp` loopt nooit
  voorbij de `notAfter` van het ondertekencertificaat. De dienst meldt zich niet gereed zonder
  onverlopen document.
- **Ondertekenen in de dienst.** Het register is runtime-config met adressen per deployment; een
  wijziging komt zo na één herstart in het document. Vooraf ondertekenen in de bouwstraat zou
  per deployment en per dag een eigen uitgifte vragen.
- **Geen cryptografie per verzoek.** De dienst levert de vooraf ondertekende bytes uit.
- **Sleutel uit een PKCS#12-keystore**, met de keten erin. De dienst start niet als de keystore
  ontbreekt of onleesbaar is, de alias mist, de sleutel geen EC P-256 is, de keten ontbreekt, het
  certificaat niet bij de sleutel hoort of verlopen is. Loopt het binnen dertig dagen af, dan
  volgt een waarschuwing in de log.
- **Een gegenereerde sleutel alleen in ontwikkel- en testmodus.** Die terugval hangt aan de
  build-time launch mode en niet aan de profielnaam: een profiel is bij het starten te kiezen, en
  een uitgerolde dienst die ongemerkt met een wegwerpsleutel ondertekent, oogt gezond.
- **JWS compact, ES256.** De standaard eist een JWS en staat P-256 toe; compact is de vorm die de
  standaard zelf toont en die elke JOSE-library in een browser in één aanroep verifieert, via
  WebCrypto. De payload is JSON; de vorm op de lijn is dat niet, en het toepassingsprofiel legt
  die lezing vast. EdDSA is het alternatief zodra de libraries van afnemers het dragen.
- **Eigen `typ`.** Zo is het document niet te verwisselen met een ander token onder dezelfde
  sleutel.
- **`smallrye-jwt-build` voor de JWS, `quarkus-scheduler` voor het verversen.** Beide zitten in de
  Quarkus-BOM.
- **Register-config dupliceren, met een wachter.** De twee echte magazijnen staan in de
  `application.properties` van de uitvraag; de gesimuleerde komen uit een gegenereerd bestand dat
  per dienst gemount wordt. De basisregels worden overgenomen, en een test faalt zodra OIN's of
  namen afwijken van die van de uitvraag. Verhuizen naar de library zou `demo-console` en het
  magazijn dezelfde verplichte URL-variabelen opleggen.
- **Geen leg in `preview-klaarzetten`.** Die stap zet netwerkregels tussen projecten; deze dienst
  roept niets aan en is alleen via de publieke route bereikbaar. De test die klaarzet- en
  cleanup-matrix gelijk eist, krijgt een expliciete lijst van projecten zonder regels.
- **Geen fuzz-doel.** De dienst parset geen body; paden en headers handelt Quarkus af.
- **Publiek oppervlak beperkt.** Swagger UI staat uit; van het beheerpad blijft alleen health
  over, dat het platform voor zijn probes nodig heeft en dat niets over de sleutel prijsgeeft.

### Afwijkingen van de API Design Rules

| Regel | Afwijking | Reden |
|---|---|---|
| `Cache-Control: no-store` | korte `max-age` op document en JWKS | publieke, niet-gevoelige gegevens die apps regelmatig moeten verversen |
| `application/json` als response | `application/jose` | de handtekening hoort bij de representatie |
| camelCase, Nederlands | snake_case, Engels in de payload | de payload is een door een externe standaard bepaald document |
| signing volgens de ADR-module | JWS compact met ES256 i.p.v. JAdES detached met PS256 | BK Connect eist ECDSA of EdDSA |

### Afwijkingen van BK Connect

| Eis | Stand | Volgt met |
|---|---|---|
| Publieke sleutel per organisatie (4.1.1.2) | ontbreekt | #1215 |
| Verwijzing naar een vindbaarheidsconfiguratie (4.1.1.2) | ontbreekt; `magazijn_url` wijst naar het magazijn zelf | #1215 |
| Intrekking en verloopdatum van organisatiesleutels (4.5, 4.6) | ontbreekt, er zijn nog geen sleutels | #1215 |
| App Managers | leeg | #1214 |
| Toegestane legalisatiemethoden (2.2.7) | ontbreekt | #1214 |

### Plichten van de stelselbeheerder (4.1.2)

| Plicht | Stand |
|---|---|
| Stelseldocument ondertekenen en publiceren | deze PR |
| Stelseldocument actueel houden | deze PR, binnen de grens van config-per-dienst |
| Publieke sleutels van organisaties beheren en valideren | #1215 |
| Legalisatiemethoden en legalisatoren aanwijzen | #1214 |
| Deelname-eisen stellen en handhaven | organisatorisch, geen code |
| Kwaliteit van diensten in het stelsel meten | organisatorisch, geen code |

## Sleutelbeheer

- De root wordt buiten CI gegenereerd en blijft offline; alleen het ondertekencertificaat met zijn
  sleutel komt in de keystore van de dienst.
- Op ZAD staat de keystore als attachment en het wachtwoord als env in hetzelfde project. Wie het
  project kan beheren, kan beide lezen; het wachtwoord beschermt dus tegen een los zwervend
  bestand, niet tegen een projectbeheerder. De toegangslijst van `mpfs-rab` is daarmee de
  toegangslijst van de sleutel.
- Elke omgeving heeft een eigen ondertekencertificaat. Een preview ondertekent met het
  testcertificaat en is aan `environment` te herkennen.
- De operator-handleiding beschrijft generatie, bewaring, geplande rotatie en noodvervanging.
- Bij elke uitgifte logt de dienst `kid`, `version`, `iat`, `exp`, het aantal organisaties en de
  herkomst van de sleutel. Sleutelmateriaal en wachtwoord komen nooit in de log.

## Stappen

1. **Service** (test-first): module-skelet en pom (JaCoCo 90%, detekt, jib), OpenAPI-spec,
   documentopbouw, keystore- en ketenvalidatie, ondertekenen en verversen, de resources,
   readiness, security-headers, CORS, `security.txt`.
2. **PKI voor test en demo:** script dat een test-root en een ondertekencertificaat maakt en tot
   een keystore bundelt, naar het patroon van de bestaande peer-PKI; uitvoer blijft buiten git.
3. **Inpassing in de repo:** root-pom, shard in `test.yml`, SARIF-stap in `detekt.yml`, pom in het
   fuzz-basis-image, hook-lijst, Bruno-collectie, `apis.json` (absolute `baseURL`, verwijzing naar
   de live `/openapi.json`), `publiccode.yml`.
4. **Lokaal:** `compose.yaml` (profiel `demo`), de twee podman-overlays, `demo/podman-up.sh`, met
   dezelfde register-mount als de uitvraag.
5. **Uitrol:** `deploy.yml` (jib-matrix, `PROJECT_STELSELREGISTER`, `meta`-output,
   `deploy-preview-stelselregister`, `deploy-test-stelselregister`, needs van `uitrol-poort` en
   `preview-afronding`), `uitrol-poort.sh` van drie naar vier, vierde leg en ghcr-pakket in
   `cleanup-preview.yml`, en de testsuites die deze aantallen vastleggen.
6. **ZAD-inrichting** (handwerk, vóór de merge; runbook onder `demo/environment/zad-demo/`):
   aliassen `MAGAZIJN_A_URL`, `MAGAZIJN_B_URL`, `MAGAZIJN_SIMULATOR_URL`; env voor uitgever,
   omgeving en `SMALLRYE_CONFIG_LOCATIONS`; attachments `stelsel-keystore`
   (`/etc/stelselregister/keystore.p12`) en `magazijnen-register`
   (`/config/magazijnen-register.properties`); health-check;
   geheugenlimiet na meting.
7. **Docs:** het toepassingsprofiel, een operator-handleiding, README, `docs/ontwikkelen.md`,
   `docs/operations/zad-gitops.md`, het C4-model, `docs/demo-runbook.md`,
   `docs/vergelijking-fbs-vorijk.md`, CLAUDE.md.

De service (1–4) en de uitrol (5–7) komen als gescheiden commits in één PR.

## Verificatie

- **Unit — document:** nul, één en meerdere magazijnen; sortering; `grantHash` afwezig; `version`
  stabiel bij gelijke inhoud en anders na een registerwijziging; `exp` begrensd door de `notAfter`
  van het certificaat.
- **Unit — keystore:** ontbrekend bestand, fout wachtwoord, ontbrekende alias, RSA-sleutel,
  verkeerde curve, ontbrekende keten, certificaat dat niet bij de sleutel hoort, verlopen
  certificaat.
- **Unit — uitgifte:** de header bevat precies de vier velden; de handtekening is 64 bytes; een
  ververst exemplaar heeft een latere `iat` en dezelfde `version`.
- **`@QuarkusTest`:** document ophalen en verifiëren zoals een app dat doet (keten tot de root,
  dan de handtekening); een gewijzigde payload, een andere root en een verlopen document worden
  geweigerd; 304 op `If-None-Match`; preflight, exposed headers en afwezigheid van
  `Access-Control-Allow-Credentials`; `Content-Type` van document en JWKS; 406 als
  problem+json; readiness faalt zonder geldig document.
- **Contract:** de spec-paden en het foutcontract via de validator; header en payload van de
  gedecodeerde JWS tegen hun schema; de JWKS tegen RFC 7517.
- **Wachter:** de register-basisregels van deze dienst en van de uitvraag noemen dezelfde OIN's en
  namen.
- **Terugval:** de gegenereerde sleutel is in een productie-build niet bereikbaar, ook niet met
  een test-profiel.
- **CI-scripts:** de bestaande suites onder `.github/scripts/` groen met vier projecten.
- **Op de eerste preview:** komt de `.p12` byte-getrouw aan als attachment, en krijgt een
  `pr-<n>` de attachments mee? Beide zijn op ZAD niet eerder beproefd; valt het eerste tegen, dan
  zijn een PEM-sleutel en een PEM-keten de terugval. Daarna via het publieke adres: het document
  verifiëren, de OIN's vergelijken met het register van de uitvraag in dezelfde deployment, en
  controleren dat de route TLS 1.3 onderhandelt.

## Stelselbeheerder en beveiligingscontact

**Logius is de stelselbeheerder.** `iss` draagt de OIN van Logius; de naam staat als subject in het
ondertekencertificaat en in het toepassingsprofiel. In de demo is dat `00000000000000001000`, de
test-OIN die de FSC-peer `logius` in `demo/environment` al voert, zodat stelseldocument en
FSC-testnet dezelfde identiteit tonen. De waarde is config (`stelseldocument.uitgever-oin`); de
echte OIN van Logius hoort pas in een document dat onder een echte root is ondertekend, niet onder
de test-root.

**`security.txt` verwijst door naar het NCSC.** De standaard staat op de pas-toe-of-leg-uit-lijst
voor elk systeem dat via HTTPS publiek bereikbaar is. Het Forum Standaardisatie adviseert
Rijksorganisaties die het centrale CVD-beleid volgen geen eigen bestand te beheren, maar door te
verwijzen naar het centrale bestand van het NCSC; RFC 9116 staat zo'n redirect uitdrukkelijk toe.
De dienst antwoordt daarom op `/.well-known/security.txt` met een `302` naar
`https://www.ncsc.nl/.well-known/security.txt`. Dat houdt contact, `Expires` en de
PGP-ondertekening op één beheerde plek: een eigen kopie zou verlopen zonder dat iemand het merkt.
Het doel is config (`stelselregister.security-txt-url`), zodat een beheerder met een eigen
CVD-beleid alleen de waarde wijzigt. De verificatie volgt de redirect en controleert dat het
doel een bestand met `Contact` en een `Expires` in de toekomst oplevert.

## Buiten de workflow om

- `cleanup-preview.yml` draait altijd de versie van main; de previews van deze PR in `mpfs-rab`
  worden met de hand opgeruimd.
- `deploy-preview-stelselregister` wordt pas een harde poort als hij als required context in de
  branch protection staat. Tot dan dekt `uitrol-poort` hem af.
