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
GECONTROLEERDE_SCRIPTS=("$LIB" "$HIER/../fundament-testcluster.sh")

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

echo "== publicatie_plugin_id =="

gelijk "plugin-id uit de publish-regel" 019b4000-3000-7000-8000-000000000002 \
  "$(publicatie_plugin_id "published plugin=cert-manager version=1.21.3 hash=$HASH_A id=019b4000-3000-7000-8000-000000000002 version_id=0a1 status=SUBMISSION_STATUS_PENDING")"
gelijk "zonder id: exit 1" 1 "$(code_van publicatie_plugin_id "published plugin=x version=1 hash=$HASH_A")"

echo "== bestaande_versie =="

gelijk "versie uit already_exists" 1.21.3 \
  "$(bestaande_versie 'Error: already_exists: version "1.21.3" already exists for this plugin')"
gelijk "andere fout: exit 1" 1 "$(code_van bestaande_versie 'Error: connection refused')"

echo "== versie_uit_catalogus =="

CATALOGUS='[{"organization":"system","plugin":"cert-manager","plugin_id":"019b4000-3000-7000-8000-000000000002","version":"1.21.3","status":"draft"},
{"organization":"system","plugin":"cloudnativepg","plugin_id":"019b4000-3000-7000-8000-000000000003","version":"v0.2.0","status":"approved"},
{"organization":"acme-corp","plugin":"cloudnativepg","plugin_id":"019b4000-3000-7000-8000-000000000009","version":"v0.2.0","status":"pending"}]'
gelijk "status en id van een concept" "draft 019b4000-3000-7000-8000-000000000002" \
  "$(versie_uit_catalogus "$CATALOGUS" cert-manager 1.21.3)"
gelijk "alleen van de organisatie system" "approved 019b4000-3000-7000-8000-000000000003" \
  "$(versie_uit_catalogus "$CATALOGUS" cloudnativepg v0.2.0)"
gelijk "onbekende versie: exit 1" 1 "$(code_van versie_uit_catalogus "$CATALOGUS" cert-manager 9.9.9)"
gelijk "lege catalogus: exit 1" 1 "$(code_van versie_uit_catalogus '[]' cert-manager 1.21.3)"

echo "== hash_uit_catalogus =="

gelijk "hash uit GetPluginDefinition" "$HASH_A" \
  "$(hash_uit_catalogus "{\"manifest\":\"YQ==\",\"definitionHash\":\"$HASH_A\"}")"
gelijk "Connect-fout: exit 1" 1 "$(code_van hash_uit_catalogus '{"code":"not_found","message":"plugin definition not found"}')"
gelijk "afgekapte hash: exit 1" 1 "$(code_van hash_uit_catalogus '{"definitionHash":"sha256:abc"}')"

echo "== functl_fout_soort =="

gelijk "geen listing" geen-listing \
  "$(functl_fout_soort 'Error: no listing named "cloudnativepg" in this organization — pass --create to reserve it')"
gelijk "unauthenticated" niet-ingelogd "$(functl_fout_soort 'unauthenticated: invalid api key')"
gelijk "http 401" niet-ingelogd "$(functl_fout_soort 'request failed: 401 Unauthorized')"
gelijk "nog nooit ingelogd" niet-ingelogd \
  "$(functl_fout_soort "Error: not authenticated: run 'functl auth login' to authenticate")"
gelijk "versie bestaat al" bestaat-al \
  "$(functl_fout_soort 'Error: already_exists: version "1.21.3" already exists for this plugin')"
gelijk "iets anders" onbekend "$(functl_fout_soort 'connection refused')"
gelijk "401 in een poortnummer is geen 401" onbekend "$(functl_fout_soort 'dial tcp 127.0.0.1:34012: connection refused')"

echo "== api_key_uit_antwoord =="

gelijk "token uit CreateAPIKey" fun_abc123 \
  "$(api_key_uit_antwoord '{"id":"01a1","token":"fun_abc123","tokenPrefix":"fun_abc"}')"
gelijk "Connect-fout: exit 1" 1 \
  "$(code_van api_key_uit_antwoord '{"code":"permission_denied","message":"not allowed"}')"
gelijk "lege token: exit 1" 1 "$(code_van api_key_uit_antwoord '{"id":"01a1","token":""}')"
gelijk "geen JSON: exit 1" 1 "$(code_van api_key_uit_antwoord '<html>502</html>')"

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
gelijk "extra mapping op 0.0.0.0 naast loopback telt niet" 1 \
  "$(code_van sandbox_past v1.36.6+k3s1 '443/tcp -> 127.0.0.1:9443
443/tcp -> 0.0.0.0:9444' 9443)"

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

gelijk "zonder hash: exit 1" 1 "$(code_van manifest_plugininstallatie cloudnativepg v0.2.0 "")"
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
bevat_niet "geen beheerlabel van cluster-worker" "fundament.io/managed-by" "$m"
bevat_niet "geen namespace-id van cluster-worker" "fundament.io/namespace-id" "$m"
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
bevat "probe herhaalt tot de policy hem kent" "--retry-all-errors" "$m"
bevat "met een grens" '--retry, "15"' "$m"

m=$(manifest_probe tnt-fbs--fbs berichtenmagazijn docker.io/curlimages/curl@sha256:def zonder-label)
bevat "probe zonder label: eigen naam" "name: probe-zonder-label-berichtenmagazijn" "$m"
bevat_niet "probe zonder label: geen clientlabel" "client.fbs.minbzk.nl" "$m"
# Ook zonder label herhalen: een nieuwe pod staat pas na een vertraging in de lijsten van de
# policy-controller, en tot dan zou een te ruime policy er net zo uitzien als een weigering. curl
# stopt bij het eerste antwoord, dus een policy die toch doorlaat geeft alsnog 200.
bevat "probe zonder label herhaalt ook" "--retry-all-errors" "$m"
bevat "probe zonder label: zelfde doel" "http://berichtenmagazijn.tnt-fbs--fbs.svc/q/health/ready" "$m"

echo "== is_commit_sha =="

gelijk "volledige SHA" 0 "$(code_van is_commit_sha b9c1253103f0121ec061507fc241580b94bc3ab4)"
gelijk "korte SHA" 1 "$(code_van is_commit_sha b9c12531)"
gelijk "branchnaam" 1 "$(code_van is_commit_sha master)"
gelijk "hoofdletters" 1 "$(code_van is_commit_sha B9C1253103F0121EC061507FC241580B94BC3AB4)"

echo "== k3d_config_loopback =="

K3D_CONFIG='ports:
  - port: 127.0.0.1:8443:443
    nodeFilters:
      - loadbalancer
  - port: 54328:54328
    nodeFilters:
      - loadbalancer
registries:
  create:
    name: "registry.localhost"
    host: "0.0.0.0"
    hostPort: "5111"'
c=$(k3d_config_loopback <<<"$K3D_CONFIG")
bevat "database-poort op loopback" "port: 127.0.0.1:54328:54328" "$c"
bevat "registry op loopback" 'host: "127.0.0.1"' "$c"
bevat "loopback-poort blijft" "port: 127.0.0.1:8443:443" "$c"
bevat_niet "geen 0.0.0.0 meer" "0.0.0.0" "$c"
bevat "de rest blijft" 'hostPort: "5111"' "$c"
gelijk "poort met hostIP blijft één keer geprefixt" 1 "$(grep -c '127.0.0.1:8443' <<<"$c")"

echo "== image_uit_definitie =="

gelijk "image met digest uit de manifest" \
  "localhost:5112/cloudnativepg-plugin@sha256:${HASH_A#sha256:}" \
  "$(image_uit_definitie "spec:
  deployment:
    image: localhost:5112/cloudnativepg-plugin@${HASH_A}
    imagePullPolicy: IfNotPresent")"
gelijk "zonder image: exit 1" 1 "$(code_van image_uit_definitie "spec: {}")"
gelijk "image zonder digest: exit 1" 1 "$(code_van image_uit_definitie "    image: localhost:5112/x:latest")"

echo "== geheim_bestand =="

bestand=$(mktemp "${TMPDIR:-/tmp}/geheim.XXXX")
geheim_bestand "$bestand"
gelijk "48 hextekens" 48 "$(wc -c <"$bestand" | tr -d ' ')"
gelijk "geen newline aan het eind" "" "$(tail -c1 "$bestand" | tr -d '[:xdigit:]')"
rm -f "$bestand"

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
gelijk "vijf --set-paren, één woord per regel" 10 "${#args[@]}"
gelijk "route aan" "--set ingress.enabled=true" "${args[0]} ${args[1]}"
bevat "hostnaam" "ingress.hostname=stelselregister.fbs.localhost" "${args[*]}"
bevat "Gateway web" "cluster.gateway.name=web" "${args[*]}"
bevat "listener https" "cluster.gateway.sectionName=https" "${args[*]}"
bevat "route zonder authenticatie, alleen op loopback" "ingress.allowUnauthenticated=true" "${args[*]}"
gelijk "onbekende dienst: exit 1" 1 "$(code_van helm_route_args profiel)"

echo "== manifest_probe logt ook de exitcode =="

bevat "probe logt status, exitcode en connections" "%{http_code} %{exitcode} %{num_connects}" "$(manifest_probe tnt-fbs--fbs stelselregister img zonder-label)"
bevat "probe met label idem" "%{http_code} %{exitcode} %{num_connects}" "$(manifest_probe tnt-fbs--fbs stelselregister img)"

echo "== enkele_replica =="

bestand=$(mktemp "${TMPDIR:-/tmp}/dienst.XXXX")
printf 'dienst:\n  # uitleg\n  singleReplica: true\n' >"$bestand"
gelijk "singleReplica: true" 0 "$(code_van enkele_replica "$bestand")"
printf 'dienst:\n  singleReplica: false\n' >"$bestand"
gelijk "singleReplica: false" 1 "$(code_van enkele_replica "$bestand")"
printf 'dienst:\n  # singleReplica: true staat hier alleen in commentaar\n' >"$bestand"
gelijk "alleen in commentaar" 1 "$(code_van enkele_replica "$bestand")"
rm -f "$bestand"
gelijk "het echte stelselregister" 0 "$(code_van enkele_replica "$HIER/../fbs-dienst/diensten/stelselregister.yaml")"
gelijk "het echte magazijn" 1 "$(code_van enkele_replica "$HIER/../fbs-dienst/diensten/berichtenmagazijn.yaml")"

echo "== probe_geweigerd =="

# Een probe logt "<http-status> <exitcode van curl> <aantal connections>". Geweigerd is alleen:
# geen antwoord, geen TCP-connection, en curl kreeg de connection geweigerd (7), zoals de
# policy-controller van k3s doet. Een timeout (28) is meerduidig (een trage dienst, een DNS-timeout)
# en telt niet.
gelijk "connection geweigerd (7): geweigerd" 0 "$(code_van probe_geweigerd "000 7 0")"
gelijk "timeout zonder connection (28): geen bewijs" 1 "$(code_van probe_geweigerd "000 28 0")"
gelijk "timeout mét connection: een trage dienst, geen weigering" 1 "$(code_van probe_geweigerd "000 28 1")"
gelijk "geweigerd maar wel een connection geteld: geen bewijs" 1 "$(code_van probe_geweigerd "000 7 1")"
gelijk "naam niet gevonden (6): geen bewijs" 1 "$(code_van probe_geweigerd "000 6 0")"
gelijk "een antwoord: niet geweigerd" 1 "$(code_van probe_geweigerd "200 0 1")"
gelijk "zonder aantal connections: geen bewijs" 1 "$(code_van probe_geweigerd "000 7")"
gelijk "probe-pod zonder uitvoer: geen bewijs" 1 "$(code_van probe_geweigerd "geen-uitvoer")"
gelijk "lege uitvoer: geen bewijs" 1 "$(code_van probe_geweigerd "")"
gelijk "alleen 000, zonder exitcode: geen bewijs" 1 "$(code_van probe_geweigerd "000")"

echo "== poorten_naar_buiten =="

gelijk "alles op loopback of het bridge-adres: niets" "" "$(poorten_naar_buiten <<'EOF'
k3d-fundament-serverlb|80/tcp, 127.0.0.1:54328->54328/tcp, 127.0.0.1:8443->443/tcp, 0.0.0.0:43917->6443/tcp, [::]:43917->6443/tcp
fun-authn-relay|172.25.0.1:38081->8080/tcp
k3d-fundament-server-0|
registry.localhost|127.0.0.1:5111->5000/tcp
EOF
)"
gelijk "database op alle adressen" "k3d-fundament-serverlb 0.0.0.0:54328->54328/tcp" \
  "$(poorten_naar_buiten <<<'k3d-fundament-serverlb|127.0.0.1:8443->443/tcp, 0.0.0.0:54328->54328/tcp, 0.0.0.0:43917->6443/tcp')"
gelijk "IPv6 in beide notaties" "registry.localhost [::]:5111->5000/tcp
registry.localhost :::5112->5000/tcp" \
  "$(poorten_naar_buiten <<<'registry.localhost|[::]:5111->5000/tcp, :::5112->5000/tcp')"
gelijk "meerdere containers, elk apart" "a 0.0.0.0:1->1/tcp
b 0.0.0.0:2->2/udp" "$(poorten_naar_buiten <<'EOF'
a|0.0.0.0:1->1/tcp
b|0.0.0.0:2->2/udp, 127.0.0.1:3->3/tcp
EOF
)"
gelijk "geen invoer: niets" "" "$(poorten_naar_buiten </dev/null)"

echo "== poortbindingen_uit_inspect =="

# De geconfigureerde bindingen uit docker inspect, ook van een gestopte container: docker ps toont
# daar geen poorten, en bij de start komen ze toch. Een lege HostIp is alle adressen.
INSPECT='[
 {"Name":"/k3d-fundament-serverlb","HostConfig":{"PortBindings":{"443/tcp":[{"HostIp":"127.0.0.1","HostPort":"8443"}],"6443/tcp":[{"HostIp":"0.0.0.0","HostPort":"43917"}]}}},
 {"Name":"/gestopt","HostConfig":{"PortBindings":{"54328/tcp":[{"HostIp":"","HostPort":"54328"}]}}},
 {"Name":"/zes","HostConfig":{"PortBindings":{"5000/tcp":[{"HostIp":"::","HostPort":"5111"}]}}},
 {"Name":"/zonder","HostConfig":{"PortBindings":null}},
 {"Name":"/leeg","HostConfig":{"PortBindings":{}}}
]'
b=$(poortbindingen_uit_inspect <<<"$INSPECT")
bevat "loopback blijft loopback" "k3d-fundament-serverlb|127.0.0.1:8443->443/tcp" "$b"
bevat "lege HostIp wordt 0.0.0.0" "gestopt|0.0.0.0:54328->54328/tcp" "$b"
gelijk "samen met poorten_naar_buiten: gestopt en IPv6, niet de API" "gestopt 0.0.0.0:54328->54328/tcp
zes :::5111->5000/tcp" "$(poorten_naar_buiten <<<"$b")"
code=$(code_van poortbindingen_uit_inspect <<<"geen json")
[ "$code" -ne 0 ] && ok "kapotte JSON: exit niet 0" || faal "kapotte JSON gaf exit 0"

echo "== kubectl_niet_gevonden =="

gelijk "NotFound" 0 "$(code_van kubectl_niet_gevonden 'Error from server (NotFound): deployments.apps "organization-api" not found')"
gelijk "API weigert de connection" 1 "$(code_van kubectl_niet_gevonden 'The connection to the server 127.0.0.1:43917 was refused - did you specify the right host or port?')"
gelijk "API onbereikbaar" 1 "$(code_van kubectl_niet_gevonden 'Unable to connect to the server: dial tcp 127.0.0.1:43917: connect: connection refused')"
gelijk "geen rechten" 1 "$(code_van kubectl_niet_gevonden 'Error from server (Forbidden): deployments.apps "organization-api" is forbidden')"
gelijk "lege uitvoer" 1 "$(code_van kubectl_niet_gevonden '')"

echo "== het script zelf, met stubs =="

SCRIPT="$HIER/../fundament-testcluster.sh"
TESTCACHE=$(mktemp -d)
trap 'rm -rf "$TESTCACHE"' EXIT

# Draait een stuk bash met het script gesourcet, in een eigen shell; stubs in $1 overschrijven
# functies van het script.
in_script() {
  XDG_CACHE_HOME="$TESTCACHE" bash -c "source '$SCRIPT'; $1" 2>&1
}

# Aangeroepen met ||, zoals het script dat doet: daar staat errexit in de functie uit.
uitvoer=$(in_script 'WERK=$(mktemp -d); TAG=main-1234567; hm() { return 1; }; installeer_dienst stelselregister || echo "MISLUKT GEMELD"')
bevat "mislukte helm-installatie: de aanroeper ziet het" "MISLUKT GEMELD" "$uitvoer"
bevat_niet "mislukte helm-installatie: geen OK" "OK:" "$uitvoer"

code=0
uitvoer=$(in_script 'kubectl get pods') || code=$?
gelijk "kale kubectl: exit 2" 2 "$code"
bevat "kale kubectl: melding" "zonder vaste context" "$uitvoer"
code=0
uitvoer=$(in_script 'helm list') || code=$?
gelijk "kale helm: exit 2" 2 "$code"

AF_STUBS='fj() { echo "fj $*"; }; git() { echo "git $*"; }; fundament_worktree() { echo "worktree gemaakt" >&2; };'
code=0
uitvoer=$(in_script "$AF_STUBS sandbox_bestaat() { false; }; platform_bestaat() { false; }; af nee") || code=$?
gelijk "af zonder iets: exit 0" 0 "$code"
bevat "af zonder iets: melding" "niets op te ruimen" "$uitvoer"

code=0
uitvoer=$(in_script "$AF_STUBS sandbox_bestaat() { false; }; platform_bestaat() { true; }; af ja") || code=$?
gelijk "af --met-platform zonder worktree: exit 0" 0 "$code"
bevat "af --met-platform zonder worktree: worktree eerst terug" "worktree gemaakt" "$uitvoer"
bevat "af --met-platform zonder worktree: platform weg" "fj cluster-delete" "$uitvoer"
bevat_niet "af --met-platform: niet 'niets op te ruimen'" "niets op te ruimen" "$uitvoer"

code=0
uitvoer=$(in_script "$AF_STUBS sandbox_bestaat() { true; }; sandbox_van_ons() { false; }; platform_bestaat() { false; }; af nee") || code=$?
gelijk "af met een vreemde sandbox: exit 2" 2 "$code"
bevat_niet "af met een vreemde sandbox: niet weggehaald" "fj plugins cluster-delete" "$uitvoer"

code=0
uitvoer=$(in_script "$AF_STUBS sandbox_bestaat() { true; }; sandbox_van_ons() { true; }; platform_bestaat() { true; }; af nee") || code=$?
gelijk "af met eigen sandbox: exit 0" 0 "$code"
bevat "af met eigen sandbox: sandbox weg" "fj plugins cluster-delete" "$uitvoer"
bevat_niet "af zonder --met-platform: platform blijft" "fj cluster-delete" "$uitvoer"
bevat "af zonder --met-platform: wijst op de vlag" "af --met-platform" "$uitvoer"

# --- controleer_dienst: het oordeel per controle ---

# kc beantwoordt de rollout ($ROLLOUT), en "<spec.replicas> <readyReplicas>" ($STAAT); de probes
# en de Gateway geven wat de variabelen zeggen. GATEWAY_WACHTTIJD=0: niet drie minuten wachten.
# De gereedheid van de pods ($STEMPEL, na de probe zonder label $STEMPEL_NA) geeft kc bij een
# "get pods"; zie gereedheid_stempel.
CONTROLE_STUBS='GATEWAY_WACHTTIJD=0; STEMPEL="p1 0 True t1"; STEMPEL_NA=;
kc() { case "$*" in *"rollout status"*) return "${ROLLOUT:-0}" ;;
  *"get pods"*) if [ -f "$WERK/na" ]; then echo "${STEMPEL_NA:-$STEMPEL}"; else echo "$STEMPEL"; fi ;;
  *jsonpath*) echo "$STAAT" ;; *) return 0 ;; esac; };
WERK=$(mktemp -d);
probe_code() { if [ "$2" = met-label ]; then echo "$MET"; else touch "$WERK/na"; echo "$ZONDER"; fi; };
via_gateway() { if [ "$2" = /openapi.json ]; then echo "$OPENAPI"; else echo "$Q"; fi; };'

controle() {
  in_script "$CONTROLE_STUBS $1; controleer_dienst stelselregister; echo \"FOUTEN=\$fouten\""
}

GOED='STAAT="2 2"; MET="200 0 1"; ZONDER="000 7 0"; OPENAPI=200; Q=404'
uitvoer=$(controle "$GOED")
bevat "controleer: alles in orde, geen fouten" "FOUTEN=0" "$uitvoer"
gelijk "controleer: vijf OK-regels" 5 "$(grep -c '^OK:' <<<"$uitvoer")"
uitvoer=$(controle "$GOED; STAAT='2 1'")
bevat "controleer: één van twee replica's niet gereed is een fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; STAAT='0 '")
bevat "controleer: nul replica's is een fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; ROLLOUT=1")
bevat "controleer: rollout niet klaar is een fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; MET='000 7 0'")
bevat "controleer: probe met label zonder antwoord is een fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; ZONDER='000 6 0'")
bevat "controleer: probe zonder label die de naam niet vindt is een fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; ZONDER='200 0 1'")
bevat "controleer: probe zonder label die binnenkomt is een fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; ZONDER='000 28 1'")
bevat "controleer: probe zonder label met connection maar zonder antwoord is een fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; OPENAPI=503")
bevat "controleer: Gateway geeft 503 is een fout" "FOUTEN=1" "$uitvoer"
# Een Service zonder gereede endpoints weigert ook met "000 7 0". Een pod die tijdens de probe
# zonder label even niet gereed was, of herstartte, maakt de weigering dus waardeloos.
uitvoer=$(controle "$GOED; STEMPEL_NA='p1 0 True t2'")
bevat "controleer: gereedheid wisselde tijdens de probe zonder label: fout" "FOUTEN=1" "$uitvoer"
bevat_niet "controleer: gereedheid wisselde: geen OK voor de weigering" "OK: stelselregister weigert" "$uitvoer"
uitvoer=$(controle "$GOED; STEMPEL_NA='p1 1 True t1'")
bevat "controleer: pod herstartte tijdens de probe zonder label: fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; STEMPEL='p1 0 False t1'")
bevat_niet "controleer: pod niet gereed vóór de probe zonder label: geen OK voor de weigering" "OK: stelselregister weigert" "$uitvoer"
bevat "controleer: pod niet gereed vóór de probe zonder label: fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; STEMPEL=''")
bevat_niet "controleer: geen pods vóór de probe zonder label: geen OK voor de weigering" "OK: stelselregister weigert" "$uitvoer"
bevat "controleer: geen pods vóór de probe zonder label: fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; STEMPEL='p1 0 True t1
p2 0 False t1'")
bevat "controleer: één van twee pods niet gereed vóór de probe zonder label: fout" "FOUTEN=1" "$uitvoer"
uitvoer=$(controle "$GOED; STEMPEL='p1 0 True t1
p2 0 True t1'")
bevat "controleer: twee pods die gereed blijven: in orde" "FOUTEN=0" "$uitvoer"
uitvoer=$(controle "$GOED; Q=200")
bevat "controleer: /q via de Gateway bereikbaar is een fout" "FOUTEN=1" "$uitvoer"

# Een probe-pod die niet start (PSA, image, scheduling), met de echte probe_code: dat mag niet
# lezen als "de NetworkPolicy weigert".
uitvoer=$(in_script 'GATEWAY_WACHTTIJD=0;
kc() { case "$*" in *"rollout status"*) return 0 ;; *"get pods"*) echo "p1 0 True t1" ;; *jsonpath*) echo "1 1" ;; *apply*) return 1 ;; *logs*) return 1 ;; *) return 0 ;; esac; };
wacht_tot() { shift 2; "$@"; }; pod_klaar() { return 1; };
via_gateway() { if [ "$2" = /openapi.json ]; then echo 200; else echo 404; fi; };
controleer_dienst stelselregister; echo "FOUTEN=$fouten"')
bevat_niet "probe-pod start niet: geen OK voor de weigering" "OK: stelselregister weigert" "$uitvoer"
bevat "probe-pod start niet: beide probes fout" "FOUTEN=2" "$uitvoer"
bevat "probe-pod start niet: de fout komt van de probe zelf" "gaf geen-uitvoer aan een pod zonder clientlabel" "$uitvoer"

# Een probe-pod die niet weg te halen of niet aan te maken is: dan zou het script de fase en de logs
# van een oude pod met dezelfde naam lezen. In een command substitution, zoals het script hem
# aanroept: daar geldt errexit niet.
for faalt in delete apply; do
  # apply leest stdin, zoals kubectl: anders meldt cat een kapotte pipe (op de CI-runner wordt
  # SIGPIPE genegeerd) en komt die melding in de uitvoer.
  uitvoer=$(in_script "kc() { case \"\$*\" in *delete*) [ $faalt != delete ] ;; *apply*) cat >/dev/null; [ $faalt != apply ] ;; *logs*) echo '000 7 0' ;; *) return 0 ;; esac; };
wacht_tot() { shift 2; \"\$@\"; }; pod_klaar() { return 0; }; echo \"\$(probe_code stelselregister zonder-label)\"")
  gelijk "probe_code: $faalt faalt geeft geen-uitvoer" "geen-uitvoer" "$uitvoer"
done

# Na een geslaagde apply: een pod die nooit klaar wordt, of klaar is zonder logs, levert geen
# uitvoer op die als weigering kan lezen; echte logs komen ongewijzigd door. De stub leest stdin
# bij apply, zoals kubectl: anders breekt de pipe en faalt de apply al.
probe_met() {
  in_script "kc() { case \"\$*\" in *logs*) printf '%s' '$2' ;; *apply*) cat >/dev/null ;; *) return 0 ;; esac; };
wacht_tot() { shift 2; \"\$@\"; }; pod_klaar() { return $1; }; echo \"\$(probe_code stelselregister zonder-label)\""
}
gelijk "probe_code: pod wordt nooit klaar" "geen-uitvoer" "$(probe_met 1 '000 7 0')"
gelijk "probe_code: pod klaar zonder logs" "geen-uitvoer" "$(probe_met 0 '')"
gelijk "probe_code: logs komen door" "000 7 0" "$(probe_met 0 '000 7 0')"

# --- voorcontrole ---

VOOR_STUBS='poort_vrij() { return "${BEZET:-0}"; }; sandbox_bestaat() { [ "${SANDBOX:-nee}" = ja ]; };
sandbox_van_ons() { [ "${VAN_ONS:-ja}" = ja ]; }; platform_bestaat() { [ "${PLATFORM:-nee}" = ja ]; };
docker_poorten() { printf "%s\n" "${POORTEN:-}"; };'
code=0
uitvoer=$(in_script "$VOOR_STUBS voorcontrole") || code=$?
gelijk "voorcontrole: niets staat er, poort vrij: exit 0" 0 "$code"
code=0
uitvoer=$(in_script "$VOOR_STUBS BEZET=1; voorcontrole") || code=$?
gelijk "voorcontrole: poort bezet: exit 2" 2 "$code"
code=0
uitvoer=$(in_script "$VOOR_STUBS SANDBOX=ja; VAN_ONS=nee; voorcontrole") || code=$?
gelijk "voorcontrole: vreemde sandbox: exit 2" 2 "$code"
code=0
uitvoer=$(in_script "$VOOR_STUBS SANDBOX=ja; voorcontrole") || code=$?
gelijk "voorcontrole: eigen sandbox: exit 0" 0 "$code"
code=0
uitvoer=$(in_script "$VOOR_STUBS PLATFORM=ja; POORTEN='k3d-fundament-serverlb|127.0.0.1:8443->443/tcp, 0.0.0.0:43917->6443/tcp'; voorcontrole") || code=$?
gelijk "voorcontrole: platform op loopback: exit 0" 0 "$code"
code=0
uitvoer=$(in_script "$VOOR_STUBS PLATFORM=ja; POORTEN='k3d-fundament-serverlb|0.0.0.0:54328->54328/tcp'; voorcontrole") || code=$?
gelijk "voorcontrole: platform met poorten op alle adressen: exit 2" 2 "$code"
bevat "voorcontrole: noemt de poort" "0.0.0.0:54328" "$uitvoer"
code=0
uitvoer=$(in_script "$VOOR_STUBS SANDBOX=ja; POORTEN='k3d-fundament-plugin-serverlb|0.0.0.0:5112->5000/tcp'; voorcontrole") || code=$?
gelijk "voorcontrole: alleen een sandbox, met poorten op alle adressen: exit 2" 2 "$code"
code=0
uitvoer=$(in_script "$VOOR_STUBS POORTEN='registry.localhost|0.0.0.0:5111->5000/tcp'; voorcontrole") || code=$?
gelijk "voorcontrole: alleen een achtergebleven registry op alle adressen: exit 2" 2 "$code"

# Een docker die faalt, levert geen lege lijst op die als "in orde" leest.
code=0
uitvoer=$(in_script 'docker() { return 1; }; alleen_loopback') || code=$?
gelijk "alleen_loopback: docker faalt: niet in orde" 1 "$code"

# --- DNS in de sandbox-node ---

# Na een wissel van netwerk stuurt de node zijn DNS nog naar de server van het vorige netwerk, en elke
# nieuwe pod blijft in ImagePullBackOff hangen. Dat moet vroeg en met de remedie falen.
code=0
uitvoer=$(in_script 'docker() { return 1; }; wacht_tot() { shift 2; "$@"; }; sandbox_dns_werkt') || code=$?
gelijk "sandbox_dns_werkt: geen DNS: exit 1" 1 "$code"
# Via k3d, niet docker restart: alleen k3d cluster start zet host.k3d.internal terug in CoreDNS,
# en zonder die naam bereikt de plugin-controller het platform niet meer.
bevat "sandbox_dns_werkt: noemt de herstart via k3d" "k3d cluster stop fundament-plugin fundament" "$uitvoer"
bevat_niet "sandbox_dns_werkt: geen docker restart" "docker restart" "$uitvoer"
code=0
uitvoer=$(in_script 'docker() { echo "docker $*"; }; wacht_tot() { shift 2; "$@"; }; sandbox_dns_werkt') || code=$?
gelijk "sandbox_dns_werkt: DNS werkt: exit 0" 0 "$code"
bevat "sandbox_dns_werkt: zoekt de registry van de images op" "nslookup ghcr.io" "$uitvoer"

# --- platform_op: alleen uitrollen als het platform er aantoonbaar niet is ---

PLATFORM_STUBS='platform_bestaat() { true; }; fj() { :; }; mise() { echo "UITROL $*"; };
wacht_tot() { shift 2; "$@"; }; console_antwoordt() { true; }; platform_gereed() { true; };
docker_poorten() { :; }; mkdir -p "$WORKTREE";'
code=0
uitvoer=$(in_script "$PLATFORM_STUBS kcp() { case \"\$*\" in *readyz*) return 0 ;; *) echo 'Unable to connect to the server: dial tcp 127.0.0.1:43917: connect: connection refused' >&2; return 1 ;; esac; }; platform_op") || code=$?
gelijk "platform_op: API-fout: exit 1" 1 "$code"
bevat_niet "platform_op: API-fout: niet opnieuw uitrollen" "UITROL" "$uitvoer"
code=0
uitvoer=$(in_script "$PLATFORM_STUBS kcp() { case \"\$*\" in *readyz*) return 0 ;; *) echo 'Error from server (NotFound): deployments.apps \"organization-api\" not found' >&2; return 1 ;; esac; }; platform_op") || code=$?
gelijk "platform_op: niet uitgerold: exit 0" 0 "$code"
bevat "platform_op: niet uitgerold: uitrollen" "UITROL exec -- skaffold run" "$uitvoer"
code=0
uitvoer=$(in_script "$PLATFORM_STUBS kcp() { return 0; }; platform_op") || code=$?
gelijk "platform_op: al uitgerold: exit 0" 0 "$code"
bevat_niet "platform_op: al uitgerold: niet opnieuw uitrollen" "UITROL" "$uitvoer"
code=0
uitvoer=$(in_script "$PLATFORM_STUBS kcp() { return 0; }; docker_poorten() { echo 'k3d-fundament-serverlb|0.0.0.0:54328->54328/tcp'; }; platform_op") || code=$?
gelijk "platform_op: poort op alle adressen na het opzetten: exit 2" 2 "$code"

# --- api_key_maken: elke key een eigen naam ---

# Een key verloopt na een uur, maar zijn naam blijft bezet: een tweede key met dezelfde naam weigert
# het platform (already_exists). Twee keer na elkaar maken moet dus twee namen opleveren.
uitvoer=$(in_script 'WERK=$(mktemp -d); mkcert() { echo /geen; };
curl() { local a; case "$*" in *login/password*) echo "{\"access_token\":\"t\"}" ;;
  *CreateAPIKey*) for a; do case "$a" in *expiresIn*) echo "$a" >>"$WERK/aanvragen" ;; esac; done; echo "{\"token\":\"k\"}" ;; esac; };
api_key_maken >/dev/null; api_key_maken >/dev/null; jq -r .name "$WERK/aanvragen"')
gelijk "api_key_maken: twee keys, twee namen" 2 "$(sort -u <<<"$uitvoer" | grep -c '^fbs-fundament-testcluster-')"

uitvoer=$(in_script 'WERK=$(mktemp -d); mkcert() { echo /geen; };
curl() { case "$*" in *login/password*) echo "{\"access_token\":\"t\"}" ;; *) echo "{\"code\":\"already_exists\"}" ;; esac; };
api_key_maken || echo MISLUKT')
bevat "api_key_maken: een weigering noemt het antwoord van het platform" "already_exists" "$uitvoer"
bevat "api_key_maken: een weigering faalt" "MISLUKT" "$uitvoer"
# Een antwoord dat de parser niet herkent, kan wel een key bevatten: die hoort niet in de uitvoer.
uitvoer=$(in_script 'WERK=$(mktemp -d); mkcert() { echo /geen; };
curl() { case "$*" in *login/password*) echo "{\"access_token\":\"t\"}" ;; *) echo "{\"apiKey\":\"GEHEIM-123\"}" ;; esac; };
api_key_maken || echo MISLUKT')
bevat_niet "api_key_maken: een onbekend antwoord lekt geen key" "GEHEIM-123" "$uitvoer"
bevat "api_key_maken: een onbekend antwoord faalt" "MISLUKT" "$uitvoer"

# --- replica's ---

uitvoer=$(REPLICAS=3 in_script 'WERK=$(mktemp -d); TAG=main-1234567; dienst_args berichtenmagazijn; printf "%s\n" "${DIENST_ARGS[@]}"')
bevat "dienst_args: het aantal replica's gaat naar de chart" "dienst.replicaCount=3" "$uitvoer"
uitvoer=$(in_script 'WERK=$(mktemp -d); TAG=main-1234567; dienst_args berichtenuitvraag; printf "%s\n" "${DIENST_ARGS[@]}"')
bevat "dienst_args: standaard twee replica's" "dienst.replicaCount=2" "$uitvoer"
# Het stelselregister draait volgens zijn chart-values met precies één replica (singleReplica);
# de chart weigert dan elk ander aantal.
uitvoer=$(REPLICAS=3 in_script 'WERK=$(mktemp -d); TAG=main-1234567; dienst_args stelselregister; printf "%s\n" "${DIENST_ARGS[@]}"')
bevat_niet "dienst_args: een dienst met singleReplica krijgt geen aantal" "replicaCount" "$uitvoer"
# vereis_tools breekt af met 9: komt op voorbij de validatie, dan draait er verder niets echt.
for geldig in 1 2 99; do
  code=0
  uitvoer=$(REPLICAS=$geldig TAG=main-1234567 in_script 'vereis_tools() { exit 9; }; op') || code=$?
  gelijk "op met REPLICAS=$geldig: voorbij de validatie" 9 "$code"
done
for ongeldig in 0 -1 twee 1.5 " 2"; do
  code=0
  uitvoer=$(REPLICAS=$ongeldig TAG=main-1234567 in_script 'vereis_tools() { exit 9; }; op') || code=$?
  gelijk "op met REPLICAS='$ongeldig': exit 2" 2 "$code"
done

echo "== kubectl en helm alleen via de wrappers =="

for bestand in "${GECONTROLEERDE_SCRIPTS[@]}"; do
  if [ ! -f "$bestand" ]; then
    faal "$bestand ontbreekt"
    continue
  fi

  # Alleen op de plek van een commando (begin van de regel, na |, &&, ;, ( of $( ): in een melding
  # of een lijst toolnamen is het geen aanroep. Commentaarregels en de definities van de wrappers
  # zelf tellen niet.
  kaal=$(grep -nE '(^|[;&|(])[[:space:]]*(kubectl|helm)( |$)' "$bestand" |
    grep -vE '^[0-9]+:[[:space:]]*#' |
    grep -vE '^[0-9]+:[[:space:]]*(kc|kcp|hm|kconfig)\(\)' || true)
  gelijk "geen kale kubectl/helm in $(basename "$bestand")" "" "$kaal"
done

echo "== geen umask voor het hele proces =="

# Een strenge umask vóór de checkout van Fundament maakt hun bestanden 600; Docker kopieert die
# rechten in de images en de niet-root-gebruiker kan ze dan niet lezen (trek.yaml: permission
# denied). De werkmap van mktemp -d is al 700.
umask_regels=$(grep -nE '^[[:space:]]*umask[[:space:]]' "$HIER/../fundament-testcluster.sh" || true)
gelijk "geen umask in fundament-testcluster.sh" "" "$umask_regels"

echo "ASSERTIES=$asserties"
exit "$mislukt"
