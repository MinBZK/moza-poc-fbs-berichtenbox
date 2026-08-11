# Lokale FSC-harness — directory + provider-peer magazijn-a

Runnable shift-left van de ZAD-deploy: een lokale FSC-directory + de provider-peer `magazijn-a`
(manager + inway + controller + txlog + eigen DB's) + een SNI-router op `:443`. Bewijst dat
`magazijn-a` zich aanmeldt (announce) bij de directory en dat de dienst `berichtenmagazijn`
daarna vindbaar is (discovery). Geen afnemende peer, geen OIDC-login-voorziening —
control-plane-only voor deze ene provider-peer. Bouwt voort op `pki/` (zie dat README voor
het cert-contract).

> **Vereist Docker + `docker compose` (v2) en gegenereerde certs** (`pki/issue.sh`, vereist
> `cfssl`). Draai eerst de PKI in `pki/`, dan de stack + smokes hieronder.

> **Alleen op je eigen machine.** De harness draait met defaults die nergens anders horen:
> postgres met `postgres/postgres`, de controller met `AUTHN_TYPE=none` en
> `CSRF_PROTECTION_ENABLED=false`, en `DISABLE_CRL_CHECKS=true`. Onder bridge zitten die achter een
> eigen containernetwerk; onder de hostnet-overlay staan ze op de loopback van de machine en zijn
> ze bereikbaar voor elk lokaal proces en elke lokale gebruiker. Niet draaien op een gedeelde of
> multi-user machine, en niet op een bastion- of VPN-host.

## Benodigdheden

- **Docker** + `docker compose` (v2). Onder **rootless podman** stapel je
  `deploy/local/docker-compose.podman.yaml` erop — zie de podman-eisen onder
  [Troubleshooting](#troubleshooting).
- Gegenereerde certs uit `pki/` — draai daar eerst `./init-ca.sh`, `./issue.sh`,
  `./gen-crl.sh` en `./verify.sh` (zie `pki/README.md`, sectie "Uitvoeren"). Zonder certs
  faalt elke container die `/pki` mount bij boot (ontbrekend bestand).

## Draaiboek

Alle commando's vanuit de **peer-root** (`demo/environment/magazijn-a/`).

```bash
# 1. Genereer de PKI voor magazijn-a (zie pki/README.md).
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

# 4. Draai alle smokes (announce -> publiceren -> discovery) in één keer.
./deploy/local/run-smokes.sh    # verwacht: "ALLE SMOKES GROEN."
```

Losse smokes (voor gerichte diagnose):

```bash
./deploy/local/smoke-announce.sh    # verwacht: "OK: magazijn-a is aangemeld ..."
./deploy/local/publish-service.sh   # verwacht: "publish: klaar." (idempotent)
./deploy/local/smoke-discover.sh    # verwacht: "SMOKE-DISCOVER GROEN."
./deploy/local/smoke-services.sh    # verwacht: "OK: alle 13 services gezond ..."
```

Opruimen (met dezelfde `-f`'s als waarmee je startte):

```bash
docker compose -f deploy/local/docker-compose.yaml down -v
```

> **Hosts-bestand niet nodig.** De SNI-hostnames (`directory.fsc-test.local`,
> `magazijn-a.fsc-test.local`, `inway.magazijn-a.fsc-test.local`) resolven *binnen* het
> docker-netwerk via de router-aliases. De UIs benader je via `localhost`-poorten hieronder.

## Wat er opkomt

- **postgres** — één instantie, per component een eigen database (`postgres-init.sql`):
  `fsc_directory`, `fsc_magazijn_a`, `fsc_controller_magazijn_a`, `fsc_txlog_magazijn_a`.
- **router** (haproxy) — SNI-passthrough op `:443` naar `manager-directory`,
  `manager-magazijn-a` en `inway-magazijn-a`.
- **manager-directory** + **directory-ui** (`http://localhost:8080`, geen login; onder de
  hostnet-overlay `8081`) — de lokale
  FSC-directory (`AUTO_SIGN_GRANTS=servicePublication,delegatedServicePublication`).
- **migrate-magazijn-a**, **manager-magazijn-a** — de manager van de peer (announce, token- en
  contractendpoints).
- **migrate-controller-magazijn-a**, **controller-magazijn-a** (`http://localhost:8090`, zonder
  login: `AUTHN_TYPE=none`) — dienst-beheer (create service, contract-publicatie).
- **migrate-txlog-magazijn-a**, **txlog-magazijn-a** — transactielog-API van de peer
  (internal-PKI-mTLS).
- **inway-magazijn-a** — registreert zich bij de controller, levert de ingress vóór
  `stub-upstream`.
- **stub-upstream** — neutrale HTTP-echo (`hashicorp/http-echo`) die de business-app vervangt;
  wordt de `endpoint_url` van `berichtenmagazijn`. Het échte data-pad dóór de inway naar de
  draaiende `berichtenmagazijn`-app is buiten scope van deze bundel.
- **toolbox** — curl-client op het netwerk voor de mTLS-onboarding-calls (`publish-service.sh`).

Geen afnemende-peer-services, geen OIDC-login-voorziening — beide zijn buiten scope voor deze
provider-only-harness.

## Smokes

| Script | Bewijst |
|--------|---------|
| `smoke-announce.sh` | `magazijn-a` (OIN `00000000000000100000`) staat in `peers.peers` met een `manager_address` op `:443`. |
| `publish-service.sh` | `berichtenmagazijn` is aangemaakt op de controller + gepubliceerd via een `servicePublication`-contract op de manager. Idempotent. |
| `smoke-discover.sh` | `berichtenmagazijn` is vindbaar in de directory-catalogus voor de magazijn-OIN. |
| `smoke-services.sh` | Elke langlopende service draait en elke migrate-job is afgerond. |
| `run-smokes.sh` | Draait de vier bovenstaande in volgorde. |

`smoke-services.sh` vangt wat de andere drie missen: componenten die crash-loopen zonder dat het
gebruikte pad erlangs komt. Txlog en stub-upstream worden door geen enkele smoke aangeroepen, dus
zonder deze statuscheck zouden ze dood kunnen zijn bij een groene keten.

`smoke-discover.sh` bevraagt de **mesh-API** van de eigen manager
(`GET /v1/peers/{dir}/services?peer_id={provider}` op de Internal-API `:9443`, met het
internal-cert), niet een directory-DB-tabel — gepubliceerde diensten worden via de mesh
opgevraagd, niet uit een `services`-tabel gelezen. Dit spiegelt repo A's bewezen
`smoke-publish.sh`. Announce en publish zijn lokaal groen bevonden; discover draait via deze
mesh-API-methode.

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
hij je stilletjes terug naar bridge. De smokes en `publish-service.sh` zijn wél veilig met één
`-f`: die gebruiken uitsluitend `exec`, `logs` en `ps`, en die zoeken containers op projectnaam
plus servicelabel zonder de servicespec opnieuw te renderen.

`!reset` vereist Compose **≥ 2.24.4**; oudere versies struikelen over de YAML-tag met een fout die
niet naar de oorzaak wijst. `podman-compose` (mét streepje) ondersteunt het niet.

Wat de overlay verandert — de poorten zijn container-listeners, niet host-publicaties:

| | Bridge (basis) | Hostnet-overlay |
|---|---|---|
| Naamresolutie | netwerk-`aliases` | `extra_hosts` naar `127.0.0.1` |
| Bind-adres | `0.0.0.0` in een eigen netns; twee poorten op `127.0.0.1` gepubliceerd | overal `127.0.0.1` |
| manager directory | `8443` / `9443` / `9444` | `18443` / `19443` / `19444` |
| manager magazijn-a | `8443` / `9443` / `9444` | `28443` / `9443` / `29444` |
| controller | registratie `9443`, administratie `9444` | `39443`, administratie blijft `9444` |
| txlog | `9443` | `49443` |
| inway | `8443` | `8443` |
| stub-upstream | `8080` | `8080` |
| controller-UI | `8080`, host-poort `8090` | `8090` |
| directory-UI | `8080`, host-poort `8080` | **`8081`** |
| monitoring (alle componenten) | `8080` / `8081` | `18080`, `28080`, `38081`, `48081`, `8444`, `8082` |
| postgres | `5432`, onbereikbaar buiten het compose-netwerk | `5432` met `listen_addresses=127.0.0.1` |
| `restart: on-failure` | onbegrensd | `on-failure:600` op elke service die het heeft |
| postgres-healthcheck | aanwezig | vervalt; wachters op `service_started` |
| router-config | `haproxy.cfg` (runtime-DNS) | `haproxy.podman-hostnet.cfg` (vaste adressen) |

De interne manager-poort `9443` en de controller-administratie `9444` blijven bewust staan:
`publish-service.sh` en `smoke-discover.sh` hebben die adressen hard staan. De controller-UI houdt
zijn vertrouwde URL doordat de host-poort `8090` hier de container-poort wordt. De directory-UI is
de enige met een nieuwe URL, want `stub-upstream` moet `8080` houden — die poort staat hard in
`publish-service.sh` als endpoint-URL van de gepubliceerde dienst.

Vijf beperkingen:

- **Niet tegelijk met logius' harness.** Beide overlays gebruiken dezelfde poortindeling, dus ze
  botsen over de hele linie — niet alleen op `:443`. Onder bridge kan het wel, want elk
  compose-project krijgt een eigen netwerk.
- **De harness deelt de poortruimte met je machine.** Draait er al iets op `:5432`, dan faalt de
  harness-postgres bij het binden en blijft hij `exited` — `smoke-services.sh` vlagt dat direct.
  Hetzelfde geldt voor `:443` en de UI-poorten.
- **De router bindt `:443`, dus de netns van de aanroeper moet
  `net.ipv4.ip_unprivileged_port_start` op 0 hebben.** De per-container `sysctls`-regel uit de
  basis vervalt hier: in een gedeelde netns mag een container die niet zetten. Controleer met
  `sysctl net.ipv4.ip_unprivileged_port_start`. Staat hij op 1024, dan faalt de router op
  `bind 127.0.0.1:443: Permission denied`, resolvet geen enkele `*.fsc-test.local`-naam meer en
  vallen alle smokes om zonder dat de managerlogs een fout tonen. Zet je hem host-breed op 0, dan
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

- **Container kan cert niet vinden** → controleer dat `pki/out/magazijn-a/<endpoint>/` en
  `pki/internal/magazijn-a/<endpoint>/` bestaan (na `./pki/issue.sh`); paden moeten
  matchen met de compose-env.
- **`permission denied` op `key.pem` bij boot** → onder rootless podman: de
  `docker-compose.podman.yaml`-overlay ontbreekt in het commando (zie de podman-eisen hieronder).
  Anders: `HOST_UID`/`HOST_GID` in
  `deploy/local/.env` matchen niet met de eigenaar van de keys. Zet ze met
  `printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> deploy/local/.env` en
  `docker compose -f deploy/local/docker-compose.yaml -f deploy/local/docker-compose.podman.yaml up -d --force-recreate`
  (onder Docker zonder die tweede `-f`; onder de hostnet-overlay met alle drie).
- **Poort bezet** (8080, 8090) → stop de conflicterende dienst of pas de `ports`/`bind`
  in `docker-compose.yaml` / `haproxy.cfg` aan. De router publiceert geen host-poort
  (SNI-passthrough intern op het compose-netwerk), dus `443` kan hier niet conflicteren — behalve
  onder de hostnet-overlay, waar de hele stack de poortruimte van je machine deelt (zie
  [Podman zonder bridge-netwerk](#podman-zonder-bridge-netwerk)).
- **Smoke faalt** → `docker compose -f deploy/local/docker-compose.yaml logs
  manager-directory manager-magazijn-a controller-magazijn-a` voor de mesh-logs. Blijft de
  announce-smoke hangen op "nog niet aangemeld" terwijl de managers gezond loggen, controleer dan
  éérst `docker compose ps -a | grep router`: de `*.fsc-test.local`-namen zijn aliassen van de
  ROUTER, dus zonder router vertrekt geen enkele announce en zie je in de managerlogs geen fout.
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

De harness mount `pki/` read-only op `/pki`. Per endpoint (`manager`, `controller`, `inway`,
`txlog`) twee ketens:

| Pad | Doel | Env |
|-----|------|-----|
| `/pki/ca/root.pem` | group-CA root (trust-anchor) | `TLS_GROUP_ROOT_CERT` |
| `/pki/internal/<peer>/ca/root.pem` | **per-peer** internal-CA root | `TLS_ROOT_CERT`, `TLS_INTERNAL_UNAUTHENTICATED_ROOT_CERT` |
| `/pki/out/<peer>/<endpoint>/{cert,key}.pem` | group-identity (hergebruikt voor token+contract) | `TLS_GROUP_CERT/KEY`, `TLS_GROUP_TOKEN_*`, `TLS_GROUP_CONTRACT_*` |
| `/pki/internal/<peer>/<endpoint>/{cert,key}.pem` | internal mTLS | `TLS_CERT/KEY`, `TLS_INTERNAL_UNAUTHENTICATED_*` |

`<peer>` ∈ {`directory`, `magazijn-a`}. De mesh verifieert de hostname niet (auth op OIN), maar
houd de paden consistent met `SELF_ADDRESS`/SNI.

De directory-peer heeft eigen CSR's onder `pki/peers/directory/`: `directory/csr.json`
(gebruikt door `manager-directory` via `/pki/{out,internal}/directory/directory/...`; `directory-ui`
gebruikt in plaats daarvan de group-cert van `magazijn-a/manager` als lezer-identiteit)
en `manager/csr.json` (scaffolding voor een latere ZAD-directory-deploy; de lokale compose wiret
het niet). Beide dragen de directory-OIN `00000000000000000010`. `issue.sh` negeert ongebruikte
endpoints, dus de extra `manager`-CSR is onschadelijk.

> **Let op:** de certs ontbreken tot `pki/issue.sh` gedraaid is (vereist `cfssl`). Zonder
> gegenereerde certs faalt stap 1 van het draaiboek — genereer ze eerst.
