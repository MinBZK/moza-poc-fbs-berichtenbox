#!/usr/bin/env bash
# Fixture-tests voor de tabelvalidatie en de dekkingscontrole van
# demo/environment/zad-demo/gezondheidscontrole.sh.
#
# Die logica bestaat volledig uit weigeren: een regel met een pad bij een tcp-scheme, een tweede
# regel voor hetzelfde component, een poort buiten het toegestane bereik. Een operator die `plan`
# draait toetst per definitie een tabel die klopt, dus het weigeren zelf wordt door geen enkele
# handmatige run geraakt — en het is precies wat een latere opruiming stilzwijgend kan slopen. Het
# script muteert 27 componenten in drie projecten zonder rollback; een validatie die niet meer
# valideert, merk je daar pas als de helft omgezet is.
#
# Er draait geen netwerk: een `zadctl` vooraan op PATH beantwoordt `deployment describe` uit een
# env-var, zodat elke combinatie van bestaand/ontbrekend component afdwingbaar is.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$(cd "${HERE}/../zad-demo" && pwd)/gezondheidscontrole.sh"

fails=0

ok() {
  echo "OK: $1"
}

fout() {
  echo "FAIL: $1" >&2
  fails=$((fails + 1))
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "${TMP}/bin"

# De stub kent drie standen die de echte CLI ook kan hebben: een normaal antwoord, een antwoord
# zonder componenten, en een antwoord waarin de sleutel helemaal ontbreekt (een CLI die van vorm
# verandert). STUB_FAAL laat één component struikelen, STUB_DESCRIBE_FAAL de beschrijving zelf.
cat > "${TMP}/bin/zadctl" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail

case " $* " in
  *" deployment describe "*)
    [ -n "${STUB_DESCRIBE_FAAL:-}" ] && { echo "stub: describe faalt" >&2; exit "$STUB_DESCRIBE_FAAL"; }
    [ -n "${STUB_GEEN_SLEUTEL:-}" ] && { echo '{"deployment":"test"}'; exit 0; }

    # STUB_MAP geeft per project/deployment een eigen lijst ("mpfb-8wh/test=a b c;..."), zodat een
    # run over meer deployments niet elk component in elke deployment terugziet. Zonder map geldt
    # STUB_COMPONENTEN voor alles.
    namen="${STUB_COMPONENTEN:-}"
    if [ -n "${STUB_MAP:-}" ]; then
      project=""; deployment=""; vorige=""
      for arg in "$@"; do
        [ "$vorige" = "-p" ] && project="$arg"
        [ "$vorige" = "describe" ] && deployment="$arg"
        vorige="$arg"
      done
      namen=""
      IFS=';' read -r -a paren <<<"$STUB_MAP"
      for paar in "${paren[@]}"; do
        [ "${paar%%=*}" = "$project/$deployment" ] && namen="${paar#*=}"
      done
    fi

    printf '{"components": ['
    eerste=1
    for n in $namen; do
      [ "$eerste" -eq 1 ] || printf ','
      printf '{"name": "%s"}' "$n"
      eerste=0
    done
    printf ']}\n'
    ;;
  *)
    echo "$*" >>"${STUB_LOG:-/dev/null}"
    if [ -n "${STUB_FAAL:-}" ]; then
      case " $* " in *" $STUB_FAAL "*) echo "stub: faalt op $STUB_FAAL" >&2; exit 1 ;; esac
    fi
    ;;
esac
exit 0
STUB

chmod +x "${TMP}/bin/zadctl"
PATH="${TMP}/bin:$PATH"
export PATH

# Eén anker in plaats van per-regel zoeken-en-vervangen: een `sed` op een rij-literal matcht niets
# zodra die rij legitiem verandert, en dan wordt élke negatieve test stil groen. Verschuift dít
# anker, dan faalt de suite luid in plaats van niets meer te toetsen.
[ "$(grep -c '^REGELS=(' "$SCRIPT")" -eq 1 ] || {
  echo "FAIL: de REGELS-anker-regel staat niet één keer in $SCRIPT; deze suite toetst niets meer" >&2
  exit 1
}

# De componentnamen die de meegeleverde tabel noemt, afgeleid uit het script zelf. Zo hoeft de stub
# geen kopie van de ZAD-werkelijkheid te dragen: hij echoot terug wat de tabel vraagt.
ALLE_COMPONENTEN="$(grep -oE '^ *"[a-z0-9-]+\|[a-z0-9-]+\|[a-z0-9-]+' "$SCRIPT" | cut -d'|' -f3 | sort -u | tr '\n' ' ')"

# Dezelfde namen, maar per project/deployment. Zonder die verdeling meldt elke deployment elk
# component terug en verdrinkt het "component zonder regel"-signaal in ruis die de tabel niet aangaat.
STUB_MAP_SCRIPT="$(grep -oE '^ *"[a-z0-9-]+\|[a-z0-9-]+\|[a-z0-9-]+' "$SCRIPT" | tr -d ' "' \
  | awk -F'|' '{ k = $1 "/" $2; m[k] = m[k] " " $3 } END { for (k in m) printf "%s=%s;", k, substr(m[k], 2) }')"

# tabel_met <regel...>: een variant van het script met precies deze tabelregels.
tabel_met() {
  local vervanging=""
  local r

  for r in "$@"; do
    vervanging+="\"$r\" "
  done

  sed "s#^REGELS=(.*#REGELS=($vervanging)#" "$SCRIPT" > "${TMP}/variant.sh"
  echo "${TMP}/variant.sh"
}

# draai <script> <modus> <filter>: zet RC en UIT. Bewust geen echo van de exitcode: een aanroeper
# die `draai ...; rc=$RC` schrijft, draait de functie in een subshell en ziet UIT daarna nooit.
draai() {
  local pad="$1" modus="$2" filter="${3:-alle}"

  RC=0
  UIT="$(bash "$pad" "$modus" "$filter" 2>&1)" || RC=$?
}

GOEDE_RIJ='mpfb-8wh|test|uitvraag|http|8086|/q/health/live|/q/health/ready'

# assert_weigert <omschrijving> <regel...>: de tabel moet worden afgewezen, en er mag niets
# gemuteerd zijn — de validatie hoort vóór de eerste aanroep te draaien.
assert_weigert() {
  local desc="$1"
  shift

  local variant rc
  variant="$(tabel_met "$@")"

  : >"${TMP}/log"
  STUB_COMPONENTEN="$ALLE_COMPONENTEN" STUB_LOG="${TMP}/log" draai "$variant" plan; rc=$RC

  if [ "$rc" -eq 0 ]; then
    fout "$desc — de tabel werd geaccepteerd"
    return
  fi

  if [ -s "${TMP}/log" ]; then
    fout "$desc — afgewezen (exit $rc) maar er was al gemuteerd: $(head -1 "${TMP}/log")"
    return
  fi

  ok "$desc (exit $rc, niets gemuteerd)"
}

assert_accepteert() {
  local desc="$1"
  shift

  local variant rc
  variant="$(tabel_met "$@")"

  STUB_COMPONENTEN="$ALLE_COMPONENTEN" draai "$variant" plan; rc=$RC

  if [ "$rc" -eq 0 ]; then
    ok "$desc"
  else
    fout "$desc — afgewezen met exit $rc: $UIT"
  fi
}

echo "== de meegeleverde tabel"

STUB_MAP="$STUB_MAP_SCRIPT" draai "$SCRIPT" plan; rc=$RC
[ "$rc" -eq 0 ] && ok "de tabel in het script doorstaat zijn eigen validatie" \
  || fout "de tabel in het script wordt door zijn eigen validatie afgewezen (exit $rc): $UIT"

echo
echo "== plan muteert niet"

: >"${TMP}/log"
STUB_MAP="$STUB_MAP_SCRIPT" STUB_LOG="${TMP}/log" draai "$SCRIPT" plan >/dev/null

if [ ! -s "${TMP}/log" ]; then
  fout "plan stuurde geen enkele aanroep — de stub of het anker klopt niet meer"
elif grep -qv -- '--dry-run' "${TMP}/log"; then
  fout "plan stuurde een aanroep zonder --dry-run: $(grep -v -- '--dry-run' "${TMP}/log" | head -1)"
else
  ok "elke aanroep in plan-modus draagt --dry-run ($(wc -l <"${TMP}/log") aanroepen)"
fi

echo
echo "== de vorm van een tabelregel"

assert_weigert "een weggevallen scheidingsteken (6 velden)" 'mpfb-8wh|test|uitvraag|http|8086|/q/health/live'
assert_weigert "een scheidingsteken te veel (8 velden)" "${GOEDE_RIJ}|extra"
assert_weigert "een lege componentnaam" 'mpfb-8wh|test||http|8086|/a|/a'
assert_weigert "een lege deploymentnaam" 'mpfb-8wh||uitvraag|http|8086|/a|/a'
assert_weigert "een onbekend project" 'mpfx-000|test|uitvraag|http|8086|/a|/a'
assert_weigert "twee regels voor hetzelfde component" "$GOEDE_RIJ" 'mpfb-8wh|test|uitvraag|http|9999|/a|/a'

echo
echo "== scheme en paden horen bij elkaar"

assert_weigert "http zonder paden" 'mpfb-8wh|test|uitvraag|http|8086||'
assert_weigert "http met alleen een liveness-pad" 'mpfb-8wh|test|uitvraag|http|8086|/a|'
assert_weigert "tcp mét paden" 'mpfb-8wh|test|redis|tcp|6379|/a|/a'
assert_weigert "none mét een poort" 'mpfb-8wh|fsc-logius|logius-fscbootstrap|none|8443||'
assert_weigert "een onbekend scheme" 'mpfb-8wh|test|uitvraag|htp|8086|/a|/a'
assert_accepteert "https met paden" 'mpfb-8wh|test|uitvraag|https|8086|/a|/a'
assert_accepteert "none zonder poort en paden" 'mpfb-8wh|fsc-logius|logius-fscbootstrap|none|||'

echo
echo "== de poortgrens van het schema"

assert_weigert "poort 1023, onder de ondergrens" 'mpfb-8wh|test|uitvraag|http|1023|/a|/a'
assert_weigert "poort 65536, boven de bovengrens" 'mpfb-8wh|test|uitvraag|http|65536|/a|/a'
assert_weigert "een niet-numerieke poort" 'mpfb-8wh|test|uitvraag|http|acht|/a|/a'
assert_accepteert "poort 1024, de ondergrens zelf" 'mpfb-8wh|test|uitvraag|http|1024|/a|/a'
assert_accepteert "poort 65535, de bovengrens zelf" 'mpfb-8wh|test|uitvraag|http|65535|/a|/a'

echo
echo "== het padformaat van het schema"

assert_weigert "een pad zonder leidende slash" 'mpfb-8wh|test|uitvraag|http|8086|q/health|/a'
assert_weigert "een pad met een query-string" 'mpfb-8wh|test|uitvraag|http|8086|/q?x=1|/a'

echo
echo "== de dekkingscontrole, beide richtingen"

variant="$(tabel_met 'mpfb-8wh|test|spookcomponent|http|8086|/a|/a')"
: >"${TMP}/log"
STUB_COMPONENTEN="uitvraag redis" STUB_LOG="${TMP}/log" draai "$variant" plan; rc=$RC

if [ "$rc" -eq 0 ]; then
  fout "een regel voor een component dat niet bestaat werd geaccepteerd"
elif [ -s "${TMP}/log" ]; then
  fout "een regel voor een onbestaand component werd afgewezen, maar er was al gemuteerd"
else
  ok "een regel voor een component dat niet bestaat wordt afgewezen (exit $rc, niets gemuteerd)"
fi

STUB_COMPONENTEN="uitvraag ongenoemd" draai "$variant" plan; rc=$RC
case "$UIT" in
  *"draait 'ongenoemd' zonder regel"*) ok "een component zonder regel wordt gemeld" ;;
  *) fout "een component zonder regel werd niet gemeld: $UIT" ;;
esac

variant="$(tabel_met "$GOEDE_RIJ")"
STUB_COMPONENTEN="uitvraag ongenoemd" draai "$variant" plan; rc=$RC

if [ "$rc" -eq 0 ]; then
  ok "een component zonder regel is een waarschuwing, geen fout"
else
  fout "een component zonder regel liet het script falen (exit $rc)"
fi

echo
echo "== een describe die niet levert wat het script verwacht"

STUB_COMPONENTEN="" STUB_LEEG=1 draai "$variant" plan; rc=$RC
case "$UIT" in
  *"noemt geen enkel component"*) ok "een lege componentenlijst wijst naar de CLI, niet naar de tabel" ;;
  *) fout "een lege componentenlijst gaf een verkeerde melding: $UIT" ;;
esac

STUB_GEEN_SLEUTEL=1 draai "$variant" plan; rc=$RC
[ "$rc" -ne 0 ] && ok "een antwoord zonder 'components' faalt (exit $rc)" \
  || fout "een antwoord zonder 'components' werd stilzwijgend geaccepteerd"

STUB_DESCRIBE_FAAL=2 draai "$variant" plan; rc=$RC
[ "$rc" -eq 2 ] && ok "exitcode 2 van describe komt onveranderd naar buiten" \
  || fout "exitcode 2 van describe werd $rc"

echo
echo "== de filter"

tel() {
  STUB_MAP="$STUB_MAP_SCRIPT" bash "$SCRIPT" plan "$1" 2>/dev/null | grep -c '^== \[plan\]' || true
}

alle="$(tel alle)"
som=$(( $(tel mpfb-8wh) + $(tel mpfm-w3h) + $(tel mpfpsm-lcl) ))

[ "$alle" -eq "$som" ] && ok "de drie projectfilters samen dekken de hele tabel ($alle regels)" \
  || fout "alle=$alle maar de projecten samen $som"

logius="$(tel fsc-logius)"
[ "$logius" -gt 0 ] && [ "$logius" -lt "$alle" ] \
  && ok "een deploymentfilter versmalt tot die deployment ($logius van $alle)" \
  || fout "de deploymentfilter fsc-logius selecteerde $logius van $alle regels"

STUB_MAP="$STUB_MAP_SCRIPT" draai "$SCRIPT" plan onbekend-project; rc=$RC
[ "$rc" -ne 0 ] && ok "een filter zonder treffers faalt met een aanwijzing (exit $rc)" \
  || fout "een filter zonder treffers werd stil geaccepteerd"

echo
echo "== plan loopt door, apply stopt"

variant="$(tabel_met "$GOEDE_RIJ" 'mpfb-8wh|test|redis|tcp|6379||' 'mpfb-8wh|test|toxiproxy-redis|http|8474|/version|/version')"

STUB_COMPONENTEN="uitvraag redis toxiproxy-redis" STUB_FAAL=redis draai "$variant" plan; rc=$RC
case "$rc:$UIT" in
  1:*"2 van 3 regels kwamen door"*) ok "plan loopt door na een fout en telt hem (exit 1)" ;;
  *) fout "plan na een fout gaf exit $rc: $UIT" ;;
esac

STUB_COMPONENTEN="uitvraag redis toxiproxy-redis" STUB_FAAL=redis draai "$variant" apply; rc=$RC
case "$rc:$UIT" in
  0:*) fout "apply eindigde met 0 terwijl een component mislukte" ;;
  *"1 van 3 componenten waren ingesteld"*) ok "apply stopt bij de eerste fout en meldt de stand (exit $rc)" ;;
  *) fout "apply na een fout gaf exit $rc zonder standmelding: $UIT" ;;
esac

echo
echo "== aanroepvorm"

draai "$SCRIPT" onbekende-modus; rc=$RC
[ "$rc" -ne 0 ] && ok "een onbekende modus faalt met de gebruiksaanwijzing (exit $rc)" \
  || fout "een onbekende modus werd geaccepteerd"

rc=0
bash "$SCRIPT" >/dev/null 2>&1 || rc=$?
[ "$rc" -ne 0 ] && ok "zonder argumenten faalt het script (exit $rc)" \
  || fout "zonder argumenten deed het script iets"

echo
if [ "$fails" -ne 0 ]; then
  echo "ROOD: ${fails} test(s) gefaald." >&2
  exit 1
fi

echo "GROEN: alle gezondheidscontrole-tests geslaagd."
