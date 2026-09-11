---
name: openapi-wijziging
description: Wijzig de OpenAPI spec van een service, genereer de interfaces, implementeer in Kotlin en werk de Bruno-collectie bij
---

# OpenAPI-wijziging workflow

De spec is de bron van waarheid. Elke endpoint-, model- of validatiewijziging begint daar, niet in
de Kotlin-code.

## Welke service

Vraag het aan de gebruiker als het niet uit de opdracht blijkt. Er zijn er twee met een eigen spec:

| Service | Spec | Bruno-collectie | Dev-poort |
|---------|------|-----------------|-----------|
| `berichtenuitvraag` | `services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml` | `bruno/berichtenuitvraag/` | 8086 |
| `berichtenmagazijn` | `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml` | `bruno/berichtenmagazijn/` | 8090 |

De sessiecache en het magazijnregister zijn interne libraries zonder eigen spec — hun CDI-facades
(`Sessiecache`, `Magazijnregister`) zijn daar het contract. Gaat de wijziging daarover, dan is deze
workflow niet van toepassing.

`demo/magazijn-simulator` heeft geen eigen spec: die genereert uit
`berichtenmagazijn-api.yaml` via een pad-verwijzing (`api.spec.file`). Wijzig je de magazijn-spec,
controleer dan of de simulator nog compileert.

## Stappen

Hieronder is `<service>` steeds `berichtenuitvraag` of `berichtenmagazijn`.

1. **Wijzig de spec**

   Houd je aan de NL API Design Rules: `/api/v1`-prefix, camelCase JSON, foutresponses als
   `application/problem+json` (RFC 9457), `API-Version`-header, HAL `_links`.

   Een BSN mag nooit in een pad, query-parameter of in de spec staan — die gaat uitsluitend via de
   header `X-Ontvanger: BSN:<waarde>`.

2. **Genereer de interfaces**

   ```bash
   ./mvnw clean compile -pl services/<service> -am
   ```

   De interfaces komen in `target/generated-sources/openapi/`. Pas die **nooit** handmatig aan —
   een PreToolUse-hook blokkeert dat ook. Wijkt het resultaat af van wat je wilde, dan wijzig je de
   spec, niet de gegenereerde code.

3. **Implementeer in Kotlin**

   Laat de resource-klasse de nieuwe of gewijzigde interface-methode implementeren. Houd de
   functionele package-structuur aan (`berichten/`, `magazijn/`, `notificatie/`), niet een
   technische (`controller/`, `service/`).

   Error handling hoort in de resource, niet in de service — zo komen foutresultaten nooit in de
   Redis-cache terecht.

4. **Lint de spec tegen de ADR-ruleset**

   ```bash
   npx @stoplight/spectral-cli lint services/<service>/src/main/resources/openapi/<service>-api.yaml \
     --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
   ```

5. **Werk de Bruno-collectie bij**

   Elke nieuwe endpoint krijgt direct een `.bru`-request onder `bruno/<service>/`, in de map van
   het functionele pad waar hij bij hoort (bv. `aanleveren/`, `ophalen-beheer/`, `uitvraag/`,
   `aanmeld/`). Volg de nummering van de bestaande bestanden. Zo blijft de collectie een levend
   exempel van de spec in plaats van een momentopname.

6. **Tests schrijven**

   Happy én unhappy paths: foutgevallen, grenswaarden, validatiefouten. Bij collecties minstens
   leeg, één en meerdere elementen — een lijst van één verbergt of je code discrimineert of gewoon
   het eerste element teruggeeft.

   Voeg de foutresponses toe aan de `OpenApiContractTest`, zodat 400/404/409/500 tegen het
   Problem-schema gevalideerd worden.

   ```bash
   ./mvnw clean test -pl services/<service> -am
   ```

   Deze suite start Testcontainers; Docker of Podman moet draaien.

7. **Controleer de gate**

   ```bash
   ./mvnw clean verify -pl services/<service> -am
   ```

   JaCoCo eist 90% line coverage en detekt faalt op élke bevinding. Let op: alleen
   `@QuarkusTest`-coverage telt mee voor de drempel — pure unit-tests met MockK dragen niet bij.

8. **Loop de build-warnings na**

   Nieuwe, onverklaarde waarschuwingen blokkeren een PR. Los ze op of accepteer ze bewust met
   reden. De al geaccepteerde waarschuwingen (jansi, guava, JBoss LogManager) staan in CLAUDE.md.
