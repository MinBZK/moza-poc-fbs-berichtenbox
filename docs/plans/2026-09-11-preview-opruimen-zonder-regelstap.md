# Preview opruimen zonder aparte regelstap

**Status:** Uitgevoerd

## Aanleiding

`cleanup-preview.yml` faalde op 10 september vier keer, op twee manieren:

- **Een PR zonder preview.** Bij het sluiten van #292, #306 en #309 bestond `pr-<n>` nooit. De stap die de cross-domain-regels weghaalt, stuurde toch een patch; Operations Manager (OM) maakte er een `configure_service`-taak van die faalde met `Deployment 'pr-<n>' not found in project`. Die taak vergrendelde het project wel: taak `27b2d6bb` uit de opruiming van #306 liet de preview-uitrol van #310 stranden op `superseded` (MinBZK/MijnOverheidZakelijk#1075).
- **Een PR mét preview.** Bij #308 was de taak die de regels weghaalde na de wachttijd van het script (2 minuten) nog niet klaar, en de stap werd rood. Hij duurde 4,5 minuut. Het leegmaken van de regels van één deployment liet OM het héle project opnieuw verwerken: database en Keycloak voor 9 deployments, en wachten tot ze alle 9 gesynchroniseerd waren ("Uitgerold, maar 6 van de 9 deployments draaien niet gezond").

## Verificatie vooraf

De regels die een preview per deployment krijgt, staan in de projectspec ónder die deployment (`deployments[].services` → `cross-domain-access`). In `RijksICTGilde/rig-cluster-projects` (`projects/mpfm-w3h.yaml`):

- De regel-stap van de opruiming van `pr-308` maakte die lijst leeg (`outbound: []`, commit om 11:31).
- De delete om 11:35 (`fa5c4d12`) haalde het complete `pr-308`-blok weg, inclusief `services` met die lege `cross-domain-access`-configuratie.

De regel-stap deed dus niets dat de delete niet ook deed. Regels die naar `pr-<n>` wijzen staan alleen onder `pr-<n>` in de drie projecten, en die ruimt elke leg van de opruiming zelf op.

## Wijziging

- **Geen regel-stap meer** in `cleanup-preview-zad`. De `CROSS_DOMAIN_REGELS_*`-lijsten en de matrixvelden `richting`/`regelsleutel` verdwijnen uit `cleanup-preview.yml`.
- **Eerst vaststellen of er een preview is.** Is `pr-<n>` aantoonbaar afwezig, dan slaat de leg de delete over: geen taak in OM, geen vergrendeld project, een groene opruiming. Is de lijst niet te lezen, dan wordt er gewoon verwijderd; de nameting beslist.
- **Nieuw script `zad-deployment-bestaat.sh`** (exitcode 0/1/2) voor die vaststelling, voor de nameting erna en voor `preview-klaarzetten.sh`, dat dezelfde logica tot nu toe zelf droeg.
- **`cross-domain-preview.sh` verliest `verwijder`**, dat nergens meer gebruikt wordt.
- Tests: nieuwe suite `test-zad-deployment-bestaat.sh`, inclusief de aansluiting op `cleanup-preview.yml`; de `verwijder`-tests verdwijnen uit `test-cross-domain-preview.sh`.

## Niet gedaan

- **De wachttijd van `cross-domain-preview.sh` verlengen.** Die stap draait nu alleen nog in `preview-klaarzetten`, met `rollout=false`: daar verwerkt OM het project niet.
- **Het `superseded`-probleem zelf.** Blijft onderwerp van #1075; deze wijziging haalt alleen een bron van botsingen weg die we zelf veroorzaakten.

## Verificatie

- Alle bash-suites onder `.github/scripts/`, `actionlint`, `shellcheck -x -S warning`.
- `cleanup-preview.yml` draait via `pull_request_target` altijd de versie op main, dus het gedrag is pas na de merge te zien: sluit een PR zonder preview (de opruiming hoort groen te zijn met de melding "er is niets op te ruimen") en een PR met preview (de delete draait, zonder regel-stap).
