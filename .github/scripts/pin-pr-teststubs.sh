#!/usr/bin/env bash
# shellcheck shell=bash
#
# Gedeeld testharnas voor de twee pin-suites: test-fuzz-basis-pin.sh en test-proeftuin-pin-pr.sh.
#
# Beide scripts muteren dezelfde soort gedeelde toestand — een branch, een PR en één regel in één
# bestand — en hebben daardoor dezelfde stubs nodig: een `gh` en een `git` die elke aanroep
# vastleggen, de PR-lijst teruggeven zoals het script hem uitvraagt, en op commando kunnen falen.
# Zonder die faal-schakelaars overleeft elke `|| true` achter een gh-aanroep de suite, en juist die
# maakt een mislukte PR-actie stil.
#
# Bewust NIET `test-*.sh` genoemd: ci-scripts.yml draait elk `test-*.sh` onder deze map als suite en
# eist er een `ASSERTIES=`-regel van. Dit bestand is er geen; het wordt gesourcet.
#
# Contract met de suite die dit sourcet:
#   WERKMAP        — bestaat al, de suite ruimt hem zelf op
#   SCRIPT         — het te draaien script
#   TESTBRANCH     — de branchnaam die aan dat script wordt meegegeven
#   DOELBESTAND    — het bestand dat het script wijzigt; `nieuw_geval` zet hem per geval
#   DOELVAR        — de naam waaronder het gemeten script dát bestand leest (DOCKERFILE of COMPOSE);
#                    `nieuw_geval` exporteert hem mee, zodat de suites hun eigen gevallen
#                    onveranderd kunnen schrijven

fails=0
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

pin_pr_stubs_opzetten() {
  mkdir -p "$WERKMAP/bin"

  # De body gaat óók naar een eigen bestand: hij loopt over meerdere regels en maakt het
  # aanroepenlogboek onleesbaar, terwijl de inhoud wél te toetsen moet zijn.
  cat > "$WERKMAP/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf 'gh %s\n' "$*" >> "$AANROEPEN"

if [ -n "${BODYS:-}" ]; then
  vorige=""

  for arg in "$@"; do
    [ "$vorige" = "--body" ] && printf '%s' "$arg" >> "$BODYS"
    vorige=$arg
  done
fi

if [ "${GH_FAALT:-}" = "${1:-} ${2:-}" ]; then
  echo "error connecting to api.github.com" >&2
  exit 1
fi

if [ "${1:-}" = "pr" ] && [ "${2:-}" = "list" ]; then
  filter=""
  vorige=""

  for arg in "$@"; do
    [ "$vorige" = "--jq" ] && filter=$arg
    vorige=$arg
  done

  printf '%s' "${PR_LIJST:-[]}" | jq -r "$filter"
fi

exit 0
STUB

  cat > "$WERKMAP/bin/git" <<'STUB'
#!/usr/bin/env bash
printf 'git %s\n' "$*" >> "$AANROEPEN"

case "${1:-}" in
  # `git diff --quiet -- <bestand>`: 0 als er niets gewijzigd is. De momentopname is de staat van
  # vóór de aanroep, dus dit is een getrouwe simulatie en geen aanname.
  diff)      cmp -s "$MOMENTOPNAME" "$DOELBESTAND" ;;
  ls-remote) exit "${LS_REMOTE_CODE:-0}" ;;
  push)      [ "${GIT_PUSH_FAALT:-0}" = 0 ] ;;
  *)         true ;;
esac
STUB

  chmod +x "$WERKMAP/bin/gh" "$WERKMAP/bin/git"
  export PATH="$WERKMAP/bin:$PATH"
}

# Zet een verse werkmap klaar voor één geval: eigen doelbestand, momentopname, aanroepenlogboek en
# bodybestand. Het vullen van dat doelbestand en het draaien doet de suite.
nieuw_geval() {
  local map="$WERKMAP/$1"

  mkdir -p "$map"
  export DOELBESTAND="$map/doel"
  export MOMENTOPNAME="$map/doel.voor"
  export AANROEPEN="$map/aanroepen"
  export BODYS="$map/bodys"
  : > "$AANROEPEN"
  : > "$BODYS"

  # Het gemeten script kent dit bestand onder zijn eigen naam; die moet dus mee bewegen met elk
  # nieuw geval, anders wijst hij nog naar de map van het vorige.
  export "${DOELVAR:?DOELVAR moet gezet zijn vóór het eerste geval}=$DOELBESTAND"
}

vastleggen() { cp "$DOELBESTAND" "$MOMENTOPNAME"; }

# `set +e` omdat een deel van de gevallen juist een niet-nul exitcode verwacht en de suites zelf
# onder `set -e` draaien. De branch expliciet meegeven, zodat de aanroep-asserties niet meeschuiven
# als de default in het script wijzigt.
#
# shellcheck disable=SC2034  # UITVOER en CODE worden gelezen door de suite die dit bestand sourcet
uitvoeren() {
  set +e
  UITVOER=$(BRANCH="$TESTBRANCH" bash "$SCRIPT" 2>&1)
  CODE=$?
  set -e
}

bevat()      { grep -qF "$2" "$AANROEPEN" && ok "$1" || fout "$1 (aanroepen: $(tr '\n' '|' < "$AANROEPEN"))"; }
bevat_niet() { grep -qF "$2" "$AANROEPEN" && fout "$1 (aanroepen: $(tr '\n' '|' < "$AANROEPEN"))" || ok "$1"; }
body()       { grep -qF "$2" "$BODYS" && ok "$1" || fout "$1 (body: $(tr '\n' '|' < "$BODYS"))"; }
gelijk()     { [ "$2" = "$3" ] && ok "$1" || fout "$1 (verwacht '$3', kreeg '$2')"; }
niet_nul()   { [ "$2" -ne 0 ] && ok "$1" || fout "$1 (exitcode 0, uitvoer: $UITVOER)"; }
meldt()      { grep -qF "$2" <<<"$UITVOER" && ok "$1" || fout "$1 (uitvoer: $UITVOER)"; }
regel()      { grep -qxF "$2" "$DOELBESTAND" && ok "$1" || fout "$1 (bestand: $(tr '\n' '|' < "$DOELBESTAND"))"; }
regel_niet() { grep -qxF "$2" "$DOELBESTAND" && fout "$1 (bestand: $(tr '\n' '|' < "$DOELBESTAND"))" || ok "$1"; }

# De afsluiting is voor beide suites gelijk: de telling is zelf-gerapporteerd en ci-scripts.yml
# bewaakt hem tegen een ondergrens, dus hij hoort er ook bij een gefaalde run te staan.
pin_pr_uitkomst() {
  echo

  if [ "$fails" -gt 0 ]; then
    echo "$fails test(s) gefaald." >&2
    echo "ASSERTIES=$geslaagd"
    exit 1
  fi

  echo "Alle tests geslaagd."
  echo "ASSERTIES=$geslaagd"
}
