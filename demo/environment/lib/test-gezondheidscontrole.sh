#!/usr/bin/env bash
# Fixture-tests voor demo/environment/zad-demo/gezondheidscontrole.sh: de tabelvalidatie, de
# dekkingscontrole, wat er per component naar de CLI gaat, en het verschil tussen plan en apply.
#
# De validatie bestaat volledig uit weigeren — een pad bij een tcp-scheme, een tweede regel voor
# hetzelfde component, een poort buiten het bereik — en een operator die `plan` draait toetst per
# definitie een tabel die klopt. Dat weigeren wordt dus door geen enkele handmatige run geraakt, en
# het is precies wat een latere opruiming stilzwijgend kan slopen. Het script muteert 27 componenten
# in drie projecten zonder rollback; een validatie die niet meer valideert, merk je daar pas als de
# helft omgezet is.
#
# Hetzelfde geldt voor de argumenten die het script bouwt. Dat de tabel klopt, zegt niets over wat
# er uiteindelijk in `service config set` terechtkomt — en een omgedraaide liveness of een
# weggevallen poort is precies de fout die het hele werk moest voorkomen.
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

# De stub kent de standen die de echte CLI ook kan hebben: een normaal antwoord (uit STUB_MAP of
# STUB_COMPONENTEN, leeg als die leeg is) en een antwoord waarin de sleutel `components` helemaal
# ontbreekt (STUB_GEEN_SLEUTEL — een CLI die van vorm verandert). STUB_FAAL laat één component
# struikelen, STUB_DESCRIBE_FAAL de beschrijving zelf en STUB_REFRESH_FAAL de uitrol van één project.
cat > "${TMP}/bin/zadctl" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail

case " $* " in
  *" project use "*)
    # `zadctl project use` schrijft project en key naar .env.zadctl in de werkmap; het script haalt
    # ze daar per project op. De key draagt hier de projectnaam, zodat een verwisseling opvalt.
    project=""
    for arg in "$@"; do
      [ "$arg" = "use" ] && continue
      case "$arg" in -*) continue ;; esac
      [ "$arg" = "project" ] && continue
      project="$arg"
    done
    printf 'ZAD_SSO_TOKEN=stub\nZAD_PROJECT_ID=%s\nZAD_API_KEY=key-%s\n' "$project" "$project" \
      > .env.zadctl
    ;;
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
  *" project refresh "*)
    echo "key=${ZAD_API_KEY:-geen} $*" >>"${STUB_LOG:-/dev/null}"
    if [ -n "${STUB_REFRESH_FAAL:-}" ]; then
      case " $* " in *" $STUB_REFRESH_FAAL "*) echo "stub: refresh faalt" >&2; exit 1 ;; esac
    fi
    ;;
  *)
    echo "key=${ZAD_API_KEY:-geen} $*" >>"${STUB_LOG:-/dev/null}"
    if [ -n "${STUB_HANG:-}" ]; then
      case " $* " in *" config set "*" $STUB_HANG "*) sleep 5 ;; esac
    fi
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
ALLE_COMPONENTEN="$(grep -oE '^ *"[a-z0-9-]+\|[a-z0-9-]+\|[a-z0-9-]+' "$SCRIPT" \
  | cut -d'|' -f3 | sort -u | tr '\n' ' ')"

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

  # Zonder deze controle zou een variant die door de vervanging onleesbaar werd, met een
  # syntaxfout afbreken — en dan slaagt elke assert_weigert om de verkeerde reden.
  bash -n "${TMP}/variant.sh" || {
    echo "FAIL: de variant met [$*] is geen geldige bash meer; de vervanging klopt niet" >&2
    exit 1
  }

  echo "${TMP}/variant.sh"
}

# draai <script> <modus> <filter>: zet RC en UIT. Bewust geen echo van de exitcode, want dan zou de
# aanroeper `rc="$(draai ...)"` schrijven — een subshell, waarna UIT bij hem leeg blijft.
WERKMAP="${TMP}/werkmap"
mkdir -p "$WERKMAP"
printf 'ZAD_SSO_TOKEN=stub\nZAD_PROJECT_ID=beginstand\nZAD_API_KEY=key-beginstand\n' \
  > "$WERKMAP/.env.zadctl"

draai() {
  local pad="$1" modus="$2" filter="${3:-alle}"

  RC=0
  UIT="$(cd "$WERKMAP" && bash "$pad" "$modus" "$filter" 2>&1)" || RC=$?
}

GOEDE_RIJ='mpfb-8wh|test|uitvraag|http|8086|/q/health/live|/q/health/ready'

# assert_weigert <omschrijving> <fragment> <regel...>: de tabel moet worden afgewezen om de reden
# die <fragment> noemt, en er mag niets gemuteerd zijn — de validatie hoort vóór de eerste aanroep
# te draaien. Zonder dat fragment zou een regel die verderop op iets ánders omvalt de assert laten
# slagen terwijl de gecontroleerde validatie allang weg is.
assert_weigert() {
  local desc="$1" fragment="$2"
  shift 2

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

  case "$UIT" in
    *"$fragment"*) ok "$desc (exit $rc, niets gemuteerd)" ;;
    *) fout "$desc — afgewezen, maar niet om '$fragment': $UIT" ;;
  esac
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

assert_weigert "een weggevallen scheidingsteken (6 velden)" "niet precies 7 velden" \
  'mpfb-8wh|test|uitvraag|http|8086|/q/health/live'
assert_weigert "een scheidingsteken te veel (8 velden)" "niet precies 7 velden" "${GOEDE_RIJ}|extra"
assert_weigert "een lege componentnaam" "mist een deployment of een componentnaam" 'mpfb-8wh|test||http|8086|/a|/a'
assert_weigert "een lege deploymentnaam" "mist een deployment of een componentnaam" 'mpfb-8wh||uitvraag|http|8086|/a|/a'
assert_weigert "een onbekend project" "onbekend project" 'mpfx-000|test|uitvraag|http|8086|/a|/a'
assert_weigert "twee regels voor hetzelfde component" "herhaalt" "$GOEDE_RIJ" 'mpfb-8wh|test|uitvraag|http|9999|/a|/a'

echo
echo "== scheme en paden horen bij elkaar"

assert_weigert "http zonder paden" "mist een pad" 'mpfb-8wh|test|uitvraag|http|8086||'
assert_weigert "http met alleen een liveness-pad" "mist een pad" 'mpfb-8wh|test|uitvraag|http|8086|/a|'
assert_weigert "tcp mét paden" "kent geen paden" 'mpfb-8wh|test|redis|tcp|6379|/a|/a'
assert_weigert "none mét een poort" "hoort poort noch paden te noemen" \
  'mpfb-8wh|fsc-logius|logius-fscbootstrap|none|8443||'
assert_weigert "een onbekend scheme" "onbekend scheme" 'mpfb-8wh|test|uitvraag|htp|8086|/a|/a'
assert_accepteert "https met paden" 'mpfb-8wh|test|uitvraag|https|8086|/a|/a'
assert_accepteert "none zonder poort en paden" 'mpfb-8wh|fsc-logius|logius-fscbootstrap|none|||'

echo
echo "== de poortgrens van het schema"

assert_weigert "poort 1023, onder de ondergrens" "buiten het toegestane" 'mpfb-8wh|test|uitvraag|http|1023|/a|/a'
assert_weigert "poort 65536, boven de bovengrens" "buiten het toegestane" 'mpfb-8wh|test|uitvraag|http|65536|/a|/a'
assert_weigert "een niet-numerieke poort" "geen numerieke poort" 'mpfb-8wh|test|uitvraag|http|acht|/a|/a'
assert_accepteert "poort 1024, de ondergrens zelf" 'mpfb-8wh|test|uitvraag|http|1024|/a|/a'
assert_accepteert "poort 65535, de bovengrens zelf" 'mpfb-8wh|test|uitvraag|http|65535|/a|/a'

echo
echo "== het padformaat van het schema"

assert_weigert "een pad zonder leidende slash" "pad dat het schema niet toelaat" \
  'mpfb-8wh|test|uitvraag|http|8086|q/health|/a'
assert_weigert "een pad met een query-string" "pad dat het schema niet toelaat" \
  'mpfb-8wh|test|uitvraag|http|8086|/q?x=1|/a'

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

STUB_COMPONENTEN="" draai "$variant" plan; rc=$RC
case "$UIT" in
  *"noemt geen enkel component"*) ok "een lege componentenlijst wijst naar de CLI, niet naar de tabel" ;;
  *) fout "een lege componentenlijst gaf een verkeerde melding: $UIT" ;;
esac

STUB_GEEN_SLEUTEL=1 draai "$variant" plan; rc=$RC
case "$rc:$UIT" in
  0:*) fout "een antwoord zonder 'components' werd stilzwijgend geaccepteerd" ;;
  *"componentenlijst niet uit het describe-antwoord"*)
    ok "een antwoord zonder 'components' faalt op het lezen zelf (exit $rc)" ;;
  *) fout "een antwoord zonder 'components' faalde, maar niet op het lezen: $UIT" ;;
esac

STUB_DESCRIBE_FAAL=2 draai "$variant" plan; rc=$RC
[ "$rc" -eq 2 ] && ok "exitcode 2 van describe komt onveranderd naar buiten" \
  || fout "exitcode 2 van describe werd $rc"

echo
echo "== de filter"

tel() {
  ( cd "$WERKMAP" && STUB_MAP="$STUB_MAP_SCRIPT" bash "$SCRIPT" plan "$1" 2>/dev/null ) \
    | grep -c '^== \[plan\]' || true
}

alle="$(tel alle)"
som=$(( $(tel mpfb-8wh) + $(tel mpfm-w3h) + $(tel mpfpsm-lcl) ))

# De ondergrens apart: zonder deze check zou een script dat meteen afbreekt alle=0 en som=0 geven,
# en dan is "de filters dekken samen de hele tabel" waar zonder iets te betekenen.
if [ "$alle" -le 0 ]; then
  fout "de kale plan-run leverde geen enkele regel op; de teller meet niets"
elif [ "$alle" -eq "$som" ]; then
  ok "de drie projectfilters samen dekken de hele tabel ($alle regels)"
else
  fout "alle=$alle maar de projecten samen $som"
fi

logius="$(tel fsc-logius)"
[ "$logius" -gt 0 ] && [ "$logius" -lt "$alle" ] \
  && ok "een deploymentfilter versmalt tot die deployment ($logius van $alle)" \
  || fout "de deploymentfilter fsc-logius selecteerde $logius van $alle regels"

STUB_MAP="$STUB_MAP_SCRIPT" draai "$SCRIPT" plan onbekend-project; rc=$RC
[ "$rc" -ne 0 ] && ok "een filter zonder treffers faalt met een aanwijzing (exit $rc)" \
  || fout "een filter zonder treffers werd stil geaccepteerd"

echo
echo "== plan loopt door, apply stopt"

variant="$(tabel_met "$GOEDE_RIJ" 'mpfb-8wh|test|redis|tcp|6379||' \
  'mpfb-8wh|test|toxiproxy-redis|http|8474|/version|/version')"

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
echo "== uitrollen aan het eind"

variant="$(tabel_met "$GOEDE_RIJ" 'mpfm-w3h|test|proeftuin|tcp|8080||' \
  'mpfb-8wh|fsc-logius|logius-fscbootstrap|none|||')"

: >"${TMP}/log"
STUB_COMPONENTEN="uitvraag proeftuin logius-fscbootstrap" STUB_LOG="${TMP}/log" \
  draai "$variant" apply; rc=$RC

# `mpfb-8wh` komt in twee deployments voor, dus dit onderscheidt één refresh per PROJECT van één
# refresh per project/deployment-paar — met twee regels in twee projecten zijn die getallen gelijk.
if [ "$rc" -ne 0 ]; then
  fout "een schone apply faalde met exit $rc: $UIT"
elif [ "$(grep -c 'project refresh' "${TMP}/log")" -ne 2 ]; then
  fout "apply rolde niet één keer per project uit: $(grep -c 'project refresh' "${TMP}/log") refreshes"
elif grep -v 'project refresh' "${TMP}/log" | grep -qv -- '--no-rollout'; then
  eerste="$(grep -v 'project refresh' "${TMP}/log" | grep -v -- '--no-rollout' | head -1)"
  fout "een mutatie ging zonder --no-rollout en rolde dus meteen uit: $eerste"
elif grep 'service config set' "${TMP}/log" | grep -qv -- '--yes'; then
  fout "een config set ging zonder --yes en blijft dus op een bevestiging hangen"
else
  ok "elke mutatie draagt --no-rollout, elke config set --yes, en er is één refresh per project"
fi

# Wat er per component geschreven wordt is het product van dit script; de tabelvalidatie zegt daar
# niets over. Een omgedraaide liveness of een weggevallen poort komt alleen hier aan het licht.
uitvraagregel="$(grep 'service config set' "${TMP}/log" | grep uitvraag | head -1)"

case "$uitvraagregel" in
  *"--set scheme=http"*"--set port=8086"*"--set liveness-path=/q/health/live"*"--set readiness-path=/q/health/ready"*)
    ok "een http-regel wordt volledig en in de goede volgorde doorgegeven" ;;
  "") fout "geen config set voor uitvraag in het log" ;;
  *) fout "de argumenten voor uitvraag kloppen niet: $uitvraagregel" ;;
esac

proeftuinregel="$(grep 'service config set' "${TMP}/log" | grep proeftuin | head -1)"

case "$proeftuinregel" in
  *"--set scheme=tcp"*"--set port=8080"*) ;;
  "") fout "geen config set voor proeftuin in het log"; proeftuinregel="x" ;;
  *) fout "de argumenten voor proeftuin kloppen niet: $proeftuinregel"; proeftuinregel="x" ;;
esac

case "$proeftuinregel" in
  x) ;;
  *liveness-path*|*readiness-path*) fout "een tcp-regel stuurde tóch een pad mee: $proeftuinregel" ;;
  *) ok "een tcp-regel stuurt scheme en poort, en geen paden" ;;
esac

bootstrapregel="$(grep 'service config set' "${TMP}/log" | grep fscbootstrap | head -1)"

case "$bootstrapregel" in
  "") fout "geen config set voor de bootstrap in het log" ;;
  *port=*|*-path=*) fout "een none-regel stuurde een poort of een pad mee: $bootstrapregel" ;;
  *"--set scheme=none"*) ok "een none-regel stuurt alleen het scheme" ;;
  *) fout "de argumenten voor de bootstrap kloppen niet: $bootstrapregel" ;;
esac

STUB_COMPONENTEN="uitvraag proeftuin logius-fscbootstrap" STUB_REFRESH_FAAL=mpfm-w3h \
  draai "$variant" apply; rc=$RC

# Mét het afsluitende punt: zonder dat zou een lijst die te vroeg gevuld wordt — en dus ook het
# mislukte project noemt — nog steeds matchen, terwijl het script dan liegt over wat live staat.
case "$rc:$UIT" in
  0:*) fout "een mislukte uitrol eindigde met exit 0" ;;
  *"Al uitgerold: mpfb-8wh."*) ok "een mislukte uitrol noemt precies de projecten die live staan (exit $rc)" ;;
  *) fout "een mislukte uitrol zei niet welke projecten al uitgerold waren: $UIT" ;;
esac

echo
echo "== de API-key per project"

# De key is per project; met de key van een ander project geeft OM 401. Zonder deze assert zou een
# script dat één key voor alles gebruikt er pas tegenaan lopen als het al halverwege is.
variant="$(tabel_met "$GOEDE_RIJ" 'mpfm-w3h|test|proeftuin|tcp|8080||')"

: >"${TMP}/log"
STUB_COMPONENTEN="uitvraag proeftuin" STUB_LOG="${TMP}/log" draai "$variant" apply; rc=$RC

if [ "$rc" -ne 0 ]; then
  fout "de apply voor de key-controle faalde met exit $rc: $UIT"
elif grep 'mpfb-8wh' "${TMP}/log" | grep -qv 'key=key-mpfb-8wh'; then
  fout "een aanroep op mpfb-8wh droeg niet de key van dat project"
elif grep 'mpfm-w3h' "${TMP}/log" | grep -qv 'key=key-mpfm-w3h'; then
  fout "een aanroep op mpfm-w3h droeg niet de key van dat project"
elif grep -q 'key=geen' "${TMP}/log"; then
  fout "een aanroep ging zonder API-key de deur uit"
else
  ok "elke aanroep draagt de API-key van zijn eigen project"
fi

# En de werkmap van de aanroeper blijft staan waar hij stond.
case "$(grep '^ZAD_PROJECT_ID=' "$WERKMAP/.env.zadctl")" in
  ZAD_PROJECT_ID=beginstand) ok "het actieve project van de werkmap blijft ongemoeid" ;;
  *) fout "het script veranderde het actieve project in de werkmap" ;;
esac

echo
echo "== afbreken tijdens een mutatie"

# De trap is het enige pad waar het script iets meldt zonder dat een aanroep faalde. Zonder deze
# test overleeft een refactor die de trap laat doorlopen of `meld_stand` eruit haalt: de operator
# krijgt dan "afgebroken" te zien terwijl de rest gewoon gemuteerd wordt.
variant="$(tabel_met "$GOEDE_RIJ" 'mpfm-w3h|test|proeftuin|tcp|8080||')"

: >"${TMP}/log"
( cd "$WERKMAP" && STUB_COMPONENTEN="uitvraag proeftuin" STUB_LOG="${TMP}/log" \
  STUB_HANG=uitvraag bash "$variant" apply ) >"${TMP}/trap-uit" 2>&1 &
trap_pid=$!

# Wachten tot de eerste config set écht hangt; anders landt het signaal vóór de mutatielus.
for _ in $(seq 1 50); do
  grep -q 'config set' "${TMP}/log" && break
  sleep 0.2
done

kill -TERM "$trap_pid" 2>/dev/null

# `wait` geeft de exitcode van het signaal door (143), en onder `set -e` zou dat de suite hier
# afbreken in plaats van de assert te draaien.
rc=0
wait "$trap_pid" || rc=$?
UIT="$(cat "${TMP}/trap-uit")"

if [ "$rc" -eq 0 ]; then
  fout "een afgebroken apply eindigde met exit 0"
elif [ "$(grep -c 'config set' "${TMP}/log")" -ne 1 ]; then
  fout "het script muteerde door na het signaal: $(grep -c 'config set' "${TMP}/log") config sets"
else
  ok "een signaal stopt de reeks in plaats van hem af te maken (exit $rc)"
fi

case "$UIT" in
  *"draagt de dienst nu wél maar is niet ingesteld"*)
    ok "een afgebroken mutatie meldt het component dat half is ingesteld" ;;
  *) fout "een afgebroken mutatie noemde het half ingestelde component niet: $UIT" ;;
esac

echo
echo "== aanroepvorm"

draai "$SCRIPT" onbekende-modus; rc=$RC
case "$rc:$UIT" in
  0:*) fout "een onbekende modus werd geaccepteerd" ;;
  *"onbekende modus"*) ok "een onbekende modus faalt met de gebruiksaanwijzing (exit $rc)" ;;
  *) fout "een onbekende modus faalde, maar niet op de modus zelf: $UIT" ;;
esac

rc=0
( cd "$WERKMAP" && bash "$SCRIPT" ) >/dev/null 2>&1 || rc=$?
[ "$rc" -ne 0 ] && ok "zonder argumenten faalt het script (exit $rc)" \
  || fout "zonder argumenten deed het script iets"

echo
if [ "$fails" -ne 0 ]; then
  echo "ROOD: ${fails} test(s) gefaald." >&2
  exit 1
fi

echo "GROEN: alle gezondheidscontrole-tests geslaagd."
