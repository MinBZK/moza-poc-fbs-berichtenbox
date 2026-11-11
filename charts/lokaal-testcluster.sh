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
# REDIS_UNPROTECTED en OUTBOUND_TLS_DISABLED). Op Fundament levert #1536 de echte voorzieningen.
set -euo pipefail

CHARTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$CHARTS/.." && pwd)
CLUSTER=${CLUSTER:-fbs-chart}
NS=fbs
HTTP_POORT=${HTTP_POORT:-8080}
ENVOY_GATEWAY_VERSIE=v1.9.2
PG_IMAGE=docker.io/library/postgres:18
REDIS_IMAGE=docker.io/redis/redis-stack-server:7.4.0-v3
DIENSTEN=(berichtenuitvraag berichtenmagazijn stelselregister)

if [ "${1:-}" = opruimen ]; then
  k3d cluster delete "$CLUSTER"
  exit 0
fi

TAG=${TAG:?zet TAG op een gepubliceerde image-tag, bijvoorbeeld main-<sha7> van de laatste main-commit}

for gereedschap in k3d kubectl helm openssl curl; do
  command -v "$gereedschap" >/dev/null || { echo "FOUT: $gereedschap ontbreekt." >&2; exit 2; }
done

WERK=$(mktemp -d)
trap 'rm -rf "$WERK"' EXIT

echo "== cluster =="

if ! k3d cluster get "$CLUSTER" >/dev/null 2>&1; then
  # Traefik uit: de routering loopt zoals op Fundament via Envoy Gateway.
  k3d cluster create "$CLUSTER" --agents 0 -p "$HTTP_POORT:80@loadbalancer" \
    --k3s-arg '--disable=traefik@server:0' --wait
fi

kubectl config use-context "k3d-$CLUSTER" >/dev/null

echo "== Envoy Gateway $ENVOY_GATEWAY_VERSIE (met de Gateway-API-CRD's) =="

helm upgrade --install eg oci://docker.io/envoyproxy/gateway-helm --version "$ENVOY_GATEWAY_VERSIE" \
  --namespace envoy-gateway-system --create-namespace --wait --timeout 5m
kubectl -n envoy-gateway-system wait --for=condition=Available deployment/envoy-gateway --timeout=5m

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -

# Een platte HTTP-listener: lokaal is er geen certificaat. Op Fundament eindigt TLS op de Gateway.
kubectl apply -f - <<EOF
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

echo "== proefvoorzieningen: PostgreSQL en Redis Stack =="

PG_WW=$(openssl rand -hex 24)
REDIS_WW=$(openssl rand -hex 24)

kubectl -n "$NS" create secret generic proef-voorzieningen \
  --from-literal=POSTGRES_PASSWORD="$PG_WW" --from-literal=REDIS_PASSWORD="$REDIS_WW" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -n "$NS" -f - <<EOF
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
      containers:
        - name: postgres
          image: $PG_IMAGE
          envFrom: [{secretRef: {name: proef-voorzieningen}}]
          ports: [{containerPort: 5432}]
          readinessProbe: {exec: {command: [pg_isready, -U, postgres]}, periodSeconds: 3}
          volumeMounts: [{name: init, mountPath: /docker-entrypoint-initdb.d}]
      volumes: [{name: init, configMap: {name: proef-postgres-init}}]
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
      containers:
        - name: redis
          image: $REDIS_IMAGE
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

kubectl -n "$NS" rollout status deployment/proef-postgres --timeout=5m
kubectl -n "$NS" rollout status deployment/proef-redis --timeout=5m

echo "== sleutel van het stelselregister =="

mkdir -p "$WERK/pki"
cp "$REPO/demo/environment/stelselregister/pki/maak-keten.sh" "$WERK/pki/"
(cd "$WERK/pki" && STELSELDOCUMENT_OMGEVING=lokaal ./maak-keten.sh >/dev/null)

kubectl -n "$NS" create secret generic stelselregister-sleutel \
  --from-file=keystore.p12="$WERK/pki/out/keystore.p12" --dry-run=client -o yaml | kubectl apply -f -

echo "== geheimen en instellingen per dienst =="

LDV_URL="jdbc:postgresql://proef-postgres.$NS.svc:5432/ldv"

kubectl -n "$NS" create secret generic berichtenuitvraag-geheimen \
  --from-literal=REDIS_PASSWORD="$REDIS_WW" --from-literal=LDV_POSTGRES_PASSWORD="$PG_WW" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic berichtenmagazijn-geheimen \
  --from-literal=DB_PASSWORD="$PG_WW" --from-literal=LDV_POSTGRES_PASSWORD="$PG_WW" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic stelselregister-geheimen \
  --from-literal=STELSELDOCUMENT_KEYSTORE_WACHTWOORD="$(cat "$WERK/pki/out/wachtwoord")" \
  --dry-run=client -o yaml | kubectl apply -f -

# Adressen naar buiten moeten https zijn, ook als ze hier niet bereikbaar zijn: de readiness hangt
# alleen aan database, Redis en het stelseldocument.
cat >"$WERK/berichtenuitvraag.yaml" <<EOF
dienst:
  env:
    REDIS_HOSTS: redis://proef-redis.$NS.svc:6379
    FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT: "true"
    PROFIEL_SERVICE_URL: https://profiel.invalid
    MAGAZIJN_A_URL: https://berichtenmagazijn.invalid
    MAGAZIJN_B_URL: https://magazijn-b.invalid
    LDV_POSTGRES_URL: $LDV_URL
    LDV_POSTGRES_USERNAME: postgres
    FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT: "true"
EOF

cat >"$WERK/berichtenmagazijn.yaml" <<EOF
dienst:
  env:
    DB_JDBC_URL: jdbc:postgresql://proef-postgres.$NS.svc:5432/magazijn
    DB_USERNAME: postgres
    DB_SCHEMA: magazijn_lokaal
    MAGAZIJN_OIN: "00000001003214345000"
    AANMELD_URL: https://aanmeld.invalid
    NOTIFICATIE_URL: https://notificatie.invalid
    PROFIEL_SERVICE_URL: https://profiel.invalid
    LDV_POSTGRES_URL: $LDV_URL
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

for dienst in "${DIENSTEN[@]}"; do
  helm upgrade --install "$dienst" "$CHARTS/fbs-dienst" --namespace "$NS" \
    -f "$CHARTS/fbs-dienst/diensten/$dienst.yaml" -f "$WERK/$dienst.yaml" \
    --set dienst.image.tag="$TAG" \
    --set dienst.extraEnvVarsSecret="$dienst-geheimen" \
    --set ingress.enabled=true --set ingress.hostname="$dienst.fbs.localhost" \
    --set cluster.gateway.name=proef --set cluster.gateway.sectionName=http
done

fouten=0

for dienst in "${DIENSTEN[@]}"; do
  if ! kubectl -n "$NS" rollout status "deployment/$dienst" --timeout=5m; then
    kubectl -n "$NS" logs "deployment/$dienst" --tail=40 || true
    fouten=$((fouten + 1))
  fi
done

echo "== bereikbaar via de Gateway =="

for dienst in "${DIENSTEN[@]}"; do
  status=000

  # De route wordt pas na de rollout door Envoy opgepikt; even geduld.
  for _ in $(seq 1 30); do
    status=$(curl -s -o /dev/null -w '%{http_code}' -H "Host: $dienst.fbs.localhost" \
      "http://localhost:$HTTP_POORT/q/health/ready" || true)
    [ "$status" = 200 ] && break
    sleep 2
  done

  if [ "$status" = 200 ]; then
    echo "OK: $dienst gezond en bereikbaar via http://$dienst.fbs.localhost:$HTTP_POORT"
  else
    echo "FOUT: $dienst via de Gateway gaf $status op /q/health/ready"
    fouten=$((fouten + 1))
  fi
done

if [ "$fouten" -ne 0 ]; then
  echo "$fouten dienst(en) niet in orde. Cluster blijft staan voor onderzoek; weg met: $0 opruimen"
  exit 1
fi

echo "Alle diensten gezond. Cluster weg met: $0 opruimen"
