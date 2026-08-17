# Ontwikkelen aan de FBS Berichtenbox

Alles wat je lokaal nodig hebt om te bouwen, testen en handmatig tegen de API's aan te praten.
Voor het opzetten van de demo-stack: [`demo-runbook.md`](demo-runbook.md). Voor draaien in
productie: [`operator-handleiding.md`](operator-handleiding.md).

## Tests draaien

Draai altijd `clean` vóór `test` of `verify`: een achtergebleven `target/` van een andere
branch-state laat Surefire stale `.class`-bestanden draaien, wat misleidende fouten geeft in
ongewijzigde code.

```bash
./mvnw clean test -pl libraries/fbs-common -am                # pure JVM
./mvnw clean test -pl libraries/fbs-magazijnregister -am      # pure JVM
./mvnw clean test -pl services/demo-console -am               # pure JVM
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am  # Docker vereist (Testcontainers)
./mvnw clean test -pl services/berichtenmagazijn -am          # Docker vereist
./mvnw clean test -pl services/berichtenuitvraag -am          # Docker vereist
```

De modules die Docker vereisen draaien hun infrastructuur via Quarkus Dev Services
(Testcontainers) — je hoeft `docker compose up -d` daar niet apart voor te starten.

## Kwaliteitsgates

`verify` draait naast de tests de gates die ook in CI staan: detekt (`maxIssues: 0`, zonder
baseline) voor de hele repo, en JaCoCo met minimaal 90% line coverage voor beide services en alle
libraries. De demo-console heeft geen coverage-gate.

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am
./mvnw detekt:check                                           # alleen de statische analyse
```

Een detekt-bevinding faalt de build en hoort echt opgelost te worden; een bewuste, onvermijdelijke
uitzondering krijgt een inline `@Suppress("Rule")` met motivatie, zodat de afweging in review
zichtbaar is.

## OpenAPI-specs linten

De specs zijn de bron van waarheid voor beide API's en moeten voldoen aan de NL API Design Rules:

```bash
npx @stoplight/spectral-cli lint services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
npx @stoplight/spectral-cli lint services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
```

## Een tweede magazijn draaien

Het stelsel is federatief, dus interessant gedrag (aggregatie, partial failure) zie je pas met meer
dan één magazijn. Een tweede instantie is een tweede *organisatie* en heeft daarom een eigen OIN en
een eigen database nodig — zonder die twee overrides publiceert hij onder de identiteit van magazijn
A en deelt hij diens opslag, en aggregeer je dus twee views op één magazijn:

```bash
MAGAZIJN_OIN=00000001823288444000 \
DB_JDBC_URL=jdbc:postgresql://localhost:5433/berichtenmagazijn \
./mvnw compile quarkus:dev -pl services/berichtenmagazijn -am \
  -Dquarkus.http.port=8091 -Ddebug=5006
```

`localhost:5433` is de `postgres-b`-instantie uit `compose.yaml` (start mee met
`docker compose up -d`); `-Ddebug=5006` voorkomt een conflict op de debug-poort van de eerste
dev-mode. Voor meer dan twee magazijnen is de demo-stack handiger dan losse dev-modes.

## API-requests handmatig uitvoeren (Bruno)

De `bruno/`-folder bevat per service een collectie van voorbeeld-requests die je tegen de lokale
dev-mode kunt uitvoeren met [Bruno](https://www.usebruno.com/).

- `bruno/berichtenmagazijn/` — aanlever- en beheer-API
- `bruno/berichtenuitvraag/` — frontend-facade (lijst, zoek, ophalen-SSE, detail, bijlage,
  PATCH/DELETE) en de aanmeld-webhook

Open de folder in Bruno, kies environment `lokaal` en run requests. De collectie spiegelt de
OpenAPI-spec: nieuwe endpoints in de spec krijgen direct een bijbehorende `.bru`-request.

## Configuratie

De belangrijkste configuratie staat in
`services/berichtenuitvraag/src/main/resources/application.properties`. Ingekort weergegeven —
het bestand zelf bevat per magazijn ook nog de FSC-grant-hash:

```properties
# Magazijnregister: de map-key is de afzender-OIN, de waarde het magazijn van die organisatie.
# %dev vult de URL uit een env-var met de lokale poort als default, zodat dezelfde
# configuratie in een container naar container-DNS wijst.
# %dev-default van MAGAZIJN_A_URL: http://localhost:8090
magazijnen."00000000000000100000".url=${MAGAZIJN_A_URL}
magazijnen."00000000000000100000".naam=Magazijn A
# %dev-default van MAGAZIJN_B_URL: http://localhost:8091
magazijnen."00000001823288444000".url=${MAGAZIJN_B_URL}
magazijnen."00000001823288444000".naam=Magazijn B
```

Omdat de map-key de OIN is, zijn dubbele OIN's structureel onmogelijk; `ConfigMagazijnregister`
valideert keys en URL's bij het opstarten en weigert buiten dev/test een niet-https-adres.

Voor productie-instellingen — TLS-eisen op Redis en het Logboek Dataverwerkingen, verplichte
overrides, tuning en monitoring — geldt de [operator-handleiding](operator-handleiding.md).
