**Status:** Concept

# Logius-profiel-service-FSC-publicatie Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** De FSC-peer `logius` (OIN `00000000000000001000`, `demo/environment/logius/`) gaat de dienst `profiel-service` aanbieden op zijn eigen inway, en krijgt lokaal een geldig, wederzijds ondertekend afnemer-contract (`ServiceConnectionGrant`) — zodat er een échte `Fsc-Grant-Hash` beschikbaar komt om `berichtenuitvraag`'s `PROFIEL_SERVICE_GRANT_HASH` mee te vullen.

**Architecture:** Logius is in dit ontwerp zowel provider (biedt straks `profiel-service` aan op `inway-logius`) als de consumer-outway die `berichtenuitvraag` zelf gebruikt (co-located, gedeelde identiteit — zie `demo/environment/logius/docs/design.md:74-75`: *"integratie is config-only (Magazijnregister-URL → lokale outway i.p.v. direct op het magazijn)"*). Het afnemer-contract is daarom **zelfreferentieel**: consumer-OIN = provider-OIN = `00000000000000001000`. Dat is geen modelleerfout maar een rechtstreeks gevolg van de co-locatiekeuze; Taak 4 verifieert expliciet dat de manager dat toestaat.

Twee bewezen patronen worden hergebruikt:
1. **Publiceren (provider-kant):** exact het patroon van `demo/environment/magazijn-a/deploy/local/publish-service.sh` (`CreateService` op de controller-Administration-API + `servicePublication`-contract op de manager-Internal-API, auto-signed door de directory).
2. **Contracteren (consumer-kant):** het generieke `GRANT_TYPE_SERVICE_CONNECTION`-patroon uit de sibling-repo `/home/claude/projects/moza-fsc-testnet/contracts/bootstrap.sh` (read-only bron, niet wijzigen) — dit patroon bestaat nog nergens in `moza-poc-fbs-berichtenbox` en wordt hier voor het eerst overgenomen en geparametriseerd voor `logius`/`profiel-service`.

De daadwerkelijke ZAD-uitrol (`CreateService` op de ZAD-inway, `ZAD_LOGIUS_UPSTREAM_URL`, en het zetten van `PROFIEL_SERVICE_URL`/`PROFIEL_SERVICE_GRANT_HASH` op de gedeployde `berichtenuitvraag`) blijft — net als bij `2026-07-31-magazijn-a-peer-migratie-plan.md` — een handmatige vervolgstap die echte ZAD-toegang vereist en dus niet door dit plan getest kan worden; Taak 6 documenteert 'm.

**Tech Stack:** Bash, curl (mTLS binnen de `toolbox`-container), jq, openssl, Docker Compose (OpenFSC v2.5.2: manager/outway/inway/controller/txlog), OpenFSC Manager/Controller Internal- en Administration-API.

## Global Constraints

- Logius-OIN (provider ÉN consumer, zelfreferentieel): `00000000000000001000`.
- Directory-OIN: `00000000000000000010` (`DIR_OIN`, ongewijzigd).
- `GROUP_ID`: `moza-fbs-test` (ongewijzigd, gedeeld met alle andere peers in deze testnet-groep).
- Dienstnaam: `profiel-service` (geen `MOZA Profiel Service` of andere schrijfwijze — moet letterlijk overeenkomen met wat het contract in `service.name` draagt).
- Bronrepo voor het contract-patroon: `/home/claude/projects/moza-fsc-testnet` (sibling-checkout, **read-only bron — wijzig daar niets**). Neem het `Copyright ©`-kopblok NIET over — de reeds gemigreerde scripts in deze repo (bv. `demo/environment/magazijn-a/deploy/local/publish-service.sh`) dragen dat kopblok ook niet, ondanks dezelfde EUPL-licentie.
- Alle nieuwe lokale scripts draaien vanuit `demo/environment/logius/` (peer-root), identiek aan de bestaande `smoke-announce.sh`/`run-smokes.sh`-conventie.
- Vereist lokaal: Docker + `docker compose` v2, `cfssl`+`jq`+`openssl` (voor de PKI/thumbprint), en dat `demo/environment/logius/pki/{init-ca,issue,gen-crl,verify}.sh` al gedraaid zijn (zie `deploy/local/README.md`, sectie "Draaiboek", stap 1) — dit plan genereert geen nieuwe PKI, het hergebruikt de bestaande `logius`-peer-certs.
- Geen `git push`, geen PR — alleen lokale commits per taak.
- Scope is uitsluitend de **lokale** `deploy/local`-harness plus documentatie-bijwerking van de ZAD-runbooks. Geen wijziging aan `services/berichtenuitvraag`-code of `application.properties` — die routering is al generiek en config-only (zie CLAUDE.md, env-var-tabel `PROFIEL_SERVICE_GRANT_HASH`); dit plan levert alleen de FSC-infrastructuur die een geldige waarde daarvoor produceert.

---

### Taak 1: `stub-upstream` toevoegen aan de lokale Logius-compose

**Files:**
- Modify: `demo/environment/logius/deploy/local/docker-compose.yaml`

**Interfaces:**
- Produces: een draaiende `stub-upstream`-container (`http://stub-upstream:8080`) die Taak 2's `CreateService` als `endpoint_url` gebruikt.

- [ ] **Stap 1: Voeg de service toe**

Gebruik de `Edit`-tool om, ná de `toolbox`-service-definitie (rond regel 262-274) en vóór
`directory-ui` (regel 276), toe te voegen:

```yaml
  stub-upstream:
    # Neutrale HTTP-echo die de business-app (het echte moza-profiel-service-backend)
    # vervangt. Wordt de endpoint_url van profiel-service; het échte data-pad dóór de
    # inway is niet in scope van deze bundel (spiegelt magazijn-a's stub-upstream).
    image: docker.io/hashicorp/http-echo:1.0
    command: ["-listen=:8080", "-text=hello from logius profiel-service stub-upstream"]
```

- [ ] **Stap 2: Maak `inway-logius` afhankelijk van `stub-upstream`**

Voeg in het bestaande `depends_on`-blok van `inway-logius` (regel 254-260) een entry toe:

```yaml
    depends_on:
      controller-logius:
        condition: service_started
      manager-logius:
        condition: service_started
      txlog-logius:
        condition: service_started
      stub-upstream:
        condition: service_started
```

- [ ] **Stap 3: YAML-syntax verifiëren**

```bash
python3 -c "import yaml; yaml.safe_load(open('demo/environment/logius/deploy/local/docker-compose.yaml'))" && echo "YAML OK"
```

Expected: `YAML OK`.

- [ ] **Stap 4: Commit**

```bash
git add demo/environment/logius/deploy/local/docker-compose.yaml
git commit -m "feat(demo): stub-upstream voor logius-profiel-service in de lokale FSC-harness"
```

---

### Taak 2: `publish-service.sh` — profiel-service aanbieden op de Logius-inway

**Files:**
- Create: `demo/environment/logius/deploy/local/publish-service.sh`

**Interfaces:**
- Consumes: `stub-upstream:8080` (Taak 1), de bestaande `pki/internal/logius/manager/{cert,key}.pem` + `pki/internal/logius/ca/root.pem`.
- Produces: de dienst `profiel-service`, gepubliceerd onder OIN `00000000000000001000` — Taak 3 (discover) en Taak 4 (consumer-contract) bouwen hierop voort.

- [ ] **Stap 1: Schrijf het script (kopie van magazijn-a's `publish-service.sh`, geherparametriseerd)**

```bash
#!/usr/bin/env bash
# Onboarding: maakt de dienst profiel-service aan op de controller Administration-API en
# publiceert 'm via een servicePublication-contract op de eigen manager Internal-API.
# Idempotent: slaat create/publish over als ze er al zijn. Manager hasht+signt het
# contract server-side; de directory (AUTO_SIGN_GRANTS=servicePublication) auto-accept.
set -euo pipefail

COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")
SERVICE_NAME="profiel-service"
PROVIDER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
GROUP_ID="moza-fbs-test"
STUB_URL="http://stub-upstream:8080"

CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem
CONTROLLER=https://controller.logius.fsc-test.local:9444
MANAGER=https://manager.logius.fsc-test.local:9443

# Vang curl-/toolbox-stderr op i.p.v. weg te gooien: een mTLS-/netwerk-/dode-container-fout
# mag niet als "nog niet klaar" maskeren (spiegelt smoke-announce.sh). Surface 'm in de loop.
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

# curl binnen de toolbox, met de internal client-cert. Stderr -> ERRLOG.
tb() { "${COMPOSE[@]}" exec -T toolbox curl -s --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

echo "publish: wachten op inway-registratie bij de controller..."
# inway->controller-registratie is asynchroon na boot; poll (spiegelt smoke-announce.sh)
# i.p.v. één harde fetch, anders racet een koude start de eerste publish-run.
INWAY_ADDR=""
elapsed=0
while [ "$elapsed" -lt 60 ]; do
  # CreateService verwacht het inway-ADRES (https://...:443, = SELF_ADDRESS), niet de naam.
  INWAY_ADDR=$(tb "$CONTROLLER/v1/inways" | grep -o 'https://inway\.logius\.fsc-test\.local:443' | head -1 || true)
  [ -n "$INWAY_ADDR" ] && break
  # Persistente fout (verkeerd cert-pad, dode toolbox, DNS) mag niet als "traag boot" maskeren.
  [ -s "$ERRLOG" ] && { echo "  WARN: controller-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }
  sleep 5; elapsed=$((elapsed + 5))
  echo "  ...inway nog niet geregistreerd (${elapsed}s)"
done
[ -n "$INWAY_ADDR" ] || { echo "FAIL: geen geregistreerde inway op de controller binnen 60s." >&2; exit 1; }
echo "  inway_address=$INWAY_ADDR"

echo "publish: $SERVICE_NAME aanmaken (idempotent)..."
if tb "$CONTROLLER/v1/services" | grep -q "\"$SERVICE_NAME\""; then
  echo "  bestaat al, skip create."
else
  tb -X POST "$CONTROLLER/v1/services" -H 'Content-Type: application/json' \
     -d "{\"name\":\"$SERVICE_NAME\",\"endpoint_url\":\"$STUB_URL\",\"inway_address\":\"$INWAY_ADDR\"}"
  echo "  aangemaakt."
fi

echo "publish: servicePublication-contract indienen (idempotent)..."
if tb "$MANAGER/v1/services/publications" | grep -q "\"$SERVICE_NAME\""; then
  echo "  al gepubliceerd, skip contract."
else
  # UUID + timestamp zijn host-lokaal (geen container-context nodig) -> host-builtins i.p.v. toolbox-exec.
  # UUID v4 (36 tekens): /proc is Linux-only, op macOS valt 'ie terug op uuidgen (lowercase).
  # Als de manager 400 geeft op het iv-formaat, genereer UUID v7.
  IV=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]')
  # Docker Desktop (macOS) draait in een VM waarvan de klok op de host kan achterlopen; de manager
  # weigert dan created_at "in the future" (HTTP 500). Backdate met een skew-marge — op Linux is de
  # skew ~0, dus onschadelijk. Blijft persistent falen? Herstart de Docker-VM (klok resynct).
  NBF=$(( $(date -u +%s) - 60 ))
  NAF=$((NBF + 315360000))                 # +10 jaar
  # --fail-with-body laat curl bij 4xx/5xx non-zero exiten MAAR print de body; vang beide zodat
  # `set -e` ons niet vóór de diagnostiek killt en de manager-respons zichtbaar is.
  RESP=$(tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{
    \"contract_content\": {
      \"iv\": \"$IV\",
      \"group_id\": \"$GROUP_ID\",
      \"hash_algorithm\": \"HASH_ALGORITHM_SHA3_512\",
      \"created_at\": $NBF,
      \"validity\": { \"not_before\": $((NBF - 60)), \"not_after\": $NAF },
      \"grants\": [ {
        \"type\": \"GRANT_TYPE_SERVICE_PUBLICATION\",
        \"directory\": { \"peer_id\": \"$DIR_OIN\" },
        \"service\": { \"peer_id\": \"$PROVIDER_OIN\", \"name\": \"$SERVICE_NAME\", \"protocol\": \"PROTOCOL_TCP_HTTP_1.1\" }
      } ]
    }
  }") || { echo "FAIL: POST /v1/contracts geweigerd (iv=$IV): ${RESP:-<leeg>} $(tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }
  # Een 2xx zónder content_hash duidt op een geweigerd formaat (iv/group_id).
  printf '%s' "$RESP" | grep -q '"content_hash"' \
    || { echo "FAIL: contract-respons zonder content_hash (mogelijk geweigerd iv/group_id-formaat): $RESP" >&2; exit 1; }
  echo "  contract ingediend (manager signt; directory auto-accept): $RESP"
fi
echo "publish: klaar."
```

- [ ] **Stap 2: Uitvoerbaar maken**

```bash
chmod +x demo/environment/logius/deploy/local/publish-service.sh
```

- [ ] **Stap 3: Draai het script (vereist een draaiende stack: `docker compose -f demo/environment/logius/deploy/local/docker-compose.yaml up -d`, zie README stap 1-3)**

```bash
./demo/environment/logius/deploy/local/publish-service.sh
```

Expected: eindigt met `publish: klaar.`, zonder `FAIL:`-regels.

- [ ] **Stap 4: Commit**

```bash
git add demo/environment/logius/deploy/local/publish-service.sh
git commit -m "feat(demo): publiceer profiel-service op de lokale logius-inway"
```

---

### Taak 3: `smoke-discover.sh` — vindbaarheid van `profiel-service` bewijzen

**Files:**
- Create: `demo/environment/logius/deploy/local/smoke-discover.sh`

**Interfaces:**
- Consumes: Taak 2 (de gepubliceerde dienst moet bestaan vóór deze smoke draait).
- Produces: geen nieuwe state — puur verificatie, gebruikt door Taak 5's `run-smokes.sh`.

- [ ] **Stap 1: Schrijf het script (kopie van magazijn-a's `smoke-discover.sh`, geherparametriseerd)**

```bash
#!/usr/bin/env bash
# Smoke: bewijst dat de door logius gepubliceerde dienst `profiel-service` als GELDIGE
# publicatie vindbaar is bij de directory. Pollt de manager Internal-API
# (GET /v1/peers/{dir}/services?peer_id={provider}) — de mesh-API, NIET een directory-DB-tabel:
# gepubliceerde diensten leven niet in een plain `services`-tabel maar worden via de mesh
# opgevraagd (spiegelt magazijn-a's smoke-discover.sh). Vereist dat publish-service.sh eerst
# draaide (run-smokes.sh doet dat).
set -euo pipefail

HERE="$(dirname "$0")"
COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
SERVICE_NAME="profiel-service"
PROVIDER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
# directory-propagatie na auto-sign is vrijwel direct; 10s volstaat na de inway-poll in publish-service.sh.
TIMEOUT=10
INTERVAL=2

# De provider bevraagt de directory via zijn EIGEN manager (internal-cert) naar de eigen
# gepubliceerde diensten. Robuuster dan de directory-DB pollen (geen tabelnaam-koppeling).
CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem
MANAGER=https://manager.logius.fsc-test.local:9443

# Vang toolbox-/curl-stderr op zodat een mTLS-/dode-container-fout niet als "nog niet vindbaar"
# maskeert (spiegelt smoke-announce.sh).
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

echo "smoke-discover: pollen tot ${SERVICE_NAME} vindbaar is bij de directory (mesh-API)..."
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  out=$("${COMPOSE[@]}" exec -T toolbox curl -s \
          --cert "$CERT" --key "$KEY" --cacert "$CA" \
          "$MANAGER/v1/peers/$DIR_OIN/services?peer_id=$PROVIDER_OIN" 2>"$ERRLOG" || true)
  [ -s "$ERRLOG" ] && { echo "  WARN: poll-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }

  if printf '%s' "$out" | grep -q "\"$SERVICE_NAME\""; then
    echo "OK: ${SERVICE_NAME} is gepubliceerd en vindbaar in de directory."
    printf 'Catalogus: %s\n' "$out"
    echo "SMOKE-DISCOVER GROEN."
    exit 0
  fi

  sleep "$INTERVAL"; elapsed=$((elapsed + INTERVAL))
  echo "  ...nog niet vindbaar (${elapsed}s)"
done

echo "FAIL: ${SERVICE_NAME} niet vindbaar binnen ${TIMEOUT}s (publish-service.sh gedraaid?)." >&2
echo "Debug: eigen publicaties (manager Internal-API) + logs:" >&2
"${COMPOSE[@]}" exec -T toolbox curl -s --cert "$CERT" --key "$KEY" --cacert "$CA" \
   "$MANAGER/v1/services/publications" >&2 || true
[ -s "$ERRLOG" ] && { echo "  -> laatste poll-fout:" >&2; tail -n 3 "$ERRLOG" >&2; }
"${COMPOSE[@]}" logs --tail=50 manager-logius manager-directory inway-logius >&2 || true
exit 1
```

- [ ] **Stap 2: Uitvoerbaar maken en draaien**

```bash
chmod +x demo/environment/logius/deploy/local/smoke-discover.sh
./demo/environment/logius/deploy/local/smoke-discover.sh
```

Expected: eindigt met `SMOKE-DISCOVER GROEN.`.

- [ ] **Stap 3: Commit**

```bash
git add demo/environment/logius/deploy/local/smoke-discover.sh
git commit -m "test(demo): smoke voor vindbaarheid van logius-profiel-service"
```

---

### Taak 4: Zelfreferentieel afnemer-contract (`ServiceConnectionGrant`) — de echte grant-hash

**Files:**
- Create: `demo/environment/logius/deploy/local/consume-service.sh`

**Interfaces:**
- Consumes: Taak 2/3 (de dienst moet gepubliceerd zijn); `pki/out/logius/outway/cert.pem` (host-pad, group-cert van de outway — al aanwezig via `pki/issue.sh`).
- Produces: een `content_hash` (afgedrukt op stdout, bewaard in `contracts/.bootstrap-state/` net als het testnet-origineel) — dit IS de waarde voor `PROFIEL_SERVICE_GRANT_HASH` (zie `libraries/fbs-common/.../fsc/FscOutwayHeaders.kt`: de header-waarde = de config-waarde, ongewijzigd doorgegeven).

**Let op — nieuw territorium:** dit zelfreferentiële patroon (consumer-OIN = provider-OIN, dezelfde manager voor indienen én accepteren) is nergens anders in deze repo of in `moza-fsc-testconsumer`/`moza-fsc-org-a` beproefd; alleen het generieke *twee-verschillende-peers*-scenario in `moza-fsc-testnet/contracts/bootstrap.sh`. Stap 3 hieronder verifieert expliciet of de manager een zelfreferentieel contract accepteert; loop je vast, zie de troubleshooting-notitie na Stap 3.

- [ ] **Stap 1: Schrijf het script (geadapteerd van `/home/claude/projects/moza-fsc-testnet/contracts/bootstrap.sh`)**

Verschillen met het origineel: geen `Copyright ©`-kopblok (zie Global Constraints), vaste
(niet-env-overrulebare) parameters voor `logius`/`profiel-service` in plaats van de generieke
`example-consumer`/`example-provider`-defaults, en `CONSUMER_*`/`PROVIDER_*` wijzen naar
dezelfde manager/certs (zelfreferentieel — zie hierboven).

```bash
#!/usr/bin/env bash
# Contract-bootstrap: zet idempotent een geldig, wederzijds ondertekend
# ServiceConnectionGrant-contract op tussen logius (consumer) en logius (provider) —
# ZELFREFERENTIEEL, omdat berichtenuitvraag's eigen outway de logius-outway IS
# (co-located identiteit, zie docs/design.md). Geadapteerd van het generieke patroon in
# de sibling-repo moza-fsc-testnet/contracts/bootstrap.sh.
#
# Stroom (OpenFSC Manager Internal-API, bewezen patroon uit publish-service.sh):
#   1. bereken de outway-GROUP-public-key-thumbprint (SPKI SHA-256 hex);
#   2. idempotentie: draagt een eerder geaccepteerd contract (state-file-hash) NOG de
#      provider-accept op de provider? -> no-op;
#   3. POST /v1/contracts (contract_content) op de manager -> tekent server-side namens
#      de consumer (2xx + content_hash = consumer-handtekening);
#   4. poll tot het contract (op content_hash) zichtbaar is, dan PUT /v1/contracts/{hash}/accept
#      (2xx = provider-handtekening) — expliciet, want AUTO_SIGN_GRANTS dekt alleen
#      (delegated)servicePublication, niet serviceConnection;
#   5. verifieer onafhankelijk (re-GET) dat het contract de provider-accept draagt.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
COMPOSE=(docker compose -f "${HERE}/docker-compose.yaml")

CONSUMER_OIN="00000000000000001000"
PROVIDER_OIN="00000000000000001000"
SERVICE_NAME="profiel-service"
GROUP_ID="moza-fbs-test"

# Outway-GROUP-cert (host-pad): hiervan de public-key-thumbprint voor de grant (SPKI-SHA256-hex,
# stabiel bij cert-rotatie). Zie moza-fsc-testnet/contracts/bootstrap.sh voor de OpenFSC-bron-
# verificatie van dit mechanisme.
OUTWAY_CERT_HOST="${HERE}/../../pki/out/logius/outway/cert.pem"

# Consumer- én provider-manager zijn hier DEZELFDE (zelfreferentieel) — internal-certs.
MANAGER="https://manager.logius.fsc-test.local:9443"
CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem

SYNC_TIMEOUT=10; SYNC_INTERVAL=2

STATE_DIR="${HERE}/../../contracts/.bootstrap-state"
STATE_FILE="${STATE_DIR}/${CONSUMER_OIN}-${PROVIDER_OIN}-${SERVICE_NAME}.hash"

ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

tb() { "${COMPOSE[@]}" exec -T toolbox curl -s --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

manager_contracts() {
  local out; out=$(tb "$MANAGER/v1/contracts") || {
    echo "  WARN: GET /v1/contracts faalde: $(tail -n1 "$ERRLOG" 2>/dev/null)" >&2; : >"$ERRLOG"; }
  printf '%s' "$out"
}

HAVE_JQ=0; command -v jq >/dev/null 2>&1 && HAVE_JQ=1

accept_state() {  # $1=json $2=content_hash $3=oin
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" --arg oin "$3" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | if ($c | length) == 0 then "unknown"
      elif ([ $c[] | .signatures?.accept? | objects ] | length) == 0 then "unknown"
      elif ($c | any((.signatures?.accept? // {}) | has($oin))) then "yes"
      else "no" end' 2>/dev/null || echo unknown
}

# --- 0. Outway-public-key-thumbprint (host-side openssl) --------------------------------------
command -v openssl >/dev/null 2>&1 || { echo "FAIL: openssl niet gevonden op de host." >&2; exit 1; }
[ -r "$OUTWAY_CERT_HOST" ] || { echo "FAIL: outway-cert niet leesbaar: $OUTWAY_CERT_HOST (draai pki/issue.sh?)" >&2; exit 1; }
THUMB=$(openssl x509 -in "$OUTWAY_CERT_HOST" -pubkey -noout \
          | openssl pkey -pubin -outform DER \
          | openssl dgst -sha256 -r | cut -d' ' -f1) || THUMB=""
case "$THUMB" in
  [0-9a-f]*) [ "${#THUMB}" -eq 64 ] || { echo "FAIL: thumbprint geen 64 hex-tekens: '$THUMB'" >&2; exit 1; } ;;
  *) echo "FAIL: kon outway-public-key-thumbprint niet berekenen uit $OUTWAY_CERT_HOST." >&2; exit 1 ;;
esac
echo "consume: outway public-key-thumbprint = $THUMB"

# --- 1. Idempotentie ----------------------------------------------------------------------------
if [ -f "$STATE_FILE" ]; then
  SAVED=$(cat "$STATE_FILE" 2>/dev/null || true)
  if [ -n "$SAVED" ]; then
    LIST=$(manager_contracts)
    case "$(accept_state "$LIST" "$SAVED" "$PROVIDER_OIN")" in
      yes)
        echo "OK: eerder geaccepteerd contract $SAVED draagt nog de provider-accept (idempotent, skip)."
        echo "GRANT-HASH: $SAVED"; exit 0 ;;
      unknown)
        if printf '%s' "$LIST" | grep -qF "$SAVED"; then
          echo "OK: eerder geaccepteerd contract $SAVED nog aanwezig (idempotent, skip; jq afwezig -> geen staat-check)."
          echo "GRANT-HASH: $SAVED"; exit 0
        fi ;;
      no) echo "consume: state-file-contract $SAVED draagt geen provider-accept meer." ;;
    esac
  fi
  echo "consume: geen bruikbaar bestaand contract — opnieuw opzetten."
fi

# --- 2. Contract opstellen + indienen -----------------------------------------------------------
IV=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]')
NBF=$(( $(date -u +%s) - 60 ))
NAF=$((NBF + 315360000))                 # +10 jaar

echo "consume: serviceConnection-contract indienen (zelfreferentieel: consumer=provider=$CONSUMER_OIN)..."
RESP=$(tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{
  \"contract_content\": {
    \"iv\": \"$IV\",
    \"group_id\": \"$GROUP_ID\",
    \"hash_algorithm\": \"HASH_ALGORITHM_SHA3_512\",
    \"created_at\": $NBF,
    \"validity\": { \"not_before\": $((NBF - 60)), \"not_after\": $NAF },
    \"grants\": [ {
      \"type\": \"GRANT_TYPE_SERVICE_CONNECTION\",
      \"service\": { \"type\": \"SERVICE_TYPE_SERVICE\", \"peer_id\": \"$PROVIDER_OIN\", \"name\": \"$SERVICE_NAME\" },
      \"outway\": {
        \"peer_id\": \"$CONSUMER_OIN\",
        \"identification\": {
          \"type\": \"OUTWAY_IDENTIFICATION_TYPE_PUBLIC_KEY_THUMBPRINT\",
          \"public_key_thumbprint\": \"$THUMB\"
        }
      }
    } ]
  }
}") || { echo "FAIL: POST /v1/contracts geweigerd: ${RESP:-<leeg>} $(tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }

HASH=$(printf '%s' "$RESP" | sed -n 's/.*"content_hash"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
[ -n "$HASH" ] || { echo "FAIL: contract-respons zonder content_hash (formaat geweigerd?): $RESP" >&2; exit 1; }
echo "  consumer-handtekening gezet (2xx); content_hash=$HASH"

# --- 3. Accepteren (zelfde manager, expliciete PUT — AUTO_SIGN_GRANTS dekt dit niet) -----------
echo "consume: wachten tot het contract zichtbaar is..."
elapsed=0; visible=0
while [ "$elapsed" -lt "$SYNC_TIMEOUT" ]; do
  if printf '%s' "$(manager_contracts)" | grep -qF "$HASH"; then visible=1; break; fi
  sleep "$SYNC_INTERVAL"; elapsed=$((elapsed + SYNC_INTERVAL))
  echo "  ...nog niet zichtbaar (${elapsed}s)"
done
[ "$visible" -eq 1 ] || { echo "FAIL: contract $HASH niet zichtbaar binnen ${SYNC_TIMEOUT}s." >&2
  "${COMPOSE[@]}" logs --tail=50 manager-logius >&2 || true; exit 1; }

echo "consume: accepteren (PUT .../accept)..."
tb -X PUT "$MANAGER/v1/contracts/$HASH/accept" -H 'Content-Type: application/json' \
  || { echo "FAIL: PUT accept ($HASH) geweigerd: $(tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }
echo "  provider-handtekening gezet (2xx)."

# --- 4. Onafhankelijk verifiëren ----------------------------------------------------------------
FINAL=$(manager_contracts)
case "$(accept_state "$FINAL" "$HASH" "$PROVIDER_OIN")" in
  yes) echo "OK: contract $HASH draagt de accept-handtekening (geverifieerd)." ;;
  unknown)
    printf '%s' "$FINAL" | grep -qF "$HASH" \
      && echo "OK (fallback, geen jq/afwijkende vorm): contract $HASH aanwezig na een 2xx-accept." \
      || { echo "FAIL: contract $HASH niet teruggevonden na accept." >&2; exit 1; } ;;
  no) echo "FAIL: contract $HASH draagt geen accept-handtekening (accept-PUT gaf 2xx, staat zegt nee — inspecteer handmatig)." >&2; exit 1 ;;
esac

mkdir -p "$STATE_DIR" && printf '%s\n' "$HASH" > "$STATE_FILE"
echo "GRANT-HASH: $HASH"
echo "CONSUME OK."
```

- [ ] **Stap 2: `.gitignore` uitbreiden voor de state-directory**

```bash
grep -q "^demo/environment/\*/contracts/" .gitignore || cat >> .gitignore << 'EOF'

# Lokale contract-bootstrap-state (content_hash, niet geheim maar host-lokaal)
demo/environment/*/contracts/.bootstrap-state/
EOF
```

- [ ] **Stap 3: Uitvoerbaar maken en draaien**

```bash
chmod +x demo/environment/logius/deploy/local/consume-service.sh
./demo/environment/logius/deploy/local/consume-service.sh
```

Expected: eindigt met `CONSUME OK.` en een regel `GRANT-HASH: <hash>`.

**Troubleshooting als de manager het zelfreferentiële contract weigert** (bv. 400/500 op de
POST, of de accept-PUT weigert omdat consumer- en provider-signatuur op dezelfde OIN botsen):
noteer de exacte foutrespons in een addendum bij `demo/environment/logius/docs/design.md`
(zie Taak 6) en behandel dit als een ontdekte OpenFSC-beperking — geen work-around zoeken door
een tweede, kunstmatige peer-identiteit te verzinnen; dat zou de architectuur (co-located
outway = berichtenuitvraag's eigen identiteit) juist tegenspreken. Rapporteer het in plaats
daarvan terug als open vraag voor het ontwerp.

- [ ] **Stap 4: Commit**

```bash
git add demo/environment/logius/deploy/local/consume-service.sh .gitignore
git commit -m "feat(demo): zelfreferentieel serviceConnection-contract voor logius-profiel-service"
```

---

### Taak 5: `run-smokes.sh` en README bijwerken

**Files:**
- Modify: `demo/environment/logius/deploy/local/run-smokes.sh`
- Modify: `demo/environment/logius/deploy/local/README.md`

**Interfaces:**
- Consumes: Taak 2, 3, 4 (roept ze in volgorde aan).

- [ ] **Stap 1: `run-smokes.sh` uitbreiden**

Vervang de inhoud van `demo/environment/logius/deploy/local/run-smokes.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
d="$(dirname "$0")"
"$d/smoke-announce.sh"
"$d/publish-service.sh"
"$d/smoke-discover.sh"
"$d/consume-service.sh"
echo "ALLE SMOKES GROEN."
```

- [ ] **Stap 2: README — "Wat er opkomt" bijwerken (inway-regel)**

Vervang in `demo/environment/logius/deploy/local/README.md` de zin (rond regel 74-76):

```
bewezen inway-config). Eigen SNI-route op de router (`inway.logius.fsc-test.local`). Biedt
(nog) geen dienst aan: er is geen `CreateService` gedaan.
```

door:

```
bewezen inway-config). Eigen SNI-route op de router (`inway.logius.fsc-test.local`). Biedt
de dienst `profiel-service` aan (zie `publish-service.sh`), met `stub-upstream` als
`endpoint_url`.
```

- [ ] **Stap 3: README — sectie "Smoke" bijwerken**

Vervang de tabel en de daaropvolgende alinea (rond regel 89-95):

```
| Script | Bewijst |
|--------|---------|
| `smoke-announce.sh` | `logius` (OIN `00000000000000001000`) staat in `peers.peers` met een `manager_address` op `:443`. |
| `run-smokes.sh` | Draait `smoke-announce.sh`. |

Announce-only: er is (nog) geen dienst-publicatie of discovery-smoke — de inway draait wel, maar
er is nog geen `CreateService` gedaan, dus er valt nog niets te discoveren of aan te roepen.
```

door:

```
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
```

- [ ] **Stap 4: Volledige lokale run**

```bash
docker compose -f demo/environment/logius/deploy/local/docker-compose.yaml down -v
docker compose -f demo/environment/logius/deploy/local/docker-compose.yaml up -d
sleep 20
./demo/environment/logius/deploy/local/run-smokes.sh
```

Expected: eindigt met `ALLE SMOKES GROEN.`.

- [ ] **Stap 5: Opruimen**

```bash
docker compose -f demo/environment/logius/deploy/local/docker-compose.yaml down -v
```

- [ ] **Stap 6: Commit**

```bash
git add demo/environment/logius/deploy/local/run-smokes.sh demo/environment/logius/deploy/local/README.md
git commit -m "docs(demo): documenteer profiel-service-publicatie + afnemer-contract in de logius-harness"
```

---

### Taak 6: ZAD-runbook + ontwerpdocument bijwerken (documentatie, geen live ZAD-call)

**Files:**
- Modify: `demo/environment/logius/deploy/zad/verify-zad.md:57-66`
- Modify: `demo/environment/logius/docs/design.md`

**Interfaces:**
- Consumes: Taak 1-5 (het lokaal bewezen patroon dat hier als ZAD-vervolgstap wordt vastgelegd).

- [ ] **Stap 1: `verify-zad.md` — vervang de sectie "Nog niet bewijsbaar: het inbound data-pad"**

Vervang (regels 57-66):

```
### Nog niet bewijsbaar: het inbound data-pad

`logius-fscinway` draait, maar biedt nog geen dienst aan — er is geen `CreateService` gedaan. Zodra de
upstream bekend is, komt daar bij:

1. `ZAD_LOGIUS_UPSTREAM_URL` in `upsert-peer.sh` (cross-deployment ingress-URL, https/:443,
   naar analogie van magazijn-a's `ZAD_MAGAZIJNA_UPSTREAM_URL`).
2. Service aanmaken + publiceren via de `logius-fscctl` Administration-API; `CreateService` verwacht het
   inway-ADRES (`SELF_ADDRESS`, `https://logius-fscinway-fsc-logius-mpfb-8wh.<base-domain>:443`), niet de naam.
3. Een smoke voor het pad `externe consumer → logius-fscinway → upstream`.
```

door:

```
### Inbound data-pad — profiel-service (lokaal bewezen, ZAD-apply is handmatig vervolgwerk)

Lokaal bewezen in `deploy/local/` (`publish-service.sh` + `smoke-discover.sh` +
`consume-service.sh`, zie `docs/plans/2026-08-12-logius-profiel-service-fsc-publicatie.md`):
`logius` publiceert de dienst `profiel-service` op zijn eigen inway en heeft een geldig,
zelfreferentieel afnemer-contract (consumer-OIN = provider-OIN, want `berichtenuitvraag`'s
eigen outway IS de logius-outway). Op ZAD moet dit nog worden herhaald tegen de échte
infrastructuur:

1. `ZAD_LOGIUS_UPSTREAM_URL` in `upsert-peer.sh` (cross-deployment ingress-URL, https/:443,
   naar analogie van magazijn-a's `ZAD_MAGAZIJNA_UPSTREAM_URL`) — wijst naar de echte
   MOZA Profiel Service, niet naar een stub.
2. `CreateService` via de `logius-fscctl` Administration-API (`SERVICE_NAME=profiel-service`,
   `endpoint_url=<ZAD_LOGIUS_UPSTREAM_URL>`, `inway_address=SELF_ADDRESS` van `logius-fscinway`,
   `https://logius-fscinway-fsc-logius-mpfb-8wh.<base-domain>:443`).
3. Het zelfreferentiële `serviceConnection`-contract opnieuw opzetten tegen de ZAD-manager
   (zelfde POST+PUT-stroom als `consume-service.sh`, met de ZAD-groep-cert-thumbprint van
   `logius-fscoutway`).
4. `PROFIEL_SERVICE_URL=https://fsc-logius-logius-fscoutway:8443` en
   `PROFIEL_SERVICE_GRANT_HASH=<content_hash uit stap 3>` als env-vars op de gedeployde
   `berichtenuitvraag`-app zetten (project `mpfb-8wh`).
5. Een smoke voor het pad `berichtenuitvraag → logius-fscoutway → logius-fscinway → upstream`.
```

- [ ] **Stap 2: Acceptatiecriteria-afvinklijst bijwerken**

Vervang (regel 75):

```
- [ ] Discover + contract + inbound data-pad: vervolgwerk (zie hierboven), niet in deze afvinklijst
```

door:

```
- [ ] `profiel-service` lokaal gepubliceerd + vindbaar + zelfreferentieel contract geldig
      (zie `deploy/local/run-smokes.sh`)
- [ ] Discover + contract + inbound data-pad OP ZAD: vervolgwerk (zie hierboven), niet in deze afvinklijst
```

- [ ] **Stap 3: Addendum in `docs/design.md`**

Gebruik de `Edit`-tool om onderaan `demo/environment/logius/docs/design.md` toe te voegen:

```markdown

## Addendum 2026-08-12 — profiel-service-publicatie + zelfreferentieel afnemer-contract

`logius` biedt sindsdien lokaal de dienst `profiel-service` aan (`deploy/local/publish-service.sh`,
naar het bewezen patroon van `magazijn-a`) en heeft een geldig, wederzijds ondertekend
`ServiceConnectionGrant`-contract (`deploy/local/consume-service.sh`, geadapteerd van
`moza-fsc-testnet/contracts/bootstrap.sh`). Het contract is bewust **zelfreferentieel**
(consumer-OIN = provider-OIN = `00000000000000001000`): `berichtenuitvraag`'s eigen outway IS
de logius-outway (co-locatie, zie hierboven), dus de "afnemer" van `profiel-service` is
architectuurgewijs dezelfde peer als de "aanbieder".

Zie `docs/plans/2026-08-12-logius-profiel-service-fsc-publicatie.md` voor de volledige
uitvoering en `deploy/zad/verify-zad.md` voor de resterende ZAD-vervolgstappen (CreateService
tegen de échte upstream, het contract herhalen tegen de ZAD-manager, en het zetten van
`PROFIEL_SERVICE_URL`/`PROFIEL_SERVICE_GRANT_HASH` op de gedeployde `berichtenuitvraag`-app).
```

- [ ] **Stap 4: Commit**

```bash
git add demo/environment/logius/deploy/zad/verify-zad.md demo/environment/logius/docs/design.md
git commit -m "docs(demo): documenteer profiel-service-publicatie in de logius-ZAD-runbooks"
```

---

### Taak 7: Eindverificatie

**Files:**
- Geen bestandswijzigingen — puur verificatie.

**Interfaces:**
- Consumes: alle voorgaande taken.

- [ ] **Stap 1: Volledige lokale harness nog één keer end-to-end, vanaf een schone stack**

```bash
docker compose -f demo/environment/logius/deploy/local/docker-compose.yaml down -v
docker compose -f demo/environment/logius/deploy/local/docker-compose.yaml up -d
sleep 20
./demo/environment/logius/deploy/local/run-smokes.sh
docker compose -f demo/environment/logius/deploy/local/docker-compose.yaml down -v
```

Expected: `ALLE SMOKES GROEN.`.

- [ ] **Stap 2: Grep — geen achtergebleven "geen CreateService"/"nog geen dienst"-tekst voor logius**

```bash
grep -rn "nog geen dienst\|geen CreateService gedaan" demo/environment/logius/ \
  || echo "GEEN treffers — OK"
```

Expected: `GEEN treffers — OK`.

- [ ] **Stap 3: Bevestig dat `services/berichtenuitvraag` en `services/berichtenmagazijn` ongewijzigd blijven**

```bash
git status --short services/berichtenuitvraag services/berichtenmagazijn libraries/
```

Expected: geen output — dit plan raakt uitsluitend `demo/environment/logius/` en `.gitignore`.

- [ ] **Stap 4: Repo-brede statuscontrole**

```bash
git status --short
```

Expected: schone working tree (alle taken al gecommit); eventueel untracked
`demo/environment/logius/contracts/.bootstrap-state/` als die niet door de nieuwe
`.gitignore`-regel (Taak 4, Stap 2) wordt gedekt — dan is die regel onjuist en moet ze
gecorrigeerd worden vóór deze stap slaagt.

Dit plan levert een lokaal volledig bewezen `profiel-service`-publicatie + geldig
afnemer-contract op voor de FSC-peer `logius`. De daadwerkelijke ZAD-`CreateService` tegen de
echte MOZA Profiel Service, het contract op de ZAD-manager, en het zetten van
`PROFIEL_SERVICE_URL`/`PROFIEL_SERVICE_GRANT_HASH` op de gedeployde `berichtenuitvraag` blijven
— net als bij `2026-07-31-magazijn-a-peer-migratie-plan.md` — menselijke, niet-geautomatiseerde
vervolgstappen die echte ZAD-toegang vereisen.
