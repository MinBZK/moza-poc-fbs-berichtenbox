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

# TODO(MinBZK/MijnOverheidZakelijk#1536): bijwerken naar de Kubernetes-versie van Fundament
# (Gardener).
KUBERNETES_VERSIE=1.32.0
# Schema's, elk gepind op een commit: zonder pin kan de uitkomst veranderen zonder diff hier.
K8S_SCHEMAS='https://raw.githubusercontent.com/yannh/kubernetes-json-schema/f7d855e49c34b039c9ef2196444910275e31e09e/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json'
CRD_SCHEMAS='https://raw.githubusercontent.com/datreeio/CRDs-catalog/501527ab9c40c827144c8f45945b25a023e344ce/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'

# De diensten die de chart moet kunnen installeren. Bij naam, zodat een verdwenen values-bestand
# opvalt in plaats van een kleinere lus op te leveren.
DIENSTEN=(berichtenuitvraag berichtenmagazijn stelselregister)

# Ondergrenzen, zodat een geschrapte controle of een rego-test die niet meer laadt opvalt in plaats
# van een kortere, groene run op te leveren. Verlagen is een zichtbare diff.
MINIMUM_CONTROLES=127
MINIMUM_REGO_TESTS=49

for gereedschap in helm conftest kubeconform; do
  if ! command -v "$gereedschap" >/dev/null; then
    echo "FOUT: $gereedschap ontbreekt op het PATH." >&2
    exit 2
  fi
done

WERK=$(mktemp -d)
trap 'rm -rf "$WERK"' EXIT
mkdir -p "$WERK/schemas"

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

# conftest slaagt ook als een namespace geen enkele regel heeft, bijvoorbeeld na een hernoemd
# package. Daarom: geslaagd én minstens één test. $1 = label, rest = argumenten voor conftest test.
conftest_met_regels() {
  local label=$1 uitvoer
  shift

  if ! uitvoer=$(conftest test --no-color "$@" 2>&1); then
    printf '%s\n' "$uitvoer"
    fout "$label"
  elif grep -q '^0 tests' <<<"$uitvoer"; then
    printf '%s\n' "$uitvoer"
    fout "$label: geen enkele regel geëvalueerd"
  else
    ok "$label"
  fi
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

  if kubeconform -strict -summary -kubernetes-version "$KUBERNETES_VERSIE" -cache "$WERK/schemas" \
      -schema-location "$K8S_SCHEMAS" -schema-location "$CRD_SCHEMAS" "$manifest"; then
    ok "$label: kubeconform"
  else
    fout "$label: kubeconform"
  fi

  conftest_met_regels "$label: beleidsregels per manifest" --namespace main --policy "$POLICY" "$manifest"
  conftest_met_regels "$label: samenhang van de release" --combine --namespace samenhang --policy "$POLICY" "$manifest"
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

# Zoekt een regel in een render. $1 = label, $2 = beschrijving, $3 = vaste tekst.
toets_bevat() {
  local label=$1 wat=$2 tekst=$3

  if grep -qF -- "$tekst" "$WERK/$label.yaml"; then
    ok "$label: $wat"
  else
    fout "$label: $wat (verwacht '$tekst')"
  fi
}

toets_bevat_niet() {
  local label=$1 wat=$2 tekst=$3

  if grep -qF -- "$tekst" "$WERK/$label.yaml"; then
    fout "$label: $wat (onverwacht '$tekst')"
  else
    ok "$label: $wat"
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

if uitvoer=$(conftest verify --no-color --policy "$POLICY" 2>&1); then
  printf '%s\n' "$uitvoer" | tail -1
  aantal=$(sed -n 's/^\([0-9]*\) tests.*/\1/p' <<<"$uitvoer" | tail -1)

  if [ "${aantal:-0}" -ge "$MINIMUM_REGO_TESTS" ]; then
    ok "conftest verify: $aantal tests"
  else
    fout "conftest verify: ${aantal:-0} tests, verwacht minstens $MINIMUM_REGO_TESTS"
  fi
else
  printf '%s\n' "$uitvoer"
  fout "conftest verify"
fi

echo "== renders per dienst en scenario =="

scenarios=("$CHART"/ci/*-values.yaml)

if [ ! -e "${scenarios[0]}" ]; then
  fout "geen scenario's in $CHART/ci/"
  scenarios=()
fi

basis="Deployment,NetworkPolicy,PodDisruptionBudget,Service,ServiceAccount"

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

  toets_soorten "$dienst-standaard" "$basis"
  toets_soorten "$dienst-gateway" "Deployment,HTTPRoute,NetworkPolicy,PodDisruptionBudget,Service,ServiceAccount"
  toets_soorten "$dienst-ingress" "Deployment,Ingress,NetworkPolicy,PodDisruptionBudget,Service,ServiceAccount"
  toets_bevat "$dienst-gateway" "getal vanaf 1e6 zonder exponent" 'value: "10485760"'
  toets_bevat "$dienst-gateway" "DNS ook op de poort van Gardener" 'port: 8053'
  toets_bevat_niet "$dienst-gateway" "geen /q in de route" 'value: "/q'
done

toets_bevat "stelselregister-standaard" "sleutel gemount" 'mountPath: /etc/stelselregister'
toets_bevat "berichtenuitvraag-gateway" "uitgaand naar de proxy's van de Gateway" 'port: 10443'
toets_bevat_niet "stelselregister-gateway" "stelselregister niet naar de Gateway" 'port: 10443'

echo "== bijzondere values =="

uitvraag=(-f "$CHART/diensten/berichtenuitvraag.yaml" -f "$CHART/ci/standaard-values.yaml")
stelsel=(-f "$CHART/diensten/stelselregister.yaml" -f "$CHART/ci/standaard-values.yaml")

# Zonder bronnen moet de regel wegvallen: een ingress-regel met lege `from` laat juist alles toe.
toets_render "zonder-bronnen" "${uitvraag[@]}" \
  --set networkPolicy.addExternalClientAccess=false --set networkPolicy.ingressNSMatchLabels=null
toets_bevat "zonder-bronnen" "inkomend verkeer helemaal dicht" '  ingress: []'

toets_render "hpa" "${uitvraag[@]}" --set dienst.autoscaling.hpa.enabled=true
toets_soorten "hpa" "Deployment,HorizontalPodAutoscaler,NetworkPolicy,PodDisruptionBudget,Service,ServiceAccount"

toets_render "pdb-min-available" "${uitvraag[@]}" --set dienst.pdb.minAvailable=1
toets_bevat_niet "pdb-min-available" "alleen minAvailable" 'maxUnavailable'
toets_render "pdb-nul" "${uitvraag[@]}" --set dienst.pdb.maxUnavailable=0
toets_bevat "pdb-nul" "maxUnavailable 0 blijft 0" 'maxUnavailable: 0'

toets_render "onveilig-toegestaan" "${uitvraag[@]}" \
  --set dienst.allowUnsafeFlags=true --set-string dienst.env.FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT=true

# Een eigen extraVolumes vervangt de lijst uit diensten/; de sleutel mag daar niet aan hangen.
toets_render "stelselregister-extra-volume" "${stelsel[@]}" \
  --set-json 'dienst.extraVolumes=[{"name":"register","configMap":{"name":"register"}}]' \
  --set-json 'dienst.extraVolumeMounts=[{"name":"register","mountPath":"/etc/register"}]'
toets_bevat "stelselregister-extra-volume" "sleutel blijft gemount" 'mountPath: /etc/stelselregister'

echo "== twee magazijnen in één namespace =="

# De plug-in "berichtenmagazijn" installeert per organisatie een release. Twee releases mogen
# elkaars objecten niet overschrijven, elkaars pods niet selecteren en elkaars hostnaam niet delen.
magazijn=(-f "$CHART/diensten/berichtenmagazijn.yaml" -f "$CHART/ci/gateway-values.yaml")

if helm template org-a "$CHART" --namespace fbs "${magazijn[@]}" --set ingress.hostname=org-a.example.nl >"$WERK/org-a.yaml" \
    && helm template org-b "$CHART" --namespace fbs "${magazijn[@]}" --set ingress.hostname=org-b.example.nl >"$WERK/org-b.yaml"; then
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

  hostnamen=$(cat "$WERK/org-a.yaml" "$WERK/org-b.yaml" | sed -n '/^  hostnames:/{n;p}' | sort | uniq -d)

  if [ -z "$hostnamen" ]; then
    ok "twee magazijnen: elk een eigen hostnaam"
  else
    fout "twee magazijnen delen hostnaam $hostnamen"
  fi
else
  fout "twee magazijnen: render faalde"
fi

echo "== renders die moeten falen =="

tag=(--set dienst.image.tag=main-0000000)
mesh=(--set dienst.tlsTermination=mesh)
kaal_uitvraag=(-f "$CHART/diensten/berichtenuitvraag.yaml")

toets_weigering "zonder-values-bestand" "dienst.image.repository is leeg" "${tag[@]}" "${mesh[@]}"
toets_weigering "zonder-tag" "dienst.image.tag of dienst.image.digest is verplicht" "${kaal_uitvraag[@]}" "${mesh[@]}"
toets_weigering "tag-latest" 'dienst.image.tag "latest" is geen main-<sha7>' "${uitvraag[@]}" --set dienst.image.tag=latest
toets_weigering "tag-main" 'dienst.image.tag "main" is geen main-<sha7>' "${uitvraag[@]}" --set dienst.image.tag=main
toets_weigering "tag-pr-zonder-sha" 'dienst.image.tag "pr-12" is geen main-<sha7>' "${uitvraag[@]}" --set dienst.image.tag=pr-12
toets_weigering "zonder-tls-keuze" 'dienst.tlsTermination "" is ongeldig' "${kaal_uitvraag[@]}" "${tag[@]}"
toets_weigering "tls-keuze-onbekend" 'dienst.tlsTermination "gateway" is ongeldig' "${kaal_uitvraag[@]}" "${tag[@]}" \
  --set dienst.tlsTermination=gateway
toets_weigering "tls-via-env" "HTTP_TLS_TERMINATION staat in dienst.env" "${uitvraag[@]}" \
  --set dienst.env.HTTP_TLS_TERMINATION=mesh
toets_weigering "onveilige-klep" "FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT zet een TLS-eis uit" "${uitvraag[@]}" \
  --set-string dienst.env.FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT=true
toets_weigering "route-zonder-hostnaam" "ingress.hostname is verplicht" "${uitvraag[@]}" \
  --set ingress.enabled=true --set cluster.gateway.name=gw
toets_weigering "route-zonder-gateway" "cluster.gateway.name is verplicht" "${uitvraag[@]}" \
  --set ingress.enabled=true --set ingress.hostname=a.example.nl
toets_weigering "route-zonder-paden" "ingress.paths is leeg" "${uitvraag[@]}" \
  --set ingress.enabled=true --set ingress.hostname=a.example.nl --set cluster.gateway.name=gw --set ingress.paths=null
toets_weigering "ingress-zonder-tls" "ingress.tls is verplicht" "${uitvraag[@]}" \
  --set ingress.enabled=true --set ingress.hostname=a.example.nl --set cluster.routingMode=ingress
toets_weigering "onbekende-routering" 'cluster.routingMode "nodeport" is ongeldig' "${uitvraag[@]}" \
  --set cluster.routingMode=nodeport
toets_weigering "default-serviceaccount" "serviceAccount.name is verplicht" "${uitvraag[@]}" \
  --set serviceAccount.create=false
toets_weigering "naam-te-lang" "is langer dan 63 tekens" "${uitvraag[@]}" \
  --set nameOverride=berichtenmagazijn-van-de-belastingdienst-toeslagen-en-douane-xx
toets_weigering "hpa-min-boven-max" "minReplicas (5) is groter dan maxReplicas (3)" "${uitvraag[@]}" \
  --set dienst.autoscaling.hpa.enabled=true --set dienst.autoscaling.hpa.minReplicas=5
toets_weigering "hpa-zonder-doel" "dienst.autoscaling.hpa vraagt targetCPU of targetMemory" "${uitvraag[@]}" \
  --set dienst.autoscaling.hpa.enabled=true --set dienst.autoscaling.hpa.targetCPU=null
toets_weigering "sleutel-zonder-secret" "dienst.sleutel vraagt secretName, key en mountPath" "${stelsel[@]}" \
  --set dienst.sleutel.secretName=
toets_weigering "stelselregister-twee-replicas" "dienst.replicaCount is 2" "${stelsel[@]}" --set dienst.replicaCount=2
toets_weigering "stelselregister-nul-replicas" "dienst.replicaCount is 0" "${stelsel[@]}" --set dienst.replicaCount=0
toets_weigering "stelselregister-hpa" "dienst.autoscaling.hpa.enabled kan niet" "${stelsel[@]}" \
  --set dienst.autoscaling.hpa.enabled=true

echo "== uitkomst =="

if [ "$geslaagd" -lt "$MINIMUM_CONTROLES" ]; then
  fout "$geslaagd controles geslaagd, verwacht minstens $MINIMUM_CONTROLES"
fi

echo "$geslaagd geslaagd, $fouten fout"

[ "$fouten" -eq 0 ]
