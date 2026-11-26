#!/usr/bin/env bash
# Unittests voor uitvraag-preview-uitrol.sh, en voor de aansluiting op deploy.yml. Geen netwerk: een
# curl-stub op het pad geeft per kanaal (Operations Manager of de readiness) het volgende geregisseerde
# antwoord.
#
# `oordeel` maakt een rode deploy groen en `wacht` is de poort daarachter. Alleen een uitrol die
# aantoonbaar landde en waarvan alleen het uitvraag-component nog niet gezond is, mag door `oordeel`;
# alleen een deployment die Operations Manager gezond noemt én een readiness die `UP` zegt, mag door
# `wacht`. Elke twijfel hoort rood te blijven.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/uitvraag-preview-uitrol.sh"
readonly DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"

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

mist() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) fout "$wat — '$naald' hoort er niet in: $hooiberg" ;;
    *) ok "$wat" ;;
  esac
}

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

# De stub kiest zijn kanaal op de URL. Per kanaal is elk antwoord er één, in volgorde, en het laatste
# blijft daarna staan. `FAAL` doet alsof curl zelf faalde, met de melding en de write-out die curl dan
# geeft. De stub legt zijn argumenten en zijn stdin vast.
cat >"$werkmap/curl" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$werkmap/aanroepen"
case " \$* " in *" -H @- "*) cat >>"$werkmap/stdin" ;; esac
case "\$*" in *"/q/health/ready"*) k=ready ;; *) k=om ;; esac
n=\$(( \$(cat "$werkmap/teller.\$k" 2>/dev/null || echo 0) + 1 ))
echo "\$n" >"$werkmap/teller.\$k"
[ -f "$werkmap/\$k.\$n" ] || n=\$(cat "$werkmap/laatste.\$k" 2>/dev/null || echo 0)
[ -f "$werkmap/\$k.\$n" ] || { printf 'geen antwoord\n000'; exit 7; }
if [ "\$(cat "$werkmap/\$k.\$n")" = FAAL ]; then
  printf 'curl: (6) Could not resolve host: kwijt.test\n000'
  exit 6
fi
cat "$werkmap/\$k.\$n"
STUB
chmod +x "$werkmap/curl"

cat >"$werkmap/slaap" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$1" >>"$werkmap/geslapen"
[ -z "\${ECHT_SLAPEN:-}" ] || sleep "\$1"
STUB
chmod +x "$werkmap/slaap"

reset_stub() {
  rm -f "$werkmap"/om.* "$werkmap"/ready.* "$werkmap"/teller.* "$werkmap"/laatste.* \
    "$werkmap/aanroepen" "$werkmap/stdin" "$werkmap/geslapen"
}

antwoorden() {
  local kanaal=$1 i=0 a
  shift

  for a in "$@"; do
    i=$((i + 1))
    printf '%s' "$a" >"$werkmap/$kanaal.$i"
  done

  echo "$i" >"$werkmap/laatste.$kanaal"
}

stub_om() { antwoorden om "$@"; }
stub_ready() { antwoorden ready "$@"; }

draai() {
  RC=0
  PATH="$werkmap:$PATH" ZAD_API_KEY=sleutel ZAD_API_URL=http://api.test ZAD_SLAAP="$werkmap/slaap" \
    URLS="${URLS-}" URL="${URL-}" "$SCRIPT" "$@" >"$werkmap/stdout" 2>"$werkmap/stderr" || RC=$?
  STDOUT=$(cat "$werkmap/stdout")
  UITVOER="$STDOUT"$'\n'$(cat "$werkmap/stderr")
  AANROEPEN=$(cat "$werkmap/aanroepen" 2>/dev/null || true)
  STDIN=$(cat "$werkmap/stdin" 2>/dev/null || true)
  GESLAPEN=$(cat "$werkmap/geslapen" 2>/dev/null || true)
}

aantal() {
  local naald=$1

  { grep -cF -- "$naald" <<<"$AANROEPEN" || true; } | tr -d ' '
}

# De echte samenstelling van het uitvraag-project, met de proxy waar het hier om draait.
readonly GEVRAAGD='[{"name":"redis","image":"redis:7"},{"name":"toxiproxy-aanmeld","image":"tp:2"},{"name":"toxiproxy-redis","image":"tp:2"},{"name":"uitvraag","image":"uv:pr-7-abc"}]'

# Een deployment zoals Operations Manager hem geeft, met de statuscode die curl erachter zet; de
# jq-filter erachter past hem per geval aan.
deployment() {
  printf '%s\n%s' "$(jq -c "${1:-.}" <<'JSON'
{
  "status": "Degraded",
  "running_sync": null,
  "pending_rollout": {"project": "mpfb-8wh", "count": 0},
  "components": [
    {"reference": "redis", "image": "redis:7"},
    {"reference": "toxiproxy-aanmeld", "image": "tp:2"},
    {"reference": "toxiproxy-redis", "image": "tp:2"},
    {"reference": "uitvraag", "image": "uv:pr-7-abc"}
  ],
  "urls": {
    "toxiproxy-aanmeld": "https://toxiproxy-aanmeld-pr-7.test",
    "uitvraag": "https://uitvraag-pr-7.test"
  },
  "errors": [
    {"resource": "Deployment/pr-7-uitvraag", "message": "Deployment \"pr-7-uitvraag\" exceeded its progress deadline"}
  ]
}
JSON
)" "${2:-200}"
}

gezond_in_om() { deployment '.status = "Healthy" | .errors = []'; }

# Zoals smallrye-health het geeft: JSON over meerdere regels.
readiness() {
  printf '{\n    "status": "%s",\n    "checks": [\n        {\n            "name": "Redis connection health check",\n            "status": "%s"\n        }\n    ]\n}\n%s' "$1" "$1" "$2"
}
readiness_up() { readiness UP 200; }
readiness_down() { readiness DOWN 503; }
router() { printf '%s\n%s' '<html>Application is not available</html>' 503; }

oordeel() {
  draai oordeel failure mpfb-8wh pr-7 uitvraag "${1:-$GEVRAAGD}"
}

stdout_is_alleen_output() {
  local wat=$1

  if grep -qvE '^(urls|url)=' <<<"$STDOUT"; then
    fout "$wat — stdout bevat meer dan urls= en url=: $STDOUT"
  else
    ok "$wat"
  fi
}

# --- het script zelf ----------------------------------------------------------------------------
# deploy.yml roept het zonder `bash` aan.
if [ -x "$SCRIPT" ]; then
  ok "het script is uitvoerbaar"
else
  fout "het script is niet uitvoerbaar; deploy.yml roept het zonder bash aan"
fi

# --- oordeel: een groene deploy -----------------------------------------------------------------
reset_stub
URLS='{"uitvraag":"https://u.test"}' URL='https://u.test' draai oordeel success mpfb-8wh pr-7 uitvraag "$GEVRAAGD"
gelijk "groene deploy: groen" 0 "$RC"
bevat "geeft de adressen van de action door" 'urls={"uitvraag":"https://u.test"}' "$STDOUT"
bevat "en zijn url" 'url=https://u.test' "$STDOUT"
stdout_is_alleen_output "en verder niets op stdout"
gelijk "zonder Operations Manager te vragen" 0 "$(aantal http)"

reset_stub
URLS='' URL='' draai oordeel success mpfb-8wh pr-7 uitvraag "$GEVRAAGD"
gelijk "groene deploy zonder adressen: rood" 1 "$RC"
bevat "en zegt dat het aan de action ligt" 'De deploy-action slaagde maar gaf geen bruikbare adressen' "$UITVOER"

reset_stub
URLS='niet-json' URL='https://u.test' draai oordeel success mpfb-8wh pr-7 uitvraag "$GEVRAAGD"
gelijk "groene deploy met adressen zonder JSON: rood" 1 "$RC"

reset_stub
URLS='{"uitvraag":"https://u.test"}' URL=$'https://u.test\nurl=https://kwaad.test' draai oordeel success mpfb-8wh pr-7 uitvraag "$GEVRAAGD"
gelijk "groene deploy met een regeleinde in url: rood" 1 "$RC"
gelijk "en zonder uitvoer op stdout" "" "$STDOUT"

reset_stub
draai oordeel cancelled mpfb-8wh pr-7 uitvraag "$GEVRAAGD"
gelijk "onbekende uitkomst van de deploy-stap: rood" 1 "$RC"
gelijk "en vraagt niets op" 0 "$(aantal http)"

# --- oordeel: een rode deploy, aanvaard ---------------------------------------------------------
reset_stub
stub_om "$(deployment)"
oordeel
gelijk "geland en alleen de uitvraag ongezond: aanvaard" 0 "$RC"
bevat "de deployment van het juiste project wordt opgevraagd" 'http://api.test/v2/projects/mpfb-8wh/deployments/pr-7' "$AANROEPEN"
bevat "de sleutel gaat via stdin" 'X-API-Key: sleutel' "$STDIN"
mist "en staat niet in de argv van curl" 'sleutel' "$AANROEPEN"
bevat "de adressen als compacte JSON" 'urls={"toxiproxy-aanmeld":"https://toxiproxy-aanmeld-pr-7.test","uitvraag":"https://uitvraag-pr-7.test"}' "$STDOUT"
bevat "url is het eerste opgegeven component met een adres" 'url=https://toxiproxy-aanmeld-pr-7.test' "$STDOUT"
stdout_is_alleen_output "de waarschuwing gaat niet naar stdout"
bevat "de aanvaarding staat als waarschuwing in de log" '::warning::' "$UITVOER"

# De volgorde van de opgegeven componenten bepaalt de url, niet die van de adressen.
reset_stub
stub_om "$(deployment)"
oordeel '[{"name":"uitvraag","image":"uv:pr-7-abc"},{"name":"toxiproxy-aanmeld","image":"tp:2"}]'
bevat "url volgt de volgorde van de componenten" 'url=https://uitvraag-pr-7.test' "$STDOUT"

# Een leeg adres telt niet, zoals bij de action.
reset_stub
stub_om "$(deployment '.urls["toxiproxy-aanmeld"] = ""')"
oordeel
gelijk "een leeg adres naast een echt adres: aanvaard" 0 "$RC"
bevat "en de url slaat het lege over" 'url=https://uitvraag-pr-7.test' "$STDOUT"

# Meldingen van ReplicaSet, pod en event van hetzelfde component tellen als datzelfde component; de
# hashes zijn zoals Kubernetes ze maakt.
reset_stub
stub_om "$(deployment '.errors = [{"resource":"Event/pr-7-uitvraag-6864549db5-8zd6c"},{"resource":"Pod/pr-7-uitvraag-6864549db5-8zd6c"},{"resource":"ReplicaSet/pr-7-uitvraag-6864549db5"},{"resource":"Event/pr-7-uitvraag-6864549db5-8zd6c.187a2b3c4d5e6f70"},{"resource":"Deployment/pr-7-uitvraag"}]')"
oordeel
gelijk "meerdere meldingen, alle van de uitvraag: aanvaard" 0 "$RC"

# Na vijf minuten kan de progress deadline nog lopen: dan is er nog geen enkele melding.
reset_stub
stub_om "$(deployment '.status = "Progressing" | .errors = []')"
oordeel
gelijk "nog bezig met uitrollen, zonder meldingen: aanvaard" 0 "$RC"

reset_stub
stub_om "$(deployment '.status = "Progressing" | del(.errors)')"
oordeel
gelijk "nog bezig, zonder errors-veld: aanvaard" 0 "$RC"

reset_stub
stub_om "$(deployment '.status = "Progressing"')"
oordeel
gelijk "nog bezig met alleen een melding van de uitvraag: aanvaard" 0 "$RC"

reset_stub
stub_om "$(gezond_in_om)"
oordeel
gelijk "inmiddels gezond: aanvaard" 0 "$RC"
bevat "met een toelichting bij de rode melding" '::notice::' "$UITVOER"
mist "zonder waarschuwing" '::warning::' "$UITVOER"
stdout_is_alleen_output "de toelichting gaat niet naar stdout"

# --- oordeel: een rode deploy, niet aanvaard ----------------------------------------------------
# De upsert faalde vóór het opslaan, en de oude preview staat er gezond bij.
reset_stub
stub_om "$(deployment '.status = "Healthy" | .errors = [] | .components[3].image = "uv:pr-7-oud"')"
oordeel
gelijk "oud image opgeslagen, oude preview gezond: rood" 1 "$RC"
bevat "met het afwijkende component" '(uitvraag)' "$UITVOER"
gelijk "zonder uitvoer op stdout" "" "$STDOUT"

reset_stub
stub_om "$(deployment '.components[0].image = "redis:6" | .components[3].image = "uv:oud"')"
oordeel
gelijk "twee afwijkende images: rood" 1 "$RC"
bevat "en noemt ze allebei" '(redis, uitvraag)' "$UITVOER"

reset_stub
stub_om "$(deployment '.components |= map(select(.reference != "toxiproxy-redis"))')"
oordeel
gelijk "een gevraagd component ontbreekt: rood" 1 "$RC"
bevat "en noemt het" '(toxiproxy-redis)' "$UITVOER"

# Opgeslagen maar niet uitgerold: de oude pods draaien gezond met de nieuwe images in het projectbestand.
reset_stub
stub_om "$(deployment '.status = "Healthy" | .errors = [] | .pending_rollout.count = 1')"
oordeel
gelijk "opgeslagen maar niet uitgerold: rood" 1 "$RC"
bevat "met het aantal" '1 wijziging' "$UITVOER"

reset_stub
stub_om "$(deployment '.pending_rollout = null')"
oordeel
gelijk "aantal wachtende uitrollen onbekend: rood" 1 "$RC"
bevat "en zegt dat" 'het aantal wachtende uitrollen ontbreekt' "$UITVOER"

reset_stub
stub_om "$(deployment '.pending_rollout.count = "0"')"
oordeel
gelijk "aantal wachtende uitrollen als tekst: rood" 1 "$RC"

reset_stub
stub_om "$(deployment '.running_sync = {"revision":"abc"}')"
oordeel
gelijk "er loopt nog een sync: rood" 1 "$RC"
bevat "en zegt dat" 'er loopt nog een sync' "$UITVOER"

# Een veld dat verdwijnt, mag de controle niet stil openzetten.
reset_stub
stub_om "$(deployment 'del(.running_sync)')"
oordeel
gelijk "running_sync ontbreekt: rood" 1 "$RC"
bevat "als antwoord zonder de vorm van een deployment" 'zonder de vorm van een deployment' "$UITVOER"

reset_stub
stub_om "$(deployment '.errors = [{"resource":"Deployment/pr-7-uitvraag"},{"resource":"Deployment/pr-7-redis"}]')"
oordeel
gelijk "Degraded, ook een ander component ongezond: rood" 1 "$RC"
bevat "en noemt dat component" 'Deployment/pr-7-redis' "$UITVOER"
mist "maar niet de uitvraag" 'Deployment/pr-7-uitvraag,' "$UITVOER"

reset_stub
stub_om "$(deployment '.status = "Progressing" | .errors = [{"resource":"Pod/pr-7-redis-7d4b9c8f6-x2kzq"}]')"
oordeel
gelijk "Progressing met een melding van een ander component: rood" 1 "$RC"
bevat "met de status" 'Progressing door meer dan' "$UITVOER"

# Een ander component waarvan de naam met die van de uitvraag begint, is een ander component, ook als
# het achtervoegsel de lengte van een hash heeft.
for ander in uitvraagx uitvraag-ldv uitvraag-aanmeld uitvraag-worker uitvraag-worker-proxy; do
  reset_stub
  stub_om "$(deployment ".errors = [{\"resource\":\"Deployment/pr-7-$ander\"}]")"
  oordeel
  gelijk "component $ander: rood" 1 "$RC"
done

# Een buur-deployment met hetzelfde voorvoegsel is een andere deployment.
reset_stub
stub_om "$(deployment '.errors = [{"resource":"Deployment/pr-70-uitvraag"}]')"
oordeel
gelijk "melding van een buur-deployment: rood" 1 "$RC"

for leeg in '.errors = []' 'del(.errors)' '.errors = null'; do
  reset_stub
  stub_om "$(deployment "$leeg")"
  oordeel
  gelijk "Degraded zonder fouten ($leeg): rood" 1 "$RC"
  bevat "en zegt waarom ($leeg)" 'Degraded zonder fouten' "$UITVOER"
done

reset_stub
stub_om "$(deployment '.status = "OutOfSync"')"
oordeel
gelijk "OutOfSync: rood" 1 "$RC"
bevat "met de status" 'status OutOfSync' "$UITVOER"

reset_stub
stub_om "$(deployment '.status = "Missing"')"
oordeel
gelijk "Missing: rood" 1 "$RC"

reset_stub
stub_om "$(deployment '.urls = {}')"
oordeel
gelijk "geen enkel adres: rood" 1 "$RC"
bevat "en zegt dat" 'heeft een publiek adres' "$UITVOER"

reset_stub
stub_om "$(deployment '.urls = {"redis":"https://redis.test"}')"
oordeel '[{"name":"uitvraag","image":"uv:pr-7-abc"}]'
gelijk "geen adres voor een opgegeven component: rood" 1 "$RC"

# Een regeleinde zou in $GITHUB_OUTPUT een eigen regel worden.
reset_stub
stub_om "$(deployment '.urls.uitvraag = "https://u.test\nurl=https://kwaad.test"')"
oordeel
gelijk "adres met een regeleinde: rood" 1 "$RC"
gelijk "en zonder uitvoer op stdout" "" "$STDOUT"
mist "en zonder waarschuwing vooraf" '::warning::' "$UITVOER"

reset_stub
stub_om "$(deployment '.urls.uitvraag = "http://u.test"')"
oordeel
gelijk "adres zonder https: rood" 1 "$RC"

reset_stub
stub_om "$(deployment '.urls.redis = true')"
oordeel
gelijk "adres dat geen tekst is: rood" 1 "$RC"
bevat "met een melding" '::error::' "$UITVOER"

reset_stub
stub_om "$(deployment '.urls = "https://u.test"')"
oordeel
gelijk "urls is geen object: rood" 1 "$RC"
bevat "met een melding" '::error::' "$UITVOER"

reset_stub
stub_om FAAL
oordeel
gelijk "deployment niet op te vragen: rood" 1 "$RC"
bevat "met wat curl meldt" 'Could not resolve host' "$UITVOER"

reset_stub
stub_om "$(deployment . 503)"
oordeel
gelijk "deployment geeft 503: rood" 1 "$RC"
bevat "met de statuscode" 'HTTP 503' "$UITVOER"

reset_stub
stub_om "$(printf '%s\n%s' 'geen json' 200)"
oordeel
gelijk "antwoord zonder JSON: rood" 1 "$RC"

reset_stub
stub_om "$(printf '%s\n%s' '<html>inlogmuur</html>' 200)"
oordeel
gelijk "HTML-antwoord: rood" 1 "$RC"
bevat "als antwoord zonder de vorm van een deployment" 'zonder de vorm van een deployment' "$UITVOER"

reset_stub
stub_om "$(printf '%s\n%s' '{"name":"pr-7","running_sync":null}' 200)"
oordeel
gelijk "antwoord zonder status: rood" 1 "$RC"

reset_stub
stub_om "$(deployment)"
oordeel '[]'
gelijk "lege componentenlijst: rood" 1 "$RC"

reset_stub
oordeel 'geen json'
gelijk "componentenlijst zonder JSON: rood" 1 "$RC"

reset_stub
RC=0
PATH="$werkmap:$PATH" ZAD_API_KEY='' "$SCRIPT" oordeel failure mpfb-8wh pr-7 uitvraag "$GEVRAAGD" >/dev/null 2>&1 || RC=$?
gelijk "rode deploy zonder API-sleutel: rood" 1 "$RC"

# --- wacht --------------------------------------------------------------------------------------
readonly URLS_PREVIEW='{"toxiproxy-aanmeld":"https://tp.test","uitvraag":"https://u.test/"}'

wacht() {
  draai wacht mpfb-8wh pr-7 uitvraag "$GEVRAAGD" "$URLS_PREVIEW" "$@"
}

reset_stub
stub_om "$(gezond_in_om)"
stub_ready "$(readiness_up)"
wacht 10 7
gelijk "meteen gezond: groen" 0 "$RC"
gelijk "één opvraging bij Operations Manager" 1 "$(aantal api.test)"
gelijk "één opvraging van de readiness" 1 "$(aantal /q/health/ready)"
bevat "op de readiness van de uitvraag, zonder dubbele slash" 'https://u.test/q/health/ready' "$AANROEPEN"
bevat "de readiness met de statuscode achter de body" '-w \n%{http_code}' "$(grep -F /q/health/ready <<<"$AANROEPEN")"
bevat "Operations Manager met de statuscode achter de body" '-w \n%{http_code}' "$(grep -F api.test <<<"$AANROEPEN")"

# Met `-f` maakt curl van een 503 van de router een eigen fout, en zegt de melding niet meer wat er is.
if grep -F /q/health/ready <<<"$AANROEPEN" | grep -qE '(^| )-[a-zA-Z]*f[a-zA-Z]*( |$)'; then
  fout "de readiness wordt zonder -f opgevraagd — $AANROEPEN"
else
  ok "de readiness wordt zonder -f opgevraagd"
fi
bevat "de sleutel gaat via stdin" 'X-API-Key: sleutel' "$STDIN"
gelijk "zonder te slapen" "" "$GESLAPEN"

# De oude pod van een bestaande preview blijft ready terwijl de nieuwe vastzit: zonder Operations
# Manager zou dit groen zijn.
reset_stub
stub_om "$(deployment)"
stub_ready "$(readiness_up)"
wacht 0 1
gelijk "readiness UP maar Operations Manager Degraded: rood" 1 "$RC"
bevat "met de staat in Operations Manager" 'Operations Manager: nog niet gezond' "$UITVOER"

reset_stub
stub_om "$(deployment '.status = "Progressing" | .errors = []')"
stub_ready "$(readiness_up)"
wacht 0 1
gelijk "readiness UP maar nog Progressing: rood" 1 "$RC"

# Opgeslagen maar niet uitgerold: gezond, met de oude pods.
reset_stub
stub_om "$(deployment '.status = "Healthy" | .errors = [] | .pending_rollout.count = 1')"
stub_ready "$(readiness_up)"
wacht 0 1
gelijk "gezond maar een wachtende uitrol: rood" 1 "$RC"
bevat "en noemt die" 'wachten 1 wijziging' "$UITVOER"

reset_stub
stub_om "$(deployment '.status = "Healthy" | .errors = [] | .components[3].image = "uv:pr-8-def"')"
stub_ready "$(readiness_up)"
wacht 0 1
gelijk "gezond met een ander image dan gevraagd: rood" 1 "$RC"

reset_stub
stub_om "$(deployment '.status = "Healthy" | .errors = [] | .running_sync = {"revision":"abc"}')"
stub_ready "$(readiness_up)"
wacht 0 1
gelijk "gezond maar met een lopende sync: rood" 1 "$RC"

# Een component dat Operations Manager uitschakelde, staat daar als gezond.
reset_stub
stub_om "$(gezond_in_om)"
stub_ready "$(router)"
wacht 0 1
gelijk "Operations Manager gezond maar readiness 503: rood" 1 "$RC"
bevat "met de readiness" 'readiness van uitvraag: HTTP 503' "$UITVOER"

reset_stub
stub_om "$(gezond_in_om)"
stub_ready "$(readiness_down)"
wacht 0 1
gelijk "readiness DOWN: rood" 1 "$RC"
bevat "met de check die niet UP is" 'niet UP: Redis connection health check' "$UITVOER"

reset_stub
stub_om "$(gezond_in_om)"
stub_ready "$(readiness UP 503)"
wacht 0 1
gelijk "readiness zegt UP met een 503: rood" 1 "$RC"

reset_stub
stub_om "$(gezond_in_om)"
stub_ready "$(readiness DOWN 200)"
wacht 0 1
gelijk "readiness zegt DOWN met een 200: rood" 1 "$RC"

reset_stub
stub_om "$(gezond_in_om)"
stub_ready FAAL
wacht 0 1
gelijk "readiness onbereikbaar: rood" 1 "$RC"
bevat "met wat curl meldt" 'curl 6: curl: (6) Could not resolve host' "$UITVOER"

reset_stub
stub_om "$(deployment)" FAAL "$(deployment '.status = "Progressing" | .errors = []')" "$(gezond_in_om)" "$(gezond_in_om)"
stub_ready "$(router)" "$(readiness_up)" "$(readiness_up)" "$(readiness_down)" "$(readiness_up)"
wacht 10 7
gelijk "na Degraded, onbereikbaar, Progressing en DOWN gezond" 0 "$RC"
gelijk "vijf opvragingen bij Operations Manager" 5 "$(aantal api.test)"
gelijk "vijf opvragingen van de readiness" 5 "$(aantal /q/health/ready)"
gelijk "met het opgegeven interval ertussen" "7 7 7 7" "$(tr '\n' ' ' <<<"$GESLAPEN" | sed 's/ $//')"

reset_stub
stub_om "$(gezond_in_om)"
stub_ready "$(router)" "$(readiness_up)"
wacht
gelijk "zonder seconden en interval: gezond" 0 "$RC"
gelijk "met een standaardinterval van 10 s" 10 "$GESLAPEN"

# De deadline telt in echte seconden: met twee seconden en één seconde slapen zijn het er hooguit drie.
reset_stub
stub_om "$(deployment)"
stub_ready "$(router)"
ECHT_SLAPEN=1 wacht 2 1
gelijk "na de deadline: rood" 1 "$RC"
opvragingen=$(aantal /q/health/ready)

if [ "$opvragingen" -ge 2 ] && [ "$opvragingen" -le 4 ]; then
  ok "binnen de deadline twee tot vier opvragingen"
else
  fout "binnen de deadline twee tot vier opvragingen — gemeten $opvragingen"
fi

reset_stub
stub_om "$(deployment . 401)"
stub_ready "$(readiness_up)"
wacht 10 1
gelijk "sleutel geweigerd: meteen rood" 1 "$RC"
gelijk "na één opvraging" 1 "$(aantal api.test)"
bevat "met de statuscode" 'HTTP 401' "$UITVOER"

reset_stub
draai wacht mpfb-8wh pr-7 uitvraag "$GEVRAAGD" '{"toxiproxy-aanmeld":"https://tp.test"}' 10 1
gelijk "geen adres voor de uitvraag: rood" 1 "$RC"
gelijk "en vraagt niets op" 0 "$(aantal http)"

for adressen in '{"uitvraag":"http://u.test"}' '' 'geen json' '{"uitvraag":"https://u.test\nx"}'; do
  reset_stub
  draai wacht mpfb-8wh pr-7 uitvraag "$GEVRAAGD" "$adressen" 10 1
  gelijk "onbruikbare adressen ($adressen): rood" 1 "$RC"
done

reset_stub
wacht 10 1 extra
gelijk "wacht met te veel argumenten: rood" 1 "$RC"

reset_stub
draai wacht mpfb-8wh pr-7 uitvraag "$GEVRAAGD"
gelijk "wacht zonder adressen: rood" 1 "$RC"

reset_stub
draai wacht mpfb-8wh pr-7 uitvraag '[]' "$URLS_PREVIEW"
gelijk "wacht met een lege componentenlijst: rood" 1 "$RC"

reset_stub
RC=0
PATH="$werkmap:$PATH" ZAD_API_KEY='' "$SCRIPT" wacht mpfb-8wh pr-7 uitvraag "$GEVRAAGD" "$URLS_PREVIEW" >/dev/null 2>&1 || RC=$?
gelijk "wacht zonder API-sleutel: rood" 1 "$RC"

# --- aanroep ------------------------------------------------------------------------------------
reset_stub
draai onzin mpfb-8wh pr-7
gelijk "onbekende actie: rood" 1 "$RC"

draai oordeel failure mpfb-8wh pr-7
gelijk "oordeel met te weinig argumenten: rood" 1 "$RC"

# --- aansluiting op deploy.yml ------------------------------------------------------------------
# Per stap, niet per job: een `continue-on-error` op de verkeerde stap of een `conclusion` in plaats
# van `outcome` maakt elke rode deploy stil groen. Een stap loopt van zijn `- ` tot de volgende stap of
# tot het einde van de job, en wordt gevonden op een regel waar dan ook in dat blok.
job() { awk -v j="  $1:" '$0 == j {f=1; next} f && /^  [a-z][a-z0-9-]*:$/ {exit} f' "$DEPLOY_YML"; }
stap_waar() {
  awk -v re="$1" '
    /^      - / { if (hit) print blok; blok = $0; hit = ($0 ~ re); next }
    /^ {0,5}[^ ]/ { if (hit) print blok; blok = ""; hit = 0; next }
    blok != "" { blok = blok "\n" $0; if ($0 ~ re) hit = 1 }
    END { if (hit) print blok }' <<<"$2"
}
stap() { stap_waar "^      (- |  )id: $1\$" "$2"; }
stap_met_naam() { stap_waar "^      (- |  )name: $1\$" "$2"; }

uitvraag_job=$(job deploy-preview-uitvraag)
magazijnen_job=$(job deploy-preview-magazijnen)
deploy_stap=$(stap deploy "$uitvraag_job")
oordeel_stap=$(stap oordeel "$uitvraag_job")
wacht_stap=$(stap_met_naam "Wacht tot de uitvraag-preview gezond is" "$magazijnen_job")

bevat "de deploy-stap is gevonden" 'zad-actions/deploy@' "$deploy_stap"
mist "zonder de oordeel-stap erin" 'id: oordeel' "$deploy_stap"
bevat "de uitvraag-deploy mag falen" 'continue-on-error: true' "$deploy_stap"
mist "het oordeel mag niet falen" 'continue-on-error' "$oordeel_stap"
# shellcheck disable=SC2016 # ${{ }} is letterlijke tekst uit de workflow, geen shell-expansie
bevat "het oordeel leest de outcome, niet de conclusion" 'UITKOMST: ${{ steps.deploy.outcome }}' "$oordeel_stap"
# shellcheck disable=SC2016 # "$UITKOMST" is letterlijke tekst uit de workflow
bevat "het oordeel roept het script aan met de uitkomst" 'uitvraag-preview-uitrol.sh oordeel "$UITKOMST"' "$oordeel_stap"
mist "het oordeel draait altijd" 'if:' "$oordeel_stap"
# shellcheck disable=SC2016 # idem
bevat "de adressen komen uit het oordeel" 'urls: ${{ steps.oordeel.outputs.urls }}' "$uitvraag_job"
# shellcheck disable=SC2016 # idem
bevat "de environment-URL komt uit het oordeel" 'url: ${{ steps.oordeel.outputs.url }}' "$uitvraag_job"
# shellcheck disable=SC2016 # idem
bevat "de wachtstap wacht vijf minuten, elke tien seconden" 'uitvraag-preview-uitrol.sh wacht "$PROJECT" "$DEPLOYMENT" uitvraag "$COMPONENTEN" "$URLS" 300 10' "$wacht_stap"
# shellcheck disable=SC2016 # idem
bevat "met de adressen van de uitvraag-job" 'URLS: ${{ needs.deploy-preview-uitvraag.outputs.urls }}' "$wacht_stap"
# shellcheck disable=SC2016 # idem
bevat "met de componenten van de uitvraag" 'COMPONENTEN: ${{ needs.meta.outputs.componenten-uitvraag }}' "$wacht_stap"
# shellcheck disable=SC2016 # idem
bevat "met de sleutel van het uitvraag-project" 'ZAD_API_KEY: ${{ secrets.ZAD_API_KEY_UITVRAAG }}' "$wacht_stap"
mist "en altijd, ook na een groene deploy" 'if:' "$wacht_stap"
bevat "de magazijnen-job hangt aan de uitvraag-job" '- deploy-preview-uitvraag' "$magazijnen_job"
bevat "en aan meta" '- meta' "$magazijnen_job"

echo "ASSERTIES=$asserties"

exit "$mislukt"
