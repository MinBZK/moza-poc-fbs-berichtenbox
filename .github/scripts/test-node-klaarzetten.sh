#!/usr/bin/env bash
# Unittests voor node-klaarzetten.sh, en voor de aansluiting op de workflows. Geen netwerk: een
# curl-stub op het pad levert een zelfgebouwde tarball.
#
# Het contract: Node komt alleen op het pad als de checksum klopt en de pin bij de versie hoort, en
# alleen dan laat Quinoa zijn eigen ongecontroleerde download achterwege.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/node-klaarzetten.sh"

asserties=0
mislukt=0

ok() {
  asserties=$((asserties + 1))
  echo "OK: $1"
}

fout() {
  mislukt=1
  echo "FOUT: $1"
}

gelijk() {
  local wat=$1 verwacht=$2 gemeten=$3

  if [ "$verwacht" = "$gemeten" ]; then
    ok "$wat"
  else
    fout "$wat — verwacht '$verwacht', gemeten '$gemeten'"
  fi
}

bevat() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) ok "$wat" ;;
    *) fout "$wat — '$naald' ontbreekt in: $hooiberg" ;;
  esac
}

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

# Een tarball met dezelfde indeling als die van nodejs.org.
mkdir -p "$werkmap/bron/node-v1.2.3-linux-x64/bin"
printf '#!/bin/sh\necho v1.2.3\n' >"$werkmap/bron/node-v1.2.3-linux-x64/bin/node"
chmod +x "$werkmap/bron/node-v1.2.3-linux-x64/bin/node"
tar -cJf "$werkmap/node-v1.2.3-linux-x64.tar.xz" -C "$werkmap/bron" node-v1.2.3-linux-x64
echte_sha=$(sha256sum "$werkmap/node-v1.2.3-linux-x64.tar.xz" | cut -d' ' -f1)

mkdir -p "$werkmap/stub"
cat >"$werkmap/stub/curl" <<STUB
#!/usr/bin/env bash
echo "\$*" >>"$werkmap/aanroepen"
uit=
while [ \$# -gt 0 ]; do
  case "\$1" in
    -o) uit=\$2; shift 2 ;;
    *) url=\$1; shift ;;
  esac
done
cp "$werkmap/\${url##*/}" "\$uit"
STUB
chmod +x "$werkmap/stub/curl"

# De runner is x86_64, tenzij een geval anders vraagt.
cat >"$werkmap/stub/uname" <<STUB
#!/usr/bin/env bash
cat "$werkmap/arch"
STUB
chmod +x "$werkmap/stub/uname"
echo x86_64 >"$werkmap/arch"

# draai <versie in properties> <pin-inhoud>; zet rc, uitvoer, pad, omgeving.
draai() {
  local versie=$1 pin=$2

  printf 'quarkus.http.port=8093\nquarkus.quinoa.package-manager-install.node-version=%s\n' "$versie" >"$werkmap/app.properties"
  printf '# commentaar telt niet\n%s\n' "$pin" >"$werkmap/node.sha256"
  rm -rf "$werkmap/doel" "$werkmap/aanroepen"
  : >"$werkmap/path"
  : >"$werkmap/env"

  rc=0
  uitvoer=$(PATH="$werkmap/stub:$PATH" GITHUB_PATH="$werkmap/path" GITHUB_ENV="$werkmap/env" \
    "$SCRIPT" "$werkmap/app.properties" "$werkmap/node.sha256" "$werkmap/doel" 2>&1) || rc=$?
  pad=$(cat "$werkmap/path")
  omgeving=$(cat "$werkmap/env")
}

echo "== Checksum klopt"
draai 1.2.3 "$echte_sha  node-v1.2.3-linux-x64.tar.xz"
gelijk "exitcode" 0 "$rc"
gelijk "bin-map op het pad" "$werkmap/doel/node-v1.2.3-linux-x64/bin" "$pad"
gelijk "Quinoa downloadt niet zelf" "QUARKUS_QUINOA_PACKAGE_MANAGER_INSTALL=false" "$omgeving"
gelijk "node draait" "v1.2.3" "$("$pad/node")"
bevat "download van nodejs.org, alleen https" "--proto =https" "$(cat "$werkmap/aanroepen")"
bevat "de juiste url" "https://nodejs.org/dist/v1.2.3/node-v1.2.3-linux-x64.tar.xz" "$(cat "$werkmap/aanroepen")"

echo "== Checksum klopt niet"
draai 1.2.3 "$(printf '0%.0s' {1..64})  node-v1.2.3-linux-x64.tar.xz"
gelijk "exitcode" 1 "$rc"
gelijk "niets op het pad" "" "$pad"
gelijk "Quinoa houdt zijn eigen download niet tegen" "" "$omgeving"
bevat "foutmelding" "Checksum van node-v1.2.3-linux-x64.tar.xz klopt niet" "$uitvoer"
gelijk "niets uitgepakt" "nee" "$([ -e "$werkmap/doel" ] && echo ja || echo nee)"

echo "== Versie opgehoogd zonder de pin"
draai 1.2.4 "$echte_sha  node-v1.2.3-linux-x64.tar.xz"
gelijk "exitcode" 1 "$rc"
gelijk "niets op het pad" "" "$pad"
bevat "foutmelding noemt het uiteenlopen" "lopen uiteen" "$uitvoer"
gelijk "geen download" "nee" "$([ -e "$werkmap/aanroepen" ] && echo ja || echo nee)"

echo "== Twee pins"
draai 1.2.3 "$echte_sha  node-v1.2.3-linux-x64.tar.xz
$echte_sha  node-v1.2.3-linux-x64.tar.xz"
gelijk "exitcode" 1 "$rc"
gelijk "niets op het pad" "" "$pad"

echo "== Geen pin"
draai 1.2.3 ""
gelijk "exitcode" 1 "$rc"
gelijk "niets op het pad" "" "$pad"

echo "== Een runner die geen x86_64 is"
echo aarch64 >"$werkmap/arch"
draai 1.2.3 "$echte_sha  node-v1.2.3-linux-x64.tar.xz"
echo x86_64 >"$werkmap/arch"
gelijk "exitcode" 1 "$rc"
gelijk "niets op het pad" "" "$pad"
bevat "foutmelding noemt de architectuur" "aarch64" "$uitvoer"
gelijk "geen download" "nee" "$([ -e "$werkmap/aanroepen" ] && echo ja || echo nee)"

echo "== Een tarball met een andere node-versie dan de pin belooft"
mkdir -p "$werkmap/bron2/node-v1.2.3-linux-x64/bin"
printf '#!/bin/sh\necho v9.9.9\n' >"$werkmap/bron2/node-v1.2.3-linux-x64/bin/node"
chmod +x "$werkmap/bron2/node-v1.2.3-linux-x64/bin/node"
mv "$werkmap/node-v1.2.3-linux-x64.tar.xz" "$werkmap/echt.tar.xz"
tar -cJf "$werkmap/node-v1.2.3-linux-x64.tar.xz" -C "$werkmap/bron2" node-v1.2.3-linux-x64
draai 1.2.3 "$(sha256sum "$werkmap/node-v1.2.3-linux-x64.tar.xz" | cut -d' ' -f1)  node-v1.2.3-linux-x64.tar.xz"
mv "$werkmap/echt.tar.xz" "$werkmap/node-v1.2.3-linux-x64.tar.xz"
gelijk "exitcode" 1 "$rc"
gelijk "niets op het pad" "" "$pad"
bevat "foutmelding" "geen werkende node v1.2.3" "$uitvoer"

for ongeldig in "" "24" "v24.22.2" "24.22.2;rm"; do
  echo "== Ongeldige versie '$ongeldig'"
  draai "$ongeldig" "$echte_sha  node-v1.2.3-linux-x64.tar.xz"
  gelijk "exitcode" 1 "$rc"
  bevat "foutmelding" "Geen geldige Node-versie" "$uitvoer"
done

echo "== quinoa-zonder-download.sh"
CONTROLE="$REPO_ROOT/.github/scripts/quinoa-zonder-download.sh"
mkdir -p "$werkmap/repo/services/beheerconsole/src" "$werkmap/repo/libraries/x" "$werkmap/repo/demo/y"
rc=0; uitvoer=$("$CONTROLE" "$werkmap/repo" 2>&1) || rc=$?
gelijk "zonder .quinoa: exitcode" 0 "$rc"
mkdir -p "$werkmap/repo/services/beheerconsole/.quinoa/node"
rc=0; uitvoer=$("$CONTROLE" "$werkmap/repo" 2>&1) || rc=$?
gelijk "met .quinoa: exitcode" 1 "$rc"
bevat "met .quinoa: foutmelding noemt de map" "services/beheerconsole/.quinoa" "$uitvoer"
rm -rf "$werkmap/repo/services/beheerconsole/.quinoa"
mkdir -p "$werkmap/repo/demo/y/.quinoa"
rc=0; "$CONTROLE" "$werkmap/repo" >/dev/null 2>&1 || rc=$?
gelijk "ook onder demo/: exitcode" 1 "$rc"

echo "== De echte pin hoort bij de echte versie"
versie=$(sed -n 's/^quarkus\.quinoa\.package-manager-install\.node-version=//p' \
  "$REPO_ROOT/services/beheerconsole/src/main/resources/application.properties")
bevat "pin noemt node-v$versie" "node-v$versie-linux-x64.tar.xz" "$(cat "$REPO_ROOT/services/beheerconsole/node.sha256")"

echo "== Elke job die de console bouwt, zet Node eerst klaar"
# De beheerconsole wordt gebouwd in de image-build van deploy.yml en in de test-shard die hem
# draait. Zonder de stap valt Quinoa stil terug op zijn eigen download.
actie="$REPO_ROOT/.github/actions/node-beheerconsole/action.yml"
gelijk "de actie roept het script aan" ja "$(grep -q 'node-klaarzetten.sh' "$actie" && echo ja || echo nee)"
gelijk "de actie geeft de echte pin mee" ja "$(grep -q 'services/beheerconsole/node.sha256' "$actie" && echo ja || echo nee)"
gelijk "deploy.yml: actie in de image-build van de console" ja "$(
  grep -A1 'uses: ./.github/actions/node-beheerconsole' "$REPO_ROOT/.github/workflows/deploy.yml" |
    grep -q "matrix.service == 'beheerconsole'" && echo ja || echo nee)"
gelijk "test.yml: actie in de stelselregister-shard" ja "$(
  grep -A1 'uses: ./.github/actions/node-beheerconsole' "$REPO_ROOT/.github/workflows/test.yml" |
    grep -q "matrix.shard.id == 'stelselregister'" && echo ja || echo nee)"
# Als opdracht, niet als commentaar: anders is de uitkomstcontrole stil uitgeschakeld.
for wf in deploy.yml test.yml; do
  gelijk "$wf: de uitkomstcontrole draait na de build" ja "$(
    grep -qE '^ +\.github/scripts/quinoa-zonder-download\.sh$' "$REPO_ROOT/.github/workflows/$wf" && echo ja || echo nee)"
done

# Dezelfde bewaarregel en dezelfde sleutel als de Maven-cache, die test-maven-cache.sh wel uitgebreid
# toetst: wijkt deze kopie af, dan kan PR-code de npm-cache van main vullen, of herstelt hij nooit.
cache_feiten=$(python3 - "$actie" "$REPO_ROOT/.github/actions/maven-cache/action.yml" <<'PY'
import sys, yaml

node, maven = (yaml.safe_load(open(p)) for p in sys.argv[1:3])

def stap(actie, id_):
    return next(s for s in actie["runs"]["steps"] if s.get("id") == id_)

print("case-gelijk=" + str(stap(node, "bewaren")["run"] == stap(maven, "bewaren")["run"]).lower())
keys = {s["with"]["key"] for s in node["runs"]["steps"] if "actions/cache" in s.get("uses", "")}
ifs = sorted(s.get("if", "") for s in node["runs"]["steps"] if "actions/cache" in s.get("uses", ""))
print("sleutels=" + str(len(keys)))
print("ifs=" + "|".join(ifs))
PY
)
bevat "npm-cache: dezelfde bewaarregel als de Maven-cache" "case-gelijk=true" "$cache_feiten"
bevat "npm-cache: bewaren en herstellen delen één sleutel" "sleutels=1" "$cache_feiten"
bevat "npm-cache: bewaren alleen op ja, herstellen anders" \
  "ifs=steps.bewaren.outputs.ja != 'true'|steps.bewaren.outputs.ja == 'true'" "$cache_feiten"

gelijk "test.yml: de stelselregister-shard bevat de console" ja "$(
  grep -oE '[{]"id":"stelselregister","modules":"[^"]*"[}]' "$REPO_ROOT/.github/workflows/test.yml" |
    grep -q 'services/beheerconsole' && echo ja || echo nee)"

echo
if [ "$mislukt" -eq 0 ]; then
  echo "Alle tests geslaagd."
else
  echo "Er zijn tests mislukt."
fi

echo "ASSERTIES=$asserties"

exit "$mislukt"
