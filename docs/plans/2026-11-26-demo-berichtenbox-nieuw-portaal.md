# De demo toont de Berichtenbox van het nieuwe MOZa-portaal

**Status:** Concept

Issue: [MinBZK/MijnOverheidZakelijk#1345](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1345).

## Context

De demo toont de berichtenbox uit de proeftuin (`MinBZK/moza-poc`), lokaal als container achter de
demo-proxy en op ZAD als eigen component. De Berichtenbox van het nieuwe MOZa-portaal
(`MinBZK/moza-portaal`) staat sinds 2026-10-27 op hun `main` (MinBZK/moza-portaal#137). `ux-fork`
bestaat niet meer als ontwikkeltak; de open vraag "wanneer" uit het issue is daarmee beantwoord:
de demo volgt `main` van moza-portaal.

Twee dingen staan vast voordat er iets verandert:

- **Het portaal start nog geen sessie (MinBZK/MijnOverheidZakelijk#1453).** De uitvraag eist sinds MinBZK/MijnOverheidZakelijk#943 een `X-Sessie` op elk
  `/berichten…`-verzoek. Het portaal stuurt die niet, dus op de lokale stack en op `test` geeft de
  uitvraag `401 urn:fbs:fout:sessie-ongeldig`. Geverifieerd op 2026-11-26 tegen
  `uitvraag-test-mpfb-8wh`. Tot MinBZK/MijnOverheidZakelijk#1453 punt 1 in het portaal zit, toont de Berichtenbox van het
  portaal geen berichten uit de keten.
- **De persona's sluiten al aan.** Het portaal kent zes persona's met `stelsel: true`
  (`proeftuin-een` t/m `-drie`, `-veel`, `-concern`, `-garage`, KVK 90000011–13, 90000001, 90000003,
  90000014). Alle zes staan in de personadienst met `bron=keten`, en het portaal leest die lijst al
  (`KETEN_PERSONAS_URL`). Wat het portaal niet toont: de BSN-persona's (MOZa is zakelijk) en
  `proeftuin-vier` (KVK 90000015, de mappen-demo).

## Snede

Per stap in de keten, elk in één zitting te reviewen:

1. **Lokale demo-stack** (deze PR). Dienst `portaal` op digest gepind in `compose.yaml`, de demo-proxy
   stuurt `/` en `/api/keten/` naar het portaal, overlays `compose.portaal-versie.yaml` en
   `compose.portaal-lokaal.yaml`, `demo/up.sh`, de rookproef en het paneel. De proeftuin blijft als
   pin voor ZAD in `compose.yaml` staan, in een eigen profiel dat lokaal niet start.
2. **Pin volgt moza-portaal.** Scripts en workflows die nu de main-commit van moza-poc vergelijken,
   vergelijken het `main-<sha>`-image van moza-portaal; dependabot-regel mee.
3. **ZAD.** `deploy.yml` en het creatiescript rollen het portaal uit als berichtenbox-component, met
   `KETEN_BACKEND_URL` en `KETEN_PERSONAS_URL` op de deployment. De proeftuin-pin en zijn scripts
   gaan weg.
4. **Documentatie en Fundament.** Runbook-scenario's per scenario nagelopen in het portaal, of met de
   reden waarom het (nog) niet kan; de chart `charts/fbs-demo` en de overige verwijzingen.

Stap 2–4 hangen van stap 1 af; stap 3 heeft pas zin als MinBZK/MijnOverheidZakelijk#1453 punt 1 in het portaal zit, anders
staat er op ZAD een Berichtenbox zonder berichten.

## Ontwerpkeuzes stap 1

- **De proxy praat niet meer met de uitvraag.** Het portaal zet `X-Ontvanger` server-side uit het
  persona-cookie en stuurt geen `Origin` mee. De `/api/v1/`-route en de cookie-route voor bijlagen
  waren er voor de proeftuin; ze vervallen, net als de CORS-allowlist van de uitvraag in het
  demo-profiel.
- **`/api/keten/` krijgt de SSE-instellingen** (HTTP/1.1, geen buffering, lange read-timeout) die
  eerst op `/api/v1/` stonden: ophalen en volgen zijn streams.
- **Poort 8096 blijft het adres van de berichtenbox**, ook in podman-hostnet: Next.js luistert op
  `PORT`, dus het portaal kan wél verzetten wat de proeftuin-nginx niet kon.
- **`AUTH_SECRET` met een vaste default.** next-auth meldt zonder geheim bij elk verzoek een fout. De
  lokale demo heeft geen inlogdienst; het geheim ondertekent alleen cookies van een stack op
  loopback. Te overschrijven met `PORTAAL_AUTH_SECRET`.
- **De rookproef meldt het sessiegat in plaats van te falen.** Stap 7 vraagt `/api/keten/berichten`
  via de proxy met alleen het persona-cookie. 200 of 409 is goed; een 401 met
  `sessie-ongeldig` is het bekende gat (MinBZK/MijnOverheidZakelijk#1453) en geeft een `LET OP`-regel; al het andere faalt.
  Zo bewijst de proef dat proxy, portaal en uitvraag aan elkaar hangen, zonder rood te staan op
  iets wat deze repo niet kan oplossen. `TODO(MinBZK/MijnOverheidZakelijk#1453)` zet de eis terug op 200/409.
- **De bijlage-stap gaat rechtstreeks naar de uitvraag**, met `X-Ontvanger` en `X-Sessie`. Wat de
  proef daar toetst — het adres uit `_links.self` levert de bijlage — blijft gelden.
- **De demo-proxy geeft het portaal `X-Forwarded-Host`.** Next.js weigert een Server Action als de
  `Origin` van de browser niet bij de host past; zonder die header vergelijkt hij met de upstream.
- **In podman-hostnet luistert het portaal op `localhost`, vastgelegd op IPv4.** Met een letterlijk
  IP-adres als `HOSTNAME` stuurt Next.js elke pagina met persona-cookie naar zichzelf door.

## Verificatie stap 1

- `demo/up.sh` in podman-bridge, daarna `demo/smoke.sh`.
- De Berichtenbox in de browser via `:8097/bediening/`, persona `proeftuin-een`.
- Script-suites (`demo/lib/test-*.sh`, `.github/scripts/test-proeftuin-*.sh`), shellcheck,
  actionlint, `docker compose config` voor elke overlay, de SELinux- en merge-guard.
