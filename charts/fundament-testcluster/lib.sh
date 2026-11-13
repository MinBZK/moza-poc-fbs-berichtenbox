#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Bouwstenen voor charts/fundament-testcluster.sh: parsers, manifests en values zonder
# bijwerkingen, zodat ze zonder cluster te toetsen zijn (test-lib.sh). Wordt gesourcet; zet daarom
# zelf geen shell-opties.

# Versie en hash uit de uitvoer van `functl plugin publish`, als "<versie> <hash>". De hash is wat
# een PluginInstallation pint; een afgekapte hash zou een installatie opleveren die nooit start.
publicatie_lees() {
  local regel

  regel=$(grep -E '^published plugin=' <<<"$1" | tail -1)
  [ -n "$regel" ] || return 1

  local versie hash
  versie=$(sed -nE 's/.* version=([^ ]+).*/\1/p' <<<"$regel")
  hash=$(sed -nE 's/.* hash=(sha256:[0-9a-f]{64})( .*|$)/\1/p' <<<"$regel")

  [ -n "$versie" ] && [ -n "$hash" ] || return 1
  echo "$versie $hash"
}

# De plugin-id uit dezelfde regel: `funops plugin approve` vraagt hem.
publicatie_plugin_id() {
  grep -E '^published plugin=' <<<"$1" | tail -1 | sed -nE 's/.* id=([0-9a-f-]{36})( .*|$)/\1/p' | grep . || return 1
}

# De hash uit het antwoord van GetPluginDefinition van de catalogus (Connect, JSON): voor een
# versie die al eerder gepubliceerd en goedgekeurd is, staat hij niet meer in een publish-uitvoer.
hash_uit_catalogus() {
  jq -er '.definitionHash | select(type == "string" and test("^sha256:[0-9a-f]{64}$"))' <<<"$1" 2>/dev/null || return 1
}

# De versie uit de melding van functl dat een versie al bestaat (versies zijn create-only).
bestaande_versie() {
  sed -nE 's/.*already_exists: version "([^"]+)" already exists.*/\1/p' <<<"$1" | grep . || return 1
}

# "<status> <plugin-id>" van een versie van een first-party plug-in, uit `funops plugin list
# --status all -o json`. Alleen de organisatie system: een andere organisatie mag dezelfde naam
# gebruiken.
versie_uit_catalogus() {
  local catalogus=$1 plugin=$2 versie=$3

  jq -er --arg p "$plugin" --arg v "$versie" \
    'first(.[] | select(.organization == "system" and .plugin == $p and .version == $v)) | "\(.status) \(.plugin_id)"' \
    <<<"$catalogus" 2>/dev/null || return 1
}

# Soort fout uit de uitvoer van functl: geen-listing, bestaat-al, niet-ingelogd of onbekend. Na een nieuwe
# uitrol van het platform zijn de API-keys weg; dat moet als "maak een nieuwe key" bij de gebruiker
# aankomen en niet als een kale RPC-fout.
functl_fout_soort() {
  case "$1" in
    *"no listing named"*) echo geen-listing ;;
    *already_exists*) echo bestaat-al ;;
    *unauthenticated* | *"not authenticated"* | *" 401 "* | *"401 Unauthorized"* | *"(401)"*) echo niet-ingelogd ;;
    *) echo onbekend ;;
  esac
}

# De key uit het antwoord van CreateAPIKey (Connect, JSON). Een Connect-fout heeft geen token.
api_key_uit_antwoord() {
  jq -er '.token | select(type == "string" and length > 0)' <<<"$1" 2>/dev/null || return 1
}

# Een willekeurig wachtwoord in een bestand, zonder newline: `kubectl create secret --from-file`
# neemt de newline over, en Redis knipt hem weg uit zijn argumenten terwijl een client hem
# meestuurt (WRONGPASS).
geheim_bestand() {
  openssl rand -hex 24 | tr -d '\n' >"$1"
}

# Slaagt als een PluginInstallation (JSON van kubectl) draait en gereed is.
plugin_staat_klaar() {
  jq -e '.status.phase == "Running" and .status.ready == true' <<<"$1" >/dev/null 2>&1 || return 1
}

# Slaagt als een bestaande sandbox van deze opstelling kan zijn: Kubernetes 1.36 en de https-poort
# op loopback. Een sandbox van `just plugins cluster-create` heeft die mapping niet; die stil
# gebruiken zou een Gateway opleveren die van buiten onbereikbaar is.
#   $1 serverversie (kubectl version), $2 uitvoer van `docker port <serverlb>`, $3 de https-poort
sandbox_past() {
  local versie=$1 mappings=$2 poort=$3

  case "$versie" in
    v1.36.*) ;;
    *)
      echo "De sandbox draait Kubernetes $versie; deze opstelling vraagt 1.36." >&2
      return 1
      ;;
  esac

  if ! grep -qxE "443/tcp -> 127\.0\.0\.1:$poort" <<<"$mappings"; then
    echo "De sandbox heeft geen mapping 127.0.0.1:$poort -> 443 (HTTPS_POORT=$poort)." >&2
    return 1
  fi

  # Een tweede mapping op een ander adres zet de proefroutes, zonder authenticatie, op het netwerk.
  if grep -E '^443/tcp -> ' <<<"$mappings" | grep -qvxE "443/tcp -> 127\.0\.0\.1:$poort"; then
    echo "De sandbox heeft naast 127.0.0.1:$poort nog een mapping op 443; die is niet van deze opstelling." >&2
    return 1
  fi
}

# Slaagt voor een volledige commit-SHA. Een korte SHA of een branchnaam laat de pin op Fundament
# stil meebewegen.
is_commit_sha() {
  [[ $1 =~ ^[0-9a-f]{40}$ ]]
}

# Een k3d-config van Fundament met hun poorten en registry op loopback in plaats van 0.0.0.0. Zij
# binden 8443 al bewust op loopback; de database (54328) en de registries (plain HTTP, zonder
# authenticatie) niet, en die horen niet op het netwerk van een laptop op een gedeelde wifi.
# Leest de config op stdin.
k3d_config_loopback() {
  sed -E \
    -e 's/^([[:space:]]*- port: )([0-9]+:[0-9]+)[[:space:]]*$/\1127.0.0.1:\2/' \
    -e 's/^([[:space:]]*host: )"0\.0\.0\.0"/\1"127.0.0.1"/'
}

# Het image van een plug-in uit zijn definitie (de manifest uit de catalogus), met digest. Na een
# opgeruimde registry moet precies dat image er weer zijn, anders start de plug-in niet.
image_uit_definitie() {
  sed -nE 's/^[[:space:]]*image:[[:space:]]*([^[:space:]]+@sha256:[0-9a-f]{64})[[:space:]]*$/\1/p' <<<"$1" | head -1 | grep . || return 1
}

# Gepubliceerde poorten op alle adressen (0.0.0.0 of ::), uit regels "<container>|<poorten>" van
# docker ps; print "<container> <binding>" per poort. De Kubernetes-API (->6443) telt niet mee: die
# zet k3d met de configs van Fundament op alle adressen. Anoniem geeft hij 401; de README noemt hem.
poorten_naar_buiten() {
  local naam poorten binding
  local -a bindingen

  while IFS='|' read -r naam poorten; do
    IFS=',' read -ra bindingen <<<"$poorten"

    for binding in "${bindingen[@]}"; do
      binding=${binding# }

      case "$binding" in
        *"->6443/tcp") ;;
        0.0.0.0:* | "[::]:"* | :::*) echo "$naam $binding" ;;
      esac
    done
  done
}

# De geconfigureerde poortbindingen uit `docker inspect` (JSON op stdin), als regels
# "<container>|<binding>, ..." voor poorten_naar_buiten. Uit HostConfig en niet uit docker ps: een
# gestopte container toont daar geen poorten, en publiceert ze toch zodra hij start. Een lege
# HostIp is alle adressen.
poortbindingen_uit_inspect() {
  jq -er '.[] | (.Name | ltrimstr("/")) + "|" + ([(.HostConfig.PortBindings // {}) | to_entries[]
    | .key as $doel | (.value // [])[]
    | (if (.HostIp // "") == "" then "0.0.0.0" else .HostIp end) + ":" + .HostPort + "->" + $doel]
    | join(", "))'
}

# Slaagt als de chart-values van een dienst (charts/fbs-dienst/diensten/<dienst>.yaml) hem op
# precies één replica vastzetten. De chart weigert dan elk ander aantal.
enkele_replica() {
  grep -qE '^[[:space:]]*singleReplica:[[:space:]]*true[[:space:]]*$' "$1"
}

# Slaagt als kubectl meldde dat het object niet bestaat. Elke andere fout (API onbereikbaar, geen
# rechten) zegt niets over of het er is.
kubectl_niet_gevonden() {
  grep -q '^Error from server (NotFound)' <<<"$1"
}

# Slaagt als niemand op 127.0.0.1:<poort> luistert. Ook een listener op alle adressen bezet hem.
poort_vrij() {
  ! ss -ltnH "sport = :$1" | grep -q .
}

# --- Manifests -----------------------------------------------------------------------------------

# Een PluginInstallation van een first-party plug-in (organisatie system), gepind op de hash van
# de definitie. Zonder hash faalt de controller bij zijn eerste update ("definitionRef is immutable
# once set"), ook in de sandbox die ongepinde installaties toestaat.
manifest_plugininstallatie() {
  local plugin=$1 versie=$2 hash=$3

  [ -n "$hash" ] || return 1

  cat <<EOF
apiVersion: plugins.fundament.io/v1
kind: PluginInstallation
metadata:
  name: system--$plugin
spec:
  definitionRef:
    organizationName: system
    pluginName: $plugin
    pluginVersion: "$versie"
    definitionHash: "$hash"
EOF
}

# Een namespace zoals de console hem maakt (tnt-<project>--<naam>), met de LimitRange die
# cluster-worker zet (cluster-worker/pkg/handler/namespace in de Fundament-repo). Zonder de
# beheerlabels van cluster-worker: die van het platform ziet de sandbox en verwijdert elke
# namespace met die labels waarvan hij de id niet kent. Pod Security restricted is strenger dan een
# tenant-namespace, maar is wat geldt in de namespace van een plug-in, en de chart moet ook door een
# plug-in te installeren zijn.
manifest_tenant_namespace() {
  local ns=$1

  case "$ns" in
    tnt-*--*) ;;
    *) return 1 ;;
  esac

  cat <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: $ns
  labels:
    pod-security.kubernetes.io/enforce: restricted
---
apiVersion: v1
kind: LimitRange
metadata:
  name: fundament-defaults
  namespace: $ns
spec:
  limits:
    - type: Container
      default: {cpu: 500m, memory: 512Mi}
      defaultRequest: {cpu: 100m, memory: 128Mi}
EOF
}

# Een projectlid: alleen de ClusterRole admin binnen de namespace, zoals Fundament die bindt.
manifest_projectlid() {
  local ns=$1

  cat <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: projectlid
  namespace: $ns
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: projectlid-admin
  namespace: $ns
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: admin
subjects:
  - kind: ServiceAccount
    name: projectlid
    namespace: $ns
EOF
}

# Een eigen CA in de namespace, in plaats van Let's Encrypt: lokaal is er geen publieke hostnaam.
manifest_lokale_ca() {
  local ns=$1

  cat <<EOF
apiVersion: cert-manager.io/v1
kind: Issuer
metadata:
  name: zelfondertekend
  namespace: $ns
spec:
  selfSigned: {}
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: lokale-ca
  namespace: $ns
spec:
  isCA: true
  commonName: fbs-fundament-testcluster
  secretName: lokale-ca
  privateKey: {algorithm: ECDSA, size: 256}
  issuerRef: {name: zelfondertekend, kind: Issuer}
---
apiVersion: cert-manager.io/v1
kind: Issuer
metadata:
  name: lokale-ca
  namespace: $ns
spec:
  ca:
    secretName: lokale-ca
EOF
}

# De Gateway zoals op het fieldlab: in de eigen namespace, alleen https, met het certificaat via de
# annotatie van cert-manager. Geen HTTP-listener: de chart hangt een route aan één benoemde
# listener, en over http zouden BSN en berichten onversleuteld gaan.
manifest_gateway() {
  local ns=$1

  cat <<EOF
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: web
  namespace: $ns
  annotations:
    cert-manager.io/issuer: lokale-ca
spec:
  gatewayClassName: eg
  listeners:
    - name: https
      protocol: HTTPS
      port: 443
      hostname: "*.fbs.localhost"
      tls:
        mode: Terminate
        certificateRefs:
          - name: web-tls
      allowedRoutes:
        namespaces:
          from: Same
EOF
}

# Een database zoals de console van de CloudNativePG-plug-in hem maakt, maar met major 18: de
# diensten draaien op PostgreSQL 18, de console biedt alleen 16 en 17.
manifest_cnpg_cluster() {
  local naam=$1 ns=$2

  cat <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: $naam
  namespace: $ns
  labels:
    app.kubernetes.io/managed-by: fundament-cloudnativepg
spec:
  instances: 1
  imageCatalogRef:
    apiGroup: postgresql.cnpg.io
    kind: ClusterImageCatalog
    name: fundament-postgresql
    major: 18
  enablePDB: false
  storage:
    size: 1Gi
EOF
}

# Redis Stack als proefvoorziening (er is geen plug-in), zoals in lokaal-testcluster.sh: het image
# draait standaard als root, als UID 999 werkt het met een beschrijfbare /data.
manifest_proef_redis() {
  local ns=$1 image=$2

  cat <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: proef-redis
  namespace: $ns
spec:
  selector: {matchLabels: {app: proef-redis}}
  template:
    metadata: {labels: {app: proef-redis}}
    spec:
      securityContext: {runAsNonRoot: true, runAsUser: 999, runAsGroup: 999, fsGroup: 999, seccompProfile: {type: RuntimeDefault}}
      volumes: [{name: data, emptyDir: {}}]
      containers:
        - name: redis
          image: $image
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}}
          volumeMounts: [{name: data, mountPath: /data}]
          env:
            - name: REDIS_PASSWORD
              valueFrom: {secretKeyRef: {name: proef-voorzieningen, key: REDIS_PASSWORD}}
            - name: REDIS_ARGS
              value: "--requirepass \$(REDIS_PASSWORD) --save '' --appendonly no"
          ports: [{containerPort: 6379}]
---
apiVersion: v1
kind: Service
metadata:
  name: proef-redis
  namespace: $ns
spec:
  selector: {app: proef-redis}
  ports: [{port: 6379}]
EOF
}

# Een eenmalige pod die /q/health/ready van een dienst binnen het cluster opvraagt en de
# statuscode logt, met de exitcode van curl en het aantal connections erachter (zie
# probe_geweigerd). Het label is wat de NetworkPolicy van de chart doorlaat; zonder komt hij niet
# binnen. Een nieuwe pod staat pas na een korte vertraging in de lijsten van de policy-controller,
# vandaar de herhaling, met én zonder label: tot dan ziet een te ruime policy er net zo uit als een
# weigering, en curl stopt bij het eerste antwoord.
manifest_probe() {
  local ns=$1 dienst=$2 image=$3 soort=${4:-met-label}
  local naam=probe-$dienst labels="  labels:
    client.fbs.minbzk.nl/$dienst: \"true\""

  if [ "$soort" = zonder-label ]; then
    naam=probe-zonder-label-$dienst
    labels=""
  fi

  cat <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $naam
  namespace: $ns
$labels
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 100, runAsGroup: 101, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: curl
      image: $image
      securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: [ALL]}}
      args: [-s, -o, /dev/null, -w, '%{http_code} %{exitcode} %{num_connects}', --max-time, "5", --retry, "15", --retry-delay, "2", --retry-all-errors, "http://$dienst.$ns.svc/q/health/ready"]
EOF
}

# Slaagt als er pods zijn en elke regel van gereedheid_stempel ("<pod> <restarts> <Ready> <sinds>")
# Ready True heeft.
alle_pods_gereed() {
  [ -n "$1" ] || return 1
  ! awk '$3 != "True" { gevonden = 1 } END { exit !gevonden }' <<<"$1"
}

# Slaagt als de uitvoer van een probe ("<http-status> <exitcode van curl> <connections>") toont
# dat de connection geweigerd werd: geen antwoord, geen TCP-connection, en curl-exitcode 7, zoals
# de policy-controller van k3s weigert. Een timeout (28) telt niet: die geeft ook een trage dienst
# of een DNS-timeout. Een probe die niet draaide of de naam niet vond, zegt niets over de
# NetworkPolicy.
probe_geweigerd() {
  local status exitcode connections rest

  read -r status exitcode connections rest <<<"$1"
  [ "$status" = 000 ] && [ "$exitcode" = 7 ] && [ "$connections" = 0 ] && [ -z "$rest" ]
}

# --- Values --------------------------------------------------------------------------------------

readonly DIENSTEN=(berichtenuitvraag berichtenmagazijn stelselregister)

# Het values-bestand bovenop charts/fbs-dienst/diensten/<dienst>.yaml. De adressen naar buiten zijn
# onbereikbaar maar https: de readiness hangt alleen aan database, Redis en het stelseldocument,
# en berichtenverkeer tussen de diensten valt buiten deze proef. Het magazijn krijgt daarnaast
# charts/voorbeelden/berichtenmagazijn-cnpg.yaml, dat zijn database uit het Secret db-app haalt.
values_dienst() {
  local dienst=$1 ns=$2

  case "$dienst" in
    berichtenuitvraag)
      # Het JDBC-adres stelt de pod zelf samen uit de velden van het CNPG-Secret, zoals het
      # voorbeeld van het magazijn: het veld uri is geen JDBC, en jdbc-uri zet het wachtwoord
      # in de URL.
      cat <<EOF
dienst:
  allowUnsafeFlags: true
  extraEnvVars:
    - name: LDV_HOST
      valueFrom: {secretKeyRef: {name: ldv-uitvraag-app, key: host}}
    - name: LDV_PORT
      valueFrom: {secretKeyRef: {name: ldv-uitvraag-app, key: port}}
    - name: LDV_DBNAME
      valueFrom: {secretKeyRef: {name: ldv-uitvraag-app, key: dbname}}
    - name: LDV_POSTGRES_USERNAME
      valueFrom: {secretKeyRef: {name: ldv-uitvraag-app, key: username}}
    - name: LDV_POSTGRES_PASSWORD
      valueFrom: {secretKeyRef: {name: ldv-uitvraag-app, key: password}}
    - name: LDV_POSTGRES_URL
      value: jdbc:postgresql://\$(LDV_HOST):\$(LDV_PORT)/\$(LDV_DBNAME)?sslmode=require
  env:
    REDIS_HOSTS: redis://proef-redis.$ns.svc:6379
    FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT: "true"
    PROFIEL_SERVICE_URL: https://profiel.invalid
    MAGAZIJN_A_URL: https://berichtenmagazijn.invalid
    MAGAZIJN_B_URL: https://magazijn-b.invalid
EOF
      ;;
    berichtenmagazijn)
      cat <<EOF
dienst:
  env:
    MAGAZIJN_OIN: "00000001003214345000"
    AANMELD_URL: https://aanmeld.invalid
    NOTIFICATIE_URL: https://notificatie.invalid
    PROFIEL_SERVICE_URL: https://profiel.invalid
EOF
      ;;
    stelselregister)
      cat <<EOF
dienst:
  env:
    STELSELDOCUMENT_UITGEVER_OIN: "00000000000000001000"
    STELSELDOCUMENT_OMGEVING: lokaal
    MAGAZIJN_A_URL: https://berichtenmagazijn.invalid
    MAGAZIJN_B_URL: https://magazijn-b.invalid
EOF
      ;;
    *) return 1 ;;
  esac
}

# De helm-argumenten voor een route aan de https-listener van Gateway web, één per regel (lees ze
# met mapfile). allowUnauthenticated: de route staat alleen op loopback open.
helm_route_args() {
  local dienst=$1

  case " ${DIENSTEN[*]} " in
    *" $dienst "*) ;;
    *) return 1 ;;
  esac

  printf '%s\n' \
    --set ingress.enabled=true \
    --set ingress.allowUnauthenticated=true \
    --set "ingress.hostname=$dienst.fbs.localhost" \
    --set cluster.gateway.name=web \
    --set cluster.gateway.sectionName=https
}
