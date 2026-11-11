#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Unittests voor lib.sh van het Fundament-testcluster: de parsers, de manifests en de values,
# zonder cluster. Wat alleen in een cluster te zien is (draait een plug-in, start een pod), toetst
# charts/fundament-testcluster.sh zelf bij het draaien.

set -euo pipefail

HIER=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
readonly HIER
readonly LIB="$HIER/lib.sh"

# De bestanden waarin elke kubectl- en helm-aanroep via de wrappers met een vaste context moet
# lopen. Een bestand dat ontbreekt telt als fout: een hernoeming mag deze controle niet stil
# uitschakelen.
GECONTROLEERDE_SCRIPTS=("$LIB")

asserties=0
mislukt=0

ok() {
  asserties=$((asserties + 1))
  echo "OK: $1"
}

faal() {
  mislukt=1
  echo "FOUT: $1"
}

gelijk() {
  local wat=$1 verwacht=$2 gemeten=$3

  if [ "$verwacht" = "$gemeten" ]; then
    ok "$wat"
  else
    faal "$wat — verwacht '$verwacht', gemeten '$gemeten'"
  fi
}

bevat() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) ok "$wat" ;;
    *) faal "$wat — '$naald' ontbreekt in: $hooiberg" ;;
  esac
}

bevat_niet() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) faal "$wat — '$naald' staat er toch in: $hooiberg" ;;
    *) ok "$wat" ;;
  esac
}

# Exitcode van een opdracht, zonder dat set -e de suite afbreekt.
code_van() {
  local code=0

  "$@" >/dev/null 2>&1 || code=$?
  echo "$code"
}

# shellcheck source=charts/fundament-testcluster/lib.sh
source "$LIB"

HASH_A=sha256:$(printf 'a%.0s' $(seq 1 64))

echo "== publicatie_lees =="

gelijk "versie en hash uit de publish-regel" "v1.21.3 $HASH_A" \
  "$(publicatie_lees "building...
published plugin=cert-manager version=v1.21.3 hash=$HASH_A id=1 version_id=2 status=SUBMISSION_STATUS_DRAFT")"
gelijk "zonder published-regel: exit 1" 1 "$(code_van publicatie_lees "error: no listing named \"x\"")"
gelijk "hash van 63 tekens: exit 1" 1 \
  "$(code_van publicatie_lees "published plugin=x version=v1 hash=sha256:$(printf 'a%.0s' $(seq 1 63)) id=1")"
gelijk "lege uitvoer: exit 1" 1 "$(code_van publicatie_lees "")"

echo "== functl_fout_soort =="

gelijk "geen listing" geen-listing \
  "$(functl_fout_soort 'Error: no listing named "cloudnativepg" in this organization — pass --create to reserve it')"
gelijk "unauthenticated" niet-ingelogd "$(functl_fout_soort 'unauthenticated: invalid api key')"
gelijk "http 401" niet-ingelogd "$(functl_fout_soort 'request failed: 401 Unauthorized')"
gelijk "iets anders" onbekend "$(functl_fout_soort 'connection refused')"

echo "== plugin_staat_klaar =="

gelijk "Running en ready" 0 "$(code_van plugin_staat_klaar '{"status":{"phase":"Running","ready":true}}')"
gelijk "Installing" 1 "$(code_van plugin_staat_klaar '{"status":{"phase":"Installing","ready":false}}')"
gelijk "Running zonder ready" 1 "$(code_van plugin_staat_klaar '{"status":{"phase":"Running"}}')"
gelijk "lege invoer" 1 "$(code_van plugin_staat_klaar '')"
gelijk "geen JSON" 1 "$(code_van plugin_staat_klaar 'Error from server (NotFound)')"

echo "== sandbox_past =="

MAPPINGS='443/tcp -> 127.0.0.1:9443
6443/tcp -> 0.0.0.0:41837'
gelijk "1.36 met de mapping" 0 "$(code_van sandbox_past v1.36.6+k3s1 "$MAPPINGS" 9443)"
melding=$(sandbox_past v1.33.5+k3s1 "$MAPPINGS" 9443 2>&1 || true)
gelijk "andere versie: exit 1" 1 "$(code_van sandbox_past v1.33.5+k3s1 "$MAPPINGS" 9443)"
bevat "andere versie: melding noemt 1.36" "1.36" "$melding"
melding=$(sandbox_past v1.36.6+k3s1 '6443/tcp -> 0.0.0.0:41837' 9443 2>&1 || true)
gelijk "zonder mapping: exit 1" 1 "$(code_van sandbox_past v1.36.6+k3s1 '6443/tcp -> 0.0.0.0:41837' 9443)"
bevat "zonder mapping: melding noemt HTTPS_POORT" "HTTPS_POORT" "$melding"
gelijk "mapping op 0.0.0.0 telt niet" 1 "$(code_van sandbox_past v1.36.6+k3s1 '443/tcp -> 0.0.0.0:9443' 9443)"
gelijk "andere poort telt niet" 1 "$(code_van sandbox_past v1.36.6+k3s1 '443/tcp -> 127.0.0.1:19443' 9443)"

echo "== poort_vrij =="

vrije_poort=$(python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
python3 -I -m http.server --bind 127.0.0.1 "$vrije_poort" >/dev/null 2>&1 &
server=$!

for _ in $(seq 1 50); do
  ss -ltn "sport = :$vrije_poort" | grep -q LISTEN && break
  sleep 0.1
done

gelijk "bezette poort: exit 1" 1 "$(code_van poort_vrij "$vrije_poort")"
kill "$server"
wait "$server" 2>/dev/null || true
gelijk "vrijgekomen poort: exit 0" 0 "$(code_van poort_vrij "$vrije_poort")"

echo "== manifest_plugininstallatie =="

m=$(manifest_plugininstallatie cloudnativepg v0.2.0 "$HASH_A")
bevat "soort" "kind: PluginInstallation" "$m"
bevat "naam met organisatie" "name: system--cloudnativepg" "$m"
bevat "organisatie" "organizationName: system" "$m"
bevat "versie gequote" 'pluginVersion: "v0.2.0"' "$m"
bevat "hash gequote" "definitionHash: \"$HASH_A\"" "$m"

echo "== manifest_tenant_namespace =="

m=$(manifest_tenant_namespace tnt-fbs--fbs)
bevat "namespace" "kind: Namespace" "$m"
bevat "naam" "name: tnt-fbs--fbs" "$m"
bevat "beheerd door cluster-worker" "fundament.io/managed-by: cluster-worker" "$m"
bevat "namespace-naam is het deel na --" "fundament.io/namespace-name: fbs" "$m"
bevat "cluster-id van de sandbox" "fundament.io/cluster-id: 019b4000-2000-7000-8000-000000000001" "$m"
bevat "organisatie-id" "fundament.io/organization-id: 019b4000-0000-7000-8000-000000000001" "$m"
bevat "project-id" "fundament.io/project-id: " "$m"
bevat "namespace-id" "fundament.io/namespace-id: " "$m"
bevat "pod security restricted" "pod-security.kubernetes.io/enforce: restricted" "$m"
bevat "LimitRange van Fundament" "name: fundament-defaults" "$m"
bevat "standaardlimiet" "default:" "$m"
bevat "standaardrequest" "defaultRequest:" "$m"
bevat_niet "LimitRange zonder maximum" "max:" "$m"
bevat_niet "LimitRange zonder minimum" "min:" "$m"
gelijk "naam zonder -- geweigerd" 1 "$(code_van manifest_tenant_namespace fbs)"

echo "== manifest_projectlid =="

m=$(manifest_projectlid tnt-fbs--fbs)
bevat "ServiceAccount" "kind: ServiceAccount" "$m"
bevat "naam projectlid" "name: projectlid" "$m"
bevat "RoleBinding" "kind: RoleBinding" "$m"
bevat "ClusterRole admin" "name: admin" "$m"
bevat_niet "geen cluster-brede binding" "kind: ClusterRoleBinding" "$m"

echo "== manifest_lokale_ca =="

m=$(manifest_lokale_ca tnt-fbs--fbs)
bevat "zelfondertekende Issuer" "selfSigned: {}" "$m"
bevat "CA-certificaat" "isCA: true" "$m"
bevat "CA-Issuer" "secretName: lokale-ca" "$m"

echo "== manifest_gateway =="

m=$(manifest_gateway tnt-fbs--fbs)
bevat "Gateway web" "name: web" "$m"
bevat "class eg" "gatewayClassName: eg" "$m"
bevat "certificaat via cert-manager" "cert-manager.io/issuer: lokale-ca" "$m"
bevat "listener https" "name: https" "$m"
bevat "protocol HTTPS" "protocol: HTTPS" "$m"
bevat "poort 443" "port: 443" "$m"
bevat "hostnaam" 'hostname: "*.fbs.localhost"' "$m"
bevat "certificaat-Secret" "name: web-tls" "$m"
bevat "routes alleen uit de eigen namespace" "from: Same" "$m"
bevat_niet "geen platte HTTP-listener" "protocol: HTTP
" "$m"

echo "== manifest_cnpg_cluster =="

m=$(manifest_cnpg_cluster db tnt-fbs--fbs)
bevat "CNPG Cluster" "kind: Cluster" "$m"
bevat "apiVersion" "apiVersion: postgresql.cnpg.io/v1" "$m"
bevat "één instance" "instances: 1" "$m"
bevat "catalogus van de plug-in" "name: fundament-postgresql" "$m"
bevat "PostgreSQL 18" "major: 18" "$m"
bevat "geen PDB, zoals de console" "enablePDB: false" "$m"
bevat "label van de plug-in" "app.kubernetes.io/managed-by: fundament-cloudnativepg" "$m"

echo "== manifest_proef_redis =="

m=$(manifest_proef_redis tnt-fbs--fbs docker.io/redis/redis-stack-server@sha256:abc)
bevat "image" "image: docker.io/redis/redis-stack-server@sha256:abc" "$m"
bevat "niet als root" "runAsNonRoot: true" "$m"
bevat "geen capabilities" "drop: [ALL]" "$m"
bevat "seccomp" "type: RuntimeDefault" "$m"
bevat "geen privilege escalation" "allowPrivilegeEscalation: false" "$m"
bevat "wachtwoord uit het Secret" "name: proef-voorzieningen" "$m"
bevat "requirepass" 'requirepass $(REDIS_PASSWORD)' "$m"

echo "== manifest_probe =="

m=$(manifest_probe tnt-fbs--fbs berichtenmagazijn docker.io/curlimages/curl@sha256:def)
bevat "pod-naam" "name: probe-berichtenmagazijn" "$m"
bevat "label dat de NetworkPolicy doorlaat" 'client.fbs.minbzk.nl/berichtenmagazijn: "true"' "$m"
bevat "eenmalig" "restartPolicy: Never" "$m"
bevat "niet als root" "runAsNonRoot: true" "$m"
bevat "geen capabilities" "drop: [ALL]" "$m"
bevat "geen privilege escalation" "allowPrivilegeEscalation: false" "$m"
bevat "health binnen het cluster" "http://berichtenmagazijn.tnt-fbs--fbs.svc/q/health/ready" "$m"

echo "== values_dienst =="

v=$(values_dienst berichtenuitvraag tnt-fbs--fbs)
bevat "uitvraag: Redis in de namespace" "REDIS_HOSTS: redis://proef-redis.tnt-fbs--fbs.svc:6379" "$v"
bevat "uitvraag: Redis-klep, er is geen Redis met TLS" 'FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT: "true"' "$v"
bevat "uitvraag: logboek uit het CNPG-Secret" "secretKeyRef: {name: ldv-uitvraag-app" "$v"
bevat "uitvraag: logboek met TLS" "sslmode=require" "$v"
bevat "uitvraag: wachtwoord van het logboek uit het Secret" "name: LDV_POSTGRES_PASSWORD" "$v"
bevat_niet "uitvraag: geen klep voor het logboek" "FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT" "$v"
bevat "uitvraag: magazijnen via https" "MAGAZIJN_A_URL: https://" "$v"

v=$(values_dienst berichtenmagazijn tnt-fbs--fbs)
bevat_niet "magazijn: geen enkele onveilige klep" "UNSAFE" "$v"
bevat_niet "magazijn: geen allowUnsafeFlags" "allowUnsafeFlags" "$v"
bevat "magazijn: OIN" 'MAGAZIJN_OIN: "00000001003214345000"' "$v"
bevat "magazijn: aanmelden via https" "AANMELD_URL: https://" "$v"

v=$(values_dienst stelselregister tnt-fbs--fbs)
bevat "stelselregister: omgeving lokaal" "STELSELDOCUMENT_OMGEVING: lokaal" "$v"
bevat "stelselregister: uitgever" 'STELSELDOCUMENT_UITGEVER_OIN: "00000000000000001000"' "$v"
bevat_niet "stelselregister: geen onveilige klep" "UNSAFE" "$v"

gelijk "onbekende dienst: exit 1" 1 "$(code_van values_dienst profiel tnt-fbs--fbs)"

echo "== helm_route_args =="

mapfile -t args < <(helm_route_args stelselregister)
gelijk "vijf argumenten, één per regel" 10 "${#args[@]}"
gelijk "route aan" "--set ingress.enabled=true" "${args[0]} ${args[1]}"
bevat "hostnaam" "ingress.hostname=stelselregister.fbs.localhost" "${args[*]}"
bevat "Gateway web" "cluster.gateway.name=web" "${args[*]}"
bevat "listener https" "cluster.gateway.sectionName=https" "${args[*]}"
bevat "route zonder authenticatie, alleen op loopback" "ingress.allowUnauthenticated=true" "${args[*]}"
gelijk "onbekende dienst: exit 1" 1 "$(code_van helm_route_args profiel)"

echo "== kubectl en helm alleen via de wrappers =="

for bestand in "${GECONTROLEERDE_SCRIPTS[@]}"; do
  if [ ! -f "$bestand" ]; then
    faal "$bestand ontbreekt"
    continue
  fi

  # Commentaarregels en de definities van de wrappers zelf tellen niet.
  kaal=$(grep -nE '(^|[^a-zA-Z_-])(kubectl|helm)( |$)' "$bestand" |
    grep -vE '^[0-9]+:[[:space:]]*#' |
    grep -vE '^[0-9]+:[[:space:]]*(kc|kcp|hm)\(\)' || true)
  gelijk "geen kale kubectl/helm in $(basename "$bestand")" "" "$kaal"
done

echo "ASSERTIES=$asserties"
exit "$mislukt"
