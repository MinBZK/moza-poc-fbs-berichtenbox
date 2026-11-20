# De demo op Fundament

**Status:** Concept

Issue: MinBZK/MijnOverheidZakelijk#1639. Bouwt op de chart `fbs-dienst` uit
MinBZK/moza-poc-fbs-berichtenbox#444 (issue #1535).

## Context

De chart uit #444 installeert de drie stelseldiensten. Op het fieldlab (23-25 november 2026) moet
de hele demo op Fundament draaien, zoals op de testomgeving: de berichtenbox van de proeftuin, het
bedieningspaneel, de demo-identiteiten, de magazijn-simulator en de stubs. Op ZAD bestaan die als
losse componenten met aliassen, attachments en een authenticatiemuur. Dat is op Fundament niet
bruikbaar. Lokaal zet `compose.yaml` ze neer met een nginx ervoor (`demo-proxy`).

## Snede

Twee PR's, gestapeld:

1. **Deze PR: de chart `charts/fbs-demo` en de values van de stelseldiensten voor de demo.** Een
   chart voor alle demo-onderdelen, plus de values waarmee de vier releases van `fbs-dienst`
   (uitvraag, magazijn A en B, stelselregister) in de demo passen. De controle zonder cluster
   (`controleer.sh`) neemt de demo mee. De handleiding beschrijft de installatie stap voor stap,
   met de scenario's die op Fundament wel en niet werken.
2. **Daarna: een installatiescript** dat de voorzieningen aanmaakt (databases, geheimen,
   Gateway-listener), de vijf releases installeert en de demo controleert. Het moet zowel op de
   sandbox uit `charts/fundament-testcluster.sh` als op het fieldlab werken.

## Ontwerpkeuzes

- **Een eigen chart, niet `fbs-dienst`.** De beleidsregels van `fbs-dienst` zijn bewust op de
  stelseldiensten gericht: alleen `/api/v1` naar buiten, verplichte TLS-keuze, een
  Quarkus-image met UID 185. Het bedieningspaneel en de berichtenbox zijn een webpagina op `/`, en
  de proeftuin, de stubs, Toxiproxy en Redis zijn geen Quarkus-images. Ze in `fbs-dienst` passen
  betekent die regels oprekken voor iedereen, ook voor een echte installatie.
- **De grens blijft: het stelsel verwijst niet naar de demo.** `fbs-demo` noemt de releases van
  `fbs-dienst` (adressen en clientlabels); `fbs-dienst` weet niets van `fbs-demo`. De values
  waarmee de stelseldiensten in de demo passen, staan onder `charts/fbs-demo/stelsel/`.
- **Eén ingang voor de browser, zoals lokaal.** Een nginx (`demo-proxy`) zet de berichtenbox, het
  paneel, de personalijst en de uitvraag achter één hostnaam. Zo is er geen CORS nodig, en toont
  het paneel de berichtenbox in een frame van dezelfde origin. De uitvraag krijgt daardoor geen
  eigen route.
- **Het paneel achter een wachtwoord.** Op ZAD staat het achter de authenticatiemuur, want de
  knop "legen" wist de magazijnen. Fundament heeft geen muur die wij kunnen gebruiken. Daarom
  vraagt de demo-proxy basic-auth op `/bediening/` en `/api/demo/`. Alleen `/api/demo/personas`
  blijft open: de berichtenbox leest daar de testaccounts, en verder gebruikt hij `/api/demo` niet.
- **Toxiproxy als één instantie, achter de routes.** De storingsknoppen werken via Toxiproxy,
  zoals op ZAD. Eén instantie draagt de vier proxies (profiel, notificatie, aanmelding, Redis);
  de console maakt ze aan via de admin-API. De routes voor profiel, notificatie en aanmelding
  wijzen naar de Toxiproxy-poort in plaats van naar de stub. Zo zet "uit" ook het verkeer via de
  Gateway uit, zonder een extra hostnaam per proxy. De admin-API staat alleen open voor de console.
- **Hostnamen onder één basisdomein.** `<naam>.<basisdomein>`, bijvoorbeeld
  `demo.fbs.1-2-3-4.sslip.io`. De Gateway krijgt één https-listener met een wildcard-hostnaam en
  een certificaat met alle namen erin. Zo blijft `sectionName: https` voor elke route gelijk, zoals
  in `fbs-dienst`.
- **Diensten roepen elkaar via de Gateway aan, over https.** Buiten het profiel `dev` eisen de
  uitvraag (magazijnen, profielservice) en het magazijn (profiel, aanmelding, notificatie) https.
  Het magazijn weigert daarnaast een bestemming die naar een privé-adres resolveert
  (SSRF-blocklist). Op het fieldlab wijst sslip.io naar het publieke adres van de Gateway.
- **Het magazijnregister als config-bestand.** `fbs-dienst` weigert `SMALLRYE_CONFIG_LOCATIONS`,
  omdat daarmee ook het profiel te wisselen is. Quarkus leest uit zichzelf
  `config/application.properties` in de werkmap (`/home/jboss` in de jib-images). De demo-chart
  levert het register als ConfigMap, en uitvraag en stelselregister mounten die daar. De inhoud
  komt uit `demo/genereer-magazijnen.py`, met `--set-file`. De simulator leest zijn set op
  dezelfde manier.
- **Onze images op tag, die van anderen op digest.** De demo-images krijgen dezelfde vaste tag als
  de stelseldiensten (`main-<sha7>` of `pr-<n>-<sha7>`). De proeftuin, nginx, Toxiproxy en Redis
  staan op digest. De proeftuin-digest komt uit `compose.yaml`, zodat de berichtenbox dezelfde
  versie draait als de rest van de demo.
- **Dezelfde standaarden als `fbs-dienst`.** Per pod een vaste non-root UID, read-only
  root-filesystem met `emptyDir` waar het image schrijft, geen capabilities, seccomp
  `RuntimeDefault`, geen service-account-token, probes, requests en limits, een NetworkPolicy die
  standaard dicht is, en een PDB. De render gaat door dezelfde beleidsregels (`policy/`).
- **Eén replica per onderdeel.** Console, simulator en Toxiproxy houden toestand in het geheugen
  (tempo, proxies, gedrag). Twee replica's geven per verzoek een andere toestand. Ze rollen uit
  met `Recreate`.

## Wat op Fundament niet kan

- **Storingen op magazijn A en B.** Ook op ZAD niet: de uitvraag spreekt de magazijnen via hun
  eigen route aan. De knoppen blijven verborgen.
- **De Redis-knop aankondigen in Mattermost.** Hoort bij de bewaakte testomgeving.
- **Aanmelding en notificatie op de sandbox.** Daar resolveert de Gateway naar een privé-adres, en
  dan weigert het magazijn de bestemming. Op het fieldlab speelt dat niet.

## Verificatie

- `charts/controleer.sh`: lint, kubeconform en de beleidsregels over de render van de demo,
  ook over de vijf releases samen.
- De images die geen Quarkus zijn (proeftuin, nginx, WireMock, Toxiproxy, Redis), lokaal onder
  podman gedraaid met de UID, de read-only rootfs en de mounts uit de chart.
- Op een cluster: in de volgende PR, op de sandbox van Fundament.
