#!/usr/bin/env bash
# Unittests voor preview-resten.sh, en de kruiscontrole met preview-resten.yml en cleanup-preview.yml.
# Geen netwerk: `gh` en `curl` zijn stubs die per aanroep een geregisseerd antwoord geven.
#
# Het zwaartepunt ligt bij de selectie. Een nummer dat ten onrechte als rest geldt, start een
# opruiming die een levende preview inclusief database weghaalt; een ongetagde versie die ten
# onrechte als los geldt, breekt een image. Beide kanten worden daarom met grensgevallen uitgeoefend.

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$HERE/../.." && pwd)
readonly HERE REPO_ROOT
readonly SCRIPT="$HERE/preview-resten.sh"
readonly RESTEN_YML="$REPO_ROOT/.github/workflows/preview-resten.yml"
readonly CLEANUP_YML="$REPO_ROOT/.github/workflows/cleanup-preview.yml"

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

bevat_niet() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) fout "$wat — '$naald' had niet mogen voorkomen in: $hooiberg" ;;
    *) ok "$wat" ;;
  esac
}

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

# --- stubs ---------------------------------------------------------------------------------------
# Elke aanroep wordt gelogd. Het antwoord hangt aan de argumenten: een hash van de argumenten
# (een leesbare sleutel wordt te lang als bestandsnaam) wijst naar een bestand in fix/, met optioneel een
# .rc- en .err-bestand. Zonder fixture: schrijvende aanroepen slagen, lezende geven een 404.
cat >"$werkmap/stub" <<'STUB'
#!/usr/bin/env bash
werkmap=$(dirname "$0")
printf '%s\n' "$(basename "$0") $*" >>"$werkmap/aanroepen"
sleutel=$(printf '%s' "$(basename "$0") $*" | sha256sum | cut -c1-32)
fix="$werkmap/fix/$sleutel"

# Een tweede antwoord voor dezelfde aanroep (de lijst ná een verwijdering).
if [ -f "$fix.daarna" ] && [ -f "$fix.gebruikt" ]; then
  cat "$fix.daarna"
  exit 0
fi

touch "$fix.gebruikt"

if [ -f "$fix" ] || [ -f "$fix.rc" ]; then
  [ -f "$fix" ] && cat "$fix"
  [ -f "$fix.err" ] && cat "$fix.err" >&2
  exit "$(cat "$fix.rc" 2>/dev/null || echo 0)"
fi

case " $* " in
  *" -X DELETE "* | *" -X POST "* | *" -X PATCH "* | *" workflow run "*) echo '{}'; exit 0 ;;
esac

echo "gh: Not Found (HTTP 404)" >&2
exit 1
STUB
chmod +x "$werkmap/stub"
ln -s stub "$werkmap/gh"
ln -s stub "$werkmap/curl"

export PREVIEW_GH="$werkmap/gh" PREVIEW_CURL="$werkmap/curl" PREVIEW_WACHT=0
# Vast "nu": 2026-10-22T00:00:00Z.
export PREVIEW_NU=1792627200

opnieuw() {
  rm -rf "$werkmap/fix" "$werkmap/aanroepen"
  mkdir -p "$werkmap/fix"
  : >"$werkmap/aanroepen"
}

# antwoord <programma> <argumenten> <stdout> [rc] [stderr]
antwoord() {
  local sleutel
  sleutel=$(printf '%s' "$1 $2" | sha256sum | cut -c1-32)
  printf '%s' "$3" >"$werkmap/fix/$sleutel"
  [ -z "${4:-}" ] || printf '%s' "$4" >"$werkmap/fix/$sleutel.rc"
  [ -z "${5:-}" ] || printf '%s' "$5" >"$werkmap/fix/$sleutel.err"
}

# antwoord_daarna <programma> <argumenten> <stdout>: het antwoord vanaf de tweede aanroep.
antwoord_daarna() {
  local sleutel
  sleutel=$(printf '%s' "$1 $2" | sha256sum | cut -c1-32)
  printf '%s' "$3" >"$werkmap/fix/$sleutel.daarna"
}

aanroepen() {
  cat "$werkmap/aanroepen"
}

# draai <subcommando> [args...]: zet UITVOER (stdout), FOUTEN (stderr) en RC.
draai() {
  RC=0
  UITVOER=$(bash "$SCRIPT" "$@" 2>"$werkmap/stderr") || RC=$?
  FOUTEN=$(cat "$werkmap/stderr")
}

# --- nummers uit namen en tags -------------------------------------------------------------------
# shellcheck source=preview-resten.sh
source "$SCRIPT"

gelijk "alleen exacte pr-<n>-namen tellen als preview" "12
7" "$(printf '%s\n' test pr-12 fsc-logius pr-0 pr-012 pr-3x xpr-4 pr-7 'pr-5 ' | nummers_uit_namen)"
gelijk "een lijst zonder previews levert niets" "" "$(printf '%s\n' test fsc-magazijna | nummers_uit_namen)"
gelijk "een nummer van acht cijfers is geen PR-nummer" "" "$(echo pr-12345678 | nummers_uit_namen)"

gelijk "tags: per-commit, kaal en met dubbel koppelteken; niet main en niet een langer nummer" "16
16
16
168
395" "$(printf '%s\n' pr-16-abc1234 pr-16 pr-16--k1 pr-168-def main-abc1234 pr-0-x latest pr-395--k1423 | nummers_uit_tags | sort)"
gelijk "pr-16 levert niet de tags van PR 168 op" "168" "$(echo pr-168-abc | nummers_uit_tags)"

# --- zad ----------------------------------------------------------------------------------------
readonly OM_URL='https://om.test/api/v2/projects/mpfb-8wh/deployments'
zad_antwoord() {
  antwoord curl "-sSf --max-time 30 --retry 3 --retry-connrefused -H X-API-Key: sleutel -H Accept: application/json $OM_URL" "$@"
}

opnieuw
zad_antwoord '{"deployments":[{"name":"test"},{"name":"pr-12"},{"name":"fsc-logius"},{"name":"pr-400"}]}'
ZAD_API_KEY=sleutel ZAD_API_URL=https://om.test/api draai zad mpfb-8wh
gelijk "zad levert de pr-nummers van het project" "0|12 400" "$RC|$(tr '\n' ' ' <<<"$UITVOER" | sed 's/ $//')"

opnieuw
zad_antwoord '{"deployments":[{"name":"test"},{"name":"fsc-logius"}]}'
ZAD_API_KEY=sleutel ZAD_API_URL=https://om.test/api draai zad mpfb-8wh
gelijk "een project zonder previews is een geslaagde, lege meting" "0|" "$RC|$UITVOER"

opnieuw
zad_antwoord '{"deployments":[{"name":"pr-12"}]}'
ZAD_API_KEY=sleutel ZAD_API_URL=https://om.test/api draai zad mpfb-8wh
gelijk "een lijst zonder test is onbepaald" 2 "$RC"
bevat "en zegt waarom" "mist 'test'" "$FOUTEN"

opnieuw
zad_antwoord '<html>proxy</html>'
ZAD_API_KEY=sleutel ZAD_API_URL=https://om.test/api draai zad mpfb-8wh
gelijk "een antwoord dat geen JSON is, is onbepaald" 2 "$RC"

opnieuw
zad_antwoord '' 22 'curl: (22) 401'
ZAD_API_KEY=sleutel ZAD_API_URL=https://om.test/api draai zad mpfb-8wh
gelijk "een mislukte opvraging is onbepaald" 2 "$RC"

opnieuw
ZAD_API_KEY='' draai zad mpfb-8wh
gelijk "zonder sleutel is zad onbepaald" 2 "$RC"
gelijk "en vraagt dan niets op" "" "$(aanroepen)"

# --- github -------------------------------------------------------------------------------------
github_fixtures() {
  antwoord gh 'api repos/o/r/environments?per_page=100 --paginate' \
    '{"environments":[{"name":"test"},{"name":"github-pages"},{"name":"pr-7"}]}{"environments":[{"name":"pr-9"}]}'
  antwoord gh 'api repos/o/r/deployments?per_page=100 --paginate' \
    '[{"environment":"test"},{"environment":"pr-7"},{"environment":"pr-7"}][{"environment":"pr-11"},{"environment":"github-pages"}]'
  antwoord gh 'api orgs/o/packages/container/fbs-a/versions?per_page=100 --paginate' \
    '[{"metadata":{"container":{"tags":["pr-7-abc","pr-7-def","main-123"]}}},{"metadata":{"container":{"tags":[]}}}]'
  antwoord gh 'api orgs/o/packages/container/fbs-b/versions?per_page=100 --paginate' \
    '[{"metadata":{"container":{}}},{"metadata":{"container":{"tags":["pr-13"]}}}]'
  antwoord gh 'api orgs/o/packages?package_type=container&per_page=100 --paginate' \
    '[{"name":"fbs-a","repository":{"full_name":"o/r"}},{"name":"fbs-b","repository":{"full_name":"o/r"}},
      {"name":"fbs-oud","repository":{"full_name":"o/r"}},{"name":"ander","repository":{"full_name":"o/elders"}},
      {"name":"los"}]'
  antwoord gh 'api orgs/o/packages/container/fbs-oud/versions?per_page=100 --paginate' \
    '[{"metadata":{"container":{"tags":["pr-21-abc"]}}}]'
  antwoord gh 'api orgs/o/packages/container/ander/versions?per_page=100 --paginate' \
    '[{"metadata":{"container":{"tags":["pr-7-abc"]}}}]'
}

opnieuw
github_fixtures
REPO=o/r ORG=o PAKKETTEN='fbs-a fbs-b' draai github
gelijk "github levert per bron de nummers" "0
7	omgeving
9	omgeving
11	github-deployment
7	github-deployment
7	ghcr:fbs-a
13	ghcr:fbs-b
21	vreemd-pakket:fbs-oud" "$RC
$UITVOER"
bevat_niet "een package van een andere repo wordt niet bekeken" "ander/versions" "$(aanroepen)"
bevat_niet "en evenmin een package zonder repo" "packages/container/los" "$(aanroepen)"

opnieuw
github_fixtures
antwoord gh 'api repos/o/r/environments?per_page=100 --paginate' '{"environments":[{"name":"pr-7"}]}'
REPO=o/r ORG=o PAKKETTEN='fbs-a fbs-b' draai github
gelijk "een omgevingslijst zonder test is onbepaald" 2 "$RC"

opnieuw
github_fixtures
antwoord gh 'api repos/o/r/deployments?per_page=100 --paginate' '[{"environment":"pr-7"}]'
REPO=o/r ORG=o PAKKETTEN='fbs-a fbs-b' draai github
gelijk "een deploymentlijst zonder test is onbepaald" 2 "$RC"

opnieuw
github_fixtures
antwoord gh 'api orgs/o/packages/container/fbs-b/versions?per_page=100 --paginate' '' 1 'gh: Server Error (HTTP 502)'
REPO=o/r ORG=o PAKKETTEN='fbs-a fbs-b' draai github
gelijk "een onleesbaar package is onbepaald, niet 'geen tags'" 2 "$RC"

opnieuw
github_fixtures
antwoord gh 'api orgs/o/packages?package_type=container&per_page=100 --paginate' '' 1 'gh: Forbidden (HTTP 403)'
REPO=o/r ORG=o PAKKETTEN='fbs-a fbs-b' draai github
gelijk "een onleesbare packagelijst is onbepaald" 2 "$RC"

REPO=o/r ORG=o PAKKETTEN='' draai github
gelijk "zonder PAKKETTEN is github onbepaald" 2 "$RC"

# --- classificeer -------------------------------------------------------------------------------
printf '%s\n' '5	omgeving' '7	omgeving' '7	zad:uitvraag' '8	ghcr:fbs-a' '9	omgeving' '10	vreemd-pakket:fbs-oud' \
  '12	omgeving' '14	ghcr:fbs-a' '14	vreemd-pakket:fbs-oud' >"$werkmap/resten.tsv"

classificeer_fixtures() {
  antwoord gh 'api repos/o/r/pulls?state=open&per_page=100 --paginate' '[{"number":5}][{"number":6}]'
  antwoord gh 'api repos/o/r/pulls/7' '{"state":"closed","closed_at":"2026-10-20T00:00:00Z"}'
  # Een uur geleden gesloten: de opruiming van het close-event loopt mogelijk nog.
  antwoord gh 'api repos/o/r/pulls/8' '{"state":"closed","closed_at":"2026-10-21T23:00:00Z"}'
  antwoord gh 'api repos/o/r/pulls/9' '{"message":"Not Found"}' 1 'gh: Not Found (HTTP 404)'
  antwoord gh 'api repos/o/r/pulls/10' '{"state":"closed","closed_at":"2026-09-01T00:00:00Z"}'
  # Tussen de open-lijst en de opvraging heropend.
  antwoord gh 'api repos/o/r/pulls/12' '{"state":"open","closed_at":null}'
  antwoord gh 'api repos/o/r/pulls/14' '{"state":"closed","closed_at":"2026-09-01T00:00:00Z"}'
}

opnieuw
classificeer_fixtures
REPO=o/r draai classificeer "$werkmap/resten.tsv"
gelijk "classificeer: open, net gesloten en heropend vallen eruit; de rest krijgt een klasse" "0
7	opruimen	omgeving,zad:uitvraag
9	geen-pr	omgeving
10	vreemd-pakket	vreemd-pakket:fbs-oud
14	opruimen	ghcr:fbs-a,vreemd-pakket:fbs-oud" "$RC
$UITVOER"
bevat_niet "een open PR wordt niet eens opgevraagd" "pulls/5" "$(aanroepen)"

opnieuw
classificeer_fixtures
antwoord gh 'api repos/o/r/pulls/9' '' 1 'gh: Server Error (HTTP 500)'
REPO=o/r draai classificeer "$werkmap/resten.tsv"
gelijk "een PR die niet op te vragen is (geen 404), is onbepaald" 2 "$RC"

opnieuw
classificeer_fixtures
antwoord gh 'api repos/o/r/pulls?state=open&per_page=100 --paginate' '' 1 'gh: Bad Gateway (HTTP 502)'
REPO=o/r draai classificeer "$werkmap/resten.tsv"
gelijk "zonder open-lijst ruimt classificeer niets in" "2|" "$RC|$UITVOER"

opnieuw
classificeer_fixtures
antwoord gh 'api repos/o/r/pulls/7' '{"state":"closed","closed_at":null}'
REPO=o/r draai classificeer "$werkmap/resten.tsv"
gelijk "een gesloten PR zonder sluitmoment is onbepaald" 2 "$RC"

opnieuw
: >"$werkmap/leeg.tsv"
antwoord gh 'api repos/o/r/pulls?state=open&per_page=100 --paginate' '[]'
REPO=o/r draai classificeer "$werkmap/leeg.tsv"
gelijk "zonder resten levert classificeer niets" "0|" "$RC|$UITVOER"

# --- opruimen -----------------------------------------------------------------------------------
opruim_fixtures() {
  antwoord gh "run list --repo o/r --workflow cleanup-preview.yml --event workflow_dispatch --limit 50 --json databaseId,displayTitle" \
    '[{"databaseId":1,"displayTitle":"Cleanup ZAD preview pr-7 resten-99-1-77"},{"databaseId":2,"displayTitle":"Cleanup ZAD preview pr-7 resten-99-1-7"},{"databaseId":3,"displayTitle":"Cleanup ZAD preview pr-8 resten-99-1-8"}]'
  antwoord gh 'run view 2 --repo o/r --json status,conclusion' '{"status":"completed","conclusion":"success"}'
  antwoord gh 'run view 3 --repo o/r --json status,conclusion' '{"status":"completed","conclusion":"failure"}'
}

opnieuw
opruim_fixtures
REPO=o/r KENMERK=resten-99-1 draai opruimen 7 8
gelijk "opruimen start per nummer cleanup-preview.yml en leest de eigen run" "0
7	success	https://github.com/o/r/actions/runs/2
8	failure	https://github.com/o/r/actions/runs/3" "$RC
$UITVOER"
bevat "het kenmerk gaat als input mee" "workflow run cleanup-preview.yml --repo o/r -f pr=7 -f aanvraag=resten-99-1-7" "$(aanroepen)"
gelijk "na elkaar: de tweede start pas als de eerste klaar is" \
  "gh workflow run cleanup-preview.yml --repo o/r -f pr=7 -f aanvraag=resten-99-1-7|gh run view 2 --repo o/r --json status,conclusion|gh workflow run cleanup-preview.yml --repo o/r -f pr=8 -f aanvraag=resten-99-1-8|gh run view 3 --repo o/r --json status,conclusion" \
  "$(grep -E 'workflow run|run view' "$werkmap/aanroepen" | paste -sd'|' -)"

opnieuw
opruim_fixtures
antwoord gh "workflow run cleanup-preview.yml --repo o/r -f pr=7 -f aanvraag=resten-99-1-7" '' 1 'HTTP 403'
REPO=o/r KENMERK=resten-99-1 draai opruimen 7 8
gelijk "een mislukte start gaat niet verloren en houdt de volgende niet tegen" "7	niet-gestart"$'\t'"
8	failure	https://github.com/o/r/actions/runs/3" "$UITVOER"

opnieuw
opruim_fixtures
REPO=o/r KENMERK=ander draai opruimen 7
gelijk "een run met een ander kenmerk telt niet" "7	niet-gevonden	" "$UITVOER"

opnieuw
opruim_fixtures
rm "$werkmap/fix/$(printf '%s' 'gh run view 2 --repo o/r --json status,conclusion' | sha256sum | cut -c1-32)"
REPO=o/r KENMERK=resten-99-1 draai opruimen 7 8
gelijk "een run die niet op te vragen blijft, geeft het wachten op en houdt de volgende niet tegen" "7	onbekend	https://github.com/o/r/actions/runs/2
8	failure	https://github.com/o/r/actions/runs/3" "$UITVOER"

REPO=o/r KENMERK='' draai opruimen 7
gelijk "zonder kenmerk start opruimen niets" 2 "$RC"

# --- ongetagd -----------------------------------------------------------------------------------
# Drie ongetagde versies: twee oud genoeg, één van gisteren. Twee getagde.
readonly VERSIES='[{"id":1,"name":"sha256:a","created_at":"2026-10-01T00:00:00Z","metadata":{"container":{"tags":["main-1"]}}},
{"id":2,"name":"sha256:b","created_at":"2026-08-01T00:00:00Z","metadata":{"container":{"tags":[]}}}]
[{"id":3,"name":"sha256:c","created_at":"2026-09-01T00:00:00Z","metadata":{"container":{}}},
{"id":4,"name":"sha256:d","created_at":"2026-10-21T00:00:00Z","metadata":{"container":{"tags":[]}}},
{"id":5,"name":"sha256:e","created_at":"2026-07-01T00:00:00Z","metadata":{"container":{"tags":["pr-7-x"]}}}]'
readonly MANIFEST='application/vnd.docker.distribution.manifest.v2+json'

ongetagd_fixtures() {
  antwoord gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' "$VERSIES"
  antwoord curl '-sSf --max-time 30 --retry 3 -u x:t https://ghcr.io/token?scope=repository:o/p:pull' '{"token":"rt"}'
  for d in a e; do
    antwoord curl "-sSf --max-time 30 --retry 3 -o /dev/null -w %{content_type} -I -H Authorization: Bearer rt -H Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json https://ghcr.io/v2/o/p/manifests/sha256:$d" "${1-$MANIFEST}"
  done
}

opnieuw
ongetagd_fixtures
ORG=o GH_TOKEN=t draai ongetagd p
gelijk "zonder VERWIJDER wordt alleen geteld" "0|p	te-verwijderen	2	alleen geïnventariseerd" "$RC|$UITVOER"
bevat_niet "en niets verwijderd" "DELETE" "$(aanroepen)"

opnieuw
ongetagd_fixtures
# Na het verwijderen staan alleen de getagde en de jonge versie er nog.
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' '[{"id":1},{"id":4},{"id":5}]'
ORG=o GH_TOKEN=t VERWIJDER=true draai ongetagd p
gelijk "oude ongetagde versies worden verwijderd en nagemeten" "0|p	verwijderd	2	" "$RC|$UITVOER"
gelijk "precies de oude ongetagde versies krijgen een DELETE" \
  "gh api orgs/o/packages/container/p/versions/2 -X DELETE|gh api orgs/o/packages/container/p/versions/3 -X DELETE" \
  "$(grep DELETE "$werkmap/aanroepen" | paste -sd'|' -)"

opnieuw
ongetagd_fixtures
ORG=o GH_TOKEN=t VERWIJDER=true draai ongetagd p
# De lijst na het verwijderen is dezelfde fixture als ervoor: alles staat er nog.
gelijk "wat na het verwijderen nog in de lijst staat, is mislukt" "p	verwijderd	0"$'\t'"
p	mislukt	2	staan na het verwijderen nog in de lijst (0 weigeringen)" "$UITVOER"

for type in application/vnd.oci.image.index.v1+json application/vnd.docker.distribution.manifest.list.v2+json ''; do
  opnieuw
  ongetagd_fixtures "$type"
  ORG=o GH_TOKEN=t VERWIJDER=true draai ongetagd p
  bevat "een getagde versie van type '${type:-leeg}' houdt alles tegen" "p	bewaard	2	" "$UITVOER"
  bevat_niet "en verwijdert niets bij type '${type:-leeg}'" "DELETE" "$(aanroepen)"
done

opnieuw
ongetagd_fixtures
antwoord curl "-sSf --max-time 30 --retry 3 -o /dev/null -w %{content_type} -I -H Authorization: Bearer rt -H Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json https://ghcr.io/v2/o/p/manifests/sha256:e" '' 22
ORG=o GH_TOKEN=t VERWIJDER=true draai ongetagd p
bevat "een niet op te vragen mediatype houdt alles tegen" "p	bewaard	2	mediatype van sha256:e niet op te vragen" "$UITVOER"
bevat_niet "en verwijdert niets" "DELETE" "$(aanroepen)"

opnieuw
ongetagd_fixtures
antwoord curl '-sSf --max-time 30 --retry 3 -u x:t https://ghcr.io/token?scope=repository:o/p:pull' '' 22
ORG=o GH_TOKEN=t VERWIJDER=true draai ongetagd p
bevat "zonder registry-token blijft alles staan" "p	bewaard	2	geen registry-token" "$UITVOER"

opnieuw
antwoord gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' \
  '[{"id":1,"name":"sha256:a","created_at":"2026-01-01T00:00:00Z","metadata":{"container":{"tags":["main-1"]}}},{"id":4,"name":"sha256:d","created_at":"2026-10-21T00:00:00Z","metadata":{"container":{"tags":[]}}}]'
ORG=o GH_TOKEN=t VERWIJDER=true draai ongetagd p
gelijk "alleen jonge ongetagde versies: niets te doen, niets opgevraagd" "0||" "$RC|$UITVOER|$(grep -c curl "$werkmap/aanroepen" | sed 's/^0$//')"

opnieuw
ORG=o GH_TOKEN=t draai ongetagd p
gelijk "een onleesbare versielijst is onbepaald" 2 "$RC"

# --- bevindingen --------------------------------------------------------------------------------
GOED='{
  "zad": {"result": "success", "outputs": {"nummers-uitvraag": "7", "nummers-magazijnen": "geen"}},
  "inventaris": {"result": "success", "outputs": {"opruimen": "7", "bevindingen": "7\topruimen\tomgeving,zad:uitvraag"}},
  "opruimen": {"result": "success", "outputs": {"uitkomsten": "7\tsuccess\thttps://x/runs/2"}},
  "ongetagd": {"result": "success", "outputs": {"uitkomsten": "p\tverwijderd\t2\t"}}
}'

# bevinding <omschrijving> <jq-bewerking op GOED> <verwacht> [CANCELLED] [OPRUIMEN]
bevinding() {
  local needs
  needs=$(jq -c "$2" <<<"$GOED")
  RC=0
  UITVOER=$(NEEDS="$needs" CANCELLED="${4:-false}" OPRUIMEN="${5:-true}" bash "$SCRIPT" bevindingen 2>&1) || RC=$?
  gelijk "$1" "0|$3" "$RC|$UITVOER"
}

bevinding "een geslaagde run zonder resten meldt niets" '.' ''
bevinding "een onleesbaar ZAD-project wordt gemeld" '.zad.outputs."nummers-magazijnen" = "onleesbaar"' \
  'onbepaald	zad:magazijnen	de deploymentlijst is niet te lezen'
bevinding "een omgevallen ZAD-leg wordt gemeld" '.zad.result = "failure"' 'onbepaald	zad	de job eindigde als failure'
bevinding "een omgevallen inventaris wordt gemeld" '.inventaris.result = "failure"' 'onbepaald	inventaris	de job eindigde als failure'
bevinding "een ontbrekende ongetagd-job wordt gemeld" 'del(.ongetagd)' 'onbepaald	ongetagd	de job eindigde als ontbreekt'
bevinding "een overgeslagen opruiming met werk wordt gemeld" '.opruimen = {"result": "skipped"}' \
  'onbepaald	opruimen	de job eindigde als skipped; voor 7 is niet vastgesteld of de opruiming lukte'
bevinding "een overgeslagen opruiming zonder werk is stil" '.inventaris.outputs.opruimen = "" | .opruimen = {"result": "skipped"}' ''
bevinding "in een inventariseer-run is een overgeslagen opruiming stil" '.opruimen = {"result": "skipped"}' '' false false
bevinding "een mislukte opruiming wordt gemeld met de run" '.opruimen.outputs.uitkomsten = "7\tfailure\thttps://x/runs/2"' \
  'opruiming-mislukt	pr-7	cleanup-preview.yml eindigde als failure (https://x/runs/2)'
bevinding "een niet-gestarte opruiming wordt gemeld" '.opruimen.outputs.uitkomsten = "7\tniet-gestart\t"' \
  'opruiming-mislukt	pr-7	cleanup-preview.yml eindigde als niet-gestart'
bevinding "geen-pr en vreemd-pakket worden gemeld, opruimen niet" \
  '.inventaris.outputs.bevindingen = "7\topruimen\tomgeving\n9\tgeen-pr\tomgeving\n10\tvreemd-pakket\tvreemd-pakket:x\n14\topruimen\tghcr:a,vreemd-pakket:x\nonbepaald\tgithub\tniet te lezen"' \
  'geen-pr	pr-9	resten zonder bijbehorende PR: omgeving
vreemd-pakket	pr-10	tags in een package buiten de opruimlijst: vreemd-pakket:x
vreemd-pakket	pr-14	tags in een package buiten de opruimlijst: ghcr:a,vreemd-pakket:x
onbepaald	github	niet te lezen'
bevinding "ongetagd: bewaard, mislukt en onbepaald worden gemeld; verwijderd niet" \
  '.ongetagd.outputs.uitkomsten = "p\tverwijderd\t2\t\nq\tbewaard\t3\tindex\nr\tmislukt\t1\tstaat er nog\ns\tonbepaald\t\tniet te lezen\nt\tte-verwijderen\t4\t"' \
  'ongetagd-bewaard	q	3 ongetagde versie(s) blijven staan: index
ongetagd-mislukt	r	1 ongetagde versie(s) staat er nog
onbepaald	ghcr:s	niet te lezen'
bevinding "een afgebroken run wordt gemeld" '.' 'onbepaald	run	de run is afgebroken; wat er staat, is niet volledig vastgesteld' true

RC=0
NEEDS='geen json' CANCELLED=false bash "$SCRIPT" bevindingen >/dev/null 2>&1 || RC=$?
gelijk "een NEEDS die geen JSON is, is onbepaald" 2 "$RC"

# --- issue --------------------------------------------------------------------------------------
readonly ISSUES_URL='api repos/o/r/issues?state=open&per_page=100 --paginate'
printf 'onbepaald\tzad:magazijnen\tniet te lezen (https://run/1)\n' >"$werkmap/bevindingen"
STAND=$(cut -f1,2 "$werkmap/bevindingen" | sort -u | sha256sum | cut -c1-16)

opnieuw
antwoord gh "$ISSUES_URL" '[]'
REPO=o/r RUN_URL=https://run/1 draai issue "$werkmap/leeg.tsv"
gelijk "niets te melden en geen issue: stil" "0|Niets te melden." "$RC|$UITVOER"
bevat_niet "en er wordt niets geschreven" "-X" "$(aanroepen)"

opnieuw
antwoord gh "$ISSUES_URL" '[{"number":40,"title":"Achtergebleven preview-resten","user":{"login":"github-actions[bot]"}}]'
REPO=o/r RUN_URL=https://run/1 draai issue "$werkmap/leeg.tsv"
gelijk "niets meer te melden en een open issue: sluiten" "0|Issue #40 gesloten: niets meer te melden." "$RC|$UITVOER"
bevat "met een afsluitende comment" "api repos/o/r/issues/40/comments -X POST" "$(aanroepen)"
bevat "en state=closed" "api repos/o/r/issues/40 -X PATCH -f state=closed" "$(aanroepen)"

opnieuw
antwoord gh "$ISSUES_URL" '[{"number":41,"title":"Achtergebleven preview-resten","user":{"login":"iemand"}},{"number":42,"title":"Achtergebleven preview-resten","user":{"login":"github-actions[bot]"},"pull_request":{}}]'
REPO=o/r RUN_URL=https://run/1 draai issue "$werkmap/bevindingen"
bevat "een issue van een mens of een PR met dezelfde titel telt niet; er komt een nieuw" "api repos/o/r/issues -X POST -f title=Achtergebleven preview-resten" "$(aanroepen)"
bevat "de beschrijving draagt de stand" "<!-- stand: $STAND -->" "$(aanroepen)"
bevat "en de bevinding als tabelrij" '| onbepaald | `zad:magazijnen` | niet te lezen (https://run/1) |' "$(aanroepen)"

opnieuw
antwoord gh "$ISSUES_URL" '[{"number":40,"title":"Achtergebleven preview-resten","user":{"login":"github-actions[bot]"}}]'
antwoord gh 'api repos/o/r/issues/40' "{\"body\":\"<!-- stand: $STAND -->\\nbla\"}"
printf 'onbepaald\tzad:magazijnen\tniet te lezen (https://run/2)\n' >"$werkmap/bevindingen2"
REPO=o/r RUN_URL=https://run/2 draai issue "$werkmap/bevindingen2"
gelijk "dezelfde stand met een andere run-URL: niets bijgewerkt" "0|Issue #40: stand ongewijzigd, niets bijgewerkt." "$RC|$UITVOER"
bevat_niet "en geen schrijfactie" "-X" "$(aanroepen)"

opnieuw
antwoord gh "$ISSUES_URL" '[{"number":40,"title":"Achtergebleven preview-resten","user":{"login":"github-actions[bot]"}}]'
antwoord gh 'api repos/o/r/issues/40' '{"body":"<!-- stand: 0000000000000000 -->"}'
REPO=o/r RUN_URL=https://run/1 draai issue "$werkmap/bevindingen"
gelijk "een gewijzigde stand werkt het issue bij" "0|Issue #40 bijgewerkt." "$RC|$UITVOER"
bevat "met een comment, zodat het in de notificaties komt" "api repos/o/r/issues/40/comments -X POST" "$(aanroepen)"

opnieuw
antwoord gh "$ISSUES_URL" '' 1 'gh: Forbidden (HTTP 403)'
REPO=o/r RUN_URL=https://run/1 draai issue "$werkmap/bevindingen"
gelijk "een onleesbare issuelijst is onbepaald, geen dubbel issue" 2 "$RC"
bevat_niet "en maakt dan niets aan" "-X POST" "$(aanroepen)"

opnieuw
antwoord gh "$ISSUES_URL" '[]'
antwoord gh "api repos/o/r/issues -X POST -f title=Achtergebleven preview-resten -f body=$(cat /dev/null)" '' 1
REPO=o/r RUN_URL=https://run/1 draai issue "$werkmap/leeg.tsv"
gelijk "zonder bevindingen wordt het aanmaken niet geprobeerd" 0 "$RC"

# --- kruiscontrole met de workflows -------------------------------------------------------------
while IFS= read -r regel; do
  case "$regel" in
    OK:*) ok "${regel#OK:}" ;;
    FOUT:*) fout "${regel#FOUT:}" ;;
    *) fout "onverwachte uitvoer van de kruiscontrole: $regel" ;;
  esac
done < <(python3 - "$RESTEN_YML" "$CLEANUP_YML" <<'PY' 2>&1 || echo "FOUT:de kruiscontrole zelf viel om"
import re
import sys

import yaml


def meld(goed, tekst):
    print(("OK:" if goed else "FOUT:") + tekst)


def lees(pad):
    with open(pad, encoding="utf-8") as bestand:
        return yaml.safe_load(bestand)


resten, cleanup = lees(sys.argv[1]), lees(sys.argv[2])
jobs = resten["jobs"]
# PyYAML leest de sleutel `on` als True.
triggers = resten.get("on", resten.get(True)) or {}


def legs(job):
    include = (((job or {}).get("strategy") or {}).get("matrix") or {}).get("include") or []
    return sorted(tuple(leg.get(veld) for veld in ("naam", "project", "key")) for leg in include)


zad_legs = legs(jobs.get("zad"))
meld(bool(zad_legs) and zad_legs == legs(cleanup["jobs"].get("cleanup-preview-zad")),
     "de ZAD-inventaris telt precies de projecten die cleanup-preview.yml opruimt")
meld(
    (jobs["zad"].get("outputs") or {})
    == {f"nummers-{n}": f"${{{{ steps.lijst.outputs.nummers-{n} }}}}" for n, _, _ in zad_legs},
    "de ZAD-inventaris heeft per leg een nummers-output",
)

meld("schedule" in triggers and "workflow_dispatch" in triggers, "de controle draait op een ritme én is handmatig te starten")

melding = jobs.get("melding") or {}
needs = melding.get("needs") or []
meld(sorted(needs) == sorted(n for n in jobs if n != "melding"), "de meldjob hangt aan elke andere job")
meld(str(melding.get("if", "")).replace(" ", "") == "${{always()}}", "de meldjob draait op always()")
meld(
    all(re.search(r"(always|cancelled)\(\)", str(s.get("if", ""))) for s in melding.get("steps") or []),
    "elke stap van de meldjob overleeft een afbreking",
)
meld((melding.get("permissions") or {}).get("issues") == "write", "de meldjob mag het issue bijwerken")
meld((jobs.get("opruimen", {}).get("permissions") or {}).get("actions") == "write", "de opruimjob mag cleanup-preview.yml starten")

# De opruiming leunt op cleanup-preview.yml: het kenmerk moet als input bestaan en in de runnaam staan,
# anders vindt `opruimen` zijn eigen run nooit terug.
dispatch = (cleanup.get("on", cleanup.get(True)) or {}).get("workflow_dispatch") or {}
meld("aanvraag" in (dispatch.get("inputs") or {}), "cleanup-preview.yml neemt een kenmerk aan")
meld("inputs.aanvraag" in str(cleanup.get("run-name", "")), "cleanup-preview.yml zet het kenmerk in de runnaam")
# `inputs.pr` vóór het kenmerk: preview-resten.sh zoekt op het kenmerk inclusief nummer.
meld(
    re.search(r"pr-\{0\} \{1\}', inputs\.pr, inputs\.aanvraag", str(cleanup.get("run-name", ""))) is not None,
    "de runnaam van een handmatige run is 'pr-<n> <kenmerk>'",
)
PY
)

echo
if [ "$mislukt" -eq 0 ]; then
  echo "Alle tests geslaagd."
else
  echo "Er zijn tests mislukt."
fi

echo "ASSERTIES=$asserties"

exit "$mislukt"
