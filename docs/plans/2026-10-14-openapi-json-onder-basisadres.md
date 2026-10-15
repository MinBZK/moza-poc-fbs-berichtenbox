# OpenAPI-document onder het basisadres

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#1224. Bouwt voort op PR #371 (gemerged), die de uitvraag-API naar
0.5.0 bracht; deze wijziging brengt haar naar 0.6.0.

## Context

De ADR-regel `/core/publish-openapi` vraagt het OpenAPI-document op `<basisadres>/openapi.json`. Beide
specs noemen `servers: /api/v1`, maar het document staat op de root (`/openapi.json`) en de specs
declareren het pad niet. Spectral geeft daarom op beide specs `paths-open-api-json-resource-exists`.

## Ontwerpkeuzes

- **Root blijft, basisadres komt erbij.** `quarkus.smallrye-openapi.path=/openapi.json` blijft staan:
  Swagger UI, README en mogelijke afnemers leunen erop. Onder `/api/v1/openapi.json` komt hetzelfde
  document via een JAX-RS-resource.
- **Een resource, geen Vert.x-reroute en geen verplaatst SmallRye-pad.** Beide alternatieven geven op
  een niet-ondersteunde methode 404 in plaats van 405 (de handler van SmallRye zet `Allow` en geeft
  door), waardoor `RouteDekkingTest` het pad als ontbrekend ziet. Een resource die de gegenereerde
  interface implementeert volgt het OpenAPI-first-patroon van de rest van de API.
- **Bron van het document:** `OpenApiDocumentService.getDocument("<default>", Format.JSON)`, dezelfde
  bron die de handler op de root gebruikt. Dat is een runtime-klasse van Quarkus en geen gedocumenteerde
  API; een onbekende naam geeft stil een leeg document. Een test borgt daarom dat beide paden byte-gelijk
  zijn en dat het document paden bevat.
- **CORS alleen op dit pad:** de resource zet `Access-Control-Allow-Origin: *`. Een `GET` zonder eigen
  headers is een simple request, dus een preflight is niet nodig. De globale CORS-policy blijft
  ongewijzigd (magazijn: expliciet uit; uitvraag: niet geconfigureerd, dus uit). Bewust alleen simple
  requests: een preflight krijgt de header niet en `API-Version` wordt niet exposed. De test stuurt een
  `Origin` mee, zodat een later aangezette CORS-laag die het pad zou weigeren opvalt.
- **Simulator genereert `Specificatie` niet.** `demo/magazijn-simulator` genereert uit de magazijn-spec;
  per gesimuleerd magazijn hetzelfde document leveren voegt niets toe, dus `apisToGenerate` beperkt
  zich tot `Aanlever,Berichten`.
- **Twee identieke resources**, niet in fbs-common: om dezelfde reden als `SecurityHeadersRegistratie`
  (geen HTTP-bedrading in een library met niet-HTTP-consumers).
- **Nieuwe tag `Specificatie`** in beide specs. De uitvraag genereert alleen `Uitvraag`; die lijst krijgt
  `Specificatie` erbij.
- **Versie:** een nieuw pad is een minor-wijziging: uitvraag 0.5.0 → 0.6.0, magazijn 0.4.0 → 0.5.0.
- **Geen Spectral-stap in CI** in deze PR: geen acceptatiecriterium, en een eigen wijziging aan de
  workflows. Vervolg na merge.

## Stappen

1. Beide specs: pad `/openapi.json` (`GET`, tag `Specificatie`, `200` `application/json`), tag-definitie,
   versie omhoog. Uitvraag ook `info-version` in `application.properties`.
2. Uitvraag-pom: `apisToGenerate` → `Uitvraag,Specificatie`.
3. Per dienst een `specificatie/SpecificatieResource` die de gegenereerde interface implementeert.
4. Tests per dienst: document onder het basisadres gelijk aan dat op de root, `application/json`, CORS-header,
   zonder `X-Ontvanger`; `RouteDekkingTest`-tellingen bijwerken; contracttest van de response.
5. Bruno: per collectie een request voor het document.
6. Docs: CLAUDE.md (`/openapi.json` expliciet configureren), README-tabel.

## Uitkomst

- **CORS is geen keuze.** De ADR-ruleset heeft ook `paths-open-api-json-specify-cors-header` (error): de
  `200` moet `access-control-allow-origin` declareren, in kleine letters, want de regel zoekt op die
  sleutel. Een leg-uit was dus geen optie.
- **Het magazijn publiceerde versie 0.1.0.** Zonder `quarkus.smallrye-openapi.info-version` neemt SmallRye
  `quarkus.application.version` over. Die regel staat nu gelijk aan de spec, net als bij de uitvraag,
  en de test vergelijkt `info.version` met `ApiInfo.SPEC_VERSION`.
- Het gepubliceerde document is het door SmallRye gescande document, met paden inclusief `/api/v1`. Dat
  gold al voor de root en is niet veranderd.

## Verificatie

- Spectral met de ADR-ruleset op beide specs: nul errors (de bekende `servers-use-https`-warning blijft).
- `./mvnw clean verify -pl services/berichtenuitvraag,services/berichtenmagazijn -am` groen, JaCoCo en
  detekt inbegrepen; `demo/magazijn-simulator` compileert nog tegen de magazijn-spec.
