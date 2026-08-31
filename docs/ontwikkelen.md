# Ontwikkelen aan de PoC Federatief Berichtenstelsel

Alles wat je lokaal nodig hebt om te bouwen, testen en handmatig tegen de API's aan te praten.
Voor het opzetten van de demo-stack: [`demo-runbook.md`](demo-runbook.md). Voor draaien in
productie is er een operator-handleiding per service:
[magazijn](operator-handleiding.md) en [uitvraag](operator-handleiding-uitvraag.md).

## Een endpoint wijzigen: OpenAPI-first

De OpenAPI-spec per service is de bron van waarheid, niet de Kotlin-code. De volgorde is daarom
altijd dezelfde:

1. Wijzig de spec — `services/<service>/src/main/resources/openapi/<service>-api.yaml`.
2. Bouw. De `openapi-generator-maven-plugin` genereert met `jaxrs-spec` en `interfaceOnly=true`
   JAX-RS-interfaces plus DTO's naar `target/generated-sources/openapi/`. Die map nooit met de
   hand aanpassen: elke build overschrijft hem.
3. Pas de Kotlin-resource aan die de interface implementeert. Wijkt je implementatie af van de
   spec, dan faalt de compilatie — dat is de bedoelde vangrail.
4. Voeg een `.bru`-request toe aan de bijbehorende Bruno-collectie, zodat die de spec blijft
   spiegelen.
5. Lint de spec (zie hieronder) en draai de tests.

## Tests draaien

Draai altijd `clean` vóór `test` of `verify`: een achtergebleven `target/` van een andere
branch-state laat Surefire stale `.class`-bestanden draaien, wat misleidende fouten geeft in
ongewijzigde code.

```bash
./mvnw clean test -pl libraries/fbs-common -am                # pure JVM
./mvnw clean test -pl libraries/fbs-magazijnregister -am      # pure JVM
./mvnw clean test -pl demo/demo-console -am                   # pure JVM + één @QuarkusTest, geen Docker
./mvnw clean test -pl demo/magazijn-simulator -am              # Docker vereist (Testcontainers)
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am  # Docker vereist (Testcontainers)
./mvnw clean test -pl services/berichtenmagazijn -am          # Docker vereist
./mvnw clean test -pl services/berichtenuitvraag -am          # Docker vereist
```

De modules die Docker vereisen draaien hun infrastructuur via Quarkus Dev Services
(Testcontainers) — je hoeft `docker compose up -d` daar niet apart voor te starten.

## Kwaliteitsgates

Er zijn twee gates, elk gebonden aan een andere Maven-fase:

- **JaCoCo, minimaal 90% line coverage** — draait in de fase `test`, dus de commando's hierboven
  bewaken hem al. Geldt voor beide services en alle libraries; de demo-console heeft geen
  coverage-gate.
- **detekt, `maxIssues: 0` zonder baseline** — draait pas in de fase `verify`, of los via het
  eigen goal.

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am   # tests + coverage + detekt op die module
./mvnw detekt:check                                      # alleen detekt, repo-breed
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

Het magazijnregister staat in
`services/berichtenuitvraag/src/main/resources/application.properties` (zoek op `magazijnen."`).
Per deelnemende organisatie zijn er drie sleutels — `url`, `naam` en `grantHash` — met de
afzender-OIN als map-key:

```properties
magazijnen."00000000000000100000".url=${MAGAZIJN_A_URL}
magazijnen."00000000000000100000".naam=Magazijn A
magazijnen."00000000000000100000".grantHash=${MAGAZIJN_A_GRANT_HASH:}
```

Omdat de map-key de OIN is, zijn dubbele OIN's structureel onmogelijk. `ConfigMagazijnregister`
valideert keys en URL's bij het opstarten en weigert buiten dev/test een niet-https-adres. In `%dev`
staan de URL's op `http://localhost:8090` en `:8091` als default, zodat dezelfde configuratie in een
container naar container-DNS wijst zonder de basisregels te hoeven overschrijven. Een lege
`grantHash` betekent: geen FSC-outway, roep het magazijn rechtstreeks aan.

Voor productie is er per service een operator-handleiding:
[magazijn](operator-handleiding.md) (verplichte overrides, LDV-TLS, outbox, monitoring) en
[uitvraag](operator-handleiding-uitvraag.md) (sessiecache-TLS, timeout-invarianten, cache-TTL's).
Beide beschrijven ook de bewust-onveilige kleppen en de alert-regels die daarbij horen.
