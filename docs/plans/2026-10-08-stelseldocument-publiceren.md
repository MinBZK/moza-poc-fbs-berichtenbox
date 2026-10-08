# Deelnemende magazijnen publiceren als ondertekend stelseldocument

**Status:** Concept

Issue: MinBZK/MijnOverheidZakelijk#1212.

## Context

Een app die berichten rechtstreeks bij de magazijnen ophaalt, moet zelf weten welke magazijnen
meedoen en hoe ze te bereiken zijn. Die lijst bestaat al: het magazijnregister. Dit plan
publiceert hem daarnaast als stelseldocument, ondertekend door de stelselbeheerder, zodat een app
de herkomst en de integriteit kan controleren. Wij vervullen de rol van stelselbeheerder.

De vorm volgt de conceptstandaard
[BK Connect draft-00](https://vorijk.nl/standaard/connect/draft-bk-connect-00.html). Die legt
vast dat het document JSON is, publiek bereikbaar, ondertekend door de stelselbeheerder en
voorzien van versienummering; dat een handtekening een JWS is met ECDSA of EdDSA; en dat een
organisatie `oin`, `name`, `discovery_url` en `public_key` draagt. Het pad, de JWS-vorm, de
distributie van de sleutel en de geldigheidsduur laat de standaard open.

De naam van de route waar dit document bij hoort, ligt nog niet vast. Module, paden, config en
ZAD-project heten daarom naar de rol (stelselbeheer) en niet naar de route.

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
      StelseldocumentOndertekenaar.kt    JWS compact, ES256
      Ondertekensleutel.kt               keystore laden + valideren, JWK + kid
      StelseldocumentConfig.kt           stelseldocument.keystore.{pad,wachtwoord,alias}
      StelseldocumentResource.kt         implementeert de gegenereerde interface
bruno/stelselregister/
```

### Contract

| Pad | Antwoord |
|---|---|
| `GET /api/v1/stelseldocument` | JWS compact (`application/jose`), ES256, header `kid` en `typ` |
| `GET /.well-known/jwks.json` | de publieke sleutel; `kid` is de RFC 7638-thumbprint |

Beide antwoorden dragen `ETag` en `Cache-Control`, en CORS staat open voor `GET`: de afnemer is
een browser-app op een andere origin.

Payload van het document (veldnamen volgens BK Connect, dus snake_case):

```json
{
  "version": "<hash van de inhoud>",
  "issued_at": "2026-10-08T12:00:00Z",
  "organizations": [{ "oin": "…", "name": "…", "discovery_url": "…" }],
  "app_managers": [],
  "document_types": [{ "name": "bericht" }]
}
```

`organizations` komt uit `Magazijnregister.alle()`, gesorteerd op OIN. `grantHash` gaat er niet
in: dat is routeringsinformatie van onze eigen outway.

## Ontwerpkeuzes

- **Eigen service, eigen ZAD-project.** Stelselbeheer is een andere rol dan uitvragen, en het
  document moet bereikbaar blijven als de uitvraag er niet is. Een eigen project (`mpfs-rab`)
  houdt de ondertekensleutel buiten de andere projecten en laat het project publiek staan: de
  toegangsmuur van ZAD is projectbreed.
- **JWS compact.** Elke JOSE-library in een browser verifieert die vorm in één aanroep. Flattened
  JWS-JSON maakt de app-code omslachtiger zonder iets op te leveren; kale JSON met een losse
  handtekening vergt canonicalisatie en is daardoor foutgevoelig.
- **`version` is een hash van de inhoud.** Hij verandert precies wanneer het register verandert en
  vraagt geen teller of opslag. `issued_at` hoort niet bij de gehashte inhoud.
- **Sleutel uit een PKCS#12-keystore.** Buiten dev/test start de dienst niet als de keystore
  ontbreekt, onleesbaar is, de alias mist of de sleutel geen EC P-256 is. In dev/test zonder pad
  wordt een sleutel in het geheugen gegenereerd, zodat er geen privésleutel in git komt.
- **Eén keer ondertekenen, bij boot.** Het register is config en dus statisch per start.
- **`smallrye-jwt-build` voor de JWS.** Zit in de Quarkus-BOM; geen nieuwe versie om te pinnen.
- **Register-config dupliceren, met een wachter.** De twee echte magazijnen staan in de
  `application.properties` van de uitvraag; de gesimuleerde komen uit een gegenereerd bestand dat
  per dienst gemount wordt. De basisregels worden overgenomen, en een test faalt zodra OIN's of
  namen afwijken van die van de uitvraag. Verhuizen naar de library zou `demo-console` en het
  magazijn dezelfde verplichte URL-variabelen opleggen.
- **Geen leg in `preview-klaarzetten`.** Die stap zet netwerkregels tussen projecten; deze dienst
  roept niets aan en is alleen via de publieke route bereikbaar. De test die klaarzet- en
  cleanup-matrix gelijk eist, krijgt een expliciete lijst van projecten zonder regels.
- **Geen fuzz-doel.** De dienst parset geen invoer.

### Bekende afwijkingen van de standaard

- `public_key` per organisatie ontbreekt tot de magazijnen een sleutel voeren (#1215). De
  standaard eist het veld; de spec-beschrijving en de operator-handleiding noemen de afwijking.
- `discovery_url` bevat de magazijn-URL uit het register, niet het adres van een
  vindbaarheidsconfiguratie. Ook dat volgt met #1215.
- `app_managers` is leeg tot er een App Manager is (#1214).

### Grens van het vertrouwensmodel

De JWKS komt van dezelfde origin als het document. Verifiëren tegen die sleutel bewijst alleen
dat het document onderweg niet is gewijzigd; vertrouwen in de afzender ontstaat pas als de app de
`kid` vastlegt. Dat vastleggen hoort bij de app (#1216).

## Stappen

1. **Service** (test-first): module-skelet en pom (JaCoCo 90%, detekt, jib), OpenAPI-spec,
   documentopbouw, keystore-validatie, ondertekenen, resource, security-headers, CORS.
2. **Inpassing in de repo:** root-pom, shard in `test.yml`, SARIF-stap in `detekt.yml`, pom in het
   fuzz-basis-image, hook-lijst, Bruno-collectie, `apis.json`, `publiccode.yml`.
3. **Lokaal:** `compose.yaml` (profiel `demo`), de twee podman-overlays, `demo/podman-up.sh`, met
   dezelfde register-mount als de uitvraag.
4. **Uitrol:** `deploy.yml` (jib-matrix, `PROJECT_STELSELREGISTER`, `meta`-output,
   `deploy-preview-stelselregister`, `deploy-test-stelselregister`, needs van `uitrol-poort` en
   `preview-afronding`), `uitrol-poort.sh` van drie naar vier, vierde leg en ghcr-pakket in
   `cleanup-preview.yml`, en de testsuites die deze aantallen vastleggen.
5. **ZAD-inrichting** (handwerk, vóór de merge; runbook onder `demo/environment/zad-demo/`):
   aliassen `MAGAZIJN_A_URL`, `MAGAZIJN_B_URL`, `MAGAZIJN_SIMULATOR_URL`; env
   `SMALLRYE_CONFIG_LOCATIONS`; attachments `stelsel-keystore`
   (`/etc/stelselregister/keystore.p12`) en `magazijnen-register`
   (`/config/magazijnen-register.properties`); health-check; geheugenlimiet na meting.
6. **Docs:** README, `docs/ontwikkelen.md`, `docs/operations/zad-gitops.md`, een
   operator-handleiding, het C4-model, `docs/demo-runbook.md`, CLAUDE.md.

De service (1–3) en de uitrol (4–6) komen als gescheiden commits in één PR.

## Verificatie

- **Unit:** documentopbouw met nul, één en meerdere magazijnen; sortering; `grantHash` afwezig;
  `version` stabiel bij gelijke inhoud en anders na een registerwijziging. Keystore: ontbrekend
  bestand, fout wachtwoord, ontbrekende alias, RSA-sleutel, verkeerde curve.
- **`@QuarkusTest`:** document ophalen en verifiëren met de sleutel uit de JWKS; een gewijzigde
  payload doorstaat de verificatie niet; CORS- en cache-headers; contracttest van beide paden en
  het foutcontract tegen de spec.
- **Wachter:** de register-basisregels van deze dienst en van de uitvraag noemen dezelfde OIN's en
  namen.
- **CI-scripts:** de bestaande suites onder `.github/scripts/` groen met vier projecten.
- **Op de eerste preview:** komt de `.p12` byte-getrouw aan als attachment, en krijgt een
  `pr-<n>` de attachments mee? Beide zijn op ZAD niet eerder beproefd; valt het eerste tegen, dan
  is een PEM-sleutel de terugval. Daarna: document ophalen via het publieke adres en verifiëren.

## Buiten de workflow om

- `cleanup-preview.yml` draait altijd de versie van main; de previews van deze PR in `mpfs-rab`
  worden met de hand opgeruimd.
- `deploy-preview-stelselregister` wordt pas een harde poort als hij als required context in de
  branch protection staat. Tot dan dekt `uitrol-poort` hem af.
