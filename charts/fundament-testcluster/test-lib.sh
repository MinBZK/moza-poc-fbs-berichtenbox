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
gelijk "versie bestaat al" bestaat-al \
  "$(functl_fout_soort 'Error: already_exists: version "1.21.3" already exists for this plugin')"
gelijk "iets anders" onbekend "$(functl_fout_soort 'connection refused')"

echo "== functl_sessie =="

# De vorm uit functl/pkg/cli/auth.go van Fundament (sinds fundament-oss/fundament#661).
gelijk "ingelogd" ingelogd \
  "$(functl_sessie '{"authenticated":true,"organization_ids":["019b4000-0000-7000-8000-000000000000"],"user_id":"01","user_name":"platform-admin"}')"
# Zo schrijft functl het echt: ingesprongen, over meer regels (json.Indent).
gelijk "ingelogd, ingesprongen zoals functl het schrijft" ingelogd "$(functl_sessie '{
  "authenticated": true,
  "organization_ids": null,
  "user_id": "01",
  "user_name": "platform-admin"
}')"
gelijk "nooit ingelogd" niet-ingelogd \
  "$(functl_sessie '{"authenticated":false,"hint":"Run '"'"'functl auth login'"'"' to authenticate.","message":"not logged in","reason":"not_logged_in"}')"
gelijk "key geweigerd" niet-ingelogd \
  "$(functl_sessie '{"authenticated":false,"hint":"...","message":"credentials rejected, they may be invalid or expired","reason":"rejected"}')"
# Een reden die functl er later bij krijgt, zegt niet dat een nieuwe key helpt.
gelijk "onbekende reden: exit 1" 1 "$(code_van functl_sessie '{"authenticated":false,"reason":"suspended"}')"
gelijk "niet ingelogd zonder reden: exit 1" 1 "$(code_van functl_sessie '{"authenticated":false}')"
gelijk "authenticated als tekst: exit 1" 1 "$(code_van functl_sessie '{"authenticated":"true"}')"
gelijk "zonder authenticated: exit 1" 1 "$(code_van functl_sessie '{"reason":"not_logged_in"}')"
# Zo antwoordde functl vóór #661, met exit 0.
gelijk "de tekst van een oude functl: exit 1" 1 \
  "$(code_van functl_sessie 'Not authenticated
Run '"'"'functl auth login'"'"' to authenticate')"
gelijk "lege uitvoer: exit 1" 1 "$(code_van functl_sessie '')"

echo "== connect_code =="

gelijk "unauthenticated" unauthenticated "$(connect_code '{"code":"unauthenticated","message":"no authorization header or auth cookie found"}')"
gelijk "unavailable" unavailable "$(connect_code '{"code":"unavailable","message":"service temporarily unavailable"}')"
gelijk "geen code: exit 1" 1 "$(code_van connect_code '{"message":"x"}')"
gelijk "een getal als code (gRPC-stijl): exit 1" 1 "$(code_van connect_code '{"code":16}')"
gelijk "geen JSON (Envoy): exit 1" 1 "$(code_van connect_code 'no healthy upstream')"

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

echo "== k3s_versie_uit_image =="

gelijk "tag met digest" "v1.36.6-k3s1" \
  "$(k3s_versie_uit_image docker.io/rancher/k3s:v1.36.6-k3s1@sha256:88d3fac10eb4a429d3c5275f4868fe4b13620f6bbca5bdb5dca0627ac76ae2a0)"
gelijk "tag zonder digest" "v1.33.5-k3s1" "$(k3s_versie_uit_image rancher/k3s:v1.33.5-k3s1)"
gelijk "registry met poort" "v1.36.6-k3s1" "$(k3s_versie_uit_image localhost:5000/rancher/k3s:v1.36.6-k3s1)"
gelijk "geen k3s-image: niets" "" "$(k3s_versie_uit_image docker.io/library/nginx:1.29 || true)"
gelijk "geen k3s-image: exit 1" 1 "$(code_van k3s_versie_uit_image docker.io/library/nginx:1.29)"
gelijk "leeg: exit 1" 1 "$(code_van k3s_versie_uit_image '')"
gelijk "alleen een digest: exit 1" 1 "$(code_van k3s_versie_uit_image rancher/k3s@sha256:88d3fac10eb4a429d3c5275f4868fe4b13620f6bbca5bdb5dca0627ac76ae2a0)"
gelijk "een naam die op k3s lijkt: exit 1" 1 "$(code_van k3s_versie_uit_image evil/notk3s:v1.36.6-k3s1)"

echo "== mappings_uit_inspect =="

# Zoals `docker port`, maar uit de configuratie: ook een gestopte container heeft ze daar.
gelijk "loopback en een lege HostIp" "443/tcp -> 127.0.0.1:9443
6443/tcp -> 0.0.0.0:41837" "$(mappings_uit_inspect <<<'[{"HostConfig":{"PortBindings":{"443/tcp":[{"HostIp":"127.0.0.1","HostPort":"9443"}],"6443/tcp":[{"HostIp":"","HostPort":"41837"}]}}}]' | sort)"
gelijk "twee bindingen op één poort" "443/tcp -> 0.0.0.0:9444
443/tcp -> 127.0.0.1:9443" "$(mappings_uit_inspect <<<'[{"HostConfig":{"PortBindings":{"443/tcp":[{"HostIp":"127.0.0.1","HostPort":"9443"},{"HostIp":"0.0.0.0","HostPort":"9444"}]}}}]' | sort)"
gelijk "geen bindingen: niets" "" "$(mappings_uit_inspect <<<'[{"HostConfig":{"PortBindings":null}}]')"
gelijk "geen container (lege lijst): niets" "" "$(mappings_uit_inspect <<<'[]')"

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

echo "== nieuw_uuid =="

uuid_een=$(nieuw_uuid)
uuid_twee=$(nieuw_uuid)
patroon='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

gelijk "een UUID in kleine letters" 1 "$(grep -Ecq "$patroon" <<<"$uuid_een" && echo 1 || echo 0)"
gelijk "elke aanroep een andere" 1 "$([ "$uuid_een" != "$uuid_twee" ] && echo 1 || echo 0)"

echo "== poort_vrij =="

vrije_poort=$(python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
python3 -I -m http.server --bind 127.0.0.1 "$vrije_poort" >/dev/null 2>&1 &
server=$!

for _ in $(seq 1 50); do
  poort_luistert "$vrije_poort" && break
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

args=()
while IFS= read -r regel; do args+=("$regel"); done < <(helm_route_args stelselregister)
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

gelijk "alles op loopback, of zonder hostpoort: niets" "" "$(poorten_naar_buiten <<'EOF'
k3d-fundament-serverlb|80/tcp, 127.0.0.1:54328->54328/tcp, 127.0.0.1:8443->443/tcp, 127.0.0.1:43917->6443/tcp
fun-authn-relay|
k3d-fundament-server-0|
registry.localhost|127.0.0.1:5111->5000/tcp
EOF
)"
gelijk "database op alle adressen" "k3d-fundament-serverlb 0.0.0.0:54328->54328/tcp" \
  "$(poorten_naar_buiten <<<'k3d-fundament-serverlb|127.0.0.1:8443->443/tcp, 0.0.0.0:54328->54328/tcp, 127.0.0.1:43917->6443/tcp')"
# Fundament bindt ook de Kubernetes-API aan loopback; een API op alle adressen is dus een cluster van
# een oudere versie, en geen uitzondering meer.
gelijk "Kubernetes-API op alle adressen telt mee" "k3d-fundament-serverlb 0.0.0.0:43917->6443/tcp
k3d-fundament-serverlb [::]:43917->6443/tcp" \
  "$(poorten_naar_buiten <<<'k3d-fundament-serverlb|127.0.0.1:8443->443/tcp, 0.0.0.0:43917->6443/tcp, [::]:43917->6443/tcp')"
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
gelijk "samen met poorten_naar_buiten: de API, gestopt en IPv6" "k3d-fundament-serverlb 0.0.0.0:43917->6443/tcp
gestopt 0.0.0.0:54328->54328/tcp
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
# Instellingen uit de shell van wie de suite draait, mogen de verwachte waarden niet verschuiven.
unset FUNDAMENT_K3D_SUBNET HTTPS_POORT REPLICAS FUNDAMENT_REF FUNDAMENT_REPO FUNCTL_API_KEY TAG

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

# De contexten staan in de kubeconfig van de worktree. Zonder --kubeconfig zou kubectl ze zoeken in
# ~/.kube/config, waar een context met dezelfde naam naar een ander cluster kan wijzen.
uitvoer=$(in_script 'command() { echo "$*"; }; kc get pods; kcp get pods; hm list')
gelijk "kc: kubeconfig van de worktree en de sandbox" 1 \
  "$(grep -c -- "^kubectl --kubeconfig $TESTCACHE/fbs-fundament-testcluster/fundament-[0-9a-f]\{8\}/.dev/kubeconfig --context k3d-fundament-plugin get pods$" <<<"$uitvoer")"
gelijk "kcp: kubeconfig van de worktree en het platform" 1 \
  "$(grep -c -- "^kubectl --kubeconfig $TESTCACHE/fbs-fundament-testcluster/fundament-[0-9a-f]\{8\}/.dev/kubeconfig --context k3d-fundament get pods$" <<<"$uitvoer")"
gelijk "hm: kubeconfig van de worktree en de sandbox" 1 \
  "$(grep -c -- "^helm --kubeconfig $TESTCACHE/fbs-fundament-testcluster/fundament-[0-9a-f]\{8\}/.dev/kubeconfig --kube-context k3d-fundament-plugin list$" <<<"$uitvoer")"

# Of de sandbox van ons is, volgt uit de containerconfiguratie en niet uit de API: zo is ook een
# gestopte sandbox te herkennen, en een die nog in de kubeconfig van een vorige FUNDAMENT_REF staat.
VAN_ONS_STUBS='kc() { echo "API GEVRAAGD" >&2; return 1; };
docker() { case "$*" in
  "inspect -f {{.Config.Image}} k3d-fundament-plugin-server-0") echo "${IMAGE:-docker.io/rancher/k3s:v1.36.6-k3s1@sha256:aa}" ;;
  "inspect k3d-fundament-plugin-serverlb") echo "[{\"HostConfig\":{\"PortBindings\":{\"443/tcp\":[{\"HostIp\":\"${IP:-127.0.0.1}\",\"HostPort\":\"9443\"}]}}}]" ;;
  *) return 1 ;; esac; };'
code=0
uitvoer=$(in_script "$VAN_ONS_STUBS sandbox_van_ons") || code=$?
gelijk "sandbox_van_ons: 1.36 op loopback, zonder API: exit 0" 0 "$code"
bevat_niet "sandbox_van_ons: vraagt de API niet" "API GEVRAAGD" "$uitvoer"
code=0
uitvoer=$(in_script "$VAN_ONS_STUBS IMAGE=docker.io/rancher/k3s:v1.33.5-k3s1; sandbox_van_ons") || code=$?
gelijk "sandbox_van_ons: andere versie: exit 1" 1 "$code"
code=0
uitvoer=$(in_script "$VAN_ONS_STUBS IMAGE=docker.io/library/nginx:1.29; sandbox_van_ons") || code=$?
gelijk "sandbox_van_ons: geen k3s-image: exit 1" 1 "$code"
bevat "sandbox_van_ons: geen k3s-image: zegt waarom" "onbekend (image docker.io/library/nginx:1.29)" "$uitvoer"
code=0
uitvoer=$(in_script "$VAN_ONS_STUBS IP=0.0.0.0; sandbox_van_ons") || code=$?
gelijk "sandbox_van_ons: https op alle adressen: exit 1" 1 "$code"
code=0
uitvoer=$(in_script 'docker() { return 1; }; sandbox_van_ons') || code=$?
gelijk "sandbox_van_ons: docker faalt: exit 1" 1 "$code"

# Het platform komt uit hun eigen recipe, met ons subnet. Hun Justfile leest FUNDAMENT_K3D_SUBNET uit
# de omgeving; daarom leest de stub hem in een kindproces, zodat alleen een export telt.
uitvoer=$(in_script 'fj() { bash -c "echo RECIPE \$* subnet=\${FUNDAMENT_K3D_SUBNET-AFWEZIG}" _ "$@"; }; docker_poorten() { :; }; platform_maken') || true
gelijk "platform_maken: hun cluster-create met het subnet" "RECIPE cluster-create subnet=172.29.0.0/16" "$uitvoer"
uitvoer=$(FUNDAMENT_K3D_SUBNET=10.99.0.0/16 in_script 'fj() { bash -c "echo subnet=\${FUNDAMENT_K3D_SUBNET-AFWEZIG}"; }; docker_poorten() { :; }; platform_maken') || true
gelijk "platform_maken: een eigen subnet gaat mee" "subnet=10.99.0.0/16" "$uitvoer"
# Fundament bepaalt de k3d-config nu zelf; deze controle is wat een open poort nog tegenhoudt.
code=0
uitvoer=$(in_script 'fj() { :; }; docker_poorten() { echo "k3d-fundament-serverlb|0.0.0.0:54328->54328/tcp"; }; platform_maken') || code=$?
gelijk "platform_maken: poort op alle adressen na het aanmaken: exit 2" 2 "$code"
bevat "platform_maken: noemt de opruiming" "af --met-platform" "$uitvoer"
code=0
uitvoer=$(in_script 'fj() { return 1; }; platform_maken') || code=$?
gelijk "platform_maken: recipe faalt: exit 2" 2 "$code"

# --- sandbox_op ---

# Naar stderr: het script stuurt de stdout van cluster-start naar /dev/null.
SANDBOX_STUBS='fj() { echo "RECIPE $*" >&2; }; sandbox_dns_werkt() { :; };
sandbox_bestaat() { [ "${SANDBOX:-ja}" = ja ]; }; sandbox_van_ons() { [ "${VAN_ONS:-ja}" = ja ]; };
docker_poorten() { printf "%s\n" "${POORTEN:-}"; }; mkdir -p "$WORKTREE/plugins";
mise() { echo "MISE KUBECONFIG=$KUBECONFIG $*"; };'
code=0
uitvoer=$(in_script "$SANDBOX_STUBS VAN_ONS=nee; sandbox_op") || code=$?
gelijk "sandbox_op: vreemde sandbox: exit 2" 2 "$code"
bevat_niet "sandbox_op: vreemde sandbox wordt niet gestart" "plugins cluster-start" "$uitvoer"
code=0
uitvoer=$(in_script "$SANDBOX_STUBS sandbox_op") || code=$?
gelijk "sandbox_op: eigen sandbox: exit 0" 0 "$code"
bevat "sandbox_op: eigen sandbox: gestart via hun recipe" "RECIPE plugins cluster-start" "$uitvoer"
bevat "sandbox_op: relays via hun recipe" "RECIPE plugins sandbox-catalog" "$uitvoer"
# Direct na het aanmaken, niet pas aan het eind: daartussen bouwt plugins deploy minutenlang.
code=0
uitvoer=$(in_script "$SANDBOX_STUBS SANDBOX=nee; POORTEN='k3d-fundament-plugin-serverlb|0.0.0.0:9443->443/tcp'; sandbox_op") || code=$?
gelijk "sandbox_op: nieuwe sandbox met een open poort: exit 2" 2 "$code"
bevat_niet "sandbox_op: nieuwe sandbox met een open poort: geen plugins deploy" "RECIPE plugins deploy" "$uitvoer"
code=0
uitvoer=$(in_script "$SANDBOX_STUBS fj() { case \"\$*\" in \"plugins cluster-start\") return 1 ;; esac; }; sandbox_op") || code=$?
gelijk "sandbox_op: starten faalt: exit 1" 1 "$code"
bevat "sandbox_op: starten faalt: noemt de opruiming" "ruim hem op met" "$uitvoer"
# De relays hebben geen hostpoort meer; staat er na hun recipe toch iets open, dan is dat een fout.
code=0
uitvoer=$(in_script "$SANDBOX_STUBS POORTEN='fun-authn-relay|0.0.0.0:38081->8080/tcp'; sandbox_op") || code=$?
gelijk "sandbox_op: open poort na sandbox-catalog: exit 2" 2 "$code"
code=0
uitvoer=$(in_script "$SANDBOX_STUBS SANDBOX=nee; sandbox_op") || code=$?
gelijk "sandbox_op: nieuwe sandbox: exit 0" 0 "$code"
bevat "sandbox_op: nieuwe sandbox op het k3s-image van 1.36" "--image $(sed -n 's/^readonly K3S_IMAGE=//p' "$SCRIPT")" "$uitvoer"
bevat "sandbox_op: nieuwe sandbox met https op loopback" "-p 127.0.0.1:9443:443@loadbalancer" "$uitvoer"
bevat "sandbox_op: nieuwe sandbox in de kubeconfig van de worktree" "KUBECONFIG=$TESTCACHE/fbs-fundament-testcluster/fundament-" "$uitvoer"
bevat "sandbox_op: nieuwe sandbox met hun config" "--config=sandbox/k3d-config.yaml" "$uitvoer"
# De stubs maakten een worktree-map; de tests van af gaan uit van een lege cache.
rm -rf "$TESTCACHE/fbs-fundament-testcluster"

# --- plugin_hervatten: de hash van een goedgekeurde versie uit de catalogus op het platform ---

CA_MAP=$(mktemp -d "$TESTCACHE/ca.XXXXXX")
: >"$CA_MAP/rootCA.pem"
HERVAT_STUBS="bestaande_versie() { echo 1.2.3; }; versie_uit_catalogus() { echo 'approved id-1'; }; fj() { :; };
mkcert_caroot() { echo '$CA_MAP'; };"
code=0
uitvoer=$(in_script "$HERVAT_STUBS curl() { echo \"CURL \$*\" >&2; echo \"curl: (7) Failed to connect\" >&2; return 7; }; plugin_hervatten cert-manager x") || code=$?
gelijk "plugin_hervatten: catalogus onbereikbaar: exit 1" 1 "$code"
bevat "plugin_hervatten: via de route op het platform" "https://marketplace-catalog-api.fundament.localhost:8443/catalog.v1.CatalogService/GetPluginDefinition" "$uitvoer"
bevat "plugin_hervatten: getoetst tegen de lokale CA" "--cacert $CA_MAP/rootCA.pem" "$uitvoer"
bevat "plugin_hervatten: noemt de verbinding, niet een ontbrekende hash" "gaf geen antwoord" "$uitvoer"
bevat_niet "plugin_hervatten: geen 'geen hash'" "geen hash" "$uitvoer"
# Een HTTP-fout (ontbrekende route of versie) is ook geen antwoord: curl faalt alleen met --fail-with-body.
code=0
uitvoer=$(in_script "$HERVAT_STUBS curl() { echo '{\"code\":\"not_found\"}'; case \"\$*\" in *--fail-with-body*) return 22 ;; esac; }; plugin_hervatten cert-manager x") || code=$?
gelijk "plugin_hervatten: HTTP-fout: exit 1" 1 "$code"
bevat "plugin_hervatten: HTTP-fout: noemt de catalogus" "gaf geen antwoord" "$uitvoer"
bevat "plugin_hervatten: HTTP-fout: toont het antwoord" "not_found" "$uitvoer"
code=0
uitvoer=$(in_script "$HERVAT_STUBS mkcert_caroot() { return 1; }; curl() { echo CURL-AANGEROEPEN; }; plugin_hervatten cert-manager x") || code=$?
gelijk "plugin_hervatten: geen CA: exit 2" 2 "$code"
bevat_niet "plugin_hervatten: geen CA: geen curl" "CURL-AANGEROEPEN" "$uitvoer"
code=0
uitvoer=$(in_script "$HERVAT_STUBS mkcert_caroot() { echo '$TESTCACHE/leeg'; }; curl() { echo CURL-AANGEROEPEN; }; plugin_hervatten cert-manager x") || code=$?
gelijk "plugin_hervatten: map zonder CA: exit 2" 2 "$code"
bevat "plugin_hervatten: map zonder CA: noemt mkcert -install" "mkcert -install" "$uitvoer"
bevat_niet "plugin_hervatten: map zonder CA: geen curl" "CURL-AANGEROEPEN" "$uitvoer"

# --- de test-VM van Fundament ---

# Met FUNDAMENT_TEST_ENV schrijven hun recipes in ~/.kube/config; het script zet hem uit.
uitvoer=$(FUNDAMENT_TEST_ENV=1 in_script 'bash -c "echo \${FUNDAMENT_TEST_ENV-weg}"')
gelijk "FUNDAMENT_TEST_ENV gaat niet mee naar hun recipes" "weg" "$uitvoer"
code=0
uitvoer=$(in_script 'in_test_vm() { true; }; geen_test_vm') || code=$?
gelijk "geen_test_vm: in hun test-VM: exit 2" 2 "$code"
code=0
uitvoer=$(in_script 'in_test_vm() { false; }; geen_test_vm') || code=$?
gelijk "geen_test_vm: op de host: exit 0" 0 "$code"

# De weigering moet in op en af zitten vóór er iets gebeurt, niet alleen als losse functie.
code=0
uitvoer=$(in_script 'in_test_vm() { true; }; vereis_tools() { :; }; af() { echo AF-BEREIKT; }; main af') || code=$?
gelijk "af in hun test-VM: exit 2" 2 "$code"
bevat_niet "af in hun test-VM: ruimt niets op" "AF-BEREIKT" "$uitvoer"
code=0
uitvoer=$(TAG=main-1234567 in_script 'in_test_vm() { true; }; vereis_tools() { :; }; werkmap() { echo WERK-BEREIKT; }; main op') || code=$?
gelijk "op in hun test-VM: exit 2" 2 "$code"
bevat_niet "op in hun test-VM: begint niet" "WERK-BEREIKT" "$uitvoer"

# Wat de gebruiker uit een melding overtypt, moet dezelfde kubeconfig noemen als het script.
uitvoer=$(in_script 'echo "$KC_SANDBOX"; echo "$KC_PLATFORM"')
gelijk "melding: kubectl voor de sandbox noemt de kubeconfig" 1 \
  "$(grep -c -- "^kubectl --kubeconfig $TESTCACHE/fbs-fundament-testcluster/fundament-[0-9a-f]\{8\}/.dev/kubeconfig --context k3d-fundament-plugin$" <<<"$uitvoer")"
gelijk "melding: kubectl voor het platform noemt de kubeconfig" 1 \
  "$(grep -c -- "^kubectl --kubeconfig $TESTCACHE/fbs-fundament-testcluster/fundament-[0-9a-f]\{8\}/.dev/kubeconfig --context k3d-fundament$" <<<"$uitvoer")"

# --- controleer zonder opstelling op deze FUNDAMENT_REF ---

code=0
uitvoer=$(FUNDAMENT_REF=0000000000000000000000000000000000000000 in_script 'main controleer') || code=$?
gelijk "controleer: geen kubeconfig in de worktree: exit 2" 2 "$code"
bevat "controleer: noemt FUNDAMENT_REF" "FUNDAMENT_REF" "$uitvoer"
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
uitvoer=$(in_script "$VOOR_STUBS PLATFORM=ja; POORTEN='k3d-fundament-serverlb|127.0.0.1:8443->443/tcp, 127.0.0.1:43917->6443/tcp'; voorcontrole") || code=$?
gelijk "voorcontrole: platform op loopback: exit 0" 0 "$code"
code=0
uitvoer=$(in_script "$VOOR_STUBS PLATFORM=ja; POORTEN='k3d-fundament-serverlb|127.0.0.1:8443->443/tcp, 0.0.0.0:43917->6443/tcp'; voorcontrole") || code=$?
gelijk "voorcontrole: platform van een oudere versie, API op alle adressen: exit 2" 2 "$code"
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
# Via k3d, zoals Fundaments eigen cluster-start, niet docker restart: k3d zet bij het starten ook de
# adressen van het netwerk opnieuw in CoreDNS.
bevat "sandbox_dns_werkt: noemt de herstart via k3d" "k3d cluster stop fundament-plugin fundament" "$uitvoer"
bevat_niet "sandbox_dns_werkt: geen docker restart" "docker restart" "$uitvoer"
code=0
uitvoer=$(in_script 'docker() { echo "docker $*"; }; wacht_tot() { shift 2; "$@"; }; sandbox_dns_werkt') || code=$?
gelijk "sandbox_dns_werkt: DNS werkt: exit 0" 0 "$code"
bevat "sandbox_dns_werkt: zoekt de registry van de images op" "nslookup ghcr.io" "$uitvoer"

# Een kapotte DNS in de node toont zich anders pas als een rollout die niet klaar komt, zonder
# oorzaak. controleer noemt hem zelf, met de remedie, en controleert de diensten daarna nog.
CONTROLEER_STUBS='WERK=$(mktemp -d -p "$XDG_CACHE_HOME"); echo ca >"$WERK/lokale-ca.crt"; controleer_dienst() { echo "DIENST $1"; }; na_afloop() { echo NA_AFLOOP; };'
code=0
uitvoer=$(in_script "$CONTROLEER_STUBS sandbox_dns_ok() { return 1; }; controleer") || code=$?
gelijk "controleer: geen DNS in de sandbox-node: exit 1" 1 "$code"
bevat "controleer: geen DNS: controleert de diensten nog" "DIENST stelselregister" "$uitvoer"
bevat "controleer: geen DNS: sluit af met de keuzes" "NA_AFLOOP" "$uitvoer"
bevat "controleer: geen DNS: noemt de herstart via k3d" "k3d cluster stop fundament-plugin fundament" "$uitvoer"
bevat "controleer: geen DNS: noemt de herstart van de machine" "herstart van de machine" "$uitvoer"
code=0
uitvoer=$(in_script "$CONTROLEER_STUBS sandbox_dns_ok() { return 0; }; controleer") || code=$?
gelijk "controleer: DNS werkt: exit 0" 0 "$code"
uitvoer=$(in_script 'vereis_tools() { echo "TOOLS $*"; exit 9; }; main controleer') || true
bevat "controleer: vraagt docker voor de DNS-controle" "TOOLS curl docker" "$uitvoer"

# --- het slotbericht ---

# Na een herstart van de machine start Docker de containers, niet k3d; zonder herstart via k3d bleven
# de pods een dag in ImagePullBackOff hangen.
uitvoer=$(in_script 'na_afloop')
bevat "na_afloop: na een herstart van de machine via k3d herstarten" \
  "k3d cluster stop fundament-plugin fundament && mise exec -- k3d cluster start fundament fundament-plugin" "$uitvoer"
bevat "na_afloop: en daarna op" "TAG=<tag> " "$uitvoer"
gelijk "na_afloop: de herstart draait k3d uit de worktree van Fundament" 1 \
  "$(grep -cE "^  \\(cd $TESTCACHE/fbs-fundament-testcluster/fundament-[0-9a-f]{8} && mise exec -- k3d cluster stop fundament-plugin fundament && mise exec -- k3d cluster start fundament fundament-plugin\\)\$" <<<"$uitvoer")"
bevat_niet "na_afloop: belooft niet dat hij vanzelf weer start" "vanzelf" "$uitvoer"
bevat_niet "na_afloop: geen docker restart" "docker restart" "$uitvoer"

# --- functl ---

# De sessie volgt uit `functl auth status -o json`. Debuglog op stderr (mise zet FUNCTL_DEBUG aan)
# hoort niet bij de JSON. De stubs loggen naar een bestand: het script slikt de uitvoer van een
# geslaagde recipe in.
FUNCTL_STUBS='WERK=$(mktemp -d -p "$XDG_CACHE_HOME"); LOG=$WERK/aanroepen; : >"$LOG"; trap "cat \"$LOG\"" EXIT;
api_key_maken() { echo "KEY GEMAAKT" >>"$LOG"; [ -z "${KEY_FAALT:-}" ] || return 1; echo fun_nieuw; };
fj() { case "$*" in
  "functl auth status -o json") echo "STATUS GEVRAAGD" >>"$LOG"; echo "time=... level=DEBUG msg=GetUserInfo" >&2
    [ -z "${STATUS_FAALT:-}" ] || { echo "Error: could not reach https://authn.fundament.localhost:8443: unavailable" >&2; return 1; }
    echo "$STATUS" ;;
  "functl auth login") echo "LOGIN met $(cat)" >>"$LOG"; [ -z "${LOGIN_FAALT:-}" ] || return 1 ;;
  "functl org set 019b4000-0000-7000-8000-000000000000") echo "ORG SET" >>"$LOG"; [ -z "${ORG_FAALT:-}" ] || return 1 ;;
  *) echo "ONVERWACHT fj $*" >>"$LOG"; return 1 ;;
esac; };'
INGELOGD='{"authenticated":true,"organization_ids":[],"user_id":"01","user_name":"platform-admin"}'
code=0
uitvoer=$(STATUS=$INGELOGD in_script "$FUNCTL_STUBS functl_inloggen") || code=$?
gelijk "functl_inloggen: ingelogd: exit 0" 0 "$code"
bevat "functl_inloggen: ingelogd: debuglog op stderr stoort niet" "OK: functl was al ingelogd" "$uitvoer"
bevat "functl_inloggen: ingelogd: kiest de organisatie" "ORG SET" "$uitvoer"
bevat_niet "functl_inloggen: ingelogd: geen nieuwe key" "KEY GEMAAKT" "$uitvoer"
bevat_niet "functl_inloggen: ingelogd: niet opnieuw inloggen" "LOGIN" "$uitvoer"
bevat_niet "functl_inloggen: ingelogd: alleen de verwachte recipes" "ONVERWACHT" "$uitvoer"
for reden in not_logged_in rejected; do
  code=0
  uitvoer=$(STATUS="{\"authenticated\":false,\"reason\":\"$reden\"}" in_script "$FUNCTL_STUBS functl_inloggen") || code=$?
  gelijk "functl_inloggen: $reden: exit 0" 0 "$code"
  bevat "functl_inloggen: $reden: logt in met een nieuwe key" "LOGIN met fun_nieuw" "$uitvoer"
  bevat "functl_inloggen: $reden: kiest daarna de organisatie" "ORG SET" "$uitvoer"
done
uitvoer=$(STATUS='{"authenticated":false,"reason":"rejected","message":"credentials rejected, they may be invalid or expired"}' \
  in_script "$FUNCTL_STUBS functl_inloggen") || true
bevat "functl_inloggen: rejected: zegt waarom er een nieuwe key komt" "credentials rejected" "$uitvoer"
code=0
uitvoer=$(STATUS='{"authenticated":false,"reason":"not_logged_in"}' KEY_FAALT=1 in_script "$FUNCTL_STUBS functl_inloggen") || code=$?
gelijk "functl_inloggen: geen key te maken: exit 1" 1 "$code"
bevat "functl_inloggen: geen key te maken: zegt dat" "kon geen API-key maken" "$uitvoer"
bevat_niet "functl_inloggen: geen key te maken: niet inloggen" "LOGIN" "$uitvoer"
code=0
uitvoer=$(STATUS='{"authenticated":false,"reason":"not_logged_in"}' LOGIN_FAALT=1 in_script "$FUNCTL_STUBS functl_inloggen") || code=$?
gelijk "functl_inloggen: login faalt: exit 1" 1 "$code"
bevat "functl_inloggen: login faalt: zegt dat" "functl auth login faalde" "$uitvoer"
bevat_niet "functl_inloggen: login faalt: geen org set" "ORG SET" "$uitvoer"
bevat_niet "functl_inloggen: login faalt zonder eigen key: noemt FUNCTL_API_KEY niet" "FUNCTL_API_KEY" "$uitvoer"
uitvoer=$(FUNCTL_API_KEY=fun_oud LOGIN_FAALT=1 in_script "$FUNCTL_STUBS functl_inloggen") || true
bevat "functl_inloggen: login faalt met eigen key: noemt FUNCTL_API_KEY" "FUNCTL_API_KEY" "$uitvoer"
# Niet te controleren: een nieuwe key lost een onbereikbaar platform niet op, en verbergt de oorzaak.
code=0
uitvoer=$(STATUS_FAALT=1 in_script "$FUNCTL_STUBS functl_inloggen") || code=$?
gelijk "functl_inloggen: status niet te controleren: exit 1" 1 "$code"
bevat "functl_inloggen: status niet te controleren: zegt dat" "kon de sessie niet controleren" "$uitvoer"
bevat "functl_inloggen: status niet te controleren: toont de melding van functl" "could not reach" "$uitvoer"
bevat_niet "functl_inloggen: status niet te controleren: geen nieuwe key" "KEY GEMAAKT" "$uitvoer"
bevat_niet "functl_inloggen: status niet te controleren: niet inloggen" "LOGIN" "$uitvoer"
code=0
uitvoer=$(STATUS='Not authenticated' in_script "$FUNCTL_STUBS functl_inloggen") || code=$?
gelijk "functl_inloggen: onbekend antwoord: exit 1" 1 "$code"
bevat "functl_inloggen: onbekend antwoord: toont het" "Not authenticated" "$uitvoer"
bevat "functl_inloggen: onbekend antwoord: toont ook stderr" "level=DEBUG" "$uitvoer"
bevat_niet "functl_inloggen: onbekend antwoord: geen nieuwe key" "KEY GEMAAKT" "$uitvoer"
code=0
uitvoer=$(STATUS=$INGELOGD ORG_FAALT=1 in_script "$FUNCTL_STUBS functl_inloggen") || code=$?
gelijk "functl_inloggen: ingelogd maar org set faalt: exit 1" 1 "$code"
bevat "functl_inloggen: ingelogd maar org set faalt: zegt dat" "functl org set faalde" "$uitvoer"
code=0
uitvoer=$(FUNCTL_API_KEY=fun_eigen in_script "$FUNCTL_STUBS functl_inloggen") || code=$?
gelijk "functl_inloggen: eigen key: exit 0" 0 "$code"
bevat "functl_inloggen: eigen key: logt daarmee in" "LOGIN met fun_eigen" "$uitvoer"
bevat_niet "functl_inloggen: eigen key: vraagt geen status" "STATUS GEVRAAGD" "$uitvoer"
bevat_niet "functl_inloggen: eigen key: geen nieuwe key" "KEY GEMAAKT" "$uitvoer"

# Een key of adres uit de shell gaat in functl vóór zijn configuratie, en kan naar een echt platform
# wijzen.
uitvoer=$(FUNDAMENT_API_KEY=k FUNCTL_API_ENDPOINT=https://a.invalid \
  FUNCTL_AUTHN_URL=https://b.invalid FUNCTL_REGISTRY_URL=c.invalid \
  in_script 'bash -c "env | grep -E \"^(FUNDAMENT_API_KEY|FUNCTL_API_ENDPOINT|FUNCTL_AUTHN_URL|FUNCTL_REGISTRY_URL)=\" || echo geen"')
gelijk "de functl-instellingen uit de shell gaan niet mee naar functl" geen "$uitvoer"

# Zonder sessie vraagt `functl auth status` het platform niets; api_key_maken is dan de eerste die
# merkt dat het niet antwoordt, en moet zeggen waarom.
KEY_STUBS='WERK=$(mktemp -d -p "$XDG_CACHE_HOME"); mkcert_caroot() { echo /ca; };'
code=0
uitvoer=$(in_script "$KEY_STUBS curl() { echo 'curl: (7) Failed to connect to authn.fundament.localhost port 8443' >&2; return 7; }; api_key_maken") || code=$?
gelijk "api_key_maken: authn onbereikbaar: exit 1" 1 "$code"
bevat "api_key_maken: authn onbereikbaar: toont de melding van curl" "Failed to connect" "$uitvoer"
# Zonder -S zwijgt curl over een geweigerde verbinding of TLS-fout; met alleen -f valt het antwoord
# van een 4xx of 5xx weg.
uitvoer=$(in_script "$KEY_STUBS curl() { echo \"CURL \$*\" >&2; return 7; }; api_key_maken") || true
bevat "api_key_maken: curl met melding (-sS)" "CURL -sS " "$uitvoer"
bevat "api_key_maken: curl met het foutantwoord" " --fail-with-body " "$uitvoer"
uitvoer=$(in_script "$KEY_STUBS curl() { echo '{\"error\":\"invalid credentials\"}'; return 22; }; api_key_maken") || true
bevat "api_key_maken: login geweigerd: toont het foutantwoord" "invalid credentials" "$uitvoer"
code=0
uitvoer=$(in_script "$KEY_STUBS mkcert_caroot() { return 1; }; curl() { echo CURL-AANGEROEPEN >&2; }; api_key_maken") || code=$?
gelijk "api_key_maken: geen CA: exit 1" 1 "$code"
bevat "api_key_maken: geen CA: zegt dat" "mkcert vindt zijn CA niet" "$uitvoer"
bevat_niet "api_key_maken: geen CA: geen curl" "CURL-AANGEROEPEN" "$uitvoer"
# curl kan ook na een geslaagd antwoord falen; dan staat het token in wat curl gaf.
uitvoer=$(in_script "$KEY_STUBS curl() { echo '{\"access_token\":\"geheim-token\"}'; return 28; }; api_key_maken") || true
bevat_niet "api_key_maken: curl faalt na een geslaagd antwoord: toont het token niet" "geheim-token" "$uitvoer"
uitvoer=$(in_script "$KEY_STUBS curl() { printf '%s' '{\"access_token\":\"geheim-token\",\"expi'; return 28; }; api_key_maken") || true
bevat_niet "api_key_maken: afgekapt antwoord: toont het token niet" "geheim-token" "$uitvoer"
# De key is wat op stdout komt; functl auth login leest alleen de eerste regel. Beide aanroepen met
# de melding van curl en een tijdslimiet.
uitvoer=$(in_script "$KEY_STUBS curl() { echo \"CURL \$*\" >>\"\$WERK/curl\"; case \"\$*\" in *login/password*) echo '{\"access_token\":\"t\"}' ;; *) echo '{\"token\":\"fun_k\"}' ;; esac; };
  key=\$(api_key_maken); echo \"KEY=[\$key]\"; cat \"\$WERK/curl\"")
bevat "api_key_maken: alleen de key op stdout" "KEY=[fun_k]" "$uitvoer"
gelijk "api_key_maken: beide aanroepen met -sS en een tijdslimiet" 2 "$(grep -E -- '^CURL ' <<<"$uitvoer" | grep -- ' -sS ' | grep -c -- ' --max-time [0-9]')"
gelijk "api_key_maken: het toegangstoken niet op de commandoregel" 0 "$(grep -c -- 'Bearer' <<<"$uitvoer")"
# Een geslaagd login-antwoord dat de parser niet herkent, kan toch een token bevatten.
uitvoer=$(in_script "$KEY_STUBS curl() { echo '{\"accessToken\":\"geheim-token\"}'; }; api_key_maken") || true
bevat "api_key_maken: onbekend login-antwoord: zegt dat" "geen access_token" "$uitvoer"
bevat_niet "api_key_maken: onbekend login-antwoord: toont het niet" "geheim-token" "$uitvoer"

# --- platform_op: alleen uitrollen als het platform er aantoonbaar niet is ---

PLATFORM_STUBS='platform_bestaat() { true; }; fj() { case "$*" in deploy*) echo "UITROL $*" ;; esac; };
wacht_tot() { shift 2; "$@"; }; console_antwoordt() { true; }; platform_gereed() { true; }; organisatie_api_stabiel() { true; }; organisatie_api_open() { false; };
docker_poorten() { :; }; mkdir -p "$WORKTREE";'
code=0
uitvoer=$(in_script "$PLATFORM_STUBS kcp() { case \"\$*\" in *readyz*) return 0 ;; *) echo 'Unable to connect to the server: dial tcp 127.0.0.1:43917: connect: connection refused' >&2; return 1 ;; esac; }; platform_op") || code=$?
gelijk "platform_op: API-fout: exit 1" 1 "$code"
bevat_niet "platform_op: API-fout: niet opnieuw uitrollen" "UITROL" "$uitvoer"
code=0
uitvoer=$(in_script "$PLATFORM_STUBS kcp() { case \"\$*\" in *readyz*) return 0 ;; *) echo 'Error from server (NotFound): deployments.apps \"organization-api\" not found' >&2; return 1 ;; esac; }; platform_op") || code=$?
gelijk "platform_op: niet uitgerold: exit 0" 0 "$code"
bevat "platform_op: niet uitgerold: uitrollen met hun recipe" "UITROL deploy local" "$uitvoer"
code=0
uitvoer=$(in_script "$PLATFORM_STUBS fj() { return 1; }; kcp() { return 0; }; platform_op") || code=$?
gelijk "platform_op: starten faalt: exit 1" 1 "$code"
bevat "platform_op: starten faalt: noemt opnieuw opzetten" "af --met-platform" "$uitvoer"

# Na een hervatting is de Deployment van organization-api al gereed terwijl zijn circuit breaker nog
# elk verzoek weigert; functl org set faalde dan met unavailable.
code=0
uitvoer=$(in_script "$PLATFORM_STUBS kcp() { return 0; };
  organisatie_api_stabiel() { ORG_API_LAATSTE='{\"code\":\"unavailable\",\"message\":\"service temporarily unavailable\"}'; false; }; platform_op") || code=$?
gelijk "platform_op: organization-api weigert nog: exit 1" 1 "$code"
bevat "platform_op: organization-api weigert nog: noemt de breaker" "circuit breaker" "$uitvoer"
bevat "platform_op: organization-api weigert nog: toont het laatste antwoord" "service temporarily unavailable" "$uitvoer"
bevat "platform_op: organization-api weigert nog: logs per deployment" "logs deploy/organization-api, en " "$uitvoer"
# Ook na een verse uitrol: CreateAPIKey is de eerstvolgende aanroep op organization-api.
code=0
uitvoer=$(in_script "$PLATFORM_STUBS kcp() { case \"\$*\" in *readyz*) return 0 ;; *) echo 'Error from server (NotFound): deployments.apps \"organization-api\" not found' >&2; return 1 ;; esac; };
  organisatie_api_stabiel() { false; }; platform_op") || code=$?
gelijk "platform_op: na een verse uitrol ook wachten op organization-api: exit 1" 1 "$code"
bevat "platform_op: na een verse uitrol: eerst uitgerold" "UITROL deploy local" "$uitvoer"
bevat "platform_op: na een verse uitrol: dan gewacht op organization-api" "circuit breaker" "$uitvoer"
# Dicht op één moment is niet genoeg: de breaker kan een peiling later alsnog opengaan.
# Aangeroepen zoals wacht_tot het doet: daar staat errexit in de functie uit.
STABIEL_STUBS='sleep() { :; }; authz_worker_gereed() { [ -z "${WORKER_NIET_GEREED:-}" ]; };
organisatie_api_open() { echo x >>"$XDG_CACHE_HOME/peilingen"; n=$(wc -l <"$XDG_CACHE_HOME/peilingen"); [ "$n" != "${FAALT_BIJ:-0}" ]; };'
for geval in '0|0|4' '1|1|1' '3|1|3' '4|1|4'; do
  IFS='|' read -r bij verwacht peilingen <<<"$geval"
  rm -f "$TESTCACHE/peilingen"
  code=0
  uitvoer=$(FAALT_BIJ=$bij in_script "$STABIEL_STUBS if organisatie_api_stabiel; then exit 0; else exit 1; fi") || code=$?
  gelijk "organisatie_api_stabiel: peiling $bij open (0 = geen): exit $verwacht" "$verwacht" "$code"
  gelijk "organisatie_api_stabiel: peiling $bij open: $peilingen peiling(en)" "$peilingen" "$(wc -l <"$TESTCACHE/peilingen")"
done
rm -f "$TESTCACHE/peilingen"
code=0
uitvoer=$(WORKER_NIET_GEREED=1 in_script "$STABIEL_STUBS if organisatie_api_stabiel; then exit 0; else echo \"\$ORG_API_LAATSTE\"; exit 1; fi") || code=$?
gelijk "organisatie_api_stabiel: authz-worker niet gereed: exit 1" 1 "$code"
gelijk "organisatie_api_stabiel: authz-worker niet gereed: geen peiling van organization-api" 0 "$( [ -f "$TESTCACHE/peilingen" ] && wc -l <"$TESTCACHE/peilingen" || echo 0)"
bevat "organisatie_api_stabiel: authz-worker niet gereed: zegt dat" "authz-worker niet gereed" "$uitvoer"
rm -f "$TESTCACHE/peilingen"
# Van de pods, niet van de Deployment: die toont na een hervatting nog de stand van vóór de pauze.
for geval in 'authz-worker-1 - True|0' 'authz-worker-1 - False|1' '|1'; do
  code=0
  uitvoer=$(in_script "kcp() { echo \"KCP \$*\" >>\"\$XDG_CACHE_HOME/kcp\"; printf '%s\\n' '${geval%|*}'; }; if authz_worker_gereed; then exit 0; else exit 1; fi") || code=$?
  gelijk "authz_worker_gereed: '${geval%|*}': exit ${geval#*|}" "${geval#*|}" "$code"
done
bevat "authz_worker_gereed: kijkt naar de pods van de worker" "-n fundament get pods -l app.kubernetes.io/name=authz-worker" "$(cat "$TESTCACHE/kcp")"
rm -f "$TESTCACHE/kcp"
uitvoer=$(in_script 'sleep() { echo "SLAAP $1"; }; authz_worker_gereed() { true; }; organisatie_api_open() { true; }; organisatie_api_stabiel')
gelijk "organisatie_api_stabiel: 15 s tussen eerste en laatste peiling" 3 "$(grep -c '^SLAAP 5$' <<<"$uitvoer")"
OPEN_STUBS='mkcert_caroot() { echo /ca; };'
for geval in 'unauthenticated|0' 'unavailable|1'; do
  code=0
  uitvoer=$(in_script "$OPEN_STUBS curl() { echo '{\"code\":\"${geval%|*}\"}'; }; organisatie_api_open") || code=$?
  gelijk "organisatie_api_open: ${geval%|*}: exit ${geval#*|}" "${geval#*|}" "$code"
done
code=0
uitvoer=$(in_script "$OPEN_STUBS curl() { echo 'no healthy upstream'; }; organisatie_api_open") || code=$?
gelijk "organisatie_api_open: Envoy zonder backend: exit 1" 1 "$code"
code=0
uitvoer=$(in_script "$OPEN_STUBS curl() { echo '{\"code\":\"unauthenticated\"}'; return 7; }; organisatie_api_open") || code=$?
gelijk "organisatie_api_open: curl faalt: exit 1" 1 "$code"
uitvoer=$(in_script "$OPEN_STUBS curl() { echo \"CURL \$*\" >&2; echo '{}'; }; organisatie_api_open || true; echo \"\$ORG_API_LAATSTE\"") || true
bevat "organisatie_api_open: curl met melding (-sS)" "CURL -sS " "$uitvoer"
bevat "organisatie_api_open: vraagt organization-api zonder token" "organization.fundament.localhost:8443/organization.v1.OrganizationService/ListOrganizations" "$uitvoer"
bevat_niet "organisatie_api_open: zonder token" "Authorization" "$uitvoer"
bevat_niet "organisatie_api_open: zonder token, ook niet uit een bestand" " -H @" "$uitvoer"
# wacht_tot kijkt pas tussen twee pogingen naar de klok; een curl zonder limiet houdt op vast.
bevat "organisatie_api_open: met een tijdslimiet" "--max-time " "$uitvoer"
bevat "organisatie_api_open: als Connect-POST" "-X POST" "$uitvoer"
bevat "organisatie_api_open: met de CA van mkcert" "--cacert /ca/rootCA.pem" "$uitvoer"
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

echo "== de README noemt de worktree van de pin =="

# De blokken om te pauzeren en te herstarten gaan naar de worktree van Fundament; na een ophoging
# van FUNDAMENT_REF zou een oud pad naar een map wijzen die er niet meer is.
pin=$(sed -nE 's/^FUNDAMENT_REF=\$\{FUNDAMENT_REF:-([0-9a-f]{8})[0-9a-f]{32}\}$/\1/p' "$HIER/../fundament-testcluster.sh")
paden=$(grep -E '^[[:space:]]*\(?cd .*fbs-fundament-testcluster/fundament-' "$HIER/../README.md" || true)
gelijk "de pin is te lezen uit het script" 8 "${#pin}"
gelijk "de README heeft blokken met de worktree" 1 "$([ -n "$paden" ] && echo 1 || echo 0)"
gelijk "elk blok noemt de worktree van de pin" "" "$(grep -v "/fundament-$pin\"" <<<"$paden" || true)"
# Elk blok met k3d gaat eerst naar de worktree en daarna terug; buiten die map vindt mise geen k3d.
k3d_blokken=$(grep -cE '^mise exec -- k3d cluster stop ' "$HIER/../README.md" || true)
gelijk "elk k3d-blok gaat naar de worktree" "$k3d_blokken" "$(grep -c . <<<"$paden")"
gelijk "elk k3d-blok gaat daarna terug" "$k3d_blokken" "$(grep -cxE 'cd -' "$HIER/../README.md" || true)"

echo "== geen umask voor het hele proces =="

# Een strenge umask vóór de checkout van Fundament maakt hun bestanden 600; Docker kopieert die
# rechten in de images en de niet-root-gebruiker kan ze dan niet lezen (trek.yaml: permission
# denied). De werkmap van mktemp -d is al 700.
umask_regels=$(grep -nE '^[[:space:]]*umask[[:space:]]' "$HIER/../fundament-testcluster.sh" || true)
gelijk "geen umask in fundament-testcluster.sh" "" "$umask_regels"

echo "== de demo op de sandbox =="

coredns=$(manifest_coredns_demo envoy-x.envoy-gateway-system.svc.cluster.local)
bevat "CoreDNS: *.fbs.localhost naar de proxy's, verankerd" \
  'rewrite name regex ^(.*)\.fbs\.localhost\.$ envoy-x.envoy-gateway-system.svc.cluster.local. answer auto' "$coredns"
bevat "CoreDNS: in coredns-custom, dat k3s inleest" 'name: coredns-custom' "$coredns"
bevat "CoreDNS: in kube-system, waar k3s hem zoekt" 'namespace: kube-system' "$coredns"
bevat "CoreDNS: als .override, binnen het standaardblok" 'fbs-demo.override: |' "$coredns"

echo "== demo(): de flow =="

# demo() met stubs voor hm, kc, docker en env: welke release verdwijnt, wat er naar
# demo-op-fundament.sh gaat, en waar het stopt.
DEMO_STUBS='WERK=$(mktemp -d); TAG=main-1234567;
hm() { echo "HM $*" >&2; case "$*" in "list "*) [ "${HMLIST:-leeg}" = fout ] && return 1; [ "${HMLIST:-leeg}" = ja ] && echo berichtenmagazijn; return 0 ;; esac; };
kc() { case "$*" in *"get service"*) echo "${SVC-envoy-x}" ;; *"get configmap coredns"*) echo "${COREFILE-import /etc/coredns/custom/*.override}" ;; *"get secret web-tls"*) printf CA | base64 ;; *"-f -"*) cat >/dev/null ;; *"delete httproute"*) echo "KC $*" >&2 ;;
  *"get httproute"*) r=$(printf "%s\n" "$@" | grep -xE "berichtenuitvraag|berichtenmagazijn|stelselregister"); case " ${LOS-} ${HELM-} " in *" $r "*) [[ " ${HELM-} " != *" $r "* ]] || echo "$r" ;; *) return 1 ;; esac ;; esac; };
docker() { case "$*" in *berichtenmagazijn*JAVA_HOME*) echo "${JH_MAGAZIJN-/usr/lib/jvm/jre}" ;; *importkeystore*) cat >/dev/null; echo "KEYTOOL $*" >&2; [ "${KEYTOOL:-ok}" = ok ] && echo KEYSTORE ;; *JAVA_HOME*) echo "${JH-/usr/lib/jvm/jre}" ;; esac; };
env() { echo "ENV $*"; ls "$WERK/demo-values"; };'

uitvoer=$(in_script "$DEMO_STUBS demo")
bevat "demo: geeft de kubeconfig van de sandbox door" "KUBECONFIG=$TESTCACHE/fbs-fundament-testcluster/" "$(echo "$uitvoer" | grep "^ENV")"
bevat "demo: met de context van de sandbox" "KUBE_CONTEXT=k3d-fundament-plugin" "$uitvoer"
bevat "demo: met de lokale CA voor de controle" "CA_BESTAND=" "$uitvoer"
gelijk "demo: extra values voor uitvraag en beide magazijnen, niet het stelselregister" \
  "berichtenmagazijn-a.yaml berichtenmagazijn-b.yaml berichtenuitvraag.yaml" \
  "$(echo "$uitvoer" | grep '\.yaml$' | sort | paste -sd' ' -)"
bevat_niet "demo: geen uninstall zonder oude release" "HM uninstall" "$uitvoer"
keytool=$(echo "$uitvoer" | sed -n '/^KEYTOOL/,/cat \/tmp\/jssecacerts/p')
bevat "demo: jssecacerts met de keytool van de uitvraag" "ghcr.io/minbzk/fbs-berichtenuitvraag:main-1234567" "$keytool"
bevat "demo: certificaten zonder versleuteling" "-Dkeystore.pkcs12.certProtectionAlgorithm=NONE" "$keytool"
bevat "demo: zonder MAC" "-Dkeystore.pkcs12.macAlgorithm=NONE" "$keytool"
regel=$(echo "$keytool" | grep -o 'keytool -list[^<]*')
bevat "demo: leest de lokale CA terug" "-alias fbs-lokale-ca" "$regel"
bevat_niet "demo: leest hem terug zonder wachtwoord, zoals de JVM" "storepass" "$regel"

uitvoer=$(in_script "$DEMO_STUBS HMLIST=ja; demo")
bevat "demo: de oude magazijn-release verdwijnt" "HM uninstall berichtenmagazijn" "$uitvoer"
bevat_niet "demo: zonder losse routes wordt er geen verwijderd" "KC " "$uitvoer"

# Na een ronde als projectlid: de routes die de beheerder los van Helm maakte verdwijnen, ook die van
# het oude magazijn; een route van een Helm-release blijft staan.
uitvoer=$(in_script "$DEMO_STUBS HMLIST=ja; LOS=\"berichtenuitvraag berichtenmagazijn stelselregister\"; demo")
gelijk "demo: alle drie losse routes verdwijnen" \
  "berichtenmagazijn berichtenuitvraag stelselregister" \
  "$(echo "$uitvoer" | sed -n 's/^KC .*delete httproute //p' | sort | paste -sd' ' -)"
bevat "demo: de losse routes verdwijnen in de namespace van de proef" "KC -n tnt-fbs--fbs delete httproute" "$uitvoer"
uitvoer=$(in_script "$DEMO_STUBS LOS=stelselregister; HELM=berichtenuitvraag; demo")
gelijk "demo: alleen de losse route verdwijnt, niet die van Helm" "stelselregister" \
  "$(echo "$uitvoer" | sed -n 's/^KC .*delete httproute //p' | paste -sd' ' -)"

# Met een oude release: een stop laat die staan.
for geval in "HMLIST=fout|helm list in" "SVC=|geen Service voor de proxy's" "SVC='a b'|meer dan één Service" \
    "COREFILE=|importeert coredns-custom niet" "JH=|JAVA_HOME van fbs-berichtenuitvraag" \
    "JH_MAGAZIJN=/anders|JAVA_HOME verschilt" "KEYTOOL=fout|jssecacerts die de JVM zonder wachtwoord leest"; do
  stub=${geval%%|*}
  [ "$stub" = HMLIST=fout ] || stub="HMLIST=ja; $stub"
  uitvoer=$(in_script "$DEMO_STUBS $stub; demo" || true)
  bevat "demo stopt bij ${geval%%|*}" "${geval#*|}" "$uitvoer"
  bevat_niet "demo stopt bij ${geval%%|*}: niets geïnstalleerd" "ENV " "$uitvoer"
  bevat_niet "demo stopt bij ${geval%%|*}: de oude release blijft" "HM uninstall" "$uitvoer"
  bevat_niet "demo stopt bij ${geval%%|*}: de losse routes blijven" "KC " "$uitvoer"
done

echo "ASSERTIES=$asserties"
exit "$mislukt"
