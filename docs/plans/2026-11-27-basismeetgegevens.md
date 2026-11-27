# Basismeetgegevens voor uitvraag, magazijn en stelselregister

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#1474, onder epic #1469 (beheermogelijkheden).

## Context

Van geen enkele stelseldienst is te zien hoeveel verzoeken er binnenkomen, hoe lang ze duren en
hoeveel er fout gaan. #1474 vraagt standaardmeetgegevens als basis voor de dashboards, meldingen
en domeinmeetwaarden in de rest van de epic. Twee beslissingen liggen nog open en blokkeren dit
werk niet: welke labels mogen (#1472) en waar de gegevens heen gaan (#1473). Het ticket zegt zelf
dat de instrumentatie eerder kan beginnen.

## Ontwerpkeuzes

1. **Prometheus-registry.** `quarkus-micrometer-registry-prometheus` in de drie diensten. Pull op
   een HTTP-endpoint is het formaat dat elk platform kan afnemen (ServiceMonitor, Prometheus,
   een OTel-collector met Prometheus-receiver). Valt #1473 op OTLP-push uit, dan is dat één
   dependency omwisselen; de instrumentatie zelf is registry-onafhankelijk.
2. **Beheerpoort, niet de applicatiepoort.** `quarkus.management.enabled=true`. Op ZAD is `/q`
   via het publieke adres bereikbaar (het statusdashboard leest daar `/q/health/ready`), dus een
   pad-afscherming volstaat niet. De beheerpoort krijgt geen route en geen Service-poort.
3. **Health en OpenAPI blijven op de applicatiepoort.** Met de beheerpoort aan verhuizen ze
   standaard mee. Dat breekt de health-checks van ZAD (per component en per preview met de hand
   ingesteld), de probes van de chart, het statusdashboard en de ADR-lint op `/openapi.json`.
   `quarkus.smallrye-health.management.enabled=false` en
   `quarkus.smallrye-openapi.management.enabled=false` houden ze waar ze zijn.
4. **Poortnummer afgeleid van de applicatiepoort: `2${quarkus.http.port}`.** In podman-hostnet
   delen alle diensten één netns; een vaste poort (Quarkus-default 9000) laat de tweede dienst
   niet starten, en magazijn-b draait daar met hetzelfde image op 8091. Met de afleiding
   verschuift de beheerpoort mee met elke `QUARKUS_HTTP_PORT`, zonder extra variabele per
   opstelling. Een `1` ervoor kan niet: Toxiproxy gebruikt die reeks al (18086, 18090, 18091).
5. **Onbekende paden tellen als één reeks** (`suppress4xx-errors=true`). Anders bepaalt elke
   aanroeper met een willekeurig pad hoeveel reeksen er bestaan. Endpoints tellen per template
   (`/berichten/{berichtId}`), niet per waarde.
6. **`DownstreamClient` handmatig gemeten.** Hij gebruikt `java.net.http`, dat Micrometer niet
   instrumenteert. Eén timer `fbs.publicatie.aflevering` met `doel`, `uitkomst` en `status`
   (klasse, niet code). Een doel buiten de config telt als `_onbekend`, zodat oude outbox-rijen
   geen reeksen aanmaken. Een falende meting verandert de uitkomst van de levering niet.
7. **Databasepool aan** (`quarkus.datasource.metrics.enabled=true`) in het magazijn.

## Bewust niet in deze stap

- Een `bestemming`-label per magazijn-OIN, en de eigen naam (`clientName`) voor de magazijnclients.
  Die heten nu `none` en zijn te herkennen aan `address`. Of `address` per magazijn mag blijven,
  hoort bij #1472.
- Domeinmeetwaarden (aanmeldingen, ophaalrondes, outbox-achterstand, wissen): die horen bij de
  domeintickets #1479–#1492.
- Een scraper of dashboard: #1473 en #1477.

## Verificatie

- `BeheerpoortTest` per dienst: `/q/metrics` geeft `404` op de applicatiepoort, health en de spec
  `200`; de beheerpoort telt per template, niet per onbekend pad; de poort is `2` + applicatiepoort.
- `UitvraagKetenE2eTest`: uitgaande aanroepen zijn per bestemming te zien (profiel-service en
  magazijn), zonder BSN of bericht-id in de uitvoer.
- `DownstreamClientTest`: een meting per poging per doel en uitkomst; statusklasse; onbekend doel;
  een falende registry laat het resultaat staan.
