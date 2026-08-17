# Federatie-harness: adres per component in plaats van poortblokken

**Status:** Uitgevoerd

## Context

De federatie-opstelling (`demo/environment/federatie/`) zet meerdere FSC-peers in één netns.
Omdat alle componenten daar hetzelfde loopback-adres delen, kreeg elke peer een blok van 100
poorten (`61000`, `61100`, …) met vaste offsets per component. Dat werkt, maar het heeft drie
gevolgen die zich pas nu laten zien:

1. **Elke overlay moet renummeren.** Niet alleen de listeners, ook elke kruisverwijzing
   (`CONTROLLER_REGISTRATION_API_ADDRESS`, `TX_LOG_API_ADDRESS`, `MANAGER_ADDRESS_INTERNAL`) draagt
   een afwijkende poort. Een peer toevoegen is vijftien offsets overtypen.
2. **De boekhouding groeit mee.** `peers.env` houdt blokken bij, de CI-guard toetst
   blok-disjunctheid, de README draagt een offset-tabel, en de smoke rekent met `BLOK + 12`.
3. **De asserts zijn niet af te bakenen.** Alles luistert op `127.0.0.1`, dus de smoke kan zijn
   eigen listeners niet onderscheiden van andere stacks in dezelfde netns. De assert
   "geen standalone-poort blijven hangen" gaat vals rood zodra de demo-stack uit `compose.yaml`
   ernaast draait: die claimt `8081`, `8082` en `8090`, precies de poorten die de standalone-overlay
   van een peer ook gebruikt.

Loopback is `127.0.0.0/8` — zestien miljoen adressen. Geverifieerd op deze machine: `127.10.1.1`,
`127.20.2.1` en `127.200.5.9` binden alle, en drie processen kunnen tegelijk `:9443` claimen zolang
hun adres verschilt.

## Ontwerp

Elke **component** krijgt een eigen loopback-adres en houdt zijn **standaardpoort**.

```
127.20.0.1  router (SNI :443)      127.20.<n>.1  manager       (8443/9443/9444/8080)
127.20.0.2  postgres               127.20.<n>.2  controller    (8080/9443/9444/8081)
127.20.0.3  directory-manager      127.20.<n>.3  txlog         (9443/8081)
127.20.0.4  directory-ui           127.20.<n>.4  inway         (8443/8081)
                                   127.20.<n>.5  outway        (8443/8081)
                                   127.20.<n>.6  stub-upstream (8080)
```

Peer `n` krijgt `127.20.<n>.0/24`: logius `127.20.1`, magazijn-a `127.20.2`.

### Waarom per component en niet per peer

Eén adres per peer is niet genoeg. Binnen één peer botsen de componenten onderling ook: manager en
inway willen beide `8443`; manager-intern, controller-registratie en txlog willen alle drie `9443`;
manager-monitoring, controller-UI en stub willen alle drie `8080`. In bridge-modus botst dat niet
omdat elke container een eigen IP heeft — dat is precies wat we hier met de hand nabouwen.

### Wat dit oplevert

- **Kruisverwijzingen kloppen weer uit zichzelf.** De basis-compose zegt al
  `https://controller.magazijn-a.fsc-test.local:9443`; met standaardpoorten is dat weer waar.
- **`127.20.` is per definitie van de federatie.** De smoke filtert daarop en ziet demo-stack- of
  standalone-listeners niet meer. De asserts worden daarmee strenger, niet losser.
- **Een peer toevoegen is één octet**, geen blok plus vijftien offsets.
- **`peers.env` verliest `BLOK_*` en `INFRA_POORTEN`** en krijgt `NET_<peer>` plus vier
  infra-adressen.

### Wat dit kost

De federatie-overlays worden niet korter. Ze zitten bovenop `docker-compose.podman-hostnet.yaml`,
dat de poorten al renummerde; die waarden moeten expliciet teruggezet worden naar de
standaardpoorten. Wat verdwijnt is het *verzinnen* van offsets, niet het aantal regels.

## Stappen

1. `federatie/peers.env` — `BLOK_*`/`INFRA_POORTEN` eruit, `NET_*` en `ADRES_*` erin.
2. `lib/fsc-harness.sh` — `fsc_component_adres <net> <component>`, plus tests in
   `lib/test-federatie-helpers.sh`.
3. `federatie/compose/{logius,magazijn-a}.yaml` — `extra_hosts` per component, bind-adressen per
   component, kruisverwijzingen terug op standaardpoorten.
4. `federatie/haproxy.federatie.cfg` — bind op het router-adres, backends naar de component-adressen.
5. `federatie/federatie.sh` — status-filter loopback-generiek.
6. `federatie/smoke-federatie.sh` — adres-gescoopte asserts; blok-checks eruit.
7. `.github/scripts/merge-guard.sh` — `127.0.0.1` → elk `127.x.y.z` (de standalone-harnessen
   blijven `127.0.0.1` gebruiken, dus beide vormen moeten door).
8. `.github/workflows/fsc-harness-overlays.yml` — blok-disjunctheid wordt adres-disjunctheid.
9. `federatie/README.md` — poorttabel wordt adrestabel.

## Verificatie

- `federatie.sh up` + `smoke-federatie.sh` groen op podman rootless.
- Beide merge-guard-jobs en `harness-scripts` (shellcheck + bash-unittests) lokaal groen.
- Negatieve controle: een component zijn federatie-overlay ontnemen bindt hem op `127.0.0.1` en
  moet de smoke rood maken.

## Uitkomst

Het risico bleek niet te bestaan: de eerste `up` op het nieuwe model kwam in één poging omhoog en de
smoke is groen op alle vijf de asserts. Geen enkele OpenFSC-component belt hardcoded naar
`127.0.0.1`.

Twee dingen kwamen er onderweg bij:

- **De disjunctheidscheck moest dedupliceren.** Onder het blokschema claimde een peer elke poort
  precies één keer; nu claimt één component hetzelfde adres met vier listeners. Zowel de smoke als de
  CI-guard las dat als "twee peers op één adres". Beide zijn gecorrigeerd, en met een mutatie
  getoetst: een adres uit een vreemd `/24` maakt ze wél rood.
- **Een gat dat het blokschema ook al had.** Een service die zijn federatie-overlay mist, valt terug
  op het adres uit de hostnet-overlay en *verdwijnt* daarmee uit de gedeclareerde adressen — geen
  enkele assert vuurde. De CI-guard eist nu dat élke `LISTEN_ADDRESS`/`MONITORING_ADDRESS` in de
  federatie-merge binnen het prefix valt. Getoetst door `txlog-magazijn-a` zijn override af te
  nemen: `LISTEN_ADDRESS=127.0.0.1:49443` wordt gemeld.

Nevenopbrengst, en de aanleiding voor deze herziening: `127.0.0.1` is nu volledig vrij terwijl de
federatie draait. De demo-stack uit `compose.yaml` kan er dus naast.
