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

# Een ander antwoord vanaf de zoveelste aanroep (standaard de tweede): de lijst ná een
# verwijdering, of een run die na een reeks mislukte opvragingen tóch antwoordt.
teller=$(($(cat "$fix.teller" 2>/dev/null || echo 0) + 1))
echo "$teller" >"$fix.teller"

if [ -f "$fix.daarna" ] && [ "$teller" -ge "$(cat "$fix.vanaf" 2>/dev/null || echo 2)" ]; then
  cat "$fix.daarna"
  exit 0
fi

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

export PREVIEW_GH="$werkmap/gh" PREVIEW_CURL="$werkmap/curl" PREVIEW_WACHT=0 PREVIEW_PAUZE=0
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

# antwoord_daarna <programma> <argumenten> <stdout> [vanaf]: het antwoord vanaf aanroep <vanaf>
# (standaard de tweede).
antwoord_daarna() {
  local sleutel
  sleutel=$(printf '%s' "$1 $2" | sha256sum | cut -c1-32)
  printf '%s' "$3" >"$werkmap/fix/$sleutel.daarna"
  printf '%s' "${4:-2}" >"$werkmap/fix/$sleutel.vanaf"
}

# antwoord_bestand <programma> <argumenten> <bestand>: een binair antwoord (een zip).
antwoord_bestand() {
  local sleutel
  sleutel=$(printf '%s' "$1 $2" | sha256sum | cut -c1-32)
  cp "$3" "$werkmap/fix/$sleutel"
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
# Het anker `(-|$)` na het nummer: zonder levert `pr-3x` het nummer 3 op, en `pr-12345678-a` de
# eerste zeven cijfers.
gelijk "een tag zonder koppelteken of einde na het nummer levert niets" "" \
  "$(printf '%s\n' pr-3x pr-12345678-a pr-12345678 | nummers_uit_tags)"
gelijk "zeven cijfers is de grens" "1234567" "$(echo pr-1234567-a | nummers_uit_tags)"

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

# De grens van 24 uur: precies 24 uur dicht telt mee, een seconde minder niet.
printf '%s\n' '20	omgeving' '21	omgeving' >"$werkmap/grens.tsv"
opnieuw
antwoord gh 'api repos/o/r/pulls?state=open&per_page=100 --paginate' '[]'
antwoord gh 'api repos/o/r/pulls/20' '{"state":"closed","closed_at":"2026-10-21T00:00:00Z"}'
antwoord gh 'api repos/o/r/pulls/21' '{"state":"closed","closed_at":"2026-10-21T00:00:01Z"}'
REPO=o/r draai classificeer "$werkmap/grens.tsv"
gelijk "precies 24 uur dicht wordt opgeruimd, 23:59:59 nog niet" "20	opruimen	omgeving" "$UITVOER"

# --- kies ---------------------------------------------------------------------------------------
# kies_nummers <aantal>: een bevindingenbestand met nummers 1..aantal als `opruimen`, plus een
# `geen-pr` en een `vreemd-pakket` die nooit gekozen mogen worden.
kies_nummers() {
  {
    seq "$1" | sed 's/$/\topruimen\tomgeving/'
    printf '90\tgeen-pr\tomgeving\n91\tvreemd-pakket\tvreemd-pakket:x\n'
  } >"$werkmap/kies.tsv"
}

kies_uit() {
  tr '\n' ' ' <<<"$UITVOER" | sed 's/ $//'
}

kies_nummers 10
MAX_PER_RUN=10 draai kies "$werkmap/kies.tsv"
gelijk "precies 10 nummers bij een plafond van 10: alle tien" "1 2 3 4 5 6 7 8 9 10" "$(kies_uit)"
MAX_PER_RUN=10 LAATSTE=4 draai kies "$werkmap/kies.tsv"
gelijk "binnen het plafond hangt de keuze niet van LAATSTE af" "1 2 3 4 5 6 7 8 9 10" "$(kies_uit)"

kies_nummers 11
MAX_PER_RUN=10 draai kies "$werkmap/kies.tsv"
gelijk "11 nummers zonder stand: begin bij het laagste" "1 2 3 4 5 6 7 8 9 10" "$(kies_uit)"
MAX_PER_RUN=10 LAATSTE=10 draai kies "$werkmap/kies.tsv"
gelijk "na laatste=10 begint de keuze bij 11 en loopt rond" "11 1 2 3 4 5 6 7 8 9" "$(kies_uit)"
MAX_PER_RUN=10 LAATSTE=11 draai kies "$werkmap/kies.tsv"
gelijk "na het hoogste nummer begint de keuze weer bij het laagste" "1 2 3 4 5 6 7 8 9 10" "$(kies_uit)"
# LAATSTE hoeft niet meer in de lijst te staan: het nummer kan intussen opgeruimd zijn.
printf '%s\n' '3	opruimen	o' '8	opruimen	o' '15	opruimen	o' '20	opruimen	o' >"$werkmap/gaten.tsv"
MAX_PER_RUN=2 LAATSTE=9 draai kies "$werkmap/gaten.tsv"
gelijk "een verdwenen LAATSTE: begin bij het eerstvolgende nummer" "15 20" "$(kies_uit)"

# Elke run gaat verder na het laatst gekozen nummer: binnen ⌈25/10⌉ runs komt elk nummer aan de beurt.
kies_nummers 25
alle=""
laatste=0
for _ in 1 2 3; do
  keuze=$(MAX_PER_RUN=10 LAATSTE=$laatste bash "$SCRIPT" kies "$werkmap/kies.tsv")
  alle="$alle $(tr '\n' ' ' <<<"$keuze")"
  laatste=$(tail -1 <<<"$keuze")
done
gelijk "binnen ⌈25/10⌉ runs komt elk nummer aan de beurt" 25 "$(tr ' ' '\n' <<<"$alle" | grep . | sort -un | grep -c .)"

kies_nummers 0
MAX_PER_RUN=10 draai kies "$werkmap/kies.tsv"
gelijk "zonder op te ruimen nummers kiest kies niets" "0|" "$RC|$UITVOER"

for fout_invoer in "MAX_PER_RUN=0" "MAX_PER_RUN=tien" "MAX_PER_RUN=" "MAX_PER_RUN=10 LAATSTE=x" "MAX_PER_RUN=10 LAATSTE="; do
  RC=0
  # shellcheck disable=SC2086 # bewust gesplitst in losse toewijzingen voor env
  env $fout_invoer bash "$SCRIPT" kies "$werkmap/kies.tsv" >/dev/null 2>&1 || RC=$?
  gelijk "kies weigert $fout_invoer" 2 "$RC"
done

# --- opruimen -----------------------------------------------------------------------------------
opruim_fixtures() {
  antwoord gh "run list --repo o/r --workflow cleanup-preview.yml --event workflow_dispatch --limit 50 --json databaseId,displayTitle" \
    '[{"databaseId":1,"displayTitle":"Cleanup ZAD preview pr-7 resten-99-1-77"},{"databaseId":2,"displayTitle":"Cleanup ZAD preview pr-7 resten-99-1-7"},{"databaseId":3,"displayTitle":"Cleanup ZAD preview pr-8 resten-99-1-8"}]'
  antwoord gh 'run view 2 --repo o/r --json status,conclusion' '{"status":"completed","conclusion":"success"}'
  antwoord gh 'run view 3 --repo o/r --json status,conclusion' '{"status":"completed","conclusion":"failure"}'
  vrij deploy.yml
  vrij cleanup-preview.yml
}

# runs <workflow> <status>: de opvraging van bezet() voor één status.
runs() {
  printf 'run list --repo o/r --workflow %s --status %s --limit 1 --json status' "$1" "$2"
}

RUNS_DEPLOY="$(runs deploy.yml in_progress)"
RUNS_CLEANUP="$(runs cleanup-preview.yml in_progress)"
readonly RUNS_DEPLOY RUNS_CLEANUP

vrij() {
  local status

  for status in queued in_progress waiting requested pending; do
    antwoord gh "$(runs "$1" "$status")" '[]'
  done
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
REPO=o/r KENMERK=ander draai opruimen 7 8 9
gelijk "een run die niet terug te vinden is, stopt de rij: de rest wacht op de volgende run" "7	niet-gevonden"$'\t'"
8	uitgesteld	de vorige opruiming in deze run was niet terug te vinden
9	uitgesteld	de vorige opruiming in deze run was niet terug te vinden" "$UITVOER"
gelijk "en start geen tweede opruiming naast de eerste" 1 "$(grep -c 'workflow run' "$werkmap/aanroepen")"

# Een lopende uitrol vergrendelt het project in Operations Manager; een delete die daartussen komt,
# laat die uitrol op `superseded` stranden.
for status in queued in_progress waiting requested pending; do
  opnieuw
  opruim_fixtures
  antwoord gh "$(runs deploy.yml "$status")" "[{\"status\":\"$status\"}]"
  REPO=o/r KENMERK=resten-99-1 draai opruimen 7 8
  gelijk "een uitrol die $status is, stelt alle nummers uit" "7	uitgesteld	er liep een uitrol of opruiming
8	uitgesteld	er liep een uitrol of opruiming" "$UITVOER"
  bevat_niet "en start niets bij $status" "workflow run" "$(aanroepen)"
done

opnieuw
opruim_fixtures
antwoord gh "$RUNS_DEPLOY" '' 1 'HTTP 502'
REPO=o/r KENMERK=resten-99-1 draai opruimen 7
gelijk "is niet vast te stellen of er een uitrol loopt, dan wacht de opruiming" "7	uitgesteld	er liep een uitrol of opruiming" "$UITVOER"

opnieuw
opruim_fixtures
antwoord gh "$RUNS_CLEANUP" '{"geen":"lijst"}'
REPO=o/r KENMERK=resten-99-1 draai opruimen 7
gelijk "een onleesbare runlijst geldt ook als bezet" "7	uitgesteld	er liep een uitrol of opruiming" "$UITVOER"

# `null` heeft in jq lengte 0; als "geen runs" gelezen zou dat een lopende uitrol verbergen.
opnieuw
opruim_fixtures
antwoord gh "$RUNS_CLEANUP" 'null'
REPO=o/r KENMERK=resten-99-1 draai opruimen 7
gelijk "een runlijst die null is, geldt ook als bezet" "7	uitgesteld	er liep een uitrol of opruiming" "$UITVOER"

# Een lopende opruiming in cleanup-preview.yml houdt net zo goed tegen als een uitrol.
opnieuw
opruim_fixtures
antwoord gh "$(runs cleanup-preview.yml queued)" '[{"status":"queued"}]'
REPO=o/r KENMERK=resten-99-1 draai opruimen 7
gelijk "een wachtende opruiming stelt ook uit" "7	uitgesteld	er liep een uitrol of opruiming" "$UITVOER"

# De controle staat vóór elke start, niet alleen vóór de eerste: begint er halverwege een uitrol,
# dan wacht de rest.
opnieuw
opruim_fixtures
antwoord_daarna gh "$RUNS_DEPLOY" '[{"status":"in_progress"}]'
REPO=o/r KENMERK=resten-99-1 draai opruimen 7 8
gelijk "een uitrol die halverwege begint, stelt de rest uit" "7	success	https://github.com/o/r/actions/runs/2
8	uitgesteld	er liep een uitrol of opruiming" "$UITVOER"

# De grens van MAX_MISSERS opvragingen op rij: 29 missers en dan een antwoord wacht door, 30 geeft op.
readonly RUN_VIEW_2='run view 2 --repo o/r --json status,conclusion'
opnieuw
opruim_fixtures
antwoord gh "$RUN_VIEW_2" '' 1 'HTTP 502'
antwoord_daarna gh "$RUN_VIEW_2" '{"status":"completed","conclusion":"success"}' 30
REPO=o/r KENMERK=resten-99-1 draai opruimen 7
gelijk "na 29 mislukte opvragingen wordt er nog gewacht" "7	success	https://github.com/o/r/actions/runs/2" "$UITVOER"

opnieuw
opruim_fixtures
antwoord gh "$RUN_VIEW_2" '' 1 'HTTP 502'
antwoord_daarna gh "$RUN_VIEW_2" '{"status":"completed","conclusion":"success"}' 31
REPO=o/r KENMERK=resten-99-1 draai opruimen 7
gelijk "na 30 mislukte opvragingen geeft het wachten op" "7	onbekend	https://github.com/o/r/actions/runs/2" "$UITVOER"
gelijk "en vraagt dan precies 30 keer" 30 "$(grep -c 'run view 2' "$werkmap/aanroepen")"

opnieuw
opruim_fixtures
rm "$werkmap/fix/$(printf '%s' "gh $RUN_VIEW_2" | sha256sum | cut -c1-32)"
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
    antwoord curl "-sSf --max-time 30 --retry 3 -o /dev/null -w %{content_type} -I -H Authorization: Bearer rt -H $ACCEPT https://ghcr.io/v2/o/p/manifests/sha256:$d" "${1-$MANIFEST}"
  done

  for d in b c; do
    manifest_antwoord "$d" '{"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json"}'
  done
}

readonly ACCEPT='Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'

# manifest_antwoord <digest-achtervoegsel> <inhoud> [rc]
manifest_antwoord() {
  antwoord curl "-sSf --max-time 30 --retry 3 -H Authorization: Bearer rt -H $ACCEPT https://ghcr.io/v2/o/p/manifests/sha256:$1" "$2" "${3:-}"
}

deletes() {
  grep DELETE "$werkmap/aanroepen" | sed 's/.*versions\/\([0-9]*\) -X DELETE/\1/' | paste -sd' ' -
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

# Een referrer (handtekening, attestation) wijst via `subject` naar een ander manifest en blijft
# staan, zonder melding.
opnieuw
ongetagd_fixtures
manifest_antwoord c '{"schemaVersion":2,"subject":{"digest":"sha256:a"}}'
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' '[{"id":1},{"id":3},{"id":4},{"id":5}]'
ORG=o GH_TOKEN=t VERWIJDER=true draai ongetagd p
gelijk "een referrer wordt niet verwijderd" "2" "$(deletes)"
gelijk "en apart gerapporteerd" "p	referrer	1	verwijzen via subject naar een ander manifest
p	verwijderd	1	" "$UITVOER"

opnieuw
ongetagd_fixtures
manifest_antwoord b '' 22
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' '[{"id":1},{"id":2},{"id":4},{"id":5}]'
ORG=o GH_TOKEN=t VERWIJDER=true draai ongetagd p
gelijk "een manifest dat niet op te halen is, blijft staan" "3" "$(deletes)"
bevat "en wordt gemeld" "p	bewaard	1	manifest niet op te halen" "$UITVOER"

opnieuw
ongetagd_fixtures
manifest_antwoord b '<html>proxy</html>'
ORG=o GH_TOKEN=t VERWIJDER=true draai ongetagd p
bevat "een manifest dat geen JSON-object is, blijft staan" "p	bewaard	1	manifest niet op te halen" "$UITVOER"

# Het plafond per aanroep: oudste eerst (id 2 is van augustus, id 3 van september).
opnieuw
ongetagd_fixtures
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' '[{"id":1},{"id":3},{"id":4},{"id":5}]'
ORG=o GH_TOKEN=t VERWIJDER=true VERWIJDER_MAX=1 draai ongetagd p
gelijk "het plafond verwijdert de oudste eerst" "2" "$(deletes)"
bevat "en meldt de rest als uitgesteld" "p	uitgesteld	1	niet onderzocht: plafond van 1 verwijderingen of 300 manifests per run" "$UITVOER"

opnieuw
ongetagd_fixtures
ORG=o GH_TOKEN=t VERWIJDER=true VERWIJDER_MAX=0 draai ongetagd p
gelijk "een opgebruikt plafond verwijdert niets" "" "$(deletes)"
gelijk "en stelt alles uit" "0|p	uitgesteld	2	niet onderzocht: plafond van 0 verwijderingen of 300 manifests per run" "$RC|$UITVOER"
bevat_niet "zonder token of mediatype op te vragen" "curl" "$(aanroepen)"

ORG=o GH_TOKEN=t VERWIJDER=true VERWIJDER_MAX=veel draai ongetagd p
gelijk "een plafond dat geen getal is, is onbepaald" 2 "$RC"

ORG=o GH_TOKEN=t VERWIJDER=true MANIFEST_MAX=veel draai ongetagd p
gelijk "een manifest-plafond dat geen getal is, is onbepaald" 2 "$RC"

# Een bewaarde referrer onder de oudste versies bezet geen plek: met een plafond van 1 wordt de
# volgende, wél verwijderbare versie gevonden, en niets geldt als uitgesteld.
opnieuw
ongetagd_fixtures
manifest_antwoord b '{"schemaVersion":2,"subject":{"digest":"sha256:a"}}'
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' '[{"id":1},{"id":2},{"id":4},{"id":5}]'
ORG=o GH_TOKEN=t VERWIJDER=true VERWIJDER_MAX=1 draai ongetagd p
gelijk "een referrer telt niet voor het verwijderplafond" "3" "$(deletes)"
gelijk "en is niet uitgesteld" "p	referrer	1	verwijzen via subject naar een ander manifest
p	verwijderd	1	" "$UITVOER"

# Het aparte plafond op manifest-opvragingen: daarna stopt het onderzoek, en wat niet bekeken is,
# is uitgesteld.
opnieuw
ongetagd_fixtures
manifest_antwoord b '{"schemaVersion":2,"subject":{"digest":"sha256:a"}}'
ORG=o GH_TOKEN=t VERWIJDER=true MANIFEST_MAX=1 draai ongetagd p
gelijk "het manifest-plafond begrenst de opvragingen" "" "$(deletes)"
bevat "en meldt het onbekeken deel als uitgesteld" "p	uitgesteld	1	niet onderzocht: plafond van 100 verwijderingen of 1 manifests per run" "$UITVOER"
gelijk "precies één manifest opgevraagd" 1 "$(grep -c 'manifests/sha256:[bc]$' "$werkmap/aanroepen")"

# De grens van zeven dagen: precies zeven dagen oud telt mee, een seconde jonger niet.
opnieuw
antwoord gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' \
  '[{"id":1,"name":"sha256:a","created_at":"2026-10-01T00:00:00Z","metadata":{"container":{"tags":["main-1"]}}},
    {"id":6,"name":"sha256:f","created_at":"2026-10-15T00:00:00Z","metadata":{"container":{"tags":[]}}},
    {"id":7,"name":"sha256:g","created_at":"2026-10-15T00:00:01Z","metadata":{"container":{"tags":[]}}}]'
antwoord curl '-sSf --max-time 30 --retry 3 -u x:t https://ghcr.io/token?scope=repository:o/p:pull' '{"token":"rt"}'
antwoord curl "-sSf --max-time 30 --retry 3 -o /dev/null -w %{content_type} -I -H Authorization: Bearer rt -H $ACCEPT https://ghcr.io/v2/o/p/manifests/sha256:a" "$MANIFEST"
ORG=o GH_TOKEN=t draai ongetagd p
gelijk "precies zeven dagen oud telt mee, een seconde jonger niet" "p	te-verwijderen	1	alleen geïnventariseerd" "$UITVOER"

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

# --- main-tags ----------------------------------------------------------------------------------
# main_versies <id> <created_at> <tags-json> ...: een versielijst, per drie argumenten één versie.
main_versies() {
  local uit=""

  while [ "$#" -ge 3 ]; do
    uit="$uit{\"id\":$1,\"name\":\"sha256:$1\",\"created_at\":\"$2\",\"metadata\":{\"container\":{\"tags\":$3}}},"
    shift 3
  done

  printf '[%s]' "${uit%,}"
}

# Twaalf oude main-versies (101-112, juli) en één van eergisteren (120): de tien nieuwste zijn 120 en
# 112-104, dus 101-103 vallen buiten de termijn. Daarnaast oude versies die nooit weg mogen: een
# main-tag naast een PR-tag (90), een ongetagde (91), en tags die op main lijken maar het niet zijn
# (92: geen sha, 93: acht tekens). Over twee pagina's, nieuwste eerst, zoals `--paginate` ze levert.
main_fixtures() {
  local i pagina1 pagina2 versies=()

  pagina1=$(main_versies 90 2026-06-01T00:00:00Z '["main-0000000","pr-7-x"]' 91 2026-06-01T00:00:00Z '[]' \
    92 2026-06-01T00:00:00Z '["main-1"]' 93 2026-06-01T00:00:00Z '["main-abc12345"]')

  for i in $(seq 101 112); do
    versies+=("$i" "2026-07-$((i - 100 + 10))T00:00:00Z" "[\"main-$(printf '%07x' "$i")\"]")
  done

  pagina2=$(main_versies "${versies[@]}" 120 2026-10-20T00:00:00Z '["main-abcdef0"]')
  antwoord gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' "$pagina2
$pagina1"
}

opnieuw
main_fixtures
ORG=o UITGEROLD=main-abcdef0 draai main-tags p
gelijk "zonder VERWIJDER worden de main-versies buiten de termijn alleen geteld" "0|p	te-verwijderen	3	alleen geïnventariseerd" "$RC|$UITVOER"
bevat_niet "en niets verwijderd" "DELETE" "$(aanroepen)"

opnieuw
main_fixtures
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' '[{"id":90},{"id":104},{"id":120}]'
ORG=o UITGEROLD=main-abcdef0 VERWIJDER=true draai main-tags p
gelijk "de main-versies buiten de termijn worden verwijderd en nagemeten" "0|p	verwijderd	3	" "$RC|$UITVOER"
gelijk "precies die versies, oudste eerst; een gedeelde, ongetagde of vreemde tag blijft" "101 102 103" "$(deletes)"

opnieuw
main_fixtures
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' '[{"id":103}]'
ORG=o UITGEROLD=main-abcdef0 VERWIJDER=true VERWIJDER_MAX=2 draai main-tags p
gelijk "het plafond verwijdert de oudste eerst" "101 102" "$(deletes)"
gelijk "en meldt de rest als uitgesteld" "p	uitgesteld	1	plafond van 2 verwijderingen per run
p	verwijderd	2	" "$UITVOER"

opnieuw
main_fixtures
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' '[]'
ORG=o UITGEROLD=main-abcdef0 VERWIJDER=true VERWIJDER_MAX=3 draai main-tags p
gelijk "een plafond gelijk aan het aantal stelt niets uit" "p	verwijderd	3	" "$UITVOER"

opnieuw
main_fixtures
ORG=o UITGEROLD=main-abcdef0 VERWIJDER=true VERWIJDER_MAX=0 draai main-tags p
gelijk "een opgebruikt plafond verwijdert niets en vraagt niets na" "0|p	uitgesteld	3	plafond van 0 verwijderingen per run|1" \
  "$RC|$UITVOER|$(grep -c 'versions?per_page' "$werkmap/aanroepen")"

ORG=o UITGEROLD=main-abcdef0 VERWIJDER=true VERWIJDER_MAX=veel draai main-tags p
gelijk "een plafond dat geen getal is, is onbepaald" 2 "$RC"

opnieuw
main_fixtures
ORG=o UITGEROLD=main-abcdef0 VERWIJDER=true draai main-tags p
gelijk "wat na het verwijderen nog in de lijst staat, is mislukt" "p	verwijderd	0"$'\t'"
p	mislukt	3	staan na het verwijderen nog in de lijst (0 weigeringen)" "$UITVOER"

opnieuw
main_fixtures
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' 'stuk'
ORG=o UITGEROLD=main-abcdef0 VERWIJDER=true draai main-tags p
gelijk "een lijst die na het verwijderen niet te lezen is, is onbepaald" 2 "$RC"

# Na weken zonder merge zijn alle main-versies oud; de tien nieuwste blijven toch, als versies om naar
# terug te gaan. Elf versies: alleen de oudste gaat.
opnieuw
antwoord gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' "$(main_versies \
  1 2026-07-01T00:00:00Z '["main-0000001"]' 2 2026-07-02T00:00:00Z '["main-0000002"]' \
  3 2026-07-03T00:00:00Z '["main-0000003"]' 4 2026-07-04T00:00:00Z '["main-0000004"]' \
  5 2026-07-05T00:00:00Z '["main-0000005"]' 6 2026-07-06T00:00:00Z '["main-0000006"]' \
  7 2026-07-07T00:00:00Z '["main-0000007"]' 8 2026-07-08T00:00:00Z '["main-0000008"]' \
  9 2026-07-09T00:00:00Z '["main-0000009"]' 10 2026-07-10T00:00:00Z '["main-000000a"]' \
  11 2026-07-11T00:00:00Z '["main-000000b"]')"
ORG=o UITGEROLD=main-000000b VERWIJDER=true draai main-tags p
gelijk "zonder recente merge blijven de tien nieuwste staan" "1" "$(deletes)"

# Een versie met een extra tag blijft staan en neemt dus geen plek onder de tien in: met de nieuwste
# gedeeld blijven er tien andere staan, en gaat er niets weg.
opnieuw
antwoord gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' "$(main_versies \
  1 2026-07-01T00:00:00Z '["main-0000001"]' 2 2026-07-02T00:00:00Z '["main-0000002"]' \
  3 2026-07-03T00:00:00Z '["main-0000003"]' 4 2026-07-04T00:00:00Z '["main-0000004"]' \
  5 2026-07-05T00:00:00Z '["main-0000005"]' 6 2026-07-06T00:00:00Z '["main-0000006"]' \
  7 2026-07-07T00:00:00Z '["main-0000007"]' 8 2026-07-08T00:00:00Z '["main-0000008"]' \
  9 2026-07-09T00:00:00Z '["main-0000009"]' 10 2026-07-10T00:00:00Z '["main-000000a"]' \
  11 2026-07-11T00:00:00Z '["main-000000b","v1"]')"
ORG=o UITGEROLD=main-000000b VERWIJDER=true draai main-tags p
gelijk "een gedeelde nieuwste neemt geen plek onder de tien in: niets te doen, niets nagevraagd" "0||1" \
  "$RC|$UITVOER|$(grep -c 'versions?per_page' "$werkmap/aanroepen")"

# De grens van veertien dagen: precies veertien dagen oud telt mee, een seconde jonger niet. Tien
# jongere versies houden de bescherming van de nieuwste buiten beeld.
opnieuw
antwoord gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' "$(main_versies \
  1 2026-10-08T00:00:00Z '["main-0000001"]' 2 2026-10-08T00:00:01Z '["main-0000002"]' \
  11 2026-10-21T00:00:00Z '["main-0000011"]' 12 2026-10-21T00:00:00Z '["main-0000012"]' \
  13 2026-10-21T00:00:00Z '["main-0000013"]' 14 2026-10-21T00:00:00Z '["main-0000014"]' \
  15 2026-10-21T00:00:00Z '["main-0000015"]' 16 2026-10-21T00:00:00Z '["main-0000016"]' \
  17 2026-10-21T00:00:00Z '["main-0000017"]' 18 2026-10-21T00:00:00Z '["main-0000018"]' \
  19 2026-10-21T00:00:00Z '["main-0000019"]' 20 2026-10-21T00:00:00Z '["main-0000020"]')"
ORG=o UITGEROLD=main-0000020 draai main-tags p
gelijk "precies veertien dagen oud telt mee, een seconde jonger niet" "p	te-verwijderen	1	alleen geïnventariseerd" "$UITVOER"

opnieuw
ORG=o UITGEROLD=main-abcdef0 draai main-tags p
gelijk "een onleesbare versielijst is onbepaald" 2 "$RC"

UITGEROLD=main-abcdef0 draai main-tags p
gelijk "zonder ORG is main-tags onbepaald" 2 "$RC"

# De laatst uitgerolde versie is de ondergrens: de bouw-jobs pushen vóór de uitrol, dus na een reeks
# rode uitrollen staan er nieuwere versies waar niets op draait. Uitgerold is 102 (main-0000066);
# alleen 101 is ouder, ook al vallen 102 en 103 buiten de tien nieuwste en de termijn.
opnieuw
main_fixtures
antwoord_daarna gh 'api orgs/o/packages/container/p/versions?per_page=100 --paginate' '[{"id":102}]'
ORG=o UITGEROLD=main-0000066 VERWIJDER=true draai main-tags p
gelijk "niets vanaf de uitgerolde versie gaat weg" "0|101" "$RC|$(deletes)"

# Uitgerold op een versie met een extra tag: die is nog steeds de grens.
opnieuw
main_fixtures
ORG=o UITGEROLD=main-0000000 VERWIJDER=true draai main-tags p
gelijk "een uitgerolde versie met een extra tag is ook de grens" "0||" "$RC|$UITVOER|$(deletes)"

opnieuw
main_fixtures
ORG=o UITGEROLD=main-1234567 VERWIJDER=true draai main-tags p
gelijk "een uitgerolde versie die niet in het package staat, is onbepaald" "2|" "$RC|$(deletes)"
bevat "met de tag in de melding" "main-1234567 staat niet in p" "$FOUTEN"

for uitgerold in '' main-abcdef main-ABCDEF0 'main-abcdef0 '; do
  opnieuw
  main_fixtures
  ORG=o UITGEROLD="$uitgerold" VERWIJDER=true draai main-tags p
  gelijk "UITGEROLD '$uitgerold' is onbepaald, zonder iets op te vragen" "2|" "$RC|$(aanroepen)"
done

# --- per-package --------------------------------------------------------------------------------
# pkg_fixture <package>: twaalf oude main-versies, waarvan de oudste twee buiten de tien nieuwste
# vallen. De nieuwste draagt in elk package de uitgerolde tag, zoals na een push naar main.
pkg_fixture() {
  local i versies=()

  for i in $(seq 1 11); do
    versies+=("$i" "2026-07-$((i + 10))T00:00:00Z" "[\"main-$(printf '%07x' "$i")\"]")
  done

  antwoord gh "api orgs/o/packages/container/$1/versions?per_page=100 --paginate" \
    "$(main_versies 12 2026-07-30T00:00:00Z '["main-ffffff0"]' "${versies[@]}")"
}

# per_package_draai <PLAFOND> <soort>
per_package_draai() {
  RC=0
  UITVOER=$(ORG=o UITGEROLD=main-ffffff0 VERWIJDER=true PAKKETTEN="a b c" PLAFOND="$1" bash "$SCRIPT" per-package "$2" 2>"$werkmap/stderr") || RC=$?
  FOUTEN=$(cat "$werkmap/stderr")
}

opnieuw
for pkg in a b c; do
  pkg_fixture "$pkg"
  antwoord_daarna gh "api orgs/o/packages/container/$pkg/versions?per_page=100 --paginate" '[]'
done
per_package_draai 3 main-tags
gelijk "het plafond geldt voor alle packages samen" "0|a	verwijderd	2	
b	uitgesteld	1	plafond van 1 verwijderingen per run
b	verwijderd	1	
c	uitgesteld	2	plafond van 0 verwijderingen per run" "$RC|$UITVOER"

# Een geweigerde verwijdering kost evengoed een aanroep.
opnieuw
for pkg in a b c; do
  pkg_fixture "$pkg"
done
antwoord_daarna gh 'api orgs/o/packages/container/b/versions?per_page=100 --paginate' '[]'
per_package_draai 3 main-tags
bevat "een geweigerde verwijdering telt mee voor het plafond" "a	mislukt	2	" "$UITVOER"
bevat "en laat de volgende minder over" "b	uitgesteld	1	plafond van 1 verwijderingen per run" "$UITVOER"

# Package b is niet te lezen: een onbepaald-regel, en c verwijdert daarna niets meer.
opnieuw
for pkg in a c; do
  pkg_fixture "$pkg"
  antwoord_daarna gh "api orgs/o/packages/container/$pkg/versions?per_page=100 --paginate" '[]'
done
per_package_draai 100 main-tags
gelijk "een package dat omvalt, wordt gemeld en stopt het verwijderen" "0|a	verwijderd	2	
b	onbepaald		De versies van b zijn niet op te vragen.
c	uitgesteld	2	plafond van 0 verwijderingen per run" "$RC|$UITVOER"
bevat_niet "c krijgt geen DELETE" "container/c/versions/1 -X DELETE" "$(aanroepen)"

# Het package valt om ná zijn verwijderingen (de nameting faalt): ook dan verwijdert de rest niets.
opnieuw
for pkg in a b c; do
  pkg_fixture "$pkg"
done
antwoord_daarna gh 'api orgs/o/packages/container/a/versions?per_page=100 --paginate' 'stuk'
per_package_draai 100 main-tags
gelijk "na een package dat halverwege omviel, verwijdert niemand meer" "a	onbepaald		De versies van a zijn na het verwijderen niet op te vragen.
b	uitgesteld	2	plafond van 0 verwijderingen per run
c	uitgesteld	2	plafond van 0 verwijderingen per run" "$UITVOER"

# De reden van het omvallen gaat mee in de melding: een ontbrekende uitgerolde versie vraagt een
# ander onderzoek dan een onleesbare lijst.
opnieuw
pkg_fixture a
RC=0
UITVOER=$(ORG=o UITGEROLD=main-1234567 VERWIJDER=true PAKKETTEN=a PLAFOND=10 bash "$SCRIPT" per-package main-tags 2>"$werkmap/stderr") || RC=$?
gelijk "een ontbrekende uitgerolde versie staat als reden in de melding" \
  "0|a	onbepaald		De uitgerolde versie main-1234567 staat niet in a; niet vast te stellen wat er draait." "$RC|$UITVOER"
bevat "en de foutuitvoer van het package blijft zichtbaar in het log" "main-1234567 staat niet in a" "$(cat "$werkmap/stderr")"

opnieuw
pkg_fixture a
antwoord_daarna gh 'api orgs/o/packages/container/a/versions?per_page=100 --paginate' '[]'
RC=0
UITVOER=$(ORG=o UITGEROLD=main-ffffff0 PAKKETTEN=a PLAFOND=10 bash "$SCRIPT" per-package main-tags 2>/dev/null) || RC=$?
gelijk "zonder VERWIJDER wordt per package alleen geteld" "0|a	te-verwijderen	2	alleen geïnventariseerd" "$RC|$UITVOER"

opnieuw
ongetagd_fixtures
RC=0
UITVOER=$(ORG=o GH_TOKEN=t PAKKETTEN=p PLAFOND=10 bash "$SCRIPT" per-package ongetagd 2>/dev/null) || RC=$?
gelijk "per-package draait ook ongetagd" "0|p	te-verwijderen	2	alleen geïnventariseerd" "$RC|$UITVOER"

per_package_draai veel main-tags
gelijk "een PLAFOND dat geen getal is, is onbepaald" 2 "$RC"
per_package_draai 3 opruimen
gelijk "een ander subcommando dan main-tags of ongetagd is onbepaald" 2 "$RC"
RC=0
ORG=o PLAFOND=3 bash "$SCRIPT" per-package main-tags >/dev/null 2>&1 || RC=$?
gelijk "zonder PAKKETTEN is per-package onbepaald" 2 "$RC"

# --- uitgerold -----------------------------------------------------------------------------------
readonly RUNS_URL='api repos/r/actions/workflows/deploy.yml/runs?branch=main&event=push&status=success&per_page=20'

# De lijst staat op aanmaakmoment, nieuwste eerst. De tweede run is ouder maar later herstart, en
# heeft dus zijn eigen, oudere versie uitgerold over die van de eerste heen.
opnieuw
antwoord gh "$RUNS_URL" '{"workflow_runs":[
  {"head_sha":"abcdef0123456789abcdef0123456789abcdef01","run_started_at":"2026-10-21T10:00:00Z"},
  {"head_sha":"1234567890abcdef1234567890abcdef12345678","run_started_at":"2026-10-21T12:00:00Z"},
  {"head_sha":"fedcba9876543210fedcba9876543210fedcba98","run_started_at":"2026-10-20T09:00:00Z"}]}'
REPO=r draai uitgerold
gelijk "de laatst gestarte geslaagde run wint, ook als hij ouder is" "0|main-1234567" "$RC|$UITVOER"

opnieuw
antwoord gh "$RUNS_URL" '{"workflow_runs":[{"head_sha":"abcdef0123456789abcdef0123456789abcdef01","run_started_at":"2026-10-21T10:00:00Z"}]}'
REPO=r draai uitgerold
gelijk "de tag van de enige geslaagde uitrol" "0|main-abcdef0" "$RC|$UITVOER"

for runs in '{"workflow_runs":[]}' '{"workflow_runs":[{"head_sha":null}]}' '{"workflow_runs":[{"head_sha":"abc"}]}' 'stuk'; do
  opnieuw
  antwoord gh "$RUNS_URL" "$runs"
  REPO=r draai uitgerold
  gelijk "geen bruikbare uitrol in '$runs' is onbepaald" "2|" "$RC|$UITVOER"
done

opnieuw
REPO=r draai uitgerold
gelijk "een onleesbare runlijst is onbepaald" 2 "$RC"

draai uitgerold
gelijk "zonder REPO is uitgerold onbepaald" 2 "$RC"

# --- bevindingen --------------------------------------------------------------------------------
GOED='{
  "zad": {"result": "success", "outputs": {"nummers-uitvraag": "7", "nummers-magazijnen": "geen"}},
  "inventaris": {"result": "success", "outputs": {"opruimen": "7", "bevindingen": "7\topruimen\tomgeving,zad:uitvraag"}},
  "opruimen": {"result": "success", "outputs": {"uitkomsten": "7\tsuccess\thttps://x/runs/2"}},
  "main-tags": {"result": "success", "outputs": {"uitkomsten": "p\tverwijderd\t3\t"}},
  "ongetagd": {"result": "success", "outputs": {"uitkomsten": "p\tverwijderd\t2\t"}}
}'

# bevinding <omschrijving> <jq-bewerking op GOED> <verwacht> [CANCELLED] [OPRUIMEN]
bevinding() {
  local needs
  needs=$(jq -c "$2" <<<"$GOED")
  RC=0
  UITVOER=$(NEEDS="$needs" CANCELLED="${4:-false}" OPRUIMEN="${5:-true}" MAX_PER_RUN=10 bash "$SCRIPT" bevindingen 2>&1) || RC=$?
  gelijk "$1" "0|$3" "$RC|$UITVOER"
}

bevinding "een geslaagde run zonder resten meldt niets" '.' ''
bevinding "een onleesbaar ZAD-project wordt gemeld" '.zad.outputs."nummers-magazijnen" = "onleesbaar"' \
  'onbepaald	zad:magazijnen	de deploymentlijst is niet te lezen'
bevinding "een omgevallen ZAD-leg wordt gemeld" '.zad.result = "failure"' 'onbepaald	zad	de job eindigde als failure'
bevinding "een omgevallen inventaris wordt gemeld" '.inventaris.result = "failure"' 'onbepaald	inventaris	de job eindigde als failure'
bevinding "een ontbrekende ongetagd-job wordt gemeld" 'del(.ongetagd)' 'onbepaald	ongetagd	de job eindigde als ontbreekt'
bevinding "een omgevallen main-tags-job wordt gemeld" '."main-tags".result = "failure"' 'onbepaald	main-tags	de job eindigde als failure'
bevinding "een geweigerde main-versie wordt gemeld" \
  '."main-tags".outputs.uitkomsten = "p\tverwijderd\t1\t\np\tmislukt\t2\tstaan na het verwijderen nog in de lijst (2 weigeringen)"' \
  'main-tags-mislukt	p	2 main-versie(s) staan na het verwijderen nog in de lijst (2 weigeringen)'
bevinding "een onleesbaar package bij main-tags wordt gemeld" \
  '."main-tags".outputs.uitkomsten = "p\tonbepaald\t\tde versies zijn niet te lezen of te beoordelen"' \
  'onbepaald	ghcr-main:p	de versies zijn niet te lezen of te beoordelen'
bevinding "een achterstand aan main-versies is stil" \
  '."main-tags".outputs.uitkomsten = "p\tuitgesteld\t40\tplafond van 150 verwijderingen per run\np\tverwijderd\t150\t"' ''
bevinding "een overgeslagen opruiming met werk meldt de job én het nummer dat er nog staat" '.opruimen = {"result": "skipped"}' \
  'onbepaald	opruimen	de job eindigde als skipped; voor 7 is niet vastgesteld of de opruiming lukte
wacht-op-opruiming	pr-7	niet aan de beurt (maximaal 10 per run); 1 nummer(s) wachten nog'
bevinding "een omgevallen opruimjob noemt zijn uitkomst bij elk nummer" '.opruimen = {"result": "failure"}' \
  'onbepaald	opruimen	de job eindigde als failure; voor 7 is niet vastgesteld of de opruiming lukte
wacht-op-opruiming	pr-7	de opruimjob eindigde als failure; 1 nummer(s) wachten nog'
bevinding "een overgeslagen opruiming zonder werk is stil" \
  '.inventaris.outputs.opruimen = "" | .inventaris.outputs.bevindingen = "" | .opruimen = {"result": "skipped"}' ''
# Een inventariseer-run ruimt niets op; wat er staat, staat er nog. Zonder deze regel sloot het
# issue met "geen resten meer" terwijl er niets was weggehaald.
bevinding "in een inventariseer-run blijft elk op te ruimen nummer gemeld" '.opruimen = {"result": "skipped"}' \
  'wacht-op-opruiming	pr-7	alleen geïnventariseerd; deze run ruimde niet op; 1 nummer(s) wachten nog' false false
# Meer nummers dan het plafond: de nummers die niet aan de beurt kwamen, staan er nog.
bevinding "nummers boven het plafond blijven gemeld, met het aantal dat nog wacht" \
  '.inventaris.outputs.bevindingen = "7\topruimen\tomgeving\n8\topruimen\tomgeving\n9\topruimen\tomgeving" | .opruimen.outputs.uitkomsten = "7\tsuccess\thttps://x/runs/2"' \
  'wacht-op-opruiming	pr-8	niet aan de beurt (maximaal 10 per run); 2 nummer(s) wachten nog
wacht-op-opruiming	pr-9	niet aan de beurt (maximaal 10 per run); 2 nummer(s) wachten nog'
bevinding "een uitgestelde opruiming blijft gemeld met de reden" \
  '.opruimen.outputs.uitkomsten = "7\tuitgesteld\ter liep een uitrol of opruiming"' \
  'wacht-op-opruiming	pr-7	uitgesteld: er liep een uitrol of opruiming'
bevinding "een mislukte opruiming wordt gemeld met de run" '.opruimen.outputs.uitkomsten = "7\tfailure\thttps://x/runs/2"' \
  'opruiming-mislukt	pr-7	cleanup-preview.yml eindigde als failure (https://x/runs/2)'
bevinding "een niet-gestarte opruiming wordt gemeld" '.opruimen.outputs.uitkomsten = "7\tniet-gestart\t"' \
  'opruiming-mislukt	pr-7	cleanup-preview.yml eindigde als niet-gestart'
bevinding "een nummer dat de vorige run groen opgeruimd werd en nu terug is, wordt gemeld" \
  '.inventaris.outputs.stand = "gelukt\t3\ngelukt\t7\ngelukt\t70\nlaatste\t70"' \
  'terug-na-opruiming	pr-7	een eerdere run meldde de opruiming als gelukt, maar er staat nog: omgeving,zad:uitvraag'
# Eenmaal teruggekomen blijft het nummer gemeld zolang het terugkomt, ook als de vorige run het niet
# probeerde; anders verdwijnt de melding om de andere run.
bevinding "een eerder teruggekomen nummer blijft gemeld zolang het terugkomt" \
  '.inventaris.outputs.stand = "terug\t7\nlaatste\t9"' \
  'terug-na-opruiming	pr-7	een eerdere run meldde de opruiming als gelukt, maar er staat nog: omgeving,zad:uitvraag'
bevinding "een vorige stand met andere nummers meldt niets" '.inventaris.outputs.stand = "gelukt\t70\nterug\t17\nlaatste\t7"' ''

# --- nieuwe-stand -------------------------------------------------------------------------------
# nieuwe_stand_van <jq-bewerking op GOED>: de stand die de meldjob voor de volgende run vastlegt.
nieuwe_stand_van() {
  NEEDS=$(jq -c "$1" <<<"$GOED") bash "$SCRIPT" nieuwe-stand 2>&1 | paste -sd'|' -
}

gelijk "de stand legt gelukt en het laatst geprobeerde nummer vast" "gelukt	7|laatste	7" "$(nieuwe_stand_van '.')"
gelijk "het laatste geprobeerde nummer is het laatste dat niet uitgesteld is" "gelukt	7|laatste	8" \
  "$(nieuwe_stand_van '.opruimen.outputs.uitkomsten = "7\tsuccess\tu\n8\tfailure\tu\n9\tuitgesteld\tbezet"')"
gelijk "een run die niets probeerde, houdt het vorige laatste vast" "laatste	42" \
  "$(nieuwe_stand_van '.opruimen = {"result": "skipped"} | .inventaris.outputs.stand = "laatste\t42\ngelukt\t5"')"
gelijk "alleen uitgestelde nummers tellen niet als geprobeerd" "laatste	42" \
  "$(nieuwe_stand_van '.opruimen.outputs.uitkomsten = "7\tuitgesteld\tbezet" | .inventaris.outputs.stand = "laatste\t42"')"
gelijk "een teruggekomen nummer gaat als terug mee naar de volgende stand" "gelukt	7|terug	7|laatste	7" \
  "$(nieuwe_stand_van '.inventaris.outputs.stand = "gelukt\t7"')"
gelijk "een teruggekomen nummer dat er niet meer staat, valt uit de stand" "gelukt	7|laatste	7" \
  "$(nieuwe_stand_van '.inventaris.outputs.stand = "terug\t70"')"
gelijk "zonder vorige stand en zonder poging: een lege stand" "" \
  "$(nieuwe_stand_van '.opruimen = {"result": "skipped"}')"

# Een opruimjob die omvalt of in zijn time-out loopt: wat al gelukt was, gaat mee, en `laatste`
# wordt het eerste gekozen nummer zonder uitkomst — het nummer waarop hij vastliep. De volgende run
# begint erna; dat nummer komt achteraan de rotatie terug.
for uitkomst in failure cancelled; do
  gelijk "een opruimjob die als $uitkomst eindigt, gaat verder na het nummer waarop hij vastliep" "gelukt	7|laatste	9" \
    "$(nieuwe_stand_van ".opruimen = {\"result\": \"$uitkomst\", \"outputs\": {\"uitkomsten\": \"7\\tsuccess\\tu\"}}
      | .inventaris.outputs.opruimen = \"7 9 12\" | .inventaris.outputs.stand = \"laatste\\t3\"")"
done

# Viel de job om vóór de eerste poging (een mislukte checkout, een afbreking), dan schuift de rij
# precies één nummer op, niet het hele venster.
gelijk "een omgevallen opruimjob zonder uitkomsten schuift één nummer op" "laatste	7" \
  "$(nieuwe_stand_van '.opruimen = {"result": "failure"} | .inventaris.outputs.opruimen = "7 9 12" | .inventaris.outputs.stand = "laatste\t3"')"
gelijk "een omgevallen opruimjob die alles afrondde, gaat verder na het laatst afgeronde" "gelukt	7|laatste	12" \
  "$(nieuwe_stand_van '.opruimen = {"result": "cancelled", "outputs": {"uitkomsten": "7\tsuccess\tu\n9\tfailure\tu\n12\tfailure\tu"}} | .inventaris.outputs.opruimen = "7 9 12"')"

# Het hele scenario: venster 3 5 … 25, 3 lukt, vast op 5. De volgende run begint bij 7, en 5 komt
# achteraan terug.
seq 3 2 25 | sed 's/$/\topruimen\tomgeving/' >"$werkmap/oneven.tsv"
laatste_na_vastlopen=$(nieuwe_stand_van '.opruimen = {"result": "cancelled", "outputs": {"uitkomsten": "3\tsuccess\tu"}}
  | .inventaris.outputs.opruimen = "3 5 7 9 11 13 15 17 19 21" | .inventaris.outputs.stand = ""' | tr '|' '\n' | sed -n 's/^laatste\t//p')
gelijk "na vastlopen op 5 is 5 het laatste" 5 "$laatste_na_vastlopen"
gelijk "en de volgende run begint bij 7, met 5 achteraan" "7 9 11 13 15 17 19 21 23 25" \
  "$(MAX_PER_RUN=10 LAATSTE="$laatste_na_vastlopen" bash "$SCRIPT" kies "$werkmap/oneven.tsv" | paste -sd' ' -)"
gelijk "een geslaagde opruimjob gaat verder na het laatst afgeronde nummer" "gelukt	7|laatste	9" \
  "$(nieuwe_stand_van '.opruimen.outputs.uitkomsten = "7\tsuccess\tu\n9\tfailure\tu\n12\tuitgesteld\tbezet" | .inventaris.outputs.opruimen = "7 9 12"')"
bevinding "geen-pr en vreemd-pakket worden gemeld, een opgeruimd nummer niet" \
  '.inventaris.outputs.bevindingen = "7\topruimen\tomgeving\n9\tgeen-pr\tomgeving\n10\tvreemd-pakket\tvreemd-pakket:x\n14\topruimen\tghcr:a,vreemd-pakket:x\nonbepaald\tgithub\tniet te lezen"
   | .opruimen.outputs.uitkomsten = "7\tsuccess\thttps://x/runs/2\n14\tsuccess\thttps://x/runs/3"' \
  'geen-pr	pr-9	resten zonder bijbehorende PR: omgeving
vreemd-pakket	pr-10	tags in een package buiten de opruimlijst: vreemd-pakket:x
vreemd-pakket	pr-14	tags in een package buiten de opruimlijst: ghcr:a,vreemd-pakket:x
onbepaald	github	niet te lezen'
bevinding "ongetagd: bewaard, mislukt, uitgesteld en onbepaald worden gemeld; verwijderd en referrer niet" \
  '.ongetagd.outputs.uitkomsten = "p\tverwijderd\t2\t\nq\tbewaard\t3\tindex\nr\tmislukt\t1\tstaat er nog\ns\tonbepaald\t\tniet te lezen\nt\tte-verwijderen\t4\t\nu\tuitgesteld\t5\tboven het plafond\nv\treferrer\t1\tsubject"' \
  'ongetagd-bewaard	q	3 ongetagde versie(s) blijven staan: index
ongetagd-mislukt	r	1 ongetagde versie(s) staat er nog
onbepaald	ghcr:s	niet te lezen
ongetagd-uitgesteld	u	5 ongetagde versie(s) boven het plafond'
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

# De stand knippert niet: een nummer dat door de rotatie de ene run "mislukt" en de andere "wacht"
# of "terug" heet, is dezelfde stand. Alleen de beschrijving verschilt.
printf 'opruiming-mislukt\tpr-7\tcleanup-preview.yml eindigde als failure (https://run/1)\n' >"$werkmap/run1"
printf 'wacht-op-opruiming\tpr-7\tniet aan de beurt\nterug-na-opruiming\tpr-7\teen eerdere run …\n' >"$werkmap/run2"
gelijk "mislukt, wacht en terug voor hetzelfde nummer geven dezelfde stand" "$(stand_van "$werkmap/run1")" "$(stand_van "$werkmap/run2")"
printf 'wacht-op-opruiming\tpr-8\tniet aan de beurt\n' >"$werkmap/run3"
bevat_niet "een ander nummer geeft een andere stand" "$(stand_van "$werkmap/run1")" "$(stand_van "$werkmap/run3")"
printf 'vreemd-pakket\tpr-7\tx\n' >"$werkmap/run4"
bevat_niet "een andere soort bevinding voor hetzelfde nummer geeft een andere stand" "$(stand_van "$werkmap/run1")" "$(stand_van "$werkmap/run4")"

opnieuw
antwoord gh "$ISSUES_URL" '[{"number":40,"title":"Achtergebleven preview-resten","user":{"login":"github-actions[bot]"}}]'
antwoord gh 'api repos/o/r/issues/40' "{\"body\":\"<!-- stand: $(stand_van "$werkmap/run1") -->\"}"
REPO=o/r RUN_URL=https://run/2 draai issue "$werkmap/run2"
gelijk "twee runs met wisselende rotatiereden: het issue wordt niet bijgewerkt" "0|Issue #40: stand ongewijzigd, niets bijgewerkt." "$RC|$UITVOER"
bevat_niet "en krijgt geen comment" "-X" "$(aanroepen)"

# Meer dan één open meld-issue: het oudste blijft, de rest gaat dicht met een verwijzing.
opnieuw
antwoord gh "$ISSUES_URL" '[{"number":52,"title":"Achtergebleven preview-resten","user":{"login":"github-actions[bot]"}},{"number":40,"title":"Achtergebleven preview-resten","user":{"login":"github-actions[bot]"}}][{"number":61,"title":"Achtergebleven preview-resten","user":{"login":"github-actions[bot]"}}]'
antwoord gh 'api repos/o/r/issues/40' "{\"body\":\"<!-- stand: $STAND -->\"}"
REPO=o/r RUN_URL=https://run/1 draai issue "$werkmap/bevindingen"
bevat "het oudste issue blijft de stand bijhouden" "Issue #40: stand ongewijzigd" "$UITVOER"
gelijk "de dubbele issues gaan dicht met een verwijzing" \
  "gh api repos/o/r/issues/52/comments -X POST -f body=Dubbel van #40; dat issue houdt de stand bij.|gh api repos/o/r/issues/52 -X PATCH -f state=closed -f state_reason=not_planned|gh api repos/o/r/issues/61/comments -X POST -f body=Dubbel van #40; dat issue houdt de stand bij.|gh api repos/o/r/issues/61 -X PATCH -f state=closed -f state_reason=not_planned" \
  "$(grep -- '-X' "$werkmap/aanroepen" | paste -sd'|' -)"

# --- vorige-stand -------------------------------------------------------------------------------
readonly ARTEFACTEN='api repos/o/r/actions/artifacts?name=preview-resten-stand&per_page=100'
python3 -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], "w"); z.writestr("stand.tsv", "gelukt\t7\nlaatste\t9\n"); z.close()' "$werkmap/stand.zip"
python3 -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], "w"); z.writestr("iets-anders.txt", "x"); z.close()' "$werkmap/fout.zip"

# Drie artefacten: het nieuwste op main (id 2), een nieuwer op een andere branch (id 3), en een
# verlopen op main dat nog nieuwer is (id 4). Alleen id 2 telt. Bewust niet op datum gerangschikt:
# de keuze mag niet van de volgorde van de API afhangen.
readonly ARTEFACT_LIJST='{"artifacts":[
  {"id":2,"expired":false,"created_at":"2026-10-21T05:20:00Z","workflow_run":{"head_branch":"main"}},
  {"id":1,"expired":false,"created_at":"2026-10-19T05:20:00Z","workflow_run":{"head_branch":"main"}},
  {"id":3,"expired":false,"created_at":"2026-10-21T09:00:00Z","workflow_run":{"head_branch":"feature/x"}},
  {"id":4,"expired":true,"created_at":"2026-10-21T10:00:00Z","workflow_run":{"head_branch":"main"}}]}'

opnieuw
antwoord gh "$ARTEFACTEN" "$ARTEFACT_LIJST"
antwoord_bestand gh 'api repos/o/r/actions/artifacts/2/zip' "$werkmap/stand.zip"
REPO=o/r draai vorige-stand "$werkmap/gelezen.tsv"
gelijk "het nieuwste niet-verlopen artefact van main wordt gelezen" "0|gelukt	7|laatste	9" "$RC|$(paste -sd'|' - <"$werkmap/gelezen.tsv")"

opnieuw
antwoord gh "$ARTEFACTEN" '{"artifacts":[{"id":3,"expired":false,"created_at":"2026-10-21T09:00:00Z","workflow_run":{"head_branch":"feature/x"}}]}'
REPO=o/r draai vorige-stand "$werkmap/gelezen.tsv"
gelijk "zonder artefact op main: geen stand (exit 1), geen fout" 1 "$RC"

opnieuw
antwoord gh "$ARTEFACTEN" '{"artifacts":[]}'
REPO=o/r draai vorige-stand "$werkmap/gelezen.tsv"
gelijk "een lege artefactlijst: geen stand" 1 "$RC"

opnieuw
antwoord gh "$ARTEFACTEN" '' 1 'HTTP 403'
REPO=o/r draai vorige-stand "$werkmap/gelezen.tsv"
gelijk "een onleesbare artefactlijst is onbepaald, niet 'geen stand'" 2 "$RC"

opnieuw
antwoord gh "$ARTEFACTEN" '{"message":"proxy"}'
REPO=o/r draai vorige-stand "$werkmap/gelezen.tsv"
gelijk "een antwoord zonder artefactlijst is onbepaald" 2 "$RC"

opnieuw
antwoord gh "$ARTEFACTEN" "$ARTEFACT_LIJST"
antwoord gh 'api repos/o/r/actions/artifacts/2/zip' '' 1 'HTTP 410'
REPO=o/r draai vorige-stand "$werkmap/gelezen.tsv"
gelijk "een artefact dat niet te downloaden is, is onbepaald" 2 "$RC"

opnieuw
antwoord gh "$ARTEFACTEN" "$ARTEFACT_LIJST"
antwoord_bestand gh 'api repos/o/r/actions/artifacts/2/zip' "$werkmap/fout.zip"
REPO=o/r draai vorige-stand "$werkmap/gelezen.tsv"
# Kapot is blijvend, niet tijdelijk: een eigen exitcode, zodat de workflow opnieuw begint in plaats
# van elke run het vastleggen te blokkeren tot de retentie verloopt.
gelijk "een artefact zonder stand.tsv is kapot (exit 3), met een lege stand" "3|0" "$RC|$(wc -c <"$werkmap/gelezen.tsv")"

opnieuw
antwoord gh "$ARTEFACTEN" "$ARTEFACT_LIJST"
antwoord gh 'api repos/o/r/actions/artifacts/2/zip' 'dit is geen zip'
REPO=o/r draai vorige-stand "$werkmap/gelezen.tsv"
gelijk "een artefact dat geen zip is, is kapot (exit 3)" 3 "$RC"
gelijk "na precies één herhaalde download" 2 "$(grep -c 'artifacts/2/zip' "$werkmap/aanroepen")"

# Een afgebroken download (exit 0, halve zip) die de tweede keer wel lukt: de stand blijft behouden.
opnieuw
antwoord gh "$ARTEFACTEN" "$ARTEFACT_LIJST"
antwoord gh 'api repos/o/r/actions/artifacts/2/zip' 'PK half'
cp "$werkmap/stand.zip" "$werkmap/fix/$(printf '%s' 'gh api repos/o/r/actions/artifacts/2/zip' | sha256sum | cut -c1-32).daarna"
REPO=o/r draai vorige-stand "$werkmap/gelezen.tsv"
gelijk "een download die de tweede keer lukt, levert de stand" "0|gelukt	7|laatste	9" "$RC|$(paste -sd'|' - <"$werkmap/gelezen.tsv")"

# Bij "Re-run all jobs" is het nieuwste artefact op main dat van de eigen eerste poging.
opnieuw
antwoord gh "$ARTEFACTEN" '{"artifacts":[
  {"id":5,"expired":false,"created_at":"2026-10-22T05:20:00Z","workflow_run":{"head_branch":"main","id":77}},
  {"id":2,"expired":false,"created_at":"2026-10-21T05:20:00Z","workflow_run":{"head_branch":"main","id":76}}]}'
antwoord_bestand gh 'api repos/o/r/actions/artifacts/2/zip' "$werkmap/stand.zip"
REPO=o/r GITHUB_RUN_ID=77 draai vorige-stand "$werkmap/gelezen.tsv"
gelijk "het artefact van de eigen run telt niet als vorige stand" "0|gelukt	7|laatste	9" "$RC|$(paste -sd'|' - <"$werkmap/gelezen.tsv")"

# --- noodmelding --------------------------------------------------------------------------------
opnieuw
antwoord gh "$ISSUES_URL" '[{"number":40,"title":"Achtergebleven preview-resten","user":{"login":"github-actions[bot]"}}]'
REPO=o/r RUN_URL=https://run/9 draai noodmelding
gelijk "de noodmelding reageert op een open issue" "0|Noodmelding geplaatst op issue #40." "$RC|$UITVOER"
bevat "met de run-URL" "api repos/o/r/issues/40/comments -X POST -f body=De controle op preview-resten kon zijn bevindingen niet melden; zie https://run/9." "$(aanroepen)"

opnieuw
antwoord gh "$ISSUES_URL" '[]'
REPO=o/r RUN_URL=https://run/9 draai noodmelding
gelijk "zonder open issue maakt de noodmelding er een" "0|Noodmelding als nieuw issue aangemaakt." "$RC|$UITVOER"
bevat "met de vaste titel, zodat de volgende run hem terugvindt" "api repos/o/r/issues -X POST -f title=Achtergebleven preview-resten" "$(aanroepen)"

# Is ook de issuelijst niet te lezen, dan geen nieuw issue: dat zou een dubbel naast een bestaand
# issue kunnen zijn. De rode job is dan het signaal.
opnieuw
antwoord gh "$ISSUES_URL" '' 1 'HTTP 502'
REPO=o/r RUN_URL=https://run/9 draai noodmelding
gelijk "een onleesbare issuelijst laat de noodmelding hard falen" 2 "$RC"
bevat_niet "en maakt geen issue aan" "-X POST" "$(aanroepen)"

opnieuw
antwoord gh "$ISSUES_URL" '[]'
antwoord gh 'api repos/o/r/issues -X POST -f title=Achtergebleven preview-resten -f body=De controle op preview-resten kon zijn bevindingen niet melden; zie https://run/9.' '' 1 'HTTP 403'
REPO=o/r RUN_URL=https://run/9 draai noodmelding
gelijk "mislukt ook de noodmelding, dan is dat zichtbaar in de exitcode" 2 "$RC"

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
meld((jobs["zad"].get("permissions") or {}) == {"contents": "read"}, "de ZAD-inventaris krijgt alleen leesrecht op de code")

# `ongetagd` vraagt per getagde versie het mediatype op; na `main-tags` zijn dat er minder. Ook na
# een omgevallen `main-tags` moet hij draaien.
ongetagd_job = jobs.get("ongetagd") or {}
meld(
    ongetagd_job.get("needs") in ("main-tags", ["main-tags"])
    and str(ongetagd_job.get("if", "")).replace(" ", "") == "${{!cancelled()}}",
    "ongetagd draait na main-tags, ook als die omviel",
)
main_run = " ".join(str(s.get("run", "")) for s in (jobs.get("main-tags") or {}).get("steps") or [])
opruimen_expr = next((str((s.get("env") or {}).get("OPRUIMEN")) for s in melding.get("steps") or []
                      if "OPRUIMEN" in (s.get("env") or {})), None)
for job, plafond in (("main-tags", "MAIN_TAGS_PER_RUN"), ("ongetagd", "ONGETAGD_PER_RUN")):
    stappen = [s for s in (jobs.get(job) or {}).get("steps") or [] if s.get("id") == job]
    stap = stappen[0] if len(stappen) == 1 else {}
    meld(
        f"PLAFOND=${plafond} .github/scripts/preview-resten.sh per-package {job} >\"$uitkomsten\"" in str(stap.get("run", ""))
        and plafond in (stap.get("env") or {})
        and ((jobs.get(job) or {}).get("permissions") or {}).get("packages") == "write",
        f"{job} ruimt alle packages op binnen één plafond",
    )
    # Een run die alleen inventariseert, verwijdert ook hier niets.
    meld(
        opruimen_expr is not None and str((stap.get("env") or {}).get("VERWIJDER")) == opruimen_expr,
        f"{job} verwijdert alleen als de run opruimt",
    )
    meld(
        ((jobs.get(job) or {}).get("outputs") or {}).get("uitkomsten") == f"${{{{ steps.{job}.outputs.uitkomsten }}}}"
        and 'echo "uitkomsten<<EINDE_UITKOMSTEN"' in str(stap.get("run", "")),
        f"{job} geeft zijn uitkomsten door aan de meldjob",
    )
meld(
    "UITGEROLD=$(.github/scripts/preview-resten.sh uitgerold)" in main_run
    and main_run.index("preview-resten.sh uitgerold") < main_run.index("per-package main-tags")
    and ((jobs.get("main-tags") or {}).get("permissions") or {}).get("actions") == "read",
    "main-tags stelt eerst de laatste uitrol vast, met leesrecht op de runs",
)

nood = [s for s in melding.get("steps") or [] if "noodmelding" in str(s.get("run", ""))]
melden = [s for s in melding.get("steps") or [] if "preview-resten.sh issue" in str(s.get("run", ""))]
meld(
    len(nood) == 1 and len(melden) == 1
    and f"steps.{melden[0].get('id')}.outcome == 'failure'" in str(nood[0].get("if", "")),
    "de noodmelding draait precies als de gewone melding faalt",
)

# De stand: de meldjob schrijft hem (alleen na een geslaagde inventaris) als het artefact dat
# vorige-stand zoekt, met stand.tsv erin; de inventaris leest hem en geeft hem door.
upload = [s for s in melding.get("steps") or [] if "upload-artifact" in str(s.get("uses", ""))]
stand_stap = [s for s in melding.get("steps") or [] if "preview-resten.sh nieuwe-stand" in str(s.get("run", ""))]
inventaris_run = " ".join(str(s.get("run", "")) for s in jobs["inventaris"].get("steps") or [])
with open(sys.argv[1].replace("workflows/preview-resten.yml", "scripts/preview-resten.sh"), encoding="utf-8") as f:
    artefact = re.search(r"^readonly STAND_ARTEFACT=(\S+)$", f.read(), re.M)
meld(
    len(upload) == 1 and artefact is not None
    and (upload[0].get("with") or {}).get("name") == artefact.group(1)
    and str((upload[0].get("with") or {}).get("path", "")).endswith("/stand.tsv"),
    "de meldjob bewaart de stand onder de naam die vorige-stand zoekt",
)
# Een herhaalde run vindt het artefact van zijn eerste poging al onder die naam.
meld(
    len(upload) == 1 and (upload[0].get("with") or {}).get("overwrite") is True,
    "de stand overschrijft het artefact van een eerdere poging van dezelfde run",
)
meld(
    len(stand_stap) == 1 and "needs.inventaris.result == 'success'" in str(stand_stap[0].get("if", ""))
    and "needs.inventaris.outputs.stand-gelezen == 'true'" in str(stand_stap[0].get("if", "")),
    "de stand wordt alleen vastgelegd na een geslaagde inventaris die de vorige stand kon lezen",
)
meld(
    "stand-gelezen" in (jobs["inventaris"].get("outputs") or {})
    and "stand_gelezen=false" in inventaris_run and "onbepaald\\tstand\\t" in inventaris_run,
    "een onleesbare vorige stand wordt gemeld en blokkeert het vastleggen",
)
# Een kapot artefact (exit 3) blokkeert het vastleggen niet, anders bevriest de rotatie tot de
# retentie verloopt; het wordt wel gemeld. Alleen een tijdelijke storing (exit 2) blokkeert.
kapot = re.search(r"\n\s*3\) stand_uitleg=.*?;;", inventaris_run, re.S)
meld(
    "0 | 1 | 3) stand_gelezen=true ;;" in inventaris_run and "*) stand_gelezen=false ;;" in inventaris_run
    and kapot is not None and "onbepaald\\tstand\\t" in kapot.group(0),
    "een kapot stand-artefact wordt gemeld maar blokkeert het vastleggen niet",
)

# De uitkomsten gaan ook mee als de opruiming omvalt of in zijn time-out loopt.
opruim_stappen = jobs["opruimen"].get("steps") or []
doorgeven = [s for s in opruim_stappen if s.get("id") == "doorgeven"]
meld(
    len(doorgeven) == 1 and "always()" in str(doorgeven[0].get("if", ""))
    and (jobs["opruimen"].get("outputs") or {}).get("uitkomsten") == "${{ steps.doorgeven.outputs.uitkomsten }}"
    and any("tee " in str(s.get("run", "")) and "preview-resten.sh opruimen" in str(s.get("run", "")) for s in opruim_stappen),
    "de uitkomsten worden per nummer weggeschreven en ook na een mislukte opruiming doorgegeven",
)
meld("preview-resten.sh vorige-stand" in inventaris_run and "LAATSTE=" in inventaris_run
     and "preview-resten.sh kies" in inventaris_run,
     "de inventaris leest de vorige stand en kiest verder na het laatst geprobeerde nummer")
meld("stand" in (jobs["inventaris"].get("outputs") or {}), "de inventaris geeft de vorige stand door aan de meldjob")

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
