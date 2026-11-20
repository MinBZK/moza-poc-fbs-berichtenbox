#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Bouwstenen voor charts/demo-op-fundament.sh zonder bijwerkingen: manifests, namen en parsers,
# zodat ze zonder cluster te toetsen zijn (test-lib.sh). Wordt gesourcet; zet daarom zelf geen
# shell-opties.

# De releases van fbs-dienst in de demo, in de volgorde van installeren. De namen staan ook in
# charts/fbs-demo/values.yaml (stelsel) en in de values onder charts/fbs-demo/stelsel/.
# shellcheck disable=SC2034 # gelezen door demo-op-fundament.sh en test-lib.sh
STELSEL_RELEASES=(berichtenuitvraag berichtenmagazijn-a berichtenmagazijn-b stelselregister)

# De images die de keten per commit publiceert; de demo draait ze allemaal op dezelfde tag.
# shellcheck disable=SC2034 # idem
DEMO_IMAGES=(fbs-berichtenuitvraag fbs-berichtenmagazijn fbs-stelselregister fbs-demo-console fbs-demo-personas
  fbs-magazijn-simulator fbs-demo-profiel fbs-externe-stubs)

# De onderdelen met een route; elk krijgt <naam>.<basisdomein>. Moet gelijk zijn aan
# templates/httproutes.yaml en de ingress.hostname van de stelselreleases.
DEMO_HOSTS=(demo simulator profiel toxiproxy-profiel notificatie aanmelding berichtenmagazijn-a berichtenmagazijn-b
  stelselregister)

# Het basisdomein bij een IPv4-adres van de Gateway: sslip.io wijst <iets>.<a-b-c-d>.sslip.io naar
# dat adres. Een hostnaam of IPv6 geeft niets: dan is een eigen basisdomein nodig.
basisdomein_uit_adres() {
  local adres=$1

  [[ $adres =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1

  local deel

  for deel in "${BASH_REMATCH[@]:1}"; do
    [ "$deel" -le 255 ] || return 1
  done

  echo "fbs.${adres//./-}.sslip.io"
}

# Het Certificate voor de https-listener: alle hostnamen expliciet, want Let's Encrypt geeft met
# HTTP-01 geen wildcard.
manifest_certificaat() {
  local ns=$1 basis=$2 issuer=$3 secret=$4 host

  cat <<EOF
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: $secret
  namespace: $ns
spec:
  secretName: $secret
  issuerRef: {name: $issuer, kind: Issuer}
  dnsNames:
EOF

  for host in "${DEMO_HOSTS[@]}"; do
    echo "    - $host.$basis"
  done
}

# Een Cluster van CloudNativePG. db draagt beide magazijnen (elk met twee pools van 20), de
# simulator (50) en het paneel (4): samen 134, boven de standaard van 100.
manifest_cluster() {
  local naam=$1 ns=$2 max=${3:-}

  cat <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: $naam
  namespace: $ns
  labels:
    app.kubernetes.io/part-of: fbs-demo
spec:
  instances: 1
  imageCatalogRef:
    apiGroup: postgresql.cnpg.io
    kind: ClusterImageCatalog
    name: fundament-postgresql
    major: 18
  enablePDB: false
  storage:
    size: 2Gi
EOF

  if [ -n "$max" ]; then
    printf '  postgresql:\n    parameters:\n      max_connections: "%s"\n' "$max"
  fi
}

# Het aantal verschillende magazijnen in een berichtenlijst van de uitvraag (JSON).
aantal_magazijnen() {
  jq -er '[.. | objects | .magazijnId? | select(type == "string")] | unique | length' <<<"$1" 2>/dev/null || return 1
}

# De sessie uit het antwoord op POST /api/v1/sessies.
sessie_uit_antwoord() {
  jq -er '.sessieId | select(type == "string" and length > 0)' <<<"$1" 2>/dev/null || return 1
}
