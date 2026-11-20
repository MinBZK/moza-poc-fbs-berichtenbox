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

# De Deployments van fbs-demo; de stelselreleases hebben elk één Deployment met de naam van de
# release. De rollout-controle eist ze allemaal, zodat een ontbrekende niet als "niets te meten"
# leest.
# shellcheck disable=SC2034 # idem
DEMO_DEPLOYMENTS=(fbs-demo-console fbs-demo-demo-proxy fbs-demo-notificatie fbs-demo-personas fbs-demo-proeftuin
  fbs-demo-profiel fbs-demo-redis fbs-demo-simulator fbs-demo-toxiproxy)

# De images die de keten per commit publiceert; de demo draait ze allemaal op dezelfde tag.
# shellcheck disable=SC2034 # idem
DEMO_IMAGES=(fbs-berichtenuitvraag fbs-berichtenmagazijn fbs-stelselregister fbs-demo-console fbs-demo-personas
  fbs-magazijn-simulator fbs-demo-profiel fbs-externe-stubs)

# De onderdelen met een route; elk krijgt <naam>.<basisdomein>. Moet gelijk zijn aan
# charts/fbs-demo/templates/httproutes.yaml en de ingress.hostname van de stelselreleases.
DEMO_HOSTS=(demo simulator profiel toxiproxy-profiel notificatie aanmelding berichtenmagazijn-a berichtenmagazijn-b
  stelselregister)

# Magazijn A (RVO) en B (Belastingdienst), zoals in charts/fbs-demo/stelsel/, en het eerste
# gesimuleerde magazijn uit demo/genereer-magazijnen.py. De simulator kent alleen de gesimuleerde.
MAGAZIJN_A_OIN=00000000000000100000
MAGAZIJN_B_OIN=00000001823288444000
# shellcheck disable=SC2034 # gelezen door demo-op-fundament.sh
SIMULATOR_OIN=00000009000000000001

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

# De sessie uit het antwoord op POST /api/v1/sessies.
sessie_uit_antwoord() {
  jq -er '.sessieId | select(type == "string" and length > 0)' <<<"$1" 2>/dev/null || return 1
}

# Het oordeel over een ophaalronde, uit de SSE-stream van GET /api/v1/berichten/_ophalen: magazijn
# A én B melden `magazijn-bevraging-voltooid` met status OK en berichten. Een lijst van berichten
# zegt dat niet: daarin staan ook de gesimuleerde magazijnen, en een organisatie die niet leverde
# staat er ook in (nietGeleverd). Zonder oordeel "in orde" print het de reden.
keten_oordeel() {
  local data oin uitkomst

  data=$(sed -n 's/^data: \{0,1\}//p' <<<"$1")

  for oin in "$MAGAZIJN_A_OIN" "$MAGAZIJN_B_OIN"; do
    # -R en fromjson?: een regel die geen JSON is, slaat jq over in plaats van de rest te negeren.
    uitkomst=$(jq -cR --arg oin "$oin" \
      'fromjson? | select(.event == "magazijn-bevraging-voltooid" and .magazijnId == $oin)' <<<"$data" | tail -1)

    if [ -z "$uitkomst" ]; then
      echo "magazijn $oin meldde geen uitkomst"
      return 1
    fi

    if ! jq -e '.status == "OK" and ((.aantalBerichten // 0) > 0)' <<<"$uitkomst" >/dev/null; then
      echo "magazijn $oin: $(jq -r '"\(.status), \(.aantalBerichten // 0) berichten"' <<<"$uitkomst")"
      return 1
    fi
  done
}

# Values die uitvraag en magazijnen in de sandbox de lokale CA laten vertrouwen. Op het fieldlab
# tekent Let's Encrypt, en dat vertrouwt de JVM al.
#
# Via jssecacerts: bestaat dat bestand, dan gebruikt de JVM het in plaats van cacerts, voor elke
# client. Het bevat daarom cacerts plus de lokale CA, zonder versleuteling: de JVM opent het zonder
# wachtwoord, en slaat versleutelde certificaten dan over. De trust-store van het
# TLS-register van Quarkus helpt niet: de REST-clients gebruiken alleen een benoemde configuratie,
# en zonder naam de JVM. De lijsten herhalen die uit charts/fbs-demo/stelsel/ en
# charts/voorbeelden/berichtenmagazijn-cnpg.yaml, want Helm vervangt een lijst; charts/controleer.sh
# toetst dat het register en de CA's van de databases blijven staan.
values_demo_jssecacerts() {
  local release=$1 java_home=$2

  echo "dienst:"
  echo "  extraVolumes:"

  case "$release" in
    berichtenuitvraag)
      cat <<'EOF'
    - name: magazijnenregister
      configMap:
        name: fbs-demo-magazijnenregister
    - name: ldv-ca
      secret:
        secretName: ldv-uitvraag-ca
        items:
          - key: ca.crt
            path: ca.crt
EOF
      ;;
    berichtenmagazijn-a | berichtenmagazijn-b)
      cat <<'EOF'
    - name: db-ca
      secret:
        secretName: db-ca
        items:
          - key: ca.crt
            path: ca.crt
EOF
      ;;
    *) return 1 ;;
  esac

  cat <<'EOF'
    - name: jssecacerts
      secret:
        secretName: gateway-ca
        items:
          - key: jssecacerts
            path: jssecacerts
  extraVolumeMounts:
EOF

  case "$release" in
    berichtenuitvraag)
      cat <<'EOF'
    - name: magazijnenregister
      mountPath: /home/jboss/config
      readOnly: true
    - name: ldv-ca
      mountPath: /etc/fbs/ldv-ca
      readOnly: true
EOF
      ;;
    *)
      cat <<'EOF'
    - name: db-ca
      mountPath: /etc/fbs/db-ca
      readOnly: true
EOF
      ;;
  esac

  cat <<EOF
    - name: jssecacerts
      mountPath: $java_home/lib/security/jssecacerts
      subPath: jssecacerts
      readOnly: true
EOF
}
