# Lokale FSC-harness — directory + provider-peer magazijn-a

Runnable shift-left van de ZAD-deploy: een lokale FSC-directory + de provider-peer `magazijn-a`
(manager + inway + controller + txlog + eigen DB's) + een SNI-router op `:443`. Bewijst dat
`magazijn-a` zich aanmeldt (announce) bij de directory en dat de dienst `berichtenmagazijn`
daarna vindbaar is (discovery). Geen afnemende peer, geen OIDC-login-voorziening —
control-plane-only voor deze ene provider-peer. Bouwt voort op `fsc/pki/` (zie dat README voor
het cert-contract).

> ## UITGESTELD — vereist Docker + gegenereerde certs (`fsc/pki/issue.sh`)
>
> Deze omgeving heeft geen `docker` en geen `cfssl`. Er is dus **niets van onderstaand draaiboek
> uitgevoerd**: de stack is niet gebooid, geen enkele smoke is groen gedraaid. Alleen de
> artefacten (compose, haproxy.cfg, postgres-init.sql, `.env.example`, dit README, de vier
> scripts) zijn neergezet en **statisch** gevalideerd (zie onderaan). Claim dus nergens dat de
> stack draait of dat een smoke geslaagd is — dat moet een mens met Docker + cfssl nog doen.

## Benodigdheden

- **Docker** + `docker compose` (v2).
- Gegenereerde certs uit `fsc/pki/` — draai daar eerst `./init-ca.sh`, `./issue.sh` en
  `./verify.sh` (zie `fsc/pki/README.md`, sectie "UITGESTELD"). Zonder certs faalt elke
  container die `/pki` mount bij boot (ontbrekend bestand).

## Draaiboek — UITGESTELD, door de mens uit te voeren

Alle commando's vanuit de **repo-root**, op branch `feature/magazijn-provider-peer-fsc`.

```bash
# 1. Genereer de PKI voor magazijn-a (UITGESTELD, zie fsc/pki/README.md).
cd fsc/pki
./init-ca.sh
./issue.sh
./verify.sh          # verwacht: "== ALLE ASSERTS GROEN =="
cd -

# 2. Harness-env. De cert-lezende containers draaien als JOUW UID/GID, zodat ze de
#    0600-privékeys via de owner-bit lezen (keys blijven dicht).
cp fsc/deploy/local/.env.example fsc/deploy/local/.env
printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> fsc/deploy/local/.env

# 3. Start de stack.
docker compose -f fsc/deploy/local/docker-compose.yaml up -d
sleep 20 && docker compose -f fsc/deploy/local/docker-compose.yaml ps

# 4. Draai alle smokes (announce -> publiceren -> discovery) in één keer.
./fsc/deploy/local/run-smokes.sh    # verwacht: "ALLE SMOKES GROEN."
```

Losse smokes (voor gerichte diagnose):

```bash
./fsc/deploy/local/smoke-announce.sh    # verwacht: "OK: magazijn-a is aangemeld ..."
./fsc/deploy/local/publish-service.sh   # verwacht: "publish: klaar." (idempotent)
./fsc/deploy/local/smoke-discover.sh    # verwacht: "SMOKE-DISCOVER GROEN."
```

Opruimen:

```bash
docker compose -f fsc/deploy/local/docker-compose.yaml down -v
```

> **Hosts-bestand niet nodig.** De SNI-hostnames (`directory.fsc-test.local`,
> `magazijn-a.fsc-test.local`, `inway.magazijn-a.fsc-test.local`) resolven *binnen* het
> docker-netwerk via de router-aliases. De UIs benader je via `localhost`-poorten hieronder.

## Wat er opkomt

- **postgres** — één instantie, per component een eigen database (`postgres-init.sql`):
  `fsc_directory`, `fsc_magazijn_a`, `fsc_controller_magazijn_a`, `fsc_txlog_magazijn_a`.
- **router** (haproxy) — SNI-passthrough op `:443` naar `manager-directory`,
  `manager-magazijn-a` en `inway-magazijn-a`.
- **manager-directory** + **directory-ui** (`http://localhost:8080`, geen login) — de lokale
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
| `smoke-announce.sh` | `magazijn-a` (OIN `00000001003214345000`) staat in `peers.peers` met een `manager_address` op `:443`. |
| `publish-service.sh` | `berichtenmagazijn` is aangemaakt op de controller + gepubliceerd via een `servicePublication`-contract op de manager. Idempotent. |
| `smoke-discover.sh` | `berichtenmagazijn` is vindbaar in de directory-catalogus voor de magazijn-OIN. |
| `run-smokes.sh` | Draait de drie bovenstaande in volgorde. |

> ### WAARSCHUWING — schema van de directory `services`-tabel niet geverifieerd
>
> `smoke-discover.sh` bevraagt `services` met kolommen `peer_id`/`name`. Dit schema is
> **niet** tegen een draaiende directory-DB geverifieerd (geen Docker beschikbaar in deze
> omgeving) — het is overgenomen naar analogie van `peers.peers` (kolom `id`) uit
> `smoke-announce.sh`, maar dat is een aanname, geen bevestigd contract. Controleer bij de
> eerste keer draaien:
>
> ```bash
> docker compose -f fsc/deploy/local/docker-compose.yaml exec postgres \
>   psql -U postgres -d fsc_directory -c '\d+ services'
> ```
>
> en pas de `SELECT`-query in `smoke-discover.sh` aan als de kolomnamen afwijken.

## Troubleshooting

- **Container kan cert niet vinden** → controleer dat `fsc/pki/out/magazijn-a/<endpoint>/` en
  `fsc/pki/internal/magazijn-a/<endpoint>/` bestaan (na `./fsc/pki/issue.sh`); paden moeten
  matchen met de compose-env.
- **`permission denied` op `key.pem` bij boot** → `HOST_UID`/`HOST_GID` in
  `fsc/deploy/local/.env` matchen niet met de eigenaar van de keys. Zet ze met
  `printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> fsc/deploy/local/.env` en
  `docker compose -f fsc/deploy/local/docker-compose.yaml up -d --force-recreate`.
- **Poort bezet** (443, 8080, 8090) → stop de conflicterende dienst of pas de `ports`/`bind`
  in `docker-compose.yaml` / `haproxy.cfg` aan.
- **Smoke faalt** → `docker compose -f fsc/deploy/local/docker-compose.yaml logs
  manager-directory manager-magazijn-a controller-magazijn-a` voor de mesh-logs.
- **`migrate-*` hangt / `database "…" does not exist`** → `postgres-init.sql` draait alleen bij
  een **vers** volume. Bestaat er al een postgres-volume van een eerdere run? Maak de ontbrekende
  DB eenmalig aan (`... exec -T postgres psql -U postgres -c "CREATE DATABASE <naam>;"`) of
  `down -v && up -d` (wist alles, re-init inclusief nieuwe DB's).

## Cert-contract (referentie, overgenomen uit `fsc/pki/README.md`)

De harness mount `fsc/pki/` read-only op `/pki`. Per endpoint (`manager`, `controller`, `inway`,
`txlog`) twee ketens:

| Pad | Doel | Env |
|-----|------|-----|
| `/pki/ca/root.pem` | group-CA root (trust-anchor) | `TLS_GROUP_ROOT_CERT` |
| `/pki/internal/<peer>/ca/root.pem` | **per-peer** internal-CA root | `TLS_ROOT_CERT`, `TLS_INTERNAL_UNAUTHENTICATED_ROOT_CERT` |
| `/pki/out/<peer>/<endpoint>/{cert,key}.pem` | group-identity (hergebruikt voor token+contract) | `TLS_GROUP_CERT/KEY`, `TLS_GROUP_TOKEN_*`, `TLS_GROUP_CONTRACT_*` |
| `/pki/internal/<peer>/<endpoint>/{cert,key}.pem` | internal mTLS | `TLS_CERT/KEY`, `TLS_INTERNAL_UNAUTHENTICATED_*` |

`<peer>` ∈ {`directory`, `magazijn-a`}. De mesh verifieert de hostname niet (auth op OIN), maar
houd de paden consistent met `SELF_ADDRESS`/SNI.

> **Let op — `directory`-peer heeft nog geen CSR's in deze repo.** `fsc/pki/peers/` bevat tot nu
> toe alleen `magazijn-a/{manager,controller,inway,txlog}/csr.json`. Voor `manager-directory` en
> `directory-ui` mount de compose paden onder `/pki/{out,internal}/directory/directory/...` —
> die moeten nog worden gegenereerd (eigen `csr.json` onder `fsc/pki/peers/directory/directory/`
> of hergebruik van repo A's group-CA-materiaal voor de directory-peer). Dit is een gat dat de
> mens moet dichten vóór stap 1 van het draaiboek slaagt; zie ook de concerns in het bundle-
> report.
