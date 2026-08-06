#!/usr/bin/env bash
# Zet de provider-peer magazijn-a (manager+controller+inway+txlog+Postgres) op ZAD via
# de v2 Operations Manager API. Lokaal/handmatig hulpmiddel voor de EENMALIGE
# componentcreatie (env/ports) en voor debugging; de doorlopende image-tag-updates lopen
# via de `deploy-test-magazijnen`-job in .github/workflows/deploy.yml.
#
# README.md naast dit script beschrijft de achtergrond: waarom de peer een eigen
# deployment `fsc-magazijna` heeft (clone-veiligheid), de uitvoervolgorde (certs →
# deployment handmatig aanmaken → plan/validate/apply → UI-only cert-attachments), de
# self-hosted Postgres-opzet en alle ZAD_*-env-vars met hun defaults. Vorm overgenomen
# van repo A's deploy/zad/upsert-directory.sh (MinBZK/moza-fsc-testnet).
#
# Twee valkuilen die het gedrag van dít script bepalen:
# - ZAD past component-config (env_vars/aliases) alleen bij COMPONENT-CREATIE toe, niet
#   bij een re-POST op een bestaande component. Config wijzigen betekent de component
#   eerst in de UI verwijderen en opnieuw applyen — de cert-attachments raak je daarmee
#   kwijt en moeten opnieuw gemount worden.
# - `:upsert-deployment` maakt géén NIEUW deployment aan (geeft wel HTTP 202, maar het
#   deployment verschijnt niet in /deployments); `fsc-magazijna` moet dus éénmalig leeg
#   in de UI bestaan, anders doet apply niets zichtbaars.
#
# De deployment is vast, dus ZAD's $DEPLOYMENT_NAME-substitutie is niet nodig: bash lost
# alle inter-component-hostnamen concreet op (*_HOST_DISPLAY) en zet ze in `env_vars`.
#
# Usage:
#   export ZAD_API_KEY=... ZAD_PG_PASSWORD=...                    # niet inline (komt anders in de shell-history)
#   ./deploy/zad/upsert-peer.sh validate                          # read-only auth-check
#   ./deploy/zad/upsert-peer.sh plan   [deployment] [tag]         # toont bodies, muteert niet
#   ./deploy/zad/upsert-peer.sh apply  [deployment] [tag]         # muteert + pollt tasks
set -euo pipefail

MODE="${1:?usage: upsert-peer.sh <validate|plan|apply> [deployment=fsc-magazijna] [tag=v2.5.2]}"
DEPLOYMENT="${2:-${ZAD_DEPLOYMENT:-fsc-magazijna}}"  # arg wint; anders ZAD_DEPLOYMENT (spoort met pki/gen-csr.sh)
IMAGE_TAG="${3:-v2.5.2}"                        # OpenFSC-versie: inway stock-image + default-tag voor de migrate-wrappers
MANAGER_TAG="${ZAD_MANAGER_TAG:-${IMAGE_TAG}}"       # migrate-wrappers (ghcr) mogen een eigen tag hebben
CONTROLLER_TAG="${ZAD_CONTROLLER_TAG:-${IMAGE_TAG}}"
TXLOG_TAG="${ZAD_TXLOG_TAG:-${IMAGE_TAG}}"
PROJECT="${ZAD_PROJECT:-mpfm-w3h}"
BASE="${ZAD_BASE:-https://zad.rijksapp.nl}"
BASE_DOMAIN="${ZAD_BASE_DOMAIN:-rig.prd1.gn2.quattro.rijksapps.nl}"
PG_SSLMODE="${ZAD_PG_SSLMODE:-disable}"          # managed DB intra-cluster: plaintext (zoals berichtenbox-JDBC)
CLONE_FROM="${ZAD_PEER_CLONE_FROM:-}"            # leeg = geen clone; klonen van `test` zou de app-componenten meenemen

# --- Self-hosted Postgres (component magazijna-fscpg) ---------------------------------------------------------
# ZAD's managed Postgres laat ons het schema/init niet inrichten (geen init-scripts, geen CREATE
# SCHEMA-rechten op eigen voorwaarden). Daarom draaien we een EIGEN postgres-component `magazijna-fscpg` die we
# volledig beheren: één database met geïsoleerde golang-migrate `schema_migrations`-tellers per
# component. manager + txlog isoleren hun teller via een eigen `search_path`-schema (aangemaakt door
# deploy/zad/postgres-init.sql, UI-attachment op /docker-entrypoint-initdb.d). De controller draait
# ZONDER search_path (uitzondering, zie CTL_SCHEMA) en beheert z'n eigen `controller`-schema; z'n teller
# landt in public. Zo botsen de tellers niet (anders skipt een migratie -> 42P01 op controller.services).
PG_USER="${ZAD_PG_USER:-fsc}"
PG_DB="${ZAD_PG_DB:-fsc}"
PG_PASSWORD="${ZAD_PG_PASSWORD:-__SET_ZAD_PG_PASSWORD__}"   # concreet bij apply (verplicht, zie check onder); nooit committen
# search_path per component. `-` i.p.v. `:-` zodat ZAD_*_SCHEMA="" écht leeg blijft (dan geen search_path
# in de DSN -> component gebruikt public). manager + txlog moeten sporen met postgres-init.sql (dat die
# twee schema's aanmaakt). De CONTROLLER is de UITZONDERING: die maakt z'n eigen `controller`-schema aan
# (schema-gekwalificeerde DDL) en loopt mét search_path vast op een dirty migratie #1 -> default LEEG
# (geen search_path; z'n schema_migrations landt in public, los van manager/txlog).
MGR_SCHEMA="${ZAD_MGR_SCHEMA-manager}"
CTL_SCHEMA="${ZAD_CTL_SCHEMA-}"
TXLOG_SCHEMA="${ZAD_TXLOG_SCHEMA-txlog}"

case "${MODE}" in validate|plan|apply) ;; *) echo "mode = validate | plan | apply"; exit 1 ;; esac
case "${DEPLOYMENT}" in ""|*[!a-z0-9-]*) echo "ongeldige deployment: '${DEPLOYMENT}'"; exit 1 ;; esac
case "${IMAGE_TAG}" in ""|*[!A-Za-z0-9._-]*) echo "ongeldige image_tag: '${IMAGE_TAG}'"; exit 1 ;; esac
case "${MANAGER_TAG}" in ""|*[!A-Za-z0-9._-]*) echo "ongeldige ZAD_MANAGER_TAG: '${MANAGER_TAG}'"; exit 1 ;; esac
case "${CONTROLLER_TAG}" in ""|*[!A-Za-z0-9._-]*) echo "ongeldige ZAD_CONTROLLER_TAG: '${CONTROLLER_TAG}'"; exit 1 ;; esac
case "${TXLOG_TAG}" in ""|*[!A-Za-z0-9._-]*) echo "ongeldige ZAD_TXLOG_TAG: '${TXLOG_TAG}'"; exit 1 ;; esac
[ "${MODE}" = apply ] && : "${ZAD_API_KEY:?zet ZAD_API_KEY in je env}"
[ "${MODE}" = apply ] && : "${ZAD_PG_PASSWORD:?zet ZAD_PG_PASSWORD in je env (wachtwoord voor de self-hosted magazijna-fscpg-Postgres)}"

# manager/controller/txlog draaien een migrate-WRAPPER (`migrate up && serve`) i.p.v. het OpenFSC
# stock-image: ZAD kent geen init-containers/args, dus de migratie moet in het image zelf zitten. De
# wrappers staan naast manager-migrate in dezelfde ghcr-repo. Wijkt een pad af, override dan het hele
# image met ZAD_MANAGER_IMAGE / ZAD_CONTROLLER_IMAGE / ZAD_TXLOG_IMAGE. De inway heeft geen DB en dus
# geen migratie -> stock-image.
MANAGER_IMAGE="${ZAD_MANAGER_IMAGE:-ghcr.io/minbzk/moza-fsc-testnet-manager-migrate:${MANAGER_TAG}}"
CONTROLLER_IMAGE="${ZAD_CONTROLLER_IMAGE:-ghcr.io/minbzk/moza-fsc-testnet-controller-migrate:${CONTROLLER_TAG}}"
INWAY_IMAGE="docker.io/federatedserviceconnectivity/inway:${IMAGE_TAG}"
TXLOG_IMAGE="${ZAD_TXLOG_IMAGE:-ghcr.io/minbzk/moza-fsc-testnet-txlog-migrate:${TXLOG_TAG}}"
POSTGRES_IMAGE="${ZAD_POSTGRES_IMAGE:-docker.io/library/postgres:17}"   # self-hosted DB (spiegelt deploy/local)

# Concrete hostnamen voor déze (vaste) deployment — zowel voor de plan-/apply-output als, direct,
# voor de inter-component-adressen in de env_vars-blobs. Geen $DEPLOYMENT_NAME-substitutie: de
# deployment is vast (fsc-magazijna/mpfm-w3h), dus bash lost de hostnaam op en we leunen niet op ZAD's
# aliases-substitutie (die alleen bij component-creatie wordt toegepast, niet bij een re-POST).
MGZMGR_HOST_DISPLAY="magazijna-fscmgr-${DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}"
MGZCTL_HOST_DISPLAY="magazijna-fscctl-${DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}"
MGZINWAY_HOST_DISPLAY="magazijna-fscinway-${DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}"
MGZTXLOG_HOST_DISPLAY="magazijna-fsctxlog-${DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}"

# Cluster-INTERNE Service-DNS (sinds de ZAD-multi-poort-fix, 2026-07-13). Elke component exposet nu
# ál zijn `ports.inbound` als ClusterIP-Service-poort; de Service heet `<deployment>-<component>` en
# is intern bereikbaar als `<deployment>-<component>:<poort>` (bv. `fsc-magazijna-magazijna-fscmgr:9443`). De interne
# FSC-edges (controller↔manager, inway→manager/controller, →txlog) lopen hierover — NIET meer over de
# publieke `:443`-ingress, waardoor ze op de interne-PKI-poort met de juiste CA landen (dat lost de
# eerdere `x509: certificate signed by unknown authority` op). Alleen de EXTERNE mesh (SELF_ADDRESS,
# directory) blijft op `:443` (SNI-passthrough). De internal-certs dragen deze namen als SAN (zie de
# csr.json's + cert-manifest.md). Kort + intra-namespace; geen $DEPLOYMENT_NAME-substitutie nodig.
MGZMGR_SVC="${DEPLOYMENT}-magazijna-fscmgr"
MGZCTL_SVC="${DEPLOYMENT}-magazijna-fscctl"
MGZINWAY_SVC="${DEPLOYMENT}-magazijna-fscinway"
MGZTXLOG_SVC="${DEPLOYMENT}-magazijna-fsctxlog"
MGZPG_SVC="${DEPLOYMENT}-magazijna-fscpg"                  # self-hosted Postgres, intern op :5432

# Repo A's directory-deployment op ZAD (project mft-tp9, deployment "test" — zie upsert-directory.sh
# se defaults). TODO(verifieer bij de echte apply): bevestig dat dit nog steeds de actieve
# directory-host is; override met ZAD_DIRECTORY_MANAGER_HOST als de directory elders draait.
DIRECTORY_MANAGER_HOST="${ZAD_DIRECTORY_MANAGER_HOST:-dirmgr-test-mft-tp9.${BASE_DOMAIN}}"

# Peer en app-component `magazijna` delen het project (`mpfm-w3h`) maar NIET de deployment: de app
# draait in `test` (door deploy.yml beheerd, bron van de PR-preview-clones), de peer in
# `fsc-magazijna`. De upstream-URL wijst dus naar de APP-deployment, niet naar `${DEPLOYMENT}`;
# een volledige override kan via ZAD_MAGAZIJNA_UPSTREAM_URL. Dit is GEEN inway-env-var
# (OpenFSC kent geen "upstream" op de inway) maar de endpoint_url die bij de service-publicatie op
# de magazijna-fscctl Administration-API wordt meegegeven — zie verify-zad.md, stap (b).
MAGAZIJNA_DEPLOYMENT="${ZAD_MAGAZIJNA_DEPLOYMENT:-test}"
MAGAZIJNA_UPSTREAM_URL="${ZAD_MAGAZIJNA_UPSTREAM_URL:-https://magazijna-${MAGAZIJNA_DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}}"

# --- env-blobs (KEY=value, newline-sep, plain). TLS_*-paden = de bijlage-mounts (UI, ontwerp A). ---
MGZMGR_ENV="$(printf '%s\n' \
  "LOG_TYPE=live" "LOG_LEVEL=info" "AUDITLOG_TYPE=stdout" \
  "GROUP_ID=moza-fbs-test" \
  "DIRECTORY_PEER_ID=00000000000000000010" \
  "AUTO_SIGN_GRANTS=" \
  "LISTEN_ADDRESS_EXTERNAL=0.0.0.0:8443" \
  "LISTEN_ADDRESS_INTERNAL=0.0.0.0:9443" \
  "LISTEN_ADDRESS_INTERNAL_UNAUTHENTICATED=0.0.0.0:9444" \
  "MONITORING_ADDRESS=0.0.0.0:8080" \
  "DISABLE_CRL_CHECKS=true" \
  "TLS_GROUP_ROOT_CERT=/etc/fsc/ca/root.pem" \
  "TLS_GROUP_CERT=/etc/fsc/out/magazijn-a/manager/cert.pem" \
  "TLS_GROUP_KEY=/etc/fsc/out/magazijn-a/manager/key.pem" \
  "TLS_GROUP_TOKEN_CERT=/etc/fsc/out/magazijn-a/manager/cert.pem" \
  "TLS_GROUP_TOKEN_KEY=/etc/fsc/out/magazijn-a/manager/key.pem" \
  "TLS_GROUP_CONTRACT_CERT=/etc/fsc/out/magazijn-a/manager/cert.pem" \
  "TLS_GROUP_CONTRACT_KEY=/etc/fsc/out/magazijn-a/manager/key.pem" \
  "TLS_ROOT_CERT=/etc/fsc/internal/magazijn-a/ca/root.pem" \
  "TLS_CERT=/etc/fsc/internal/magazijn-a/manager/cert.pem" \
  "TLS_KEY=/etc/fsc/internal/magazijn-a/manager/key.pem" \
  "TLS_INTERNAL_UNAUTHENTICATED_ROOT_CERT=/etc/fsc/internal/magazijn-a/ca/root.pem" \
  "TLS_INTERNAL_UNAUTHENTICATED_CERT=/etc/fsc/internal/magazijn-a/manager/cert.pem" \
  "TLS_INTERNAL_UNAUTHENTICATED_KEY=/etc/fsc/internal/magazijn-a/manager/key.pem" \
  "SELF_ADDRESS=https://${MGZMGR_HOST_DISPLAY}:443" \
  "DIRECTORY_MANAGER_ADDRESS=https://${DIRECTORY_MANAGER_HOST}:443" \
  "CONTROLLER_REGISTRATION_API_ADDRESS=https://${MGZCTL_SVC}:9443" \
  "TX_LOG_API_ADDRESS=https://${MGZTXLOG_SVC}:8443")"

# Sinds de self-hosted Postgres (magazijna-fscpg) is de STORAGE_POSTGRES_DSN concreet (geen ZAD $DATABASE_*-
# substitutie meer) -> hij hoort in env_vars, niet in aliases. De DSN's worden hieronder aan elke
# ENV toegevoegd (zie _pg_dsn). Aliases zijn daarmee leeg.
# Poortkeuze adressen: EXTERNE mesh (SELF_ADDRESS, directory) op :443 (ingress SNI-passthrough ->
# pod :8443); INTERNE edges op de cluster-Service-DNS + interne-PKI-poort (9443/9444, txlog 8443).
MGZMGR_ALIASES=""

MGZCTL_ENV="$(printf '%s\n' \
  "LOG_TYPE=live" "LOG_LEVEL=info" "AUDITLOG_TYPE=stdout" \
  "GROUP_ID=moza-fbs-test" \
  "DIRECTORY_PEER_ID=00000000000000000010" \
  "AUTHN_TYPE=none" \
  "AUTHZ_TYPE=rbac" \
  "CSRF_PROTECTION_ENABLED=false" \
  "LISTEN_ADDRESS_UI=0.0.0.0:8080" \
  "LISTEN_ADDRESS_REGISTRATION_API=0.0.0.0:9443" \
  "LISTEN_ADDRESS_ADMINISTRATION_API=0.0.0.0:9444" \
  "MONITORING_ADDRESS=0.0.0.0:8081" \
  "TLS_ROOT_CERT=/etc/fsc/internal/magazijn-a/ca/root.pem" \
  "TLS_CERT=/etc/fsc/internal/magazijn-a/controller/cert.pem" \
  "TLS_KEY=/etc/fsc/internal/magazijn-a/controller/key.pem" \
  "MANAGER_ADDRESS_INTERNAL=https://${MGZMGR_SVC}:9443")"
MGZCTL_ALIASES=""

MGZINWAY_ENV="$(printf '%s\n' \
  "LOG_TYPE=live" "LOG_LEVEL=info" \
  "NAME=magazijn-a-inway" \
  "GROUP_ID=moza-fbs-test" \
  "LISTEN_ADDRESS=0.0.0.0:8443" \
  "MONITORING_ADDRESS=0.0.0.0:8081" \
  "DISABLE_CRL_CHECKS=true" \
  "TLS_GROUP_ROOT_CERT=/etc/fsc/ca/root.pem" \
  "TLS_GROUP_CERT=/etc/fsc/out/magazijn-a/inway/cert.pem" \
  "TLS_GROUP_KEY=/etc/fsc/out/magazijn-a/inway/key.pem" \
  "TLS_ROOT_CERT=/etc/fsc/internal/magazijn-a/ca/root.pem" \
  "TLS_CERT=/etc/fsc/internal/magazijn-a/inway/cert.pem" \
  "TLS_KEY=/etc/fsc/internal/magazijn-a/inway/key.pem" \
  "SELF_ADDRESS=https://${MGZINWAY_HOST_DISPLAY}:443" \
  "CONTROLLER_REGISTRATION_API_ADDRESS=https://${MGZCTL_SVC}:9443" \
  "MANAGER_INTERNAL_UNAUTHENTICATED_ADDRESS=https://${MGZMGR_SVC}:9444" \
  "TX_LOG_API_ADDRESS=https://${MGZTXLOG_SVC}:8443")"
# Geen managed DB en geen $DATABASE_*-substitutie -> geen aliases nodig (alle adressen staan
# concreet in env_vars hierboven).
MGZINWAY_ALIASES=""

# txlog-api (mirror van deploy/local): mTLS op de INTERNAL-PKI (geen group-cert, geen GROUP_ID —
# group-agnostische opslag). De manager/inway loggen hier transacties; OpenFSC eist een niet-lege
# TX_LOG_API_ADDRESS voor een niet-directory manager. txlog-hardening / het echte data-pad is #728.
MGZTXLOG_ENV="$(printf '%s\n' \
  "LOG_TYPE=live" "LOG_LEVEL=info" \
  "LISTEN_ADDRESS=0.0.0.0:8443" \
  "MONITORING_ADDRESS=0.0.0.0:8081" \
  "TLS_ROOT_CERT=/etc/fsc/internal/magazijn-a/ca/root.pem" \
  "TLS_CERT=/etc/fsc/internal/magazijn-a/txlog/cert.pem" \
  "TLS_KEY=/etc/fsc/internal/magazijn-a/txlog/key.pem")"
MGZTXLOG_ALIASES=""

# --- self-hosted Postgres: component-env + concrete DSN per FSC-component -----------------------------
# magazijna-fscpg draait het officiële postgres-image (config puur via POSTGRES_*-env, geen command nodig). PGDATA
# in een subdir zodat een eventueel gemount volume met lost+found de init niet blokkeert. Het init-script
# (schema's manager + txlog) is een UI-attachment op /docker-entrypoint-initdb.d (zie postgres-init.sql + cert-manifest).
MGZPG_ENV="$(printf '%s\n' \
  "POSTGRES_USER=${PG_USER}" \
  "POSTGRES_PASSWORD=${PG_PASSWORD}" \
  "POSTGRES_DB=${PG_DB}" \
  "PGDATA=/var/lib/postgresql/data/pgdata")"

# Concrete DSN naar de magazijna-fscpg-Service met per-component search_path. Toegevoegd aan env_vars (niet
# aliases): geen ZAD-substitutie meer nodig. ${schema:+...} laat de search_path weg als het schema
# leeg is (ZAD_*_SCHEMA="").
_pg_dsn() {  # $1=search_path-schema (mag leeg)
  printf 'STORAGE_POSTGRES_DSN=postgres://%s:%s@%s:5432/%s?sslmode=%s%s' \
    "${PG_USER}" "${PG_PASSWORD}" "${MGZPG_SVC}" "${PG_DB}" "${PG_SSLMODE}" "${1:+&search_path=${1}}"
}
MGZMGR_ENV="${MGZMGR_ENV}"$'\n'"$(_pg_dsn "${MGR_SCHEMA}")"
MGZCTL_ENV="${MGZCTL_ENV}"$'\n'"$(_pg_dsn "${CTL_SCHEMA}")"
MGZTXLOG_ENV="${MGZTXLOG_ENV}"$'\n'"$(_pg_dsn "${TXLOG_SCHEMA}")"

# component-body (AddComponentRequest) via jq -> correcte JSON-escaping.
# `ports` (array) sinds de ZAD-multi-poort-fix (2026-07-13): elke poort krijgt een Service-poort,
# ports[0] blijft de ingress. Schema: "Use either 'port' or 'ports', not both" — wij gebruiken ports.
component_body() {  # $1=name $2=image $3=ports_json $4=env  [$5=services_json=[]]  [$6=aliases=""]
  jq -n --arg name "$1" --arg image "$2" --argjson ports "$3" --arg env "$4" \
        --argjson services "${5:-[]}" --arg aliases "${6:-}" --arg dep "${DEPLOYMENT}" \
    '{name:$name, image:$image, ports:$ports, env_vars:$env, deployment_names:[$dep]}
     + (if ($services|length) > 0 then {services:$services} else {} end)
     + (if $aliases == "" then {} else {aliases:$aliases} end)'
}

DEPLOY_BODY="$(jq -n --arg d "${DEPLOYMENT}" --arg cf "${CLONE_FROM}" \
  --arg mgr "${MANAGER_IMAGE}" --arg ctl "${CONTROLLER_IMAGE}" --arg inway "${INWAY_IMAGE}" \
  --arg txlog "${TXLOG_IMAGE}" --arg pg "${POSTGRES_IMAGE}" \
  '{deploymentName:$d, domain_format:"component-deployment-project",
    components:[{reference:"magazijna-fscpg", image:$pg}, {reference:"magazijna-fscmgr", image:$mgr}, {reference:"magazijna-fscctl", image:$ctl}, {reference:"magazijna-fscinway", image:$inway}, {reference:"magazijna-fsctxlog", image:$txlog}]}
   + (if $cf=="" then {} else {cloneFrom:$cf, forceClone:false} end)')"

# Poorten per component (ports[0] = ingress). manager/controller exposen naast de ingress hun interne
# mTLS-poorten (9443 auth, 9444 unauth) als eigen Service-poort; inway/txlog hebben alleen hun ene
# listener; magazijna-fscpg alleen :5432. Geen managed-DB-binding meer ([]): de DB is nu de magazijna-fscpg-component.
# Deze arrays moeten sporen met de LISTEN_ADDRESS_*-poorten in de env-blobs hierboven.
MGZPG_BODY="$(component_body magazijna-fscpg "${POSTGRES_IMAGE}" '[5432]' "${MGZPG_ENV}" '[]' "")"
MGZMGR_BODY="$(component_body magazijna-fscmgr "${MANAGER_IMAGE}" '[8443,9443,9444]' "${MGZMGR_ENV}" '[]' "${MGZMGR_ALIASES}")"
MGZCTL_BODY="$(component_body magazijna-fscctl "${CONTROLLER_IMAGE}" '[8080,9443,9444]' "${MGZCTL_ENV}" '[]' "${MGZCTL_ALIASES}")"
MGZINWAY_BODY="$(component_body magazijna-fscinway "${INWAY_IMAGE}" '[8443]' "${MGZINWAY_ENV}" '[]' "${MGZINWAY_ALIASES}")"
MGZTXLOG_BODY="$(component_body magazijna-fsctxlog "${TXLOG_IMAGE}" '[8443]' "${MGZTXLOG_ENV}" '[]' "${MGZTXLOG_ALIASES}")"

# ---- plan: toon alleen ----
if [ "${MODE}" = plan ]; then
  echo "### deployment (:upsert-deployment)"; echo "${DEPLOY_BODY}"
  echo "### component magazijna-fscpg (self-hosted Postgres + init-schema's)"; echo "${MGZPG_BODY}"
  echo "### component magazijna-fscmgr (manager -> magazijna-fscpg schema '${MGR_SCHEMA:-public}')"; echo "${MGZMGR_BODY}"
  echo "### component magazijna-fscctl (controller -> magazijna-fscpg schema '${CTL_SCHEMA:-public}')"; echo "${MGZCTL_BODY}"
  echo "### component magazijna-fscinway (inway)"; echo "${MGZINWAY_BODY}"
  echo "### component magazijna-fsctxlog (txlog-api -> magazijna-fscpg schema '${TXLOG_SCHEMA:-public}')"; echo "${MGZTXLOG_BODY}"
  echo "Extern (mesh, :443): magazijna-fscmgr=${MGZMGR_HOST_DISPLAY} magazijna-fscinway=${MGZINWAY_HOST_DISPLAY}"
  echo "Intern (cluster-Service-DNS): ${MGZMGR_SVC}:9443/:9444  ${MGZCTL_SVC}:9443/:9444  ${MGZTXLOG_SVC}:8443  db=${MGZPG_SVC}:5432  (magazijna-fscctl-UI: ${MGZCTL_HOST_DISPLAY}:443)"
  echo "Directory-manager (repo A, extern): ${DIRECTORY_MANAGER_HOST}"
  echo "Upstream naar de app (ingress-URL, cross-deployment): ${MAGAZIJNA_UPSTREAM_URL}"
  exit 0
fi

API="${BASE}/api/v2/projects/${PROJECT}"
resp="$(mktemp)"; trap 'rm -f "${resp}"' EXIT
hdr=(-H "X-API-Key: ${ZAD_API_KEY}")

poll_task() {  # $1=task_id
  local id="$1" i status
  for i in $(seq 1 45); do
    # --fail: HTTP 4xx/5xx op de tasks-API mag niet als "nog bezig" (status=null) tellen; retry.
    if ! curl -sS --fail "${hdr[@]}" "${BASE}/api/tasks/${id}" -o "${resp}"; then
      echo "  task ${id}: tasks-API HTTP-fout (poging ${i}/45) — retry" >&2
      sleep 2; continue
    fi
    status="$(jq -r '.status' "${resp}")"
    case "${status}" in
      completed) echo "  task ${id}: completed"; return 0 ;;
      failed)    echo "  task ${id}: FAILED -> $(jq -r '.error_message // .result.error' "${resp}")" >&2; return 1 ;;
      *)         sleep 2 ;;
    esac
  done
  echo "  task ${id}: nog bezig na ~90s (async ArgoCD-sync) — niet geblokkeerd, check later met 'validate'." >&2
  return 0
}

post() {  # $1=label $2=path $3=body
  echo "POST ${2}  (${1})"
  local code; code="$(curl -sS "${hdr[@]}" -H 'Content-Type: application/json' \
    -X POST --data "${3}" -o "${resp}" -w '%{http_code}' "${API}${2}")"
  echo "  -> HTTP ${code}"
  case "${code}" in 2*) ;; *) jq . "${resp}" 2>/dev/null || cat "${resp}"; return 1 ;; esac
  local tid; tid="$(jq -r '.task_id // empty' "${resp}")"
  # if/then/else zodat poll_task's non-zero (FAILED-task) propageert i.p.v. gemaskeerd door `|| {…}`.
  if [ -n "${tid}" ]; then
    poll_task "${tid}"
  else
    jq . "${resp}"
  fi
}

# ---- apply ----
echo "== validate =="
code="$(curl -sS "${hdr[@]}" -o "${resp}" -w '%{http_code}' "${API}/deployments")"
[ "${code}" = 200 ] || { echo "auth/connectie faalt (HTTP ${code})"; cat "${resp}"; exit 1; }
echo "auth OK — deployments + componenten:"
jq -r '.deployments[]? | "  - \(.name): \([.components[]?.reference] | join(", "))"' "${resp}" 2>/dev/null || true
if [ "${MODE}" = validate ]; then echo "validate OK (read-only, niets gemuteerd)."; exit 0; fi

echo "== upsert deployment '${DEPLOYMENT}' =="
post "deployment" "/:upsert-deployment" "${DEPLOY_BODY}"

echo "== componenten aanmaken/bijwerken =="
post "magazijna-fscpg"    "/components" "${MGZPG_BODY}"      # DB eerst; de FSC-componenten retryen tot hij up is
post "magazijna-fscmgr"   "/components" "${MGZMGR_BODY}"
post "magazijna-fscctl"   "/components" "${MGZCTL_BODY}"
post "magazijna-fscinway" "/components" "${MGZINWAY_BODY}"
post "magazijna-fsctxlog" "/components" "${MGZTXLOG_BODY}"

# De EERSTE :upsert-deployment (hierboven) rolt de pods uit MET de config van de vórige run — de
# POST /components hierna zet de nieuwe env pas ná die rollout. Doe daarom NOG één :upsert-deployment
# zodat de pods opnieuw uitrollen met de zojuist gezette component-config (anders loopt de env één
# deploy achter). Zo hoeven bestaande componenten NIET verwijderd te worden (cert-attachments blijven).
echo "== deployment opnieuw uitrollen met verse component-config =="
post "deployment (re-roll)" "/:upsert-deployment" "${DEPLOY_BODY}"

# Diagnose: bevestig wat er ná de apply daadwerkelijk als deployment `${DEPLOYMENT}` bestaat
# (een 202 op :upsert-deployment betekent "geaccepteerd", niet per se "zichtbaar als deployment").
echo "== staat na apply: deployment '${DEPLOYMENT}' =="
if curl -sS "${hdr[@]}" -o "${resp}" "${API}/deployments"; then
  if jq -e --arg d "${DEPLOYMENT}" '.deployments[]? | select(.name==$d)' "${resp}" >/dev/null 2>&1; then
    echo "  gevonden:"
    jq -r --arg d "${DEPLOYMENT}" '.deployments[]? | select(.name==$d)
      | "  name=\(.name) status=\(.status // "?") issues=\(.issues // "?") componenten=\([.components[]?.reference] | join(","))"' "${resp}"
  else
    echo "  NIET in /deployments — deployment '${DEPLOYMENT}' bestaat (nog) niet ondanks 202." >&2
    echo "  alle deployments:" >&2
    jq -r '.deployments[]? | "    - \(.name) [\(.status // "?")]"' "${resp}" >&2 || true
  fi
fi

echo "Klaar. Nog handmatig (UI):"
echo "  - magazijna-fscpg: init-script als bijlage op /docker-entrypoint-initdb.d/10-schemas.sql (zie postgres-init.sql)."
echo "  - FSC-componenten: cert-bijlagen op /etc/fsc/... + Publicatie op het web modus 2 op magazijna-fscmgr/magazijna-fscinway."
echo "  - DB-migraties: manager/controller/txlog migreren automatisch bij boot via hun migrate-wrapper-image (geen handmatige stap)."
echo "Extern (mesh, :443): magazijna-fscmgr=${MGZMGR_HOST_DISPLAY} magazijna-fscinway=${MGZINWAY_HOST_DISPLAY}"
echo "Intern (cluster-Service-DNS): ${MGZMGR_SVC}:9443/:9444  ${MGZCTL_SVC}:9443/:9444  ${MGZTXLOG_SVC}:8443  db=${MGZPG_SVC}:5432"
