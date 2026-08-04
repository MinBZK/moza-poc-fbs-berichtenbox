#!/usr/bin/env bash
# Copyright © MOZa FSC Testnet — Licensed under the EUPL
# Materialiseert de per-endpoint csr.json's van de ZAD-peer `magazijn-a`
# (pki/peers/magazijn-a/<endpoint>/csr.json) uit de ZAD-topologie-env-vars, zodat een PROJECT- of
# DEPLOYMENT-wissel géén handmatige csr-edits meer vraagt: zet ZAD_PROJECT (evt. ZAD_DEPLOYMENT/
# ZAD_BASE_DOMAIN) en her-genereer. De lokale-proof `directory`-peer (pki/peers/directory/) heeft
# geen ZAD-Service-SAN's en blijft statisch — die raken we hier niet aan.
#
# Waarom nodig: elke internal-cert draagt cluster-interne Service-DNS als SAN (multi-poort-fix,
# 2026-07-13). Die namen bevatten het ZAD-project (namespace `rig-prd-<project>`) + de deployment,
# net als de externe mesh-host. Deze env-vars zijn DEZELFDE die deploy/zad/upsert-peer.sh gebruikt
# (zelfde defaults), zodat cert-SAN's en de adressen in de deploy per definitie sporen.
#
# De peer-IDENTITEIT (OIN, O, endpoints, component-korte-namen, de statische fsc-test.local-namen)
# staat hieronder als tabel; alléén de omgeving-afhankelijke SAN's worden uit env afgeleid.
#
# Wordt automatisch aangeroepen door issue.sh (vóór de cfssl-uitgifte); los draaibaar voor een
# git-diff van de csr's ná een env-wissel. Vereist: jq.
set -euo pipefail

BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

# --- ZAD-topologie (DEZELFDE env-vars + defaults als deploy/zad/upsert-peer.sh) ---------------------
PROJECT="${ZAD_PROJECT:-mpfm-w3h}"
DEPLOYMENT="${ZAD_DEPLOYMENT:-fsc-magazijna}"              # upsert-peer.sh neemt dit als arg (zelfde default)
BASE_DOMAIN="${ZAD_BASE_DOMAIN:-rig.prd1.gn2.quattro.rijksapps.nl}"
NAMESPACE="${ZAD_NAMESPACE:-rig-prd-${PROJECT}}"          # OpenShift-namespace = rig-prd-<project>
CLUSTER_DOMAIN="${ZAD_CLUSTER_DOMAIN:-svc.cluster.local}"

case "${PROJECT}"    in ""|*[!a-z0-9-]*) echo "ongeldig ZAD_PROJECT: '${PROJECT}'" >&2;    exit 1 ;; esac
case "${DEPLOYMENT}" in ""|*[!a-z0-9-]*) echo "ongeldig ZAD_DEPLOYMENT: '${DEPLOYMENT}'" >&2; exit 1 ;; esac

# --- Peer-identiteit (statisch) --------------------------------------------------------------------
PEER="magazijn-a"
OIN="00000000000000100000"                                # = subject.serialNumber = Peer ID
# endpoint:component-korte-naam (de ZAD-component + Service heet `<deployment>-<short>`). Volgorde
# bepaalt de uitvoer-volgorde; spiegelt de MGZ*_SVC-namen in upsert-peer.sh.
ENDPOINTS=( "manager:magazijna-fscmgr" "controller:magazijna-fscctl" "inway:magazijna-fscinway" "txlog:magazijna-fsctxlog" )

# Genereer per endpoint de csr.json. SAN-volgorde: peer-identiteit (alleen manager) ->
# <endpoint>.<peer>.fsc-test.local -> externe mesh-host -> Service-kortnaam -> Service-FQDN.
for spec in "${ENDPOINTS[@]}"; do
  endpoint="${spec%%:*}"
  short="${spec##*:}"

  hosts=()
  # De manager ís de peer-identiteit in de mesh -> draagt óók de kale peer-FQDN als SAN.
  [ "${endpoint}" = manager ] && hosts+=( "${PEER}.fsc-test.local" )
  hosts+=( "${endpoint}.${PEER}.fsc-test.local" )                                  # interne/lokale naam
  hosts+=( "${short}-${DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}" )                     # extern (mesh, ingress)
  hosts+=( "${DEPLOYMENT}-${short}" )                                              # Service-kortnaam (intra-ns)
  hosts+=( "${DEPLOYMENT}-${short}.${NAMESPACE}.${CLUSTER_DOMAIN}" )               # Service-FQDN (cross-project)

  hosts_json="$(printf '%s\n' "${hosts[@]}" | jq -R . | jq -s .)"
  out="${BASE_DIR}/peers/${PEER}/${endpoint}/csr.json"
  mkdir -p "$(dirname "${out}")"
  jq -n --arg cn "${endpoint}.${PEER}.fsc-test.local" --arg sn "${OIN}" --arg o "${PEER}" \
        --argjson hosts "${hosts_json}" \
    '{CN:$cn, key:{algo:"rsa", size:4096}, hosts:$hosts, serialnumber:$sn, names:[{O:$o, C:"NL"}]}' \
    > "${out}"
  echo "csr ${PEER}/${endpoint}: $(printf '%s ' "${hosts[@]}")"
done
echo "OK: csr.json's gegenereerd voor project=${PROJECT} deployment=${DEPLOYMENT} (namespace ${NAMESPACE})"
