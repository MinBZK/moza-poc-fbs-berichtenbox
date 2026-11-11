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

# Het exacte aantal controles en een ondergrens voor de rego-tests, zodat een geschrapte controle
# of een rego-test die niet meer laadt opvalt in plaats van een kortere, groene run op te leveren.
# Elke wijziging van het aantal is zo een zichtbare diff.
VERWACHTE_CONTROLES=190
MINIMUM_REGO_TESTS=57

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

# conftest slaagt ook als een namespace geen enkele regel heeft (een hernoemd package), en meldt
# warnings en exceptions zonder te falen. Daarom: geslaagd, minstens één test, geen warning, geen
# exception. $1 = label, rest = argumenten voor conftest test.
conftest_met_regels() {
  local label=$1 uitvoer
  shift

  if ! uitvoer=$(conftest test --no-color --fail-on-warn "$@" 2>&1); then
    printf '%s\n' "$uitvoer"
    fout "$label"
  elif grep -q '^0 tests' <<<"$uitvoer"; then
    printf '%s\n' "$uitvoer"
    fout "$label: geen enkele regel geëvalueerd"
  elif ! grep -q ', 0 exceptions' <<<"$uitvoer"; then
    printf '%s\n' "$uitvoer"
    fout "$label: een exception schakelt regels uit"
  else
    ok "$label"
  fi
}

# Telt een vaste tekst in een render. $1 = label, $2 = beschrijving, $3 = tekst, $4 = aantal.
toets_aantal() {
  local label=$1 wat=$2 tekst=$3 verwacht=$4 gevonden
  gevonden=$(grep -cF -- "$tekst" "$WERK/$label.yaml" || true)

  if [ "$gevonden" -eq "$verwacht" ]; then
    ok "$label: $wat"
  else
    fout "$label: $wat ('$tekst' ${gevonden}x, verwacht ${verwacht}x)"
  fi
}

# Een render die moet slagen, zonder verdere controle. $1 = label, rest = argumenten voor helm.
toets_slaagt() {
  local label=$1
  shift

  if helm template fbs "$CHART" --namespace fbs "$@" >/dev/null 2>"$WERK/slaagt.log"; then
    ok "$label: render slaagt"
  else
    cat "$WERK/slaagt.log"
    fout "$label: render faalde"
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

# Het aantal geslaagde tests telt, niet het totaal: een test met een todo_-voorvoegsel telt
# conftest als overgeslagen, en die run is groen.
if uitvoer=$(conftest verify --no-color --policy "$POLICY" 2>&1); then
  printf '%s\n' "$uitvoer" | tail -1
  aantal=$(sed -n 's/^[0-9]* tests, \([0-9]*\) passed.*/\1/p' <<<"$uitvoer" | tail -1)

  if ! grep -q ', 0 skipped' <<<"$uitvoer"; then
    fout "conftest verify: overgeslagen tests"
  elif [ "${aantal:-0}" -ge "$MINIMUM_REGO_TESTS" ]; then
    ok "conftest verify: $aantal tests geslaagd"
  else
    fout "conftest verify: ${aantal:-0} tests geslaagd, verwacht minstens $MINIMUM_REGO_TESTS"
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
  toets_bevat_niet "$dienst-gateway" "geen /q in de route" 'value: "/q'
  # Inkomend alleen van de eigen Gateway: één selector op de naam van die Gateway.
  toets_aantal "$dienst-gateway" "inkomend alleen van de eigen Gateway" "owning-gateway-name: 'fundament-gateway'" 1

  # DNS op 53 en 8053, elk over UDP en TCP.
  dns=$(grep -A1 -E 'port: (53|8053)$' "$WERK/$dienst-gateway.yaml" | grep -cE 'protocol: (UDP|TCP)' || true)

  if [ "$dns" -eq 4 ]; then
    ok "$dienst-gateway: DNS op 53 en 8053 over UDP en TCP"
  else
    fout "$dienst-gateway: DNS-regels $dns, verwacht 4"
  fi
done

for dienst in berichtenuitvraag berichtenmagazijn; do
  toets_aantal "$dienst-gateway" "uitgaand naar de proxy's van de Gateway" 'port: 10443' 1
  toets_aantal "$dienst-gateway" "uitgaand alleen naar Envoy-proxy's" 'app.kubernetes.io/managed-by: envoy-gateway' 1
  toets_bevat_niet "$dienst-gateway" "geen platte HTTP naar de Gateway" 'port: 10080'
done

toets_aantal "stelselregister-gateway" "stelselregister niet naar de Gateway" 'port: 10443' 0
toets_aantal "berichtenuitvraag-gateway" "streams zonder request-timeout" 'request: 0s' 1
toets_aantal "stelselregister-gateway" "geen streamregel zonder streams" 'request: 0s' 0
toets_bevat "stelselregister-standaard" "keystore gemount" 'mountPath: /etc/stelselregister'
toets_bevat "stelselregister-standaard" "keystore alleen-lezen" 'readOnly: true'
toets_bevat "stelselregister-standaard" "keystore alleen voor de groep" 'defaultMode: 0440'
toets_bevat "stelselregister-standaard" "keystore uit <fullname>-keystore" 'secretName: fbs-stelselregister-keystore'

echo "== bijzondere values =="

uitvraag=(-f "$CHART/diensten/berichtenuitvraag.yaml" -f "$CHART/ci/standaard-values.yaml")
stelsel=(-f "$CHART/diensten/stelselregister.yaml" -f "$CHART/ci/standaard-values.yaml")

# Zonder bronnen moet de regel wegvallen: een ingress-regel met lege `from` laat juist alles toe.
toets_render "zonder-bronnen" "${uitvraag[@]}" \
  --set networkPolicy.addExternalClientAccess=false --set networkPolicy.ingressNSMatchLabels=null
toets_bevat "zonder-bronnen" "inkomend verkeer helemaal dicht" '  ingress: []'

toets_render "hpa" "${uitvraag[@]}" --set dienst.autoscaling.hpa.enabled=true
toets_soorten "hpa" "Deployment,HorizontalPodAutoscaler,NetworkPolicy,PodDisruptionBudget,Service,ServiceAccount"

toets_render "hpa-min-is-max" "${uitvraag[@]}" --set dienst.autoscaling.hpa.enabled=true \
  --set dienst.autoscaling.hpa.minReplicas=3 --set dienst.autoscaling.hpa.maxReplicas=3

toets_render "pdb-min-available" "${uitvraag[@]}" --set dienst.pdb.minAvailable=1
toets_bevat "pdb-min-available" "minAvailable gezet" 'minAvailable: 1'
toets_bevat_niet "pdb-min-available" "alleen minAvailable" 'maxUnavailable'
toets_render "pdb-nul" "${uitvraag[@]}" --set dienst.pdb.maxUnavailable=0
toets_bevat "pdb-nul" "maxUnavailable 0 blijft 0" 'maxUnavailable: 0'

# Met --set ...=null verdwijnt de sleutel; de PDB moet dan op de standaard terugvallen.
for veld in minAvailable maxUnavailable; do
  toets_render "pdb-$veld-null" "${uitvraag[@]}" --set "dienst.pdb.$veld=null"
  toets_aantal "pdb-$veld-null" "valt terug op maxUnavailable: 1" 'maxUnavailable: 1' 1
  toets_bevat_niet "pdb-$veld-null" "geen veld zonder waarde" 'minAvailable:'
done

toets_render "onveilig-toegestaan" "${uitvraag[@]}" \
  --set dienst.allowUnsafeFlags=true --set-string dienst.env.FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT=true
toets_bevat "onveilig-toegestaan" "klep staat in de env" 'name: FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT'

# De tag-vormen die de keten publiceert: main-<sha7>, pr-<n>-<sha7>, en met -r<n>/-k<n> erachter.
for t in main-0123abc pr-12-0123abc pr-12-0123abc-r2 pr-12-0123abc-k7 pr-12-0123abc-r2-k7; do
  toets_slaagt "tag-$t" -f "$CHART/diensten/berichtenuitvraag.yaml" -f "$CHART/ci/standaard-values.yaml" \
    --set dienst.image.tag="$t"
done

# Een digest mag naast elke tag; dan telt de digest.
toets_slaagt "digest-met-vaste-tag" "${uitvraag[@]}" --set dienst.image.tag=latest \
  --set dienst.image.digest=sha256:0000000000000000000000000000000000000000000000000000000000000000

# Een naam van precies 63 tekens: release fbs, een streepje en een nameOverride van 59.
toets_slaagt "naam-63-tekens" "${uitvraag[@]}" --set nameOverride="$(printf 'a%.0s' $(seq 1 59))"

# Een eigen extraVolumes vervangt de lijst uit diensten/; de keystore mag daar niet aan hangen.
toets_render "stelselregister-extra-volume" "${stelsel[@]}" \
  --set-json 'dienst.extraVolumes=[{"name":"register","configMap":{"name":"register"}}]' \
  --set-json 'dienst.extraVolumeMounts=[{"name":"register","mountPath":"/etc/register"}]'
toets_bevat "stelselregister-extra-volume" "keystore blijft gemount" 'mountPath: /etc/stelselregister'

echo "== twee magazijnen in één namespace =="

# De plug-in "berichtenmagazijn" installeert per organisatie een release. Twee releases mogen
# elkaars objecten niet overschrijven, elkaars pods niet selecteren en elkaars aanroepers niet
# toelaten.
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

  if grep -qF 'client.fbs.minbzk.nl/org-a-berichtenmagazijn: "true"' "$WERK/org-a.yaml" \
      && ! grep -q 'org-b' "$WERK/org-a.yaml"; then
    ok "twee magazijnen: aanroepers per release"
  else
    fout "release org-a laat niet alleen de eigen aanroepers toe"
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
for t in pr-12 main-012345 main-01234567 main-0123ABC main-0123abc-r pr-12-0123abc-x1; do
  toets_weigering "tag-$t" "dienst.image.tag \"$t\" is geen main-<sha7>" "${uitvraag[@]}" --set dienst.image.tag="$t"
done

toets_weigering "zonder-tls-keuze" 'dienst.tlsTermination "" is ongeldig' "${kaal_uitvraag[@]}" "${tag[@]}"
toets_weigering "tls-keuze-onbekend" 'dienst.tlsTermination "gateway" is ongeldig' "${kaal_uitvraag[@]}" "${tag[@]}" \
  --set dienst.tlsTermination=gateway
toets_weigering "tls-app" "dienst.tlsTermination=app levert de chart niet" "${kaal_uitvraag[@]}" "${tag[@]}" \
  --set dienst.tlsTermination=app
toets_weigering "tls-via-env" "HTTP_TLS_TERMINATION staat in dienst.env of extraEnvVars" "${uitvraag[@]}" \
  --set dienst.env.HTTP_TLS_TERMINATION=mesh
toets_weigering "tls-via-extra-env" "HTTP_TLS_TERMINATION staat in dienst.env of extraEnvVars" "${uitvraag[@]}" \
  --set-json 'dienst.extraEnvVars=[{"name":"HTTP_TLS_TERMINATION","value":"mesh"}]'

# Elke klep, in elke schrijfwijze die Quarkus leest, via dienst.env en via extraEnvVars.
for klep in FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT \
    FBS_OUTWAY_UNSAFE_ALLOW_UNVERIFIED_TLS fbs_redis_unsafe_allow_plaintext; do
  toets_weigering "klep-env-$klep" "$klep zet een TLS-eis uit" "${uitvraag[@]}" --set-string "dienst.env.$klep=true"
done

toets_weigering "klep-property-vorm" "fbs.redis.unsafe-allow-plaintext zet een TLS-eis uit" "${uitvraag[@]}" \
  --set-json 'dienst.env={"fbs.redis.unsafe-allow-plaintext":"true"}'
toets_weigering "klep-extra-env" "FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT zet een TLS-eis uit" "${uitvraag[@]}" \
  --set-json 'dienst.extraEnvVars=[{"name":"FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT","value":"true"}]'
toets_weigering "klep-toestemming-als-tekst" "FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT zet een TLS-eis uit" "${uitvraag[@]}" \
  --set-string dienst.allowUnsafeFlags=false --set-string dienst.env.FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT=true

for naam in QUARKUS_PROFILE JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS JAVA_OPTS_APPEND; do
  toets_weigering "profiel-$naam" "$naam kan het Quarkus-profiel wijzigen" "${uitvraag[@]}" \
    --set-string "dienst.env.$naam=dev"
done

route=(--set ingress.enabled=true --set ingress.allowUnauthenticated=true --set ingress.hostname=a.example.nl)

toets_weigering "route-zonder-toestemming" "ingress.enabled vraagt ingress.allowUnauthenticated=true" "${uitvraag[@]}" \
  --set ingress.enabled=true --set ingress.hostname=a.example.nl --set cluster.gateway.name=gw
toets_weigering "route-zonder-hostnaam" "ingress.hostname is verplicht" "${uitvraag[@]}" \
  --set ingress.enabled=true --set ingress.allowUnauthenticated=true --set cluster.gateway.name=gw
toets_weigering "route-zonder-gateway" "cluster.gateway.name is verplicht" "${uitvraag[@]}" "${route[@]}"
toets_weigering "route-zonder-paden" "ingress.paths is leeg" "${uitvraag[@]}" "${route[@]}" \
  --set cluster.gateway.name=gw --set ingress.paths=null

for type in RegularExpression ImplementationSpecific Prefix; do
  toets_weigering "route-padtype-$type" "type \"$type\" is niet toegestaan" "${uitvraag[@]}" "${route[@]}" \
    --set cluster.gateway.name=gw --set-json "ingress.paths=[{\"type\":\"$type\",\"value\":\"/\"}]"
done

toets_weigering "ingress-zonder-tls" "ingress.tls is verplicht" "${uitvraag[@]}" "${route[@]}" \
  --set cluster.routingMode=ingress
toets_weigering "gateway-egress-zonder-podselector" "networkPolicy.gatewayEgress vraagt namespaceMatchLabels en podMatchLabels" \
  "${uitvraag[@]}" --set networkPolicy.gatewayEgress.podMatchLabels=null
toets_weigering "onbekende-routering" 'cluster.routingMode "nodeport" is ongeldig' "${uitvraag[@]}" \
  --set cluster.routingMode=nodeport
toets_weigering "default-serviceaccount" "serviceAccount.name is verplicht" "${uitvraag[@]}" \
  --set serviceAccount.create=false
toets_weigering "naam-64-tekens" "is langer dan 63 tekens" "${uitvraag[@]}" \
  --set nameOverride="$(printf 'a%.0s' $(seq 1 60))"
toets_weigering "hpa-min-boven-max" "minReplicas (5) is groter dan maxReplicas (3)" "${uitvraag[@]}" \
  --set dienst.autoscaling.hpa.enabled=true --set dienst.autoscaling.hpa.minReplicas=5
toets_weigering "hpa-zonder-doel" "dienst.autoscaling.hpa vraagt targetCPU of targetMemory" "${uitvraag[@]}" \
  --set dienst.autoscaling.hpa.enabled=true --set dienst.autoscaling.hpa.targetCPU=null
toets_weigering "keystore-zonder-secret" "dienst.keystore vraagt secretName, key en mountPath" "${stelsel[@]}" \
  --set dienst.keystore.secretName=
toets_weigering "stelselregister-twee-replicas" "dienst.replicaCount is 2" "${stelsel[@]}" --set dienst.replicaCount=2
toets_weigering "stelselregister-nul-replicas" "dienst.replicaCount is 0" "${stelsel[@]}" --set dienst.replicaCount=0
toets_weigering "stelselregister-hpa" "dienst.autoscaling.hpa.enabled kan niet" "${stelsel[@]}" \
  --set dienst.autoscaling.hpa.enabled=true

echo "== uitkomst =="

if [ "$geslaagd" -ne "$VERWACHTE_CONTROLES" ]; then
  fout "$geslaagd controles geslaagd, verwacht precies $VERWACHTE_CONTROLES; pas VERWACHTE_CONTROLES aan als de wijziging bedoeld is"
fi

echo "$geslaagd geslaagd, $fouten fout"

[ "$fouten" -eq 0 ]
