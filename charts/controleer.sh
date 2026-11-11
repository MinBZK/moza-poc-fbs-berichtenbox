#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Controleert de chart fbs-dienst zonder cluster: lint, schema's, beleidsregels en de renders die
# moeten falen. Draait in ci-scripts.yml en lokaal; vereist helm, conftest en kubeconform.
#
#   charts/controleer.sh
#
# Een fout die hier boven komt, komt anders pas in het cluster boven: als een pod niet start of
# een route een 500 geeft.
set -euo pipefail

CHARTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CHART="$CHARTS/fbs-dienst"
POLICY="$CHARTS/policy"

# De Kubernetes-versie waartegen de schema's toetsen. Fundament draait op Gardener; bijwerken als
# de versie daar bekend is (#1536).
KUBERNETES_VERSIE=1.32.0
# Schema's van de Gateway API (HTTPRoute), gepind op een commit van de CRDs-catalog.
CRD_SCHEMAS='https://raw.githubusercontent.com/datreeio/CRDs-catalog/501527ab9c40c827144c8f45945b25a023e344ce/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'

# De diensten die de chart moet kunnen installeren. Bij naam, zodat een verdwenen values-bestand
# opvalt in plaats van een kleinere lus op te leveren.
DIENSTEN=(berichtenuitvraag berichtenmagazijn stelselregister)

for gereedschap in helm conftest kubeconform; do
  if ! command -v "$gereedschap" >/dev/null; then
    echo "FOUT: $gereedschap ontbreekt op het PATH." >&2
    exit 2
  fi
done

WERK=$(mktemp -d)
trap 'rm -rf "$WERK"' EXIT

fouten=0
geslaagd=0

ok() {
  geslaagd=$((geslaagd + 1))
  echo "OK: $*"
}

fout() {
  fouten=$((fouten + 1))
  echo "FOUT: $*"
}

# Toetst één render. $1 = label, rest = argumenten voor helm.
toets_render() {
  local label=$1
  shift
  local manifest="$WERK/$label.yaml"

  if ! helm lint --strict --quiet "$CHART" "$@" >"$WERK/lint.log" 2>&1; then
    cat "$WERK/lint.log"
    fout "$label: helm lint"
    return
  fi

  ok "$label: helm lint"

  if ! helm template fbs "$CHART" --namespace fbs "$@" >"$manifest" 2>"$WERK/template.log"; then
    cat "$WERK/template.log"
    fout "$label: helm template"
    return
  fi

  ok "$label: helm template"

  if kubeconform -strict -summary -kubernetes-version "$KUBERNETES_VERSIE" \
      -schema-location default -schema-location "$CRD_SCHEMAS" "$manifest"; then
    ok "$label: kubeconform"
  else
    fout "$label: kubeconform"
  fi

  if conftest test --no-color --namespace main --policy "$POLICY" "$manifest"; then
    ok "$label: beleidsregels per manifest"
  else
    fout "$label: beleidsregels per manifest"
  fi

  if conftest test --no-color --combine --namespace samenhang --policy "$POLICY" "$manifest"; then
    ok "$label: samenhang van de release"
  else
    fout "$label: samenhang van de release"
  fi
}

# Telt de objecten per soort in een render. $1 = label, $2 = verwachte soorten, gesorteerd.
toets_soorten() {
  local label=$1 verwacht=$2 gevonden
  gevonden=$(sed -n 's/^kind: //p' "$WERK/$label.yaml" | sort | paste -sd, -)

  if [ "$gevonden" = "$verwacht" ]; then
    ok "$label: objecten $gevonden"
  else
    fout "$label: objecten '$gevonden', verwacht '$verwacht'"
  fi
}

# Een render die moet falen, met een melding die de oorzaak noemt. $1 = label, $2 = verwacht
# fragment van de melding, rest = argumenten voor helm.
toets_weigering() {
  local label=$1 verwacht=$2
  shift 2

  if helm template fbs "$CHART" --namespace fbs "$@" >/dev/null 2>"$WERK/weigering.log"; then
    fout "$label: render slaagde, maar had moeten falen"
  elif grep -qF -- "$verwacht" "$WERK/weigering.log"; then
    ok "$label: geweigerd ($verwacht)"
  else
    cat "$WERK/weigering.log"
    fout "$label: geweigerd, maar zonder de melding '$verwacht'"
  fi
}

echo "== beleidsregels zelf =="

if conftest verify --no-color --policy "$POLICY"; then
  ok "conftest verify"
else
  fout "conftest verify"
fi

echo "== renders per dienst en scenario =="

scenarios=("$CHART"/ci/*-values.yaml)

if [ ! -e "${scenarios[0]}" ]; then
  fout "geen scenario's in $CHART/ci/"
  scenarios=()
fi

for dienst in "${DIENSTEN[@]}"; do
  waarden="$CHART/diensten/$dienst.yaml"

  if [ ! -f "$waarden" ]; then
    fout "$dienst: $waarden ontbreekt"
    continue
  fi

  for scenario in "${scenarios[@]}"; do
    naam=$(basename "$scenario" -values.yaml)
    toets_render "$dienst-$naam" -f "$waarden" -f "$scenario"
  done

  basis="Deployment,NetworkPolicy,PodDisruptionBudget,Service,ServiceAccount"
  toets_soorten "$dienst-standaard" "$basis"
  toets_soorten "$dienst-gateway" "Deployment,HTTPRoute,NetworkPolicy,PodDisruptionBudget,Service,ServiceAccount"
  toets_soorten "$dienst-ingress" "Deployment,Ingress,NetworkPolicy,PodDisruptionBudget,Service,ServiceAccount"
done

echo "== NetworkPolicy zonder toegestane bronnen =="

# Zonder bronnen moet de regel wegvallen: een ingress-regel met lege `from` laat juist alles toe.
toets_render "zonder-bronnen" -f "$CHART/diensten/berichtenuitvraag.yaml" -f "$CHART/ci/standaard-values.yaml" \
  --set networkPolicy.addExternalClientAccess=false --set networkPolicy.ingressNSMatchLabels=null

if grep -q '^  ingress: \[\]$' "$WERK/zonder-bronnen.yaml"; then
  ok "zonder-bronnen: inkomend verkeer helemaal dicht"
else
  fout "zonder-bronnen: verwacht 'ingress: []' in de NetworkPolicy"
fi

echo "== twee magazijnen in één namespace =="

# De plug-in "berichtenmagazijn" installeert per organisatie een release. Twee releases mogen
# elkaars objecten niet overschrijven en elkaars pods niet selecteren.
magazijn=(-f "$CHART/diensten/berichtenmagazijn.yaml" -f "$CHART/ci/gateway-values.yaml")

if helm template org-a "$CHART" --namespace fbs "${magazijn[@]}" >"$WERK/org-a.yaml" \
    && helm template org-b "$CHART" --namespace fbs "${magazijn[@]}" >"$WERK/org-b.yaml"; then
  dubbel=$(cat "$WERK/org-a.yaml" "$WERK/org-b.yaml" \
    | awk '/^kind: /{k=$2} /^  name: /{print k "/" $2}' | sort | uniq -d)

  if [ -z "$dubbel" ]; then
    ok "twee magazijnen: geen gedeelde objectnamen"
  else
    fout "twee magazijnen delen objecten: $dubbel"
  fi

  instanties=$(sed -n 's/^ *app.kubernetes.io\/instance: //p' "$WERK/org-a.yaml" | sort -u | paste -sd, -)

  if [ "$instanties" = "org-a" ]; then
    ok "twee magazijnen: selectors en labels per release"
  else
    fout "release org-a draagt instance-labels '$instanties'"
  fi
else
  fout "twee magazijnen: render faalde"
fi

echo "== renders die moeten falen =="

uitvraag=(-f "$CHART/diensten/berichtenuitvraag.yaml")
stelsel=(-f "$CHART/diensten/stelselregister.yaml" --set dienst.image.tag=main-0000000)

toets_weigering "zonder-values-bestand" "dienst.image.repository is leeg" --set dienst.image.tag=main-0000000
toets_weigering "zonder-tag" "dienst.image.tag of dienst.image.digest is verplicht" "${uitvraag[@]}"
toets_weigering "route-zonder-hostnaam" "ingress.hostname is verplicht" "${uitvraag[@]}" \
  --set dienst.image.tag=main-0000000 --set ingress.enabled=true --set cluster.gateway.name=gw
toets_weigering "route-zonder-gateway" "cluster.gateway.name is verplicht" "${uitvraag[@]}" \
  --set dienst.image.tag=main-0000000 --set ingress.enabled=true --set ingress.hostname=a.example.nl
toets_weigering "onbekende-routering" 'cluster.routingMode "nodeport" is ongeldig' "${uitvraag[@]}" \
  --set dienst.image.tag=main-0000000 --set cluster.routingMode=nodeport
toets_weigering "default-serviceaccount" "serviceAccount.name is verplicht" "${uitvraag[@]}" \
  --set dienst.image.tag=main-0000000 --set serviceAccount.create=false
toets_weigering "stelselregister-twee-replicas" "dienst.replicaCount is 2" "${stelsel[@]}" --set dienst.replicaCount=2
toets_weigering "stelselregister-nul-replicas" "dienst.replicaCount is 0" "${stelsel[@]}" --set dienst.replicaCount=0
toets_weigering "stelselregister-hpa" "dienst.autoscaling.hpa.enabled kan niet" "${stelsel[@]}" \
  --set dienst.autoscaling.hpa.enabled=true

echo "== uitkomst =="

if [ "$geslaagd" -eq 0 ]; then
  fout "geen enkele controle geslaagd: deze run meet niets"
fi

echo "$geslaagd geslaagd, $fouten fout"

[ "$fouten" -eq 0 ]
