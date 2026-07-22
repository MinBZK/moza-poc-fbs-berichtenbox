**Status:** Uitgevoerd — runtime-verificatie (Docker) openstaand

> **Lokaal geverifieerd:** 11 generator-unittests groen (afzender==magazijn-OIN, opt-in
> klopt, fail-fast op onbekende OIN/lege lijst), detekt schoon, `node --check` op de JS,
> demo-stubs + dataset valide JSON, uitvraag-augmentatie groen, compose parst met de extra
> profiel-mount. **Nog te doen (Docker):** rebuild + `docker compose --profile demo up -d
> --force-recreate`, dan per persona ophalen — Pietersen 2 magazijnen (RVO+Belastingdienst),
> Bakkerij alleen RVO, Garage alleen Belastingdienst; basisvulling `geslaagd == aangeboden`.

# Demo-platform — realistisch afzender-/magazijnmodel — ontwerp

Verfijning bovenop fase 1 (data) en fase 2a (UI). Maakt de demo trouw aan het FBS-model:
één magazijn = één organisatie, en de profielservice bepaalt per persona welke
organisaties hij ontvangt.

## Aanleiding

De huidige demo overtreedt het kernmodel uit `CLAUDE.md` ("één magazijn per organisatie;
`magazijnId` ís de afzender-OIN"). Fase 1 leverde alle berichten onder één afzender-OIN aan
en zette de organisatienaam in het onderwerp — waardoor één magazijn berichten van meerdere
"afzenders" toont. Onrealistisch.

## Kernbevinding (verkenning)

De uitvraag doet de per-persona magazijnselectie **al**: `ProfielMagazijnResolver` roept
`getPartij(ontvanger)` aan, leest de opt-in afzender-OIN's uit de voorkeuren en bevraagt
**alleen die magazijnen** (`ProfielMagazijnResolver.resolve` → `bepaalMagazijnen`). Het echte
FBS-mechanisme. De demo hoeft dus geen uitvraag-code te wijzigen — alleen data/config.

`afzender` (per bericht, uit de aanlever-request) en `magazijnId` (de OIN van het bevraagde
magazijn) zijn losse velden; door de 1:1-conventie vallen ze samen zodra elk bericht in
magazijn X afzender = X's OIN krijgt.

## Model

| Magazijn | Organisatie | OIN (bestaand) |
|---|---|---|
| A | RVO | `00000001003214345000` |
| B | Belastingdienst | `00000001823288444000` |

Opt-in-matrix (profielservice bepaalt dit per persona):

| Persona | Type:waarde | Ontvangt van | Magazijnen bevraagd |
|---|---|---|---|
| J. Pietersen | BSN:999993653 | RVO + Belastingdienst | 2 |
| Bakkerij De Vroege Vogel | BSN:123456782 | RVO | 1 |
| Garage Van Dijk B.V. | KVK:12345678 | Belastingdienst | 1 |

Elk bericht: afzender = OIN van zijn magazijn; ontvanger = een persona die bij die
organisatie opt-in staat (anders 403 bij aanleveren).

## Randvoorwaarde: publiek stub-image schoon houden

`wiremock/externe-stubs/mappings/` wordt in een **publiek GHCR-image** gebakken en breed
uitgerold; per `wiremock/externe-stubs/README.md` mogen die mappings **geen elfproef-geldige
BSN's** bevatten (AVG art. 32). De personas hebben juist geldige BSN's (het magazijn eist die
bij aanlevering).

**Oplossing:** de persona-specifieke voorkeuren komen in een aparte, **demo-only** map
`wiremock/demo-profiel/mappings/`, die alleen de lokale compose-`profiel-service` mount —
níet in het ZAD-image. De gedeelde `externe-stubs`-catch-all blijft ongemoeid (lagere
prioriteit; de demo-stubs winnen op de drie persona-paden).

## Wijzigingen

1. **`wiremock/demo-profiel/mappings/`** (nieuw, demo-only): drie mappings, per persona een
   `OntvangViaBerichtenbox`-voorkeur met de juiste organisatie-OIN's in scope, `priority: 1`.
2. **`compose.yaml`**: de `profiel-service` mount naast de gedeelde mappings ook
   `./wiremock/demo-profiel/mappings` (als subdir onder de mappings-root; WireMock laadt
   recursief).
3. **Generator + `basis.json`**: afzender = OIN van het doelmagazijn; ontvangers alleen
   personas die bij die organisatie opt-in staan. De generator kent per persona zijn
   organisaties en per organisatie een naam + realistische onderwerpen.
4. **Config-naam (`%dev`)** in berichtenuitvraag: `magazijnen."<OIN>".naam` → "RVO" /
   "Belastingdienst" onder `%dev` (prod houdt "Magazijn A/B").
5. **UI (`berichtenbox.js`)**: bouw een OIN→naam-map uit de ophaal-events en toon de
   organisatienaam i.p.v. de kale afzender-OIN.

## Grens

Met twee magazijnen kan een persona {RVO}, {Belastingdienst} of beide zien. Rijkere lijsten
volgen vanzelf met fase 6 (variabel aantal stub-magazijnen); dan krijgt elke stub een eigen
OIN + naam en kunnen personas langere organisatielijsten hebben.

## Verificatie

- Aanleveren: basisvulling `geslaagd == aangeboden` (geen 403 → opt-ins kloppen met dataset).
- Per persona ophalen: Pietersen 2 magazijnen, Bakkerij/Garage elk 1 (zichtbaar in de
  voortgangsregels); lijst toont "RVO"/"Belastingdienst" als afzender.
- Generator-unittests: afzender == gekozen magazijn-OIN; ontvanger opt-in bij dat magazijn.
- detekt schoon; `node --check` op de JS.
