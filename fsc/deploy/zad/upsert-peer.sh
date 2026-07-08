#!/usr/bin/env bash
# Zet de provider-peer magazijn-a (manager+controller+inway) op ZAD via de v2 Operations Manager
# API, naast de bestaande app-component `magazijna` in project mpfm-w3h. Gebaseerd op repo A's
# deploy/zad/upsert-directory.sh (MinBZK/moza-fsc-testnet) — zelfde validate/plan/apply-vorm, één
# bron voor CLI + de workflow zad-deploy-peer.yml.
#
# Model: de peer draait in een eigen deployment (default `peer`). `:upsert-deployment` zet per
# component de {reference,image} en maakt/updatet het deployment; POST /components verrijkt elke
# component met env_vars/port/services/aliases.
#
# BELANGRIJK — het `peer`-deployment moet ÉÉNMALIG handmatig (leeg) worden aangemaakt in de
# Operations Manager-UI. Een `:upsert-deployment` zónder cloneFrom geeft wel HTTP 202 maar creëert
# géén NIEUWE deployment (bewezen: `peer` verscheen niet in /deployments). Een BESTAAND (leeg)
# deployment wordt door `:upsert-deployment` + `POST /components` wél gevuld/geüpdatet — dat is dit
# script. cloneFrom is optioneel (ZAD_PEER_CLONE_FROM) maar afgeraden: clonen van bv. `test` sleept
# de app-images (clickhouse/magazijna/magazijnb) mee in `peer`.
# NIET via de API (UI-only): bijlagen (cert-mount) + "Publicatie op het web" (passthrough-TLS) —
# zie cert-manifest.md.
#
# DB: elke component met een eigen managed Postgres (mgzmgr, mgzctl) krijgt zijn STORAGE_POSTGRES_DSN
# via ZAD-substitutievars ($DATABASE_*), in de `aliases`-body — die vars zijn pas bij deploy-tijd
# bekend en kunnen dus niet in bash worden opgelost. txlog is in deze bundel bewust NIET meegenomen
# (TX_LOG_API_ADDRESS staat leeg op mgzmgr/mgzinway — txlog-hardening is later werk).
#
# $DEPLOYMENT_NAME (letterlijk, ZAD-substitutievar) wordt gebruikt voor de per-deployment
# hostnamen van mgzmgr/mgzctl/mgzinway, zodat één (project-brede) component-definitie in elke
# deployment (test, pr-...) de juiste hostnaam krijgt — zie de MGZ*_HOST-opbouw hieronder.
# ZAD substitueert $DEPLOYMENT_NAME en $DATABASE_* UITSLUITEND in `aliases`, niet in `env_vars`
# (zie repo A's upsert-directory.sh). Elke waarde die zo'n substitutievar bevat — ook
# component-onderlinge adressen zoals CONTROLLER_REGISTRATION_API_ADDRESS op mgzmgr — hoort dus
# in `aliases`; alleen waarden zonder substitutievar horen in `env_vars`.
#
# Usage:
#   export ZAD_API_KEY=...                          # niet inline (echo't anders)
#   ./fsc/deploy/zad/upsert-peer.sh validate                       # read-only auth-check
#   ./fsc/deploy/zad/upsert-peer.sh plan   [deployment] [tag]       # toont bodies, muteert niet
#   ./fsc/deploy/zad/upsert-peer.sh apply  [deployment] [tag]       # muteert + pollt tasks
# Env: ZAD_API_KEY (verplicht bij apply), ZAD_PROJECT (mpfm-w3h), ZAD_BASE (zad.rijksapp.nl),
#      ZAD_BASE_DOMAIN (rig.prd1...), ZAD_MANAGER_TAG (ghcr manager-tag, default = tag),
#      ZAD_DIRECTORY_MANAGER_HOST (repo A's directory-manager-host op ZAD), ZAD_PG_SSLMODE (disable),
#      ZAD_MAGAZIJNA_UPSTREAM_URL (endpoint_url van de app-component, zie TODO bij mgzinway).
set -euo pipefail

MODE="${1:?usage: upsert-peer.sh <validate|plan|apply> [deployment=test] [tag=v1.43.7]}"
DEPLOYMENT="${2:-test}"
IMAGE_TAG="${3:-v1.43.7}"                        # OpenFSC stock-tag (controller/inway; default voor de manager-wrapper)
MANAGER_TAG="${ZAD_MANAGER_TAG:-${IMAGE_TAG}}"   # manager-migrate (ghcr) kan een eigen tag hebben
PROJECT="${ZAD_PROJECT:-mpfm-w3h}"
BASE="${ZAD_BASE:-https://zad.rijksapp.nl}"
BASE_DOMAIN="${ZAD_BASE_DOMAIN:-rig.prd1.gn2.quattro.rijksapps.nl}"
PG_SSLMODE="${ZAD_PG_SSLMODE:-disable}"          # managed DB intra-cluster: plaintext (zoals berichtenbox-JDBC)
CLONE_FROM="${ZAD_PEER_CLONE_FROM:-}"            # leeg = geen clone; `peer` wordt éénmalig handmatig (leeg) aangemaakt

case "${MODE}" in validate|plan|apply) ;; *) echo "mode = validate | plan | apply"; exit 1 ;; esac
case "${DEPLOYMENT}" in ""|*[!a-z0-9-]*) echo "ongeldige deployment: '${DEPLOYMENT}'"; exit 1 ;; esac
case "${IMAGE_TAG}" in ""|*[!A-Za-z0-9._-]*) echo "ongeldige image_tag: '${IMAGE_TAG}'"; exit 1 ;; esac
case "${MANAGER_TAG}" in ""|*[!A-Za-z0-9._-]*) echo "ongeldige ZAD_MANAGER_TAG: '${MANAGER_TAG}'"; exit 1 ;; esac
[ "${MODE}" = apply ] && : "${ZAD_API_KEY:?zet ZAD_API_KEY in je env}"

MANAGER_IMAGE="ghcr.io/minbzk/moza-fsc-testnet/manager-migrate:${MANAGER_TAG}"
CONTROLLER_IMAGE="docker.io/federatedserviceconnectivity/controller:${IMAGE_TAG}"
INWAY_IMAGE="docker.io/federatedserviceconnectivity/inway:${IMAGE_TAG}"

# Display-hostnamen (déze deployment, voor de mens-leesbare plan-/apply-output).
MGZMGR_HOST_DISPLAY="mgzmgr-${DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}"
MGZCTL_HOST_DISPLAY="mgzctl-${DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}"
MGZINWAY_HOST_DISPLAY="mgzinway-${DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}"

# Deployment-agnostische hostnamen voor in de component-bodies: ZAD vult $DEPLOYMENT_NAME per
# deployment in (test, pr-...) -> hoort in env_vars/aliases, niet in bash opgelost.
MGZMGR_HOST='mgzmgr-$DEPLOYMENT_NAME-'"${PROJECT}.${BASE_DOMAIN}"
MGZCTL_HOST='mgzctl-$DEPLOYMENT_NAME-'"${PROJECT}.${BASE_DOMAIN}"
MGZINWAY_HOST='mgzinway-$DEPLOYMENT_NAME-'"${PROJECT}.${BASE_DOMAIN}"

# Repo A's directory-deployment op ZAD (project mft-tp9, deployment "test" — zie upsert-directory.sh
# se defaults). TODO(verifieer bij de echte apply): bevestig dat dit nog steeds de actieve
# directory-host is; override met ZAD_DIRECTORY_MANAGER_HOST als de directory elders draait.
DIRECTORY_MANAGER_HOST="${ZAD_DIRECTORY_MANAGER_HOST:-dirmgr-test-mft-tp9.${BASE_DOMAIN}}"

# De peer draait in een EIGEN deployment (`peer`, clobber-veilig los van deploy.yml's
# `test`/`pr-<n>`), dus de inway bereikt de magazijna-app-component cross-deployment via de
# ZAD-ingress-URL (https, :443 — de ingress mapt naar de app-containerpoort; geen poort in de
# URL). Default = de stabiele `test`-deployment van de app; override met ZAD_MAGAZIJNA_DEPLOYMENT
# (bv. een PR-preview `pr-140`) of volledig met ZAD_MAGAZIJNA_UPSTREAM_URL. Dit is GEEN inway-
# env-var (OpenFSC kent geen "upstream" op de inway) maar de endpoint_url die bij de service-
# publicatie op de mgzctl Administration-API wordt meegegeven — zie verify-zad.md, stap (b).
MAGAZIJNA_DEPLOYMENT="${ZAD_MAGAZIJNA_DEPLOYMENT:-test}"
MAGAZIJNA_UPSTREAM_URL="${ZAD_MAGAZIJNA_UPSTREAM_URL:-https://magazijna-${MAGAZIJNA_DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}}"

# --- env-blobs (KEY=value, newline-sep, plain). TLS_*-paden = de bijlage-mounts (UI, ontwerp A). ---
MGZMGR_ENV="$(printf '%s\n' \
  "LOG_TYPE=live" "LOG_LEVEL=info" "AUDITLOG_TYPE=stdout" \
  "GROUP_ID=moza-fbs-test" \
  "DIRECTORY_PEER_ID=00000000000000000010" \
  "TX_LOG_API_ADDRESS=" \
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
  "TLS_INTERNAL_UNAUTHENTICATED_KEY=/etc/fsc/internal/magazijn-a/manager/key.pem")"

# Aliases = env-vars met ZAD-substitutievars ($DEPLOYMENT_NAME voor de eigen hostnaam, $DATABASE_*
# voor de managed Postgres). \$ houdt ze letterlijk (ZAD vult ze per deployment in, niet de shell).
# :443 = de mesh-poort (ingress SNI-passthrough -> pod :8443); OpenFSC eist een expliciete poort in
# het manager-adres (zie upsert-directory.sh), dus niet weglaten.
MGZMGR_ALIASES="$(printf '%s\n' \
  "SELF_ADDRESS=https://${MGZMGR_HOST}:443" \
  "DIRECTORY_MANAGER_ADDRESS=https://${DIRECTORY_MANAGER_HOST}:443" \
  "CONTROLLER_REGISTRATION_API_ADDRESS=https://${MGZCTL_HOST}:443" \
  "STORAGE_POSTGRES_DSN=postgres://\$DATABASE_SERVER_USER:\$DATABASE_PASSWORD@\$DATABASE_SERVER_HOST:5432/\$DATABASE_DB?sslmode=${PG_SSLMODE}")"

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
  "TLS_KEY=/etc/fsc/internal/magazijn-a/controller/key.pem")"
# mgzctl heeft een eigen managed Postgres (los van mgzmgr's DB) -> eigen DSN-alias.
MGZCTL_ALIASES="$(printf '%s\n' \
  "MANAGER_ADDRESS_INTERNAL=https://${MGZMGR_HOST}:443" \
  "STORAGE_POSTGRES_DSN=postgres://\$DATABASE_SERVER_USER:\$DATABASE_PASSWORD@\$DATABASE_SERVER_HOST:5432/\$DATABASE_DB?sslmode=${PG_SSLMODE}")"

MGZINWAY_ENV="$(printf '%s\n' \
  "LOG_TYPE=live" "LOG_LEVEL=info" \
  "NAME=magazijn-a-inway" \
  "GROUP_ID=moza-fbs-test" \
  "TX_LOG_API_ADDRESS=" \
  "LISTEN_ADDRESS=0.0.0.0:8443" \
  "MONITORING_ADDRESS=0.0.0.0:8081" \
  "DISABLE_CRL_CHECKS=true" \
  "TLS_GROUP_ROOT_CERT=/etc/fsc/ca/root.pem" \
  "TLS_GROUP_CERT=/etc/fsc/out/magazijn-a/inway/cert.pem" \
  "TLS_GROUP_KEY=/etc/fsc/out/magazijn-a/inway/key.pem" \
  "TLS_ROOT_CERT=/etc/fsc/internal/magazijn-a/ca/root.pem" \
  "TLS_CERT=/etc/fsc/internal/magazijn-a/inway/cert.pem" \
  "TLS_KEY=/etc/fsc/internal/magazijn-a/inway/key.pem")"
# Geen managed DB, dus geen $DATABASE_*-substitutie nodig; wel drie mesh-adressen met
# $DEPLOYMENT_NAME -> die horen in aliases (env_vars expandeert geen ZAD-substitutievars).
MGZINWAY_ALIASES="$(printf '%s\n' \
  "SELF_ADDRESS=https://${MGZINWAY_HOST}:443" \
  "CONTROLLER_REGISTRATION_API_ADDRESS=https://${MGZCTL_HOST}:443" \
  "MANAGER_INTERNAL_UNAUTHENTICATED_ADDRESS=https://${MGZMGR_HOST}:443")"

# component-body (AddComponentRequest) via jq -> correcte JSON-escaping.
component_body() {  # $1=name $2=image $3=port $4=env  [$5=services_json=[]]  [$6=aliases=""]
  jq -n --arg name "$1" --arg image "$2" --argjson port "$3" --arg env "$4" \
        --argjson services "${5:-[]}" --arg aliases "${6:-}" --arg dep "${DEPLOYMENT}" \
    '{name:$name, image:$image, port:$port, env_vars:$env, deployment_names:[$dep]}
     + (if ($services|length) > 0 then {services:$services} else {} end)
     + (if $aliases == "" then {} else {aliases:$aliases} end)'
}

DEPLOY_BODY="$(jq -n --arg d "${DEPLOYMENT}" --arg cf "${CLONE_FROM}" \
  --arg mgr "${MANAGER_IMAGE}" --arg ctl "${CONTROLLER_IMAGE}" --arg inway "${INWAY_IMAGE}" \
  '{deploymentName:$d, domain_format:"component-deployment-project",
    components:[{reference:"mgzmgr", image:$mgr}, {reference:"mgzctl", image:$ctl}, {reference:"mgzinway", image:$inway}]}
   + (if $cf=="" then {} else {cloneFrom:$cf, forceClone:false} end)')"

MGZMGR_BODY="$(component_body mgzmgr "${MANAGER_IMAGE}" 8443 "${MGZMGR_ENV}" '["postgresql-database"]' "${MGZMGR_ALIASES}")"
MGZCTL_BODY="$(component_body mgzctl "${CONTROLLER_IMAGE}" 8080 "${MGZCTL_ENV}" '["postgresql-database"]' "${MGZCTL_ALIASES}")"
MGZINWAY_BODY="$(component_body mgzinway "${INWAY_IMAGE}" 8443 "${MGZINWAY_ENV}" '[]' "${MGZINWAY_ALIASES}")"

# ---- plan: toon alleen ----
if [ "${MODE}" = plan ]; then
  echo "### deployment (:upsert-deployment)"; echo "${DEPLOY_BODY}"
  echo "### component mgzmgr (manager + managed Postgres)"; echo "${MGZMGR_BODY}"
  echo "### component mgzctl (controller + managed Postgres)"; echo "${MGZCTL_BODY}"
  echo "### component mgzinway (inway)"; echo "${MGZINWAY_BODY}"
  echo "Hostnamen (deployment '${DEPLOYMENT}'): mgzmgr=${MGZMGR_HOST_DISPLAY} mgzctl=${MGZCTL_HOST_DISPLAY} mgzinway=${MGZINWAY_HOST_DISPLAY}"
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

echo "== componenten aanmaken =="
post "mgzmgr"   "/components" "${MGZMGR_BODY}"
post "mgzctl"   "/components" "${MGZCTL_BODY}"
post "mgzinway" "/components" "${MGZINWAY_BODY}"

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

echo "Klaar. Nog handmatig (UI): bijlagen (certs op /etc/fsc/...) + Publicatie op het web modus 2 op mgzmgr."
echo "Hostnamen: mgzmgr=${MGZMGR_HOST_DISPLAY} mgzctl=${MGZCTL_HOST_DISPLAY} mgzinway=${MGZINWAY_HOST_DISPLAY}"
