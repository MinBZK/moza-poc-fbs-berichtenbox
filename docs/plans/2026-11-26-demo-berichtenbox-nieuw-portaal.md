# De demo toont de Berichtenbox van het nieuwe MOZa-portaal

**Status:** Uitgevoerd

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

Eerst gepland als vier PR's (lokale stack, pin, ZAD, documentatie). Op 2026-11-27 besloot de
opdrachtgever anders: MinBZK/MijnOverheidZakelijk#1453 hoort bij #1345, en alles wat de
Berichtenbox in moza-poc kon, moet na de overstap in het portaal werken. Niet opknippen in stappen
die los niets werkends opleveren. Daarom twee PR's, één per repository:

- **MinBZK/moza-portaal#303**: #1453 in het portaal (sessies, volledigheid, berichtsleutel,
  bijlage via `_links`, events, mappen, persona `proeftuin-vier`).
- **MinBZK/moza-poc-fbs-berichtenbox#492** (dit plan): de hele #1345 aan onze kant — lokale stack,
  pin, ZAD, Fundament en documentatie.

#492 pint op het image van #303 tot die gemerged is en het portaalteam een `main-<sha7>`-image
heeft gebouwd; vóór de merge van #492 gaat de pin naar dat image.

## Ontwerpkeuzes: lokale stack

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
- **De rookproef speelt de sessie van de Berichtenbox na.** Stap 7 start via de proxy een sessie
  bij het portaal (201 met het cookie `keten-sessie`), leest `/api/keten/berichten` met dat cookie
  (200 of 409) en beëindigt de sessie (204). Het cookie komt uit de headers: het portaal zet het
  met `Secure`, en curl stuurt zo'n cookie over gewone HTTP niet terug.
- **De bijlage-stap gaat rechtstreeks naar de uitvraag**, met `X-Ontvanger` en `X-Sessie`. Wat de
  proef daar toetst — het adres uit `_links.self` levert de bijlage — blijft gelden.
- **De demo-proxy geeft het portaal `X-Forwarded-Host`.** Next.js weigert een Server Action als de
  `Origin` van de browser niet bij de host past; zonder die header vergelijkt hij met de upstream.
- **In podman-hostnet luistert het portaal op `localhost`, vastgelegd op IPv4.** Met een letterlijk
  IP-adres als `HOSTNAME` stuurt Next.js elke pagina met persona-cookie naar zichzelf door.

## Ontwerpkeuzes: pin, ZAD en Fundament

- **Eén pin, de regel van `portaal` in `compose.yaml`.** De proeftuin en zijn profiel `zad-pin`
  verdwijnen. `portaal-image.sh` leest de regel voor de deploy, het creatiescript en de
  Fundament-installatie; `PORTAAL_IMAGE` overschrijft hem voor één draai.
- **De pin-controle kent de tags van het portaal.** Main-images heten `main-<sha7>` (leesbaar:
  `main`) en worden met de hand gebouwd, dus `ontbreekt` kan dagen duren. Previews staan in
  hetzelfde repository als `pr-<n>-<sha7>`; alleen de tag onderscheidt ze, en ze verdwijnen bij het
  sluiten van hun PR.
- **Het component op ZAD heet `portaal`**, poort 3000, met `KETEN_BACKEND_URL` en
  `KETEN_PERSONAS_URL` op de publieke adressen van uitvraag en personadienst (de NetworkPolicy laat
  tussen projecten alleen de route toe). `AUTH_SECRET` en `HOSTNAME` gaan op de env-laag van alleen
  het portaal: ZAD voegt de aliassen van een deployment samen tot één secret dat elk component
  krijgt. `AUTH_SECRET` krijgt een willekeurige waarde, alleen als hij er nog niet is: een nieuw
  geheim zou elke open Berichtenbox uitloggen. Aanmaken gebeurt met de hand vóór de merge; het oude
  component `proeftuin` gaat erna weg.
- **Op Fundament een eigen Secret `fbs-demo-portaal`.** `secret_eenmalig` voegt geen key toe aan
  een bestaand Secret, dus een installatie van vóór het portaal krijgt zo alsnog een geheim.
  `HOSTNAME=0.0.0.0` expliciet, want Kubernetes zet de variabele op de podnaam.
- **De persona-kruiscontrole vergelijkt op KVK-nummer.** Het portaal koppelt zo, en zijn id's
  wijken af van de onze (`proeftuin-veel` is `grootbedrijf`).

## Verificatie

- `demo/up.sh` in podman-bridge, daarna `demo/smoke.sh`.
- De Berichtenbox in de browser via `:8097/bediening/`, persona `proeftuin-een`.
- Script-suites (`demo/lib/test-*.sh`, `.github/scripts/test-proeftuin-*.sh`), shellcheck,
  actionlint, `docker compose config` voor elke overlay, de SELinux- en merge-guard.
