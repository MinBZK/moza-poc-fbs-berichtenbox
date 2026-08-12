# Lokale FSC-harness — directory + peer logius

Runnable shift-left van de ZAD-deploy: een lokale FSC-directory + de peer `logius`
(manager + outway + inway + controller + txlog + eigen DB's) + een SNI-router op `:443`. Bewijst dat
`logius` zich aanmeldt (announce) bij de directory, de dienst `profiel-service` aanbiedt en
vindbaar/afneembaar is (publicatie, discovery, een zelfreferentieel afnemer-contract). Geen
OIDC-login-voorziening — dat blijft buiten scope voor deze peer-harness. Bouwt voort op `pki/`
(zie dat README voor het cert-contract).

> **Vereist Docker + `docker compose` (v2) en gegenereerde certs** (`pki/issue.sh`, vereist
> `cfssl`). Draai eerst de PKI in `pki/`, dan de stack + smoke hieronder.

> **Alleen op je eigen machine.** De harness draait met defaults die nergens anders horen:
> postgres met `postgres/postgres`, de controller met `AUTHN_TYPE=none` en
> `CSRF_PROTECTION_ENABLED=false`, en `DISABLE_CRL_CHECKS=true`. Onder bridge zitten die achter een
> eigen containernetwerk; onder de hostnet-overlay staan ze op de loopback van de machine en zijn
> ze bereikbaar voor elk lokaal proces en elke lokale gebruiker. Onder die overlay draaien de
> cert-lezende containers bovendien in jouw UID (`keep-id`) én in jouw netwerk-namespace: de grens
> tussen container en host is daar dunner dan de standaard rootless-mapping. Niet draaien op een
> gedeelde of multi-user machine, en niet op een bastion- of VPN-host.

## Benodigdheden

- **Docker** + `docker compose` (v2). Onder **rootless podman** stapel je
  `deploy/local/docker-compose.podman.yaml` erop — zie de podman-eisen onder
  [Troubleshooting](#troubleshooting).
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

# 3. Start de stack. Onder rootless podman: hang er
#    `-f deploy/local/docker-compose.podman.yaml` achter, in élk compose-commando hieronder.
docker compose -f deploy/local/docker-compose.yaml up -d
sleep 20 && docker compose -f deploy/local/docker-compose.yaml ps

# 4. Draai de smoke.
./deploy/local/run-smokes.sh    # verwacht: "ALLE SMOKES GROEN."
```

Losse smokes (voor gerichte diagnose, elk zelfstandig draaibaar):

```bash
./deploy/local/smoke-announce.sh     # verwacht: "OK: logius is aangemeld ..."
./deploy/local/smoke-services.sh     # verwacht: "OK: alle 14 services gezond ..."
./deploy/local/publish-service.sh    # verwacht: "publish: klaar."
./deploy/local/smoke-discover.sh     # verwacht: "SMOKE-DISCOVER GROEN."
./deploy/local/consume-service.sh    # verwacht: "CONSUME OK."
```

Opruimen (met dezelfde `-f`'s als waarmee je startte):

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
  peer-harnessen moeten tegelijk kunnen draaien. Container-poort blijft `8080`. (Tegelijk draaien
  lukt alleen onder bridge; zie [Podman zonder bridge-netwerk](#podman-zonder-bridge-netwerk).)
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
- **toolbox** — curl-client op het netwerk voor de mTLS-onboarding-calls: `publish-service.sh`,
  `smoke-discover.sh` en `consume-service.sh` draaien hun curl-aanroepen allemaal via
  `docker compose exec -T toolbox curl`.

Geen OIDC-login-voorziening — dat blijft buiten scope voor deze harness (zie de sectie "Smoke"
hieronder voor wat wél is aangetoond: announce, dienst-publicatie, discovery en een
afnemer-contract).

## Smoke

| Script | Bewijst |
|--------|---------|
| `smoke-announce.sh` | `logius` (OIN `00000000000000001000`) staat in `peers.peers` met een `manager_address` op `:443`. |
| `smoke-services.sh` | Elke langlopende service draait en elke migrate-job is afgerond. |
| `publish-service.sh` | `profiel-service` is aangemaakt op de controller en gepubliceerd (servicePublication-contract, auto-signed). |
| `smoke-discover.sh` | `profiel-service` is vindbaar via de manager-mesh-API (`GET /v1/peers/{dir}/services`). |
| `consume-service.sh` | Een zelfreferentieel `serviceConnection`-contract is wederzijds ondertekend; levert de `Fsc-Grant-Hash`-waarde. |
| `run-smokes.sh` | Draait alle vijf in volgorde. |

De inway draait, biedt `profiel-service` aan, en er is een geldig afnemer-contract — het
volledige lokale FSC-bewijs voor deze dienst is hiermee rond. Het écht dóór de inway heen
aanroepen van `stub-upstream` (het data-pad) én de integratie met `berichtenuitvraag` zelf
blijven buiten deze lokale proof (zie `deploy/zad/verify-zad.md`).

**Waarom `smoke-services.sh` ernaast staat.** De overige smokes bewijzen het functionele
eindresultaat via de API's, maar tonen de container-status zelf niet. De announce raakt maar een
deel van de stack — de manager announce't ook prima terwijl outway, inway, controller en txlog
crash-loopen, want die worden lui gedialed — en een inway die herhaaldelijk crasht en herstart
vóórdat de poll in `publish-service.sh` slaagt, blijft eveneens onzichtbaar. Zo zou het
geaccepteerde `:9444`-risico zich manifesteren: de inway gebruikt
`MANAGER_INTERNAL_UNAUTHENTICATED_ADDRESS` (`:9444`), en als `fsc-inway serve` v2.5.2 tóch de
authenticated `:9443` blijkt te eisen, faalt de boot daar zichtbaar terwijl de rest groen blijft.
`smoke-services.sh` leest daarom de containerstatus uit en dumpt de laatste logregels van wat niet
draait. Juist die componenten hernummert de hostnet-overlay van poort, dus een fout daarin moet
luid falen.

## Podman zonder bridge-netwerk

Draait podman zélf in een container, dan kan netavark geen bridge opzetten (`failed to set
autoconf sysctl: Permission denied` — `/proc/sys` is daar niet schrijfbaar) en ontbreekt
`aardvark-dns`, waardoor geen enkele containernaam resolvet. De overlay
`docker-compose.podman-hostnet.yaml` laat alle containers dan de netns van de aanroeper delen:

```bash
docker compose -f deploy/local/docker-compose.yaml \
               -f deploy/local/docker-compose.podman.yaml \
               -f deploy/local/docker-compose.podman-hostnet.yaml up -d
./deploy/local/run-smokes.sh
```

**Elk verder compose-commando heeft dezelfde drie `-f`'s nodig** — `up`, `down`, `restart`,
`--force-recreate`. Met één `-f` rendert compose de services opnieuw uit alleen de basis en draait
hij je stilletjes terug naar bridge. De smokes zijn wél veilig met één `-f`: die lezen alleen
(`exec`, `logs`, `ps`, `config`) en hercreëren niets — `ps` en `exec` vinden de draaiende
containers op projectnaam plus servicelabel, ongeacht met hoeveel `-f` ze gestart zijn.

`!reset` vereist Compose **≥ 2.24.4**; oudere versies struikelen over de YAML-tag met een fout die
niet naar de oorzaak wijst. `podman-compose` (mét streepje) ondersteunt het niet.

Wat de overlay verandert — de poorten zijn container-listeners, niet host-publicaties:

| | Bridge (basis) | Hostnet-overlay |
|---|---|---|
| Naamresolutie | netwerk-`aliases` | `extra_hosts` naar `127.0.0.1` |
| Bind-adres | `0.0.0.0` in een eigen netns; twee poorten op `127.0.0.1` gepubliceerd | overal `127.0.0.1` |
| manager directory | `8443` / `9443` / `9444` | `18443` / `19443` / `19444` |
| manager logius | `8443` / `9443` / `9444` | `28443` / `9443` / `29444` |
| controller | registratie `9443`, administratie `9444` | `39443`, administratie blijft `9444` |
| txlog | `9443` | `49443` |
| outway | `8443` | `58443` |
| inway | `8443` | `8443` |
| directory-UI | `8080`, host-poort `8081` | `8081` |
| controller-UI | `8080`, host-poort `8091` | `8091` |
| monitoring (alle componenten) | `8080` / `8081` | `18080`, `28080`, `38081`, `48081`, `58081`, `8444`, `8082` |
| postgres | `5432`, onbereikbaar buiten het compose-netwerk | `5432` met `listen_addresses=127.0.0.1` |
| postgres-healthcheck | aanwezig | vervalt; wachters op `service_started` |
| `restart: on-failure` | onbegrensd | `on-failure:600` op elke service die het heeft |
| router-config | `haproxy.cfg` (runtime-DNS) | `haproxy.podman-hostnet.cfg` (vaste adressen) |

De UI's houden dus hun vertrouwde URL: wat onder bridge de host-poort was, is hier de
container-poort. De interne manager-poort `9443` en de controller-administratie `9444` blijven
bewust staan: magazijn-a's `publish-service.sh` en `smoke-discover.sh` hebben die adressen hard
staan, en beide overlays houden vrijwel dezelfde poortindeling aan.

Vijf beperkingen:

- **Niet tegelijk met magazijn-a's harness.** Beide overlays gebruiken vrijwel dezelfde
  poortindeling, dus ze botsen op bijna elke poort — niet alleen op `:443`. Onder bridge kan het
  wel, want elk compose-project krijgt een eigen netwerk.
- **De harness deelt de poortruimte met je machine.** Draait er al iets op `:5432`, dan faalt de
  harness-postgres bij het binden en blijft hij `exited` — `smoke-services.sh` vlagt dat direct.
  Hetzelfde geldt voor `:443` en de UI-poorten.
- **De router bindt `:443`, dus de netns van de aanroeper moet
  `net.ipv4.ip_unprivileged_port_start` op 0 hebben.** De per-container `sysctls`-regel uit de
  basis vervalt hier: in een gedeelde netns mag een container die niet zetten. Controleer met
  `sysctl net.ipv4.ip_unprivileged_port_start`. Staat hij op 1024, dan faalt de router op
  `bind 127.0.0.1:443: Permission denied`. De namen resolven dan nog wél — die komen hier uit
  `extra_hosts`, niet van de router — maar elke `:443`-verbinding krijgt ECONNREFUSED en alle
  smokes vallen om zonder dat de managerlogs een fout tonen. Zet je hem host-breed op 0, dan
  mag voortaan elke onprivilegieerde gebruiker op die machine onder poort 1024 binden — draai dat
  na afloop terug.
- **Single-host.** Onder bridge publiceerde de router al geen host-poort, dus federatie met een
  externe peer kon toen ook niet; deze overlay cementeert dat. Draai `127.0.0.1` niet terug naar
  `0.0.0.0` om een multi-host-testnet te bouwen — dan zet je de hele stack, inclusief postgres en
  de controller zonder authenticatie, op het netwerk.
- **Faalt een container tijdens de eerste `up`** (`container ID 0 cannot be mapped to a host ID`),
  dan blijft hij in status `Created` achter en geeft elke volgende `up`
  `write /proc/<pid>/uid_map: Operation not permitted`. Dat is geen keep-id-probleem maar een race
  bij het aanmaken van de ID-gemapte laagkopie. Ruim op en probeer opnieuw; soms is dat twee of
  drie keer nodig voordat de stack compleet staat:

  ```bash
  podman ps -a --format '{{.Names}} {{.Status}}' | grep -v ' Up ' | awk '{print $1}' \
    | xargs -r podman rm -f
  ```

## Troubleshooting

- **Container kan cert niet vinden** → controleer dat `pki/out/logius/<endpoint>/` en
  `pki/internal/logius/<endpoint>/` bestaan (na `./pki/issue.sh`); paden moeten
  matchen met de compose-env.
- **`permission denied` op `key.pem` bij boot** → onder rootless podman: de
  `docker-compose.podman.yaml`-overlay ontbreekt in het commando (zie de podman-eisen hieronder).
  Anders: `HOST_UID`/`HOST_GID` in `deploy/local/.env` matchen niet met de eigenaar van de keys.
  Zet ze met
  `printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> deploy/local/.env` en
  `docker compose -f deploy/local/docker-compose.yaml -f deploy/local/docker-compose.podman.yaml up -d --force-recreate`
  (onder Docker zonder die tweede `-f`; onder de hostnet-overlay met alle drie).
- **Poort bezet** (8081, 8091) → stop de conflicterende dienst of pas de `ports`/`bind`
  in `docker-compose.yaml` / `haproxy.cfg` aan. De router publiceert geen host-poort
  (SNI-passthrough intern op het compose-netwerk), dus `443` kan hier niet conflicteren — behalve
  onder de hostnet-overlay, waar de hele stack de poortruimte van je machine deelt (zie
  [Podman zonder bridge-netwerk](#podman-zonder-bridge-netwerk)).
- **Smoke faalt** → `docker compose -f deploy/local/docker-compose.yaml logs
  manager-directory manager-logius controller-logius` voor de mesh-logs. Blijft de announce-smoke
  hangen op "nog niet aangemeld" terwijl de managers gezond loggen, controleer dan éérst
  `docker compose -f deploy/local/docker-compose.yaml ps -a | grep router`: de `*.fsc-test.local`-namen zijn aliassen van de ROUTER,
  dus zonder router vertrekt geen enkele announce en zie je in de managerlogs geen fout.
- **Podman i.p.v. Docker** → de harness draait op beide, mits:
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
  - Stapel `-f deploy/local/docker-compose.podman.yaml` op elk compose-commando. Die overlay zet
    `userns_mode: "keep-id"` op de cert-lezende services. Rootless podman mapt host-UID 1000 op
    container-UID 0; zonder keep-id is de UID uit `user:` een subuid die de 0600-privékeys niet
    mag lezen, en faalt elke container die `/pki` mount bij boot. De regel staat bewust niet in de
    basis: Docker accepteert alleen een lege `userns_mode` of `host` en weigert op `keep-id`.
  - Geen bruikbaar bridge-netwerk (podman in een container)? Zie
    [Podman zonder bridge-netwerk](#podman-zonder-bridge-netwerk) hierboven.
- **`migrate-*` hangt / `database "…" does not exist`** → `postgres-init.sql` draait alleen bij
  een **vers** volume. Bestaat er al een postgres-volume van een eerdere run? Maak de ontbrekende
  DB eenmalig aan (`... exec -T postgres psql -U postgres -c "CREATE DATABASE <naam>;"`) of
  `down -v && up -d` (wist alles, re-init inclusief nieuwe DB's) — met dezelfde `-f`'s
  als waarmee je startte.

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
