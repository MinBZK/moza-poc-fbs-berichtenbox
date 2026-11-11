#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Installeert de drie diensten met de chart op een k3d-cluster, achter Envoy Gateway zoals op
# Fundament, en controleert dat ze gezond starten en via de Gateway bereikbaar zijn.
#
#   TAG=main-<sha7> charts/lokaal-testcluster.sh          opzetten en controleren
#   charts/lokaal-testcluster.sh opruimen                  cluster weg
#
# Vereist k3d, kubectl, helm, openssl en curl op de host; draait niet in een container zonder
# /dev/fuse en /lib/modules. PostgreSQL en Redis Stack zijn proefvoorzieningen in het cluster,
# zonder TLS: de diensten krijgen daarvoor hun onveilige kleppen (die loggen bij elke start
# REDIS_UNPROTECTED en OUTBOUND_TLS_DISABLED). Op Fundament levert
# MinBZK/MijnOverheidZakelijk#1536 de echte voorzieningen.
#
# Elke kubectl- en helm-aanroep noemt de context van dit cluster, en het script wisselt de
# huidige context niet: wie tijdens het draaien elders van context wisselt, mag deze proefinstallatie
# met haar onveilige kleppen niet in een echt cluster krijgen.
set -euo pipefail

CHARTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$CHARTS/.." && pwd)
CLUSTER=${CLUSTER:-fbs-chart}
CONTEXT="k3d-$CLUSTER"
NS=fbs
HTTP_POORT=${HTTP_POORT:-8080}
ENVOY_GATEWAY_VERSIE=v1.9.3
PG_IMAGE=docker.io/library/postgres:18@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42
REDIS_IMAGE=docker.io/redis/redis-stack-server:7.4.0-v3@sha256:7d8e657d60d525534d6abe96e7645ab30ef1b4f1ffedc6eeb2d4d4283b3a49b9
DIENSTEN=(berichtenuitvraag berichtenmagazijn stelselregister)

if [ "${1:-}" = opruimen ]; then
  k3d cluster delete "$CLUSTER"
  exit 0
fi

TAG=${TAG:?zet TAG op een gepubliceerde image-tag, bijvoorbeeld main-<sha7> van de laatste main-commit}

for gereedschap in k3d kubectl helm openssl curl; do
  command -v "$gereedschap" >/dev/null || { echo "FOUT: $gereedschap ontbreekt." >&2; exit 2; }
done

kc() { kubectl --context "$CONTEXT" "$@"; }
hm() { helm --kube-context "$CONTEXT" "$@"; }

umask 077
WERK=$(mktemp -d)
trap 'rm -rf "$WERK"' EXIT

echo "== cluster =="

if ! k3d cluster get "$CLUSTER" >/dev/null 2>&1; then
  # Traefik uit: de routering loopt zoals op Fundament via Envoy Gateway. Alleen op loopback: de
  # proefinstallatie draait zonder TLS en zonder authenticatie.
  k3d cluster create "$CLUSTER" --agents 0 -p "127.0.0.1:$HTTP_POORT:80@loadbalancer" \
    --k3s-arg '--disable=traefik@server:0' --kubeconfig-switch-context=false --wait
fi

echo "== Envoy Gateway $ENVOY_GATEWAY_VERSIE (met de Gateway-API-CRD's) =="

hm upgrade --install eg oci://docker.io/envoyproxy/gateway-helm --version "$ENVOY_GATEWAY_VERSIE" \
  --namespace envoy-gateway-system --create-namespace --wait --timeout 5m
kc -n envoy-gateway-system wait --for=condition=Available deployment/envoy-gateway --timeout=5m

kc create namespace "$NS" --dry-run=client -o yaml | kc apply -f -
# Zoals Fundament de namespaces van plug-ins inricht: de API-server weigert elke pod die Pod
# Security "restricted" niet haalt, ook de proefvoorzieningen hieronder.
kc label namespace "$NS" pod-security.kubernetes.io/enforce=restricted --overwrite

# Een platte HTTP-listener: lokaal is er geen certificaat. Op Fundament eindigt TLS op de Gateway.
kc apply -f - <<EOF
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata:
  name: eg
spec:
  controllerName: gateway.envoyproxy.io/gatewayclass-controller
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: proef
  namespace: $NS
spec:
  gatewayClassName: eg
  listeners:
    - name: http
      protocol: HTTP
      port: 80
      allowedRoutes:
        namespaces:
          from: Same
EOF

kc -n "$NS" wait gateway/proef --for=condition=Programmed --timeout=5m

echo "== proefvoorzieningen: PostgreSQL en Redis Stack =="

# Geheimen als bestand, niet op de commandoregel: die is via ps zichtbaar voor andere gebruikers.
openssl rand -hex 24 >"$WERK/pg-wachtwoord"
openssl rand -hex 24 >"$WERK/redis-wachtwoord"

kc -n "$NS" create secret generic proef-voorzieningen \
  --from-file=POSTGRES_PASSWORD="$WERK/pg-wachtwoord" --from-file=REDIS_PASSWORD="$WERK/redis-wachtwoord" \
  --dry-run=client -o yaml | kc apply -f -

kc apply -n "$NS" -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: proef-postgres-init
data:
  databases.sql: |
    CREATE DATABASE magazijn;
    CREATE DATABASE ldv;
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: proef-postgres
spec:
  selector: {matchLabels: {app: proef-postgres}}
  template:
    metadata: {labels: {app: proef-postgres}}
    spec:
      # De gebruiker postgres uit het image; de data staat in een emptyDir die hij mag beschrijven.
      securityContext: {runAsNonRoot: true, runAsUser: 999, runAsGroup: 999, fsGroup: 999, seccompProfile: {type: RuntimeDefault}}
      containers:
        - name: postgres
          image: $PG_IMAGE
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}}
          envFrom: [{secretRef: {name: proef-voorzieningen}}]
          ports: [{containerPort: 5432}]
          # Via TCP: tijdens de init luistert de tijdelijke server alleen op de socket.
          readinessProbe: {exec: {command: [pg_isready, -h, 127.0.0.1, -U, postgres]}, periodSeconds: 3}
          volumeMounts:
            - {name: init, mountPath: /docker-entrypoint-initdb.d}
            - {name: data, mountPath: /var/lib/postgresql}
            - {name: socket, mountPath: /var/run/postgresql}
      volumes:
        - {name: init, configMap: {name: proef-postgres-init}}
        - {name: data, emptyDir: {}}
        - {name: socket, emptyDir: {}}
---
apiVersion: v1
kind: Service
metadata:
  name: proef-postgres
spec:
  selector: {app: proef-postgres}
  ports: [{port: 5432}]
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: proef-redis
spec:
  selector: {matchLabels: {app: proef-redis}}
  template:
    metadata: {labels: {app: proef-redis}}
    spec:
      # Het image draait standaard als root; als een andere gebruiker werkt het met een
      # beschrijfbare /data.
      securityContext: {runAsNonRoot: true, runAsUser: 999, runAsGroup: 999, fsGroup: 999, seccompProfile: {type: RuntimeDefault}}
      volumes: [{name: data, emptyDir: {}}]
      containers:
        - name: redis
          image: $REDIS_IMAGE
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
spec:
  selector: {app: proef-redis}
  ports: [{port: 6379}]
EOF

kc -n "$NS" rollout status deployment/proef-postgres --timeout=5m
kc -n "$NS" rollout status deployment/proef-redis --timeout=5m

echo "== keystore van het stelselregister =="

mkdir -p "$WERK/pki"
cp "$REPO/demo/environment/stelselregister/pki/maak-keten.sh" "$WERK/pki/"
(cd "$WERK/pki" && STELSELDOCUMENT_OMGEVING=lokaal ./maak-keten.sh >/dev/null)

kc -n "$NS" create secret generic stelselregister-keystore \
  --from-file=keystore.p12="$WERK/pki/out/keystore.p12" --dry-run=client -o yaml | kc apply -f -

echo "== geheimen en instellingen per dienst =="

kc -n "$NS" create secret generic berichtenuitvraag-geheimen \
  --from-file=REDIS_PASSWORD="$WERK/redis-wachtwoord" --from-file=LDV_POSTGRES_PASSWORD="$WERK/pg-wachtwoord" \
  --dry-run=client -o yaml | kc apply -f -
kc -n "$NS" create secret generic berichtenmagazijn-geheimen \
  --from-file=DB_PASSWORD="$WERK/pg-wachtwoord" --from-file=LDV_POSTGRES_PASSWORD="$WERK/pg-wachtwoord" \
  --dry-run=client -o yaml | kc apply -f -
kc -n "$NS" create secret generic stelselregister-geheimen \
  --from-file=STELSELDOCUMENT_KEYSTORE_WACHTWOORD="$WERK/pki/out/wachtwoord" \
  --dry-run=client -o yaml | kc apply -f -

# Adressen naar buiten moeten https zijn, ook als ze hier niet bereikbaar zijn: de readiness hangt
# alleen aan database, Redis en het stelseldocument.
cat >"$WERK/berichtenuitvraag.yaml" <<EOF
dienst:
  allowUnsafeFlags: true
  env:
    REDIS_HOSTS: redis://proef-redis.$NS.svc:6379
    FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT: "true"
    PROFIEL_SERVICE_URL: https://profiel.invalid
    MAGAZIJN_A_URL: https://berichtenmagazijn.invalid
    MAGAZIJN_B_URL: https://magazijn-b.invalid
    LDV_POSTGRES_URL: jdbc:postgresql://proef-postgres.$NS.svc:5432/ldv
    LDV_POSTGRES_USERNAME: postgres
    FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT: "true"
EOF

# Het logboek van het magazijn schrijft in het schema dat Flyway in de eigen database aanmaakt.
cat >"$WERK/berichtenmagazijn.yaml" <<EOF
dienst:
  allowUnsafeFlags: true
  env:
    DB_JDBC_URL: jdbc:postgresql://proef-postgres.$NS.svc:5432/magazijn
    DB_USERNAME: postgres
    DB_SCHEMA: magazijn_lokaal
    MAGAZIJN_OIN: "00000001003214345000"
    AANMELD_URL: https://aanmeld.invalid
    NOTIFICATIE_URL: https://notificatie.invalid
    PROFIEL_SERVICE_URL: https://profiel.invalid
    LDV_POSTGRES_URL: jdbc:postgresql://proef-postgres.$NS.svc:5432/magazijn
    LDV_POSTGRES_USERNAME: postgres
    FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT: "true"
EOF

cat >"$WERK/stelselregister.yaml" <<EOF
dienst:
  env:
    STELSELDOCUMENT_UITGEVER_OIN: "00000000000000001000"
    STELSELDOCUMENT_OMGEVING: lokaal
    MAGAZIJN_A_URL: https://berichtenmagazijn.invalid
    MAGAZIJN_B_URL: https://magazijn-b.invalid
EOF

echo "== installeren met de chart =="

# tlsTermination=mesh: lokaal is het pod-netwerk niet versleuteld. Dat kan hier, met proefgegevens
# op loopback; op Fundament hoort die keuze bij het pod-netwerk daar. Hetzelfde geldt voor
# allowUnauthenticated: de route staat alleen op loopback open.
for dienst in "${DIENSTEN[@]}"; do
  hm upgrade --install "$dienst" "$CHARTS/fbs-dienst" --namespace "$NS" \
    -f "$CHARTS/fbs-dienst/diensten/$dienst.yaml" -f "$WERK/$dienst.yaml" \
    --set dienst.image.tag="$TAG" \
    --set dienst.tlsTermination=mesh \
    --set dienst.extraEnvVarsSecret="$dienst-geheimen" \
    --set ingress.enabled=true --set ingress.allowUnauthenticated=true --set ingress.hostname="$dienst.fbs.localhost" \
    --set cluster.gateway.name=proef --set cluster.gateway.sectionName=http
done

fouten=0

for dienst in "${DIENSTEN[@]}"; do
  if ! kc -n "$NS" rollout status "deployment/$dienst" --timeout=5m; then
    kc -n "$NS" logs "deployment/$dienst" --tail=40 || true
    fouten=$((fouten + 1))
  fi
done

echo "== bereikbaar via de Gateway =="

# Geeft de HTTP-status van een pad op de hostnaam van een dienst, via de Gateway.
status() {
  curl -s -o /dev/null -w '%{http_code}' -H "Host: $1.fbs.localhost" "http://127.0.0.1:$HTTP_POORT$2" || true
}

for dienst in "${DIENSTEN[@]}"; do
  code=000

  # De route wordt pas na de rollout door Envoy opgepikt, en bij de eerste keer wacht dat op het
  # binnenhalen van het proxy-image.
  for _ in $(seq 1 90); do
    code=$(status "$dienst" /openapi.json)
    [ "$code" = 200 ] && break
    sleep 2
  done

  if [ "$code" = 200 ]; then
    echo "OK: $dienst bereikbaar via http://$dienst.fbs.localhost:$HTTP_POORT/openapi.json"
  else
    echo "FOUT: $dienst via de Gateway gaf $code op /openapi.json"
    fouten=$((fouten + 1))
  fi

  # /q (health, Swagger UI) hoort niet via de Gateway bereikbaar te zijn.
  code=$(status "$dienst" /q/health/ready)

  if [ "$code" = 404 ]; then
    echo "OK: $dienst /q niet via de Gateway (404)"
  else
    echo "FOUT: $dienst /q/health/ready via de Gateway gaf $code, verwacht 404"
    fouten=$((fouten + 1))
  fi
done

if [ "$fouten" -ne 0 ]; then
  echo "$fouten controle(s) niet in orde. Cluster blijft staan voor onderzoek (kubectl --context $CONTEXT); weg met: $0 opruimen"
  exit 1
fi

echo "Alle diensten gezond. Cluster weg met: $0 opruimen"
