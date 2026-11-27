#!/usr/bin/env bash
# Fixture-tests voor demo/environment/zad-demo/portaal-component.sh, met een zadctl-stub.
#
# Het script zet het ondertekengeheim van het portaal. Gaat dat mis, dan merkt niemand het tot een
# demo: een nieuw geheim logt iedereen met een open Berichtenbox uit, en een geheim dat er nooit op
# kwam laat next-auth bij elk verzoek een fout melden. De stub volgt de semantiek van zadctl die de
# runbooks beschrijven: `env set` wijzigt alleen een bestaande naam, `env add` alleen een nieuwe.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../zad-demo/portaal-component.sh"

fails=0
geslaagd=0
ok() { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT
mkdir -p "$WERKMAP/bin"

cat >"$WERKMAP/bin/zadctl" <<'STUB'
#!/usr/bin/env bash
echo "zadctl $*" >>"$STATE/log"
args=("$@")
set -- "${args[@]:3}" # --strict -p <project> eraf

case "$1 $2" in
  "deployment describe")
    echo '{"components":[{"name":"democonsole","image":"ghcr.io/x/fbs-demo-console:t"}'"${PORTAAL_JSON:-}"']}'
    ;;
  "env list")
    if [ -n "${ENV_LIST_FAALT:-}" ]; then
      echo "Error: Operations Manager onbereikbaar" >&2
      exit 2
    fi

    printf 'NAME VALUE\n'
    sed 's/=.*/ (set, not returned by the API)/' "$STATE/env" 2>/dev/null

    # Na de treffer nog veel uitvoer: een `grep -q` in een pipe zou de schrijver hier afbreken.
    if [ -n "${ENV_LIST_LANG:-}" ]; then
      for i in $(seq 1 5000); do echo "OPVULLING_$i (set)"; done
    fi
    ;;
  "env set" | "env add")
    for a in "${@:3}"; do
      case "$a" in -c | portaal | --dry-run) continue ;; esac
      naam=${a%%=*}

      if [ "$2" = set ] && ! grep -q "^$naam=" "$STATE/env" 2>/dev/null; then
        echo "Error: '$naam' bestaat niet; gebruik env add" >&2
        exit 1
      fi

      if [ "$2" = add ] && grep -q "^$naam=" "$STATE/env" 2>/dev/null; then
        echo "Error: '$naam' bestaat al; gebruik env set" >&2
        exit 1
      fi

      case " $* " in *" --dry-run "*) continue ;; esac
      sed -i "/^$naam=/d" "$STATE/env" 2>/dev/null || true
      echo "$a" >>"$STATE/env"
    done
    ;;
  "alias get") exit 1 ;;
  *) : ;;
esac
STUB
chmod +x "$WERKMAP/bin/zadctl"
export PATH="$WERKMAP/bin:$PATH"

PORTAAL=',{"name":"portaal","image":"x"}'

# Elk geval een eigen toestand; de schakelaars gaan expliciet terug, want een toewijzing vóór een
# functie-aanroep blijft in bash staan na die aanroep.
geval() {
  export STATE="$WERKMAP/$1"
  mkdir -p "$STATE"
  : >"$STATE/log"
  unset PORTAAL_JSON ENV_LIST_FAALT ENV_LIST_LANG
}

draai() {
  CODE=0
  bash "$SCRIPT" "$1" >/dev/null 2>"$STATE/err" || CODE=$?
}

gelijk() {
  if [ "$2" = "$3" ]; then ok "$1"; else fout "$1 (verwacht '$3', kreeg '$2'; stderr: $(tr '\n' '|' <"$STATE/err"))"; fi
}

namen() { sed 's/=.*//' "$STATE/env" 2>/dev/null | sort | tr '\n' ' '; }
waarde() { sed -n "s/^$1=//p" "$STATE/env" 2>/dev/null; }

# --- een vers portaal ---------------------------------------------------------------------------

geval vers
draai apply
gelijk "vers: apply slaagt" "$CODE" 0
gelijk "vers: HOSTNAME en AUTH_SECRET op het portaal" "$(namen)" "AUTH_SECRET HOSTNAME "
gelijk "vers: HOSTNAME op 0.0.0.0" "$(waarde HOSTNAME)" "0.0.0.0"
gelijk "vers: een geheim van 32 bytes" "$(waarde AUTH_SECRET | base64 -d 2>/dev/null | wc -c | tr -d ' ')" 32
gelijk "vers: nieuwe namen met env add" "$(grep -c ' env add -c portaal' "$STATE/log")" 2

# --- een bestaand portaal -----------------------------------------------------------------------

geval bestaand
printf 'HOSTNAME=0.0.0.0\nAUTH_SECRET=oud\n' >"$STATE/env"
export PORTAAL_JSON=$PORTAAL
draai apply
gelijk "bestaand: apply slaagt" "$CODE" 0
gelijk "bestaand: het geheim blijft" "$(waarde AUTH_SECRET)" "oud"
gelijk "bestaand: HOSTNAME met env set" "$(grep -c ' env set -c portaal HOSTNAME=0.0.0.0' "$STATE/log")" 1
gelijk "bestaand: AUTH_SECRET niet aangeraakt" "$(grep -c 'AUTH_SECRET=' "$STATE/log")" 0

# Gemaakt door een versie die beide als alias zette: de env-laag is leeg.
geval zonder-env
export PORTAAL_JSON=$PORTAAL
draai apply
gelijk "bestaand zonder env: apply slaagt" "$CODE" 0
gelijk "bestaand zonder env: beide komen erop" "$(namen)" "AUTH_SECRET HOSTNAME "

# --- de env-lijst is niet te lezen --------------------------------------------------------------

geval lijst-faalt
printf 'HOSTNAME=0.0.0.0\nAUTH_SECRET=oud\n' >"$STATE/env"
export PORTAAL_JSON=$PORTAAL ENV_LIST_FAALT=1
draai apply
gelijk "lijst faalt: het script stopt met de code van zadctl" "$CODE" 2
gelijk "lijst faalt: het geheim blijft" "$(waarde AUTH_SECRET)" "oud"
gelijk "lijst faalt: geen enkele env-mutatie" "$(grep -cE ' env (set|add) ' "$STATE/log")" 0

geval lijst-lang
printf 'HOSTNAME=0.0.0.0\nAUTH_SECRET=oud\n' >"$STATE/env"
export PORTAAL_JSON=$PORTAAL ENV_LIST_LANG=1
draai apply
gelijk "lange lijst: apply slaagt" "$CODE" 0
gelijk "lange lijst: het geheim blijft" "$(waarde AUTH_SECRET)" "oud"
gelijk "lange lijst: geen env add op een bestaande naam" "$(grep -c ' env add ' "$STATE/log")" 0

# Een falende openssl mag geen leeg geheim zetten.
geval openssl-faalt
printf '#!/usr/bin/env bash\nexit 1\n' >"$WERKMAP/bin/openssl"
chmod +x "$WERKMAP/bin/openssl"
draai apply
rm -f "$WERKMAP/bin/openssl"
gelijk "openssl faalt: het script stopt" "$([ "$CODE" -ne 0 ] && echo gestopt)" "gestopt"
gelijk "openssl faalt: geen AUTH_SECRET naar zadctl" "$(grep -c 'AUTH_SECRET' "$STATE/log")" 0

# Een naam die alleen als deel van een andere naam voorkomt, is er niet.
geval deelnaam
printf 'HOSTNAME=0.0.0.0\nOUD_AUTH_SECRET_X=1\n' >"$STATE/env"
export PORTAAL_JSON=$PORTAAL
draai apply
gelijk "deelnaam: AUTH_SECRET komt er alsnog op" "$(waarde AUTH_SECRET | base64 -d 2>/dev/null | wc -c | tr -d ' ')" 32

# --- plan ---------------------------------------------------------------------------------------

geval plan-bestaand
printf 'HOSTNAME=0.0.0.0\n' >"$STATE/env"
export PORTAAL_JSON=$PORTAAL
draai plan
gelijk "plan: slaagt" "$CODE" 0
gelijk "plan: elke env-mutatie draagt --dry-run" \
  "$(grep -E ' env (set|add) ' "$STATE/log" | grep -vc -- '--dry-run' || true)" 0
gelijk "plan: HOSTNAME als dry-run" "$(grep -c ' env set -c portaal HOSTNAME=0.0.0.0 --dry-run' "$STATE/log")" 1
gelijk "plan: geen AUTH_SECRET naar zadctl" "$(grep -c 'AUTH_SECRET' "$STATE/log")" 0
gelijk "plan: niets gewijzigd" "$(namen)" "HOSTNAME "

geval plan-vers
draai plan
gelijk "plan op een vers portaal: slaagt" "$CODE" 0
gelijk "plan op een vers portaal: geen env-aanroepen" "$(grep -c ' env ' "$STATE/log")" 0

if [ "$fails" -eq 0 ]; then
  echo "Alle tests geslaagd."
fi

echo "ASSERTIES=$geslaagd"

if [ "$fails" -ne 0 ]; then
  echo "$fails assertie(s) gefaald." >&2
  exit 1
fi
