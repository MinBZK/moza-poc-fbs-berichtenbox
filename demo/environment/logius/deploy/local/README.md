# Lokale FSC-harness — directory + peer logius

Runnable shift-left van de ZAD-deploy: een lokale FSC-directory + de peer `logius`
(manager + outway + inway + controller + txlog + eigen DB's) + een SNI-router op `:443`. Bewijst dat
`logius` zich aanmeldt (announce) bij de directory. Geen aangeboden dienst, geen
OIDC-login-voorziening — control-plane-only voor deze ene peer. Bouwt voort op `pki/`
(zie dat README voor het cert-contract).

> **Vereist Docker + `docker compose` (v2) en gegenereerde certs** (`pki/issue.sh`, vereist
> `cfssl`). Draai eerst de PKI in `pki/`, dan de stack + smoke hieronder.

## Benodigdheden

- **Docker** + `docker compose` (v2).
- Gegenereerde certs uit `pki/` — draai daar eerst `./init-ca.sh`, `./issue.sh`,
  `./gen-crl.sh` en `./verify.sh` (zie `pki/README.md`, sectie "Uitvoeren"). Zonder certs
  faalt elke container die `/pki` mount bij boot (ontbrekend bestand).

## Draaiboek

Alle commando's vanuit de **peer-root** (`demo/environment/logius/`).

```bash
# 1. Genereer de PKI voor logius (zie pki/README.md).
cd pki
./init-ca.sh
./issue.sh
./gen-crl.sh
./verify.sh          # verwacht: "== ALLE ASSERTS GROEN =="
cd -

# 2. Harness-env. De cert-lezende containers draaien als JOUW UID/GID, zodat ze de
#    0600-privékeys via de owner-bit lezen (keys blijven dicht).
cp deploy/local/.env.example deploy/local/.env
printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> deploy/local/.env

# 3. Start de stack.
docker compose -f deploy/local/docker-compose.yaml up -d
sleep 20 && docker compose -f deploy/local/docker-compose.yaml ps

# 4. Draai de smoke.
./deploy/local/run-smokes.sh    # verwacht: "ALLE SMOKES GROEN."
```

Losse smoke (voor gerichte diagnose):

```bash
./deploy/local/smoke-announce.sh    # verwacht: "OK: logius is aangemeld ..."
```

Opruimen:

```bash
docker compose -f deploy/local/docker-compose.yaml down -v
```

> **Hosts-bestand niet nodig.** De SNI-hostnames (`directory.fsc-test.local`,
> `logius.fsc-test.local`, `inway.logius.fsc-test.local`) resolven *binnen* het
> docker-netwerk via de router-aliases. De UIs benader je via `localhost`-poorten hieronder.

## Wat er opkomt

- **postgres** — één instantie, per component een eigen database (`postgres-init.sql`):
  `fsc_directory`, `fsc_logius`, `fsc_controller_logius`, `fsc_txlog_logius`.
- **router** (haproxy) — SNI-passthrough op `:443` naar `manager-directory` en
  `manager-logius`.
- **manager-directory** + **directory-ui** (`http://localhost:8081`, geen login) — de lokale
  FSC-directory (`AUTO_SIGN_GRANTS=servicePublication,delegatedServicePublication`). Host-poort
  `8081` i.p.v. de standaard `8080`: `magazijn-a` bindt `8080`/`8090` al lokaal, en beide
  peer-harnessen moeten tegelijk kunnen draaien. Container-poort blijft `8080`.
- **migrate-logius**, **manager-logius** — de manager van de peer (announce, token- en
  contractendpoints).
- **migrate-controller-logius**, **controller-logius** (`http://localhost:8091`, zonder
  login: `AUTHN_TYPE=none`) — dienst-beheer (contract-publicatie, delegatie). Host-poort `8091`
  i.p.v. `8090`, zelfde reden als hierboven; container-poort blijft `8080`.
- **migrate-txlog-logius**, **txlog-logius** — transactielog-API van de peer
  (internal-PKI-mTLS).
- **outway-logius** — client-egress: leest z'n contract-/service-config van de eigen
  manager (internal-authenticated `:9443`) en logt uitgaande transacties bij txlog. Geen inbound
  SNI-route (geen aangeboden dienst).
- **inway-logius** — ingress-proxy: registreert zich bij de eigen controller (`:9443`) en
  leest z'n config bij de eigen manager op de internal-**unauthenticated** poort (`:9444`) —
  bewust anders dan de outway, die de authenticated `:9443` gebruikt (conform magazijn-a's
  bewezen inway-config). Eigen SNI-route op de router (`inway.logius.fsc-test.local`). Biedt
  de dienst `profiel-service` aan (zie `publish-service.sh`), met `stub-upstream` als
  `endpoint_url`.
- **toolbox** — curl-client op het netwerk voor mTLS-onboarding-calls (niet gebruikt door deze
  announce-only-proof, maar beschikbaar voor gerichte diagnose).

Geen aangeboden-dienst-onboarding, geen OIDC-login-voorziening — beide zijn buiten scope voor
deze announce-proof. De inway draait wel (mesh-ingress + registratie), maar biedt nog geen dienst
aan.

## Smoke

| Script | Bewijst |
|--------|---------|
| `smoke-announce.sh` | `logius` (OIN `00000000000000001000`) staat in `peers.peers` met een `manager_address` op `:443`. |
| `publish-service.sh` | `profiel-service` is aangemaakt op de controller en gepubliceerd (servicePublication-contract, auto-signed). |
| `smoke-discover.sh` | `profiel-service` is vindbaar via de manager-mesh-API (`GET /v1/peers/{dir}/services`). |
| `consume-service.sh` | Een zelfreferentieel `serviceConnection`-contract is wederzijds ondertekend; levert de `Fsc-Grant-Hash`-waarde. |
| `run-smokes.sh` | Draait alle vier in volgorde. |

De inway draait, biedt `profiel-service` aan, en er is een geldig afnemer-contract — het
volledige lokale FSC-bewijs voor deze dienst is hiermee rond. Het écht dóór de inway heen
aanroepen van `stub-upstream` (het data-pad) én de integratie met `berichtenuitvraag` zelf
blijven buiten deze lokale proof (zie `deploy/zad/verify-zad.md`).

**Inway-boot handmatig controleren.** `run-smokes.sh` test alleen de announce; een crash-loopende
inway wordt daardoor niet gesignaleerd. Dat is precies hoe het geaccepteerde `:9444`-risico zich
zou manifesteren: de inway gebruikt `MANAGER_INTERNAL_UNAUTHENTICATED_ADDRESS` (`:9444`), en als
`fsc-inway serve` v2.5.2 tóch de authenticated `:9443` blijkt te eisen, faalt de boot daar
zichtbaar. Controleer dus na `docker compose up -d`:

```text
docker compose -f deploy/local/docker-compose.yaml ps inway-logius      # verwacht: running, niet restarting
docker compose -f deploy/local/docker-compose.yaml logs inway-logius | tail -30
```

## Troubleshooting

- **Container kan cert niet vinden** → controleer dat `pki/out/logius/<endpoint>/` en
  `pki/internal/logius/<endpoint>/` bestaan (na `./pki/issue.sh`); paden moeten
  matchen met de compose-env.
- **`permission denied` op `key.pem` bij boot** → `HOST_UID`/`HOST_GID` in
  `deploy/local/.env` matchen niet met de eigenaar van de keys. Zet ze met
  `printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> deploy/local/.env` en
  `docker compose -f deploy/local/docker-compose.yaml up -d --force-recreate`. Kloppen ze wél,
  dan draai je waarschijnlijk **rootless podman** — dat vergt UID-mapping die deze harness nog
  niet meelevert (zie het podman-punt hieronder).
- **Poort bezet** (8081, 8091) → stop de conflicterende dienst of pas de `ports`/`bind`
  in `docker-compose.yaml` / `haproxy.cfg` aan. De router publiceert geen host-poort
  (SNI-passthrough intern op het compose-netwerk), dus `443` kan hier niet conflicteren.
- **Smoke faalt** → `docker compose -f deploy/local/docker-compose.yaml logs
  manager-directory manager-logius controller-logius` voor de mesh-logs. Blijft de announce-smoke
  hangen op "nog niet aangemeld" terwijl de managers gezond loggen, controleer dan éérst
  `docker compose ps -a | grep router`: de `*.fsc-test.local`-namen zijn aliassen van de ROUTER,
  dus zonder router vertrekt geen enkele announce en zie je in de managerlogs geen fout.
- **Podman i.p.v. Docker** → onder **rootful** podman draait de harness zoals hieronder.
  **Rootless** podman — de normale modus op Linux — heeft daarnaast UID-mapping nodig: het mapt
  host-UID 1000 op container-UID 0, waardoor `user: "1000:1000"` in de container een subuid wordt
  en de container de `0600`-privékeys van host-UID 1000 níét meer leest. Elke `/pki`-mountende
  container faalt dan bij boot op `permission denied`. De oplossing (`userns_mode: "keep-id"` op
  de cert-lezende services) komt met de podman-overlay uit de opvolg-PR; tot die er is, draai je
  hier rootful podman of Docker. Voor beide podman-modi geldt verder:
  - Gebruik `docker compose` of `podman compose` (zónder streepje). `podman-compose` (mét
    streepje) is een losse herimplementatie die `depends_on: condition:` en netwerk-`aliases:`
    niet volledig dekt — de managers starten dan vóór hun migraties en de peers vinden elkaar
    niet op hun `*.fsc-test.local`-naam.
  - De `router` heeft `sysctls: net.ipv4.ip_unprivileged_port_start=0` nodig: het haproxy-image
    draait als non-root en podman zet die sysctl, anders dan Docker Desktop, niet op 0 → `bind
    :443` faalt met `Permission denied`.
  - `haproxy.cfg` gebruikt `parse-resolv-conf` in plaats van een vast nameserver-adres: Docker's
    embedded DNS zit op `127.0.0.11`, podman's aardvark-dns op de netwerk-gateway. Met een hard
    adres logt de router `<NOSRV>` en zijn alle backends onbereikbaar.
- **`migrate-*` hangt / `database "…" does not exist`** → `postgres-init.sql` draait alleen bij
  een **vers** volume. Bestaat er al een postgres-volume van een eerdere run? Maak de ontbrekende
  DB eenmalig aan (`... exec -T postgres psql -U postgres -c "CREATE DATABASE <naam>;"`) of
  `down -v && up -d` (wist alles, re-init inclusief nieuwe DB's).

## Cert-contract (referentie, overgenomen uit `pki/README.md`)

De harness mount `pki/` read-only op `/pki`. Per endpoint (`manager`, `controller`, `outway`,
`inway`, `txlog`) twee ketens:

| Pad | Doel | Env |
|-----|------|-----|
| `/pki/ca/root.pem` | group-CA root (trust-anchor) | `TLS_GROUP_ROOT_CERT` |
| `/pki/internal/<peer>/ca/root.pem` | **per-peer** internal-CA root | `TLS_ROOT_CERT`, `TLS_INTERNAL_UNAUTHENTICATED_ROOT_CERT` |
| `/pki/out/<peer>/<endpoint>/{cert,key}.pem` | group-identity (hergebruikt voor token+contract) | `TLS_GROUP_CERT/KEY`, `TLS_GROUP_TOKEN_*`, `TLS_GROUP_CONTRACT_*` |
| `/pki/internal/<peer>/<endpoint>/{cert,key}.pem` | internal mTLS | `TLS_CERT/KEY`, `TLS_INTERNAL_UNAUTHENTICATED_*` |

`<peer>` ∈ {`directory`, `logius`}. De mesh verifieert de hostname niet (auth op OIN), maar
houd de paden consistent met `SELF_ADDRESS`/SNI.

De directory-peer heeft eigen CSR's onder `pki/peers/directory/`: `directory/csr.json`
(gebruikt door `manager-directory` via `/pki/{out,internal}/directory/directory/...`; `directory-ui`
gebruikt in plaats daarvan de group-cert van `logius/manager` als lezer-identiteit)
en `manager/csr.json` (scaffolding voor een latere ZAD-directory-deploy; de lokale compose wiret
het niet). Beide dragen de directory-OIN `00000000000000000010`. `issue.sh` negeert ongebruikte
endpoints, dus de extra `manager`-CSR is onschadelijk.

> **Let op:** de certs ontbreken tot `pki/issue.sh` gedraaid is (vereist `cfssl`). Zonder
> gegenereerde certs faalt stap 1 van het draaiboek — genereer ze eerst.
