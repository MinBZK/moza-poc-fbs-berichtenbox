#!/usr/bin/env bash
# Unittests voor cross-domain-preview.sh. Geen netwerk: elke test zet een curl-stub op het pad die
# de opgevraagde URL en body wegschrijft en een geregisseerd antwoord teruggeeft.
#
# Wat hier bewaakt wordt is vooral stil falen. Een netwerkregel die niet staat, valt pas op wanneer
# iemand tijdens een demo op een knop drukt: de regel is geen onderdeel van een healthcheck en de
# deployment blijft `Healthy`. Elke tak die "gelukt" zou kunnen melden zonder dat er iets stond,
# heeft daarom een eigen assertie.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/cross-domain-preview.sh"

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

# Een curl-stub die elke aanroep wegschrijft (argumenten in `aanroepen`, JSON-bodies in `bodies`)
# en een geregisseerd antwoord teruggeeft: `patch_antwoord` voor de PATCH, `taakstatus` voor het
# pollen erna. Met `patch_rc` doet de stub alsof curl zelf faalde. `deploymentconfigs` voegt
# configuraties per deployment toe aan het projectantwoord (met een komma ervoor), en `taakantwoord`
# vervangt het hele taakantwoord.
maak_stub() {
  local map=$1 patch_antwoord=$2 taakstatus=$3 patch_rc=${4:-0} projectregels=${5:-'"regel","democonsole-naar-redis"'}
  local deploymentconfigs=${6:-} taakantwoord=${7:-}

  # Elke gequote naam een eigen regel-object: `"a","b"` wordt `{"name":"a"},{"name":"b"}`. Met de
  # namen als één object zou het antwoord bij twee regels geen geldige JSON zijn, en dan leest het
  # script elke deploymentregel als "nog niet gezet" — de tests daarvoor zouden slagen om de
  # verkeerde reden.
  local projectitems
  projectitems=$(printf '%s' "$projectregels" | sed 's/"\([^"]*\)"/{"name":"\1"}/g')

  rm -f "$map/taak"
  [ -z "$taakantwoord" ] || printf '%s' "$taakantwoord" >"$map/taak"

  cat >"$map/curl" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$map/aanroepen"
for arg in "\$@"; do
  case "\$arg" in
    '{'*) printf '%s\n' "\$arg" >>"$map/bodies" ;;
  esac
done

if printf '%s' "\$*" | grep -q '/tasks/'; then
  if [ -f "$map/taak" ]; then
    cat "$map/taak"
  else
    printf '{"task_id":"t-1","status":"$taakstatus"}'
  fi
  exit 0
fi

# De GET op de projectconfiguratie: het PATCH-pad draagt /config/deployment/, dit niet.
if printf '%s' "\$*" | grep -q 'cross-domain-access/config' && ! printf '%s' "\$*" | grep -q '/config/deployment/'; then
  printf '{"configurations":[{"target":"project","config":{"inbound":[$projectitems]}}$deploymentconfigs]}'
  exit 0
fi

printf '%s' '$patch_antwoord'
exit $patch_rc
STUB
  chmod +x "$map/curl"
}

# Draait het script met de stub op het pad. Zet $RC op de exitcode en $UITVOER op wat het
# schreef, zodat een test op allebei kan toetsen.
draai() {
  local map=$1
  shift

  RC=0
  UITVOER=$(PATH="$map:$PATH" ZAD_API_KEY=sleutel ZAD_API_URL=http://api.test ZAD_ROLLOUT="${ROLLOUT:-}" \
    bash "$SCRIPT" "$@" 2>&1) || RC=$?
}

# --- de body per richting -----------------------------------------------------------------------
# De kern van dit script: in een inbound-regel is de tegenpartij `from`, in een outbound-regel `to`.
# Die twee verwisselen levert een regel op die valideert maar niets openzet — geen foutmelding,
# geen verkeer.

# shellcheck source=.github/scripts/cross-domain-preview.sh
source "$SCRIPT"

# Het script draagt zelf een `fout` die meteen `exit 1` doet, en die overschrijft de helper
# hierboven. Zonder dit herstel stopt de suite bij de eerste mislukking: de rest blijft ongemeten en
# de samenvatting onderaan wordt nooit bereikt. De run faalt dan wél, maar toont één probleem waar
# er meer kunnen zijn.
fout() {
  mislukt=1
  echo "FOUT: $1"
}

gelijk "inbound vult de from-kant in" \
  '{"add":[{"name":"r","from":{"deployment":"pr-7"}}]}' \
  "$(patch_body zet pr-7 inbound r)"

gelijk "outbound vult de to-kant in" \
  '{"add":[{"name":"r","to":{"deployment":"pr-7"}}]}' \
  "$(patch_body zet pr-7 outbound r)"

gelijk "verwijderen noemt alleen de regelnaam" \
  '{"remove":["r"]}' \
  "$(patch_body verwijder pr-7 inbound r)"

# Opruimen mag de richting niet nodig hebben: cleanup-preview.yml kent alleen de regelnaam, en een
# body die per richting verschilt zou daar een extra parameter afdwingen die niets toevoegt.
gelijk "verwijderen is gelijk voor beide richtingen" \
  "$(patch_body verwijder pr-7 inbound r)" \
  "$(patch_body verwijder pr-7 outbound r)"

# --- meerdere regels in één patch ---------------------------------------------------------------
# Een deployment draagt tot vijf regels. Per regel een eigen aanroep zou per regel de
# projectcontrole doen en per regel op een taak wachten; de API neemt een lijst.

gelijk "twee regels leveren twee add-items" \
  '{"add":[{"name":"een","from":{"deployment":"pr-7"}},{"name":"twee","from":{"deployment":"pr-7"}}]}' \
  "$(patch_body zet pr-7 inbound een twee)"

gelijk "twee regels leveren twee remove-namen" \
  '{"remove":["een","twee"]}' \
  "$(patch_body verwijder pr-7 inbound een twee)"

# Eén regel moet exact leveren wat hij vóór de uitbreiding leverde: de bestaande regel op ZAD is
# met die vorm gezet en de patch is een add/remove per naam.
gelijk "een enkele regel houdt zijn oude vorm" \
  '{"add":[{"name":"r","to":{"deployment":"pr-7"}}]}' \
  "$(patch_body zet pr-7 outbound r)"

# --- argumentcontrole ---------------------------------------------------------------------------
werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT
maak_stub "$werkmap" '{"task_id":"t-1"}' completed

draai "$werkmap" zet mpfm-w3h pr-7 zijwaarts regel
gelijk "een onbekende richting stopt het script" 1 "$RC"

draai "$werkmap" plakken mpfm-w3h pr-7 inbound regel
gelijk "een onbekende actie stopt het script" 1 "$RC"

draai "$werkmap" zet mpfm-w3h '' inbound regel
gelijk "een lege deployment stopt het script" 1 "$RC"

draai "$werkmap" zet mpfm-w3h pr-7 inbound ''
gelijk "een lege regelnaam stopt het script" 1 "$RC"

RC=0
PATH="$werkmap:$PATH" ZAD_API_KEY='' ZAD_API_URL=http://api.test bash "$SCRIPT" zet p d inbound r >/dev/null 2>&1 || RC=$?
gelijk "zonder ZAD_API_KEY stopt het script" 1 "$RC"

# --- de gelukkige weg ---------------------------------------------------------------------------
rm -f "$werkmap/aanroepen" "$werkmap/bodies"
draai "$werkmap" zet mpfm-w3h pr-7 outbound democonsole-naar-redis
gelijk "een geslaagde patch eindigt met 0" 0 "$RC"

aanroepen=$(tr '\n' ' ' <"$werkmap/aanroepen")
bevat "de projectconfiguratie wordt eerst opgevraagd" \
  '/v2/projects/mpfm-w3h/services/cross-domain-access/config ' "$aanroepen"
bevat "de patch gaat naar het deployment-pad van de richting" \
  '/v2/projects/mpfm-w3h/services/cross-domain-access/config/deployment/pr-7/outbound' "$aanroepen"
bevat "de patch draagt de API-sleutel" 'X-API-Key: sleutel' "$aanroepen"
bevat "de taak wordt daarna opgevraagd" '/tasks/t-1' "$aanroepen"
bevat "de body vult de to-kant met de deployment" \
  '"to":{"deployment":"pr-7"}' "$(cat "$werkmap/bodies")"

# Meerdere regels in één aanroep: één patch, één taak. Zou het script per regel patchen, dan zou
# een half geslaagde ronde de helft van de netwerkregels achterlaten zonder dat iets dat aanwijst.
maak_stub "$werkmap" '{"task_id":"t-1"}' completed 0 '"democonsole-naar-redis","democonsole-naar-toxiproxy-redis"'
rm -f "$werkmap/aanroepen" "$werkmap/bodies"
draai "$werkmap" zet mpfb-8wh pr-7 inbound democonsole-naar-redis democonsole-naar-toxiproxy-redis
gelijk "meerdere regels in een aanroep eindigen met 0" 0 "$RC"
gelijk "en gaan in een enkele patch" 1 "$(grep -c PATCH "$werkmap/aanroepen")"
bevat "met beide namen erin" 'democonsole-naar-toxiproxy-redis' "$(cat "$werkmap/bodies")"

# En een regel die op projectniveau ontbreekt, mag niet meeliften op een buurregel die er wél staat.
maak_stub "$werkmap" '{"task_id":"t-1"}' completed 0 '"democonsole-naar-redis"'
draai "$werkmap" zet mpfb-8wh pr-7 inbound democonsole-naar-redis democonsole-naar-toxiproxy-redis
gelijk "een ontbrekende regel naast een bestaande stopt het script" 1 "$RC"
bevat "en noemt de ontbrekende" 'democonsole-naar-toxiproxy-redis' "$UITVOER"

maak_stub "$werkmap" '{"task_id":"t-1"}' completed

# --- stil falen ---------------------------------------------------------------------------------
# Elk van deze drie zou zonder eigen tak een groene stap opleveren over een regel die niet staat.

maak_stub "$werkmap" '{"detail":"nee"}' completed 22
draai "$werkmap" zet mpfm-w3h pr-7 inbound regel
gelijk "een geweigerde patch stopt het script" 1 "$RC"

# HTTP 200 met een foutmelding erin: curl klaagt niet, dus zonder de taak-id-controle zou het
# script hier "gelukt" melden over een regel die nooit geschreven is.
maak_stub "$werkmap" '{"melding":"aangenomen"}' completed
draai "$werkmap" zet mpfm-w3h pr-7 inbound regel
gelijk "een antwoord zonder taak-id stopt het script" 1 "$RC"
bevat "en zegt waaróm het stopte" 'Geen taak-id' "$UITVOER"

maak_stub "$werkmap" '{"task_id":"t-1"}' failed
draai "$werkmap" zet mpfm-w3h pr-7 inbound regel
gelijk "een gefaalde taak stopt het script" 1 "$RC"
bevat "en noemt de eindtoestand" "eindigde als 'failed'" "$UITVOER"

# --- uitrol uitstellen --------------------------------------------------------------------------
# preview-klaarzetten.sh zet de regels van een nieuwe preview vóór zijn eerste uitrol. Zonder de vlag
# rolt de patch meteen uit, en rendert Operations Manager een deployment waarvan de peers nog niet
# bestaan.
maak_stub "$werkmap" '{"task_id":"t-1"}' completed
rm -f "$werkmap/aanroepen"
ROLLOUT=false draai "$werkmap" zet mpfm-w3h pr-7 outbound democonsole-naar-redis
gelijk "een patch zonder uitrol eindigt met 0" 0 "$RC"
bevat "ZAD_ROLLOUT=false stuurt rollout=false mee" \
  '/config/deployment/pr-7/outbound?rollout=false' "$(tr '\n' ' ' <"$werkmap/aanroepen")"

rm -f "$werkmap/aanroepen"
draai "$werkmap" zet mpfm-w3h pr-7 outbound democonsole-naar-redis
case "$(cat "$werkmap/aanroepen")" in
  *rollout=*) fout "zonder ZAD_ROLLOUT gaat er tóch een rollout-vlag mee" ;;
  *) ok "zonder ZAD_ROLLOUT rolt de patch gewoon uit" ;;
esac

rm -f "$werkmap/aanroepen"
ROLLOUT=flase draai "$werkmap" zet mpfm-w3h pr-7 outbound democonsole-naar-redis
gelijk "een onbekende ZAD_ROLLOUT stopt het script" 1 "$RC"
case "$(cat "$werkmap/aanroepen" 2>/dev/null || true)" in
  *PATCH*) fout "met een onbekende ZAD_ROLLOUT ging er tóch een patch uit" ;;
  *) ok "en patcht dan niets" ;;
esac

# Het taakantwoord draagt twee `status`-velden: dat van de taak en, in het resultaat, dat van de
# verwerking. Een patch zonder uitrol eindigt als taak `completed` met verwerking `skipped`. Wie de
# verkeerde leest, wacht twee minuten op een taak die al klaar is; de time-out hieronder maakt dat
# rood in plaats van traag.
maak_stub "$werkmap" '{"task_id":"t-1"}' completed 0 '"democonsole-naar-redis"' '' \
  '{"task_id":"t-1","status":"completed","result":{"status":"success","processing":{"status":"skipped","reason":"rollout_disabled"}}}'
RC=0
PATH="$werkmap:$PATH" ZAD_API_KEY=sleutel ZAD_API_URL=http://api.test ZAD_ROLLOUT=false \
  timeout 20 bash "$SCRIPT" zet mpfm-w3h pr-7 outbound democonsole-naar-redis >/dev/null 2>&1 || RC=$?
gelijk "een taak die klaar is met een overgeslagen verwerking telt als klaar" 0 "$RC"

# --- regels die al staan ------------------------------------------------------------------------
# Een uitgestelde patch blijft in Operations Manager als "wacht op uitrol" meetellen tot iemand het
# hele project herverwerkt. Wat al goed staat, hoort dus geen patch te krijgen. Wat maar een beetje
# anders staat wel: een gekloonde preview draagt dezelfde namen, maar met de kloonbron als peer.
GEZET_PR7=',{"target":"deployment","deployment":"pr-7","config":{"outbound":[{"name":"democonsole-naar-redis","to":{"deployment":"pr-7"}}]}}'

maak_stub "$werkmap" '{"task_id":"t-1"}' completed 0 '"democonsole-naar-redis"' "$GEZET_PR7"
rm -f "$werkmap/aanroepen"
draai "$werkmap" zet mpfm-w3h pr-7 outbound democonsole-naar-redis
gelijk "een regel die al staat is geen fout" 0 "$RC"
gelijk "en krijgt geen patch" 0 "$(grep -c PATCH "$werkmap/aanroepen" || true)"
bevat "de uitvoer zegt dat hij al stond" 'stonden al' "$UITVOER"

# Het omgekeerde van het geval hierboven: opruimen moet altijd patchen, ook wat al staat.
rm -f "$werkmap/aanroepen"
draai "$werkmap" verwijder mpfm-w3h pr-7 outbound democonsole-naar-redis
gelijk "opruimen patcht ook een regel die staat" 1 "$(grep -c PATCH "$werkmap/aanroepen" || true)"

geval_patcht() {
  local wat=$1 configs=$2
  shift 2

  maak_stub "$werkmap" '{"task_id":"t-1"}' completed 0 '"democonsole-naar-redis","democonsole-naar-toxiproxy-redis"' "$configs"

  # Een antwoord dat geen JSON is, leest het script als "nog niet gezet" en patcht dan altijd: de
  # uitkomst hieronder zou kloppen zonder dat de vergelijking iets toetste.
  if ! "$werkmap/curl" 'http://api.test/v2/projects/p/services/cross-domain-access/config' | jq -e . >/dev/null 2>&1; then
    fout "$wat — de stub levert geen geldige JSON, dus deze toets meet niets"

    return
  fi

  rm -f "$werkmap/aanroepen"
  draai "$werkmap" zet mpfm-w3h pr-7 outbound "$@"

  if [ "$RC" -eq 0 ] && [ "$(grep -c PATCH "$werkmap/aanroepen" || true)" -eq 1 ]; then
    ok "$wat"
  else
    fout "$wat — rc=$RC, aanroepen: $(tr '\n' ' ' <"$werkmap/aanroepen" 2>/dev/null)"
  fi
}

geval_patcht "één regel die nog ontbreekt is genoeg voor een patch" "$GEZET_PR7" \
  democonsole-naar-redis democonsole-naar-toxiproxy-redis

geval_patcht "een regel met de kloonbron als peer wordt omgezet" \
  ',{"target":"deployment","deployment":"pr-7","config":{"outbound":[{"name":"democonsole-naar-redis","to":{"deployment":"test"}}]}}' \
  democonsole-naar-redis

geval_patcht "een regel in de andere richting telt niet als gezet" \
  ',{"target":"deployment","deployment":"pr-7","config":{"inbound":[{"name":"democonsole-naar-redis","from":{"deployment":"pr-7"}}]}}' \
  democonsole-naar-redis

geval_patcht "een regel onder een andere deployment telt niet als gezet" \
  ',{"target":"deployment","deployment":"test","config":{"outbound":[{"name":"democonsole-naar-redis","to":{"deployment":"pr-7"}}]}}' \
  democonsole-naar-redis

maak_stub "$werkmap" '{"task_id":"t-1"}' completed

# --- de projectregel moet bestaan ---------------------------------------------------------------
# Operations Manager laat het genereren nooit falen op een kapotte regel: een deployment-patch
# zonder projectregel wordt een regel op zichzelf, mist component en poort, en wordt met een
# waarschuwing overgeslagen. De API accepteert de patch wél. Zonder deze controle zou de stap dus
# groen melden over een netwerkregel die er nooit komt.
maak_stub "$werkmap" '{"task_id":"t-1"}' completed 0 '"een-andere-regel"'
draai "$werkmap" zet mpfm-w3h pr-7 outbound democonsole-naar-redis
gelijk "een ontbrekende projectregel stopt het script" 1 "$RC"
bevat "en zegt dat de regel op projectniveau ontbreekt" 'op projectniveau' "$UITVOER"

# Opruimen mag daar niet op stuklopen: is de projectregel al weg, dan moet de deployment-patch er
# júist nog af kunnen.
rm -f "$werkmap/aanroepen"
draai "$werkmap" verwijder mpfm-w3h pr-7 outbound democonsole-naar-redis
gelijk "opruimen vraagt niet om een projectregel" 0 "$RC"
case "$(tr '\n' ' ' <"$werkmap/aanroepen")" in
  *'cross-domain-access/config '*) fout "opruimen vroeg de projectconfiguratie tóch op" ;;
  *) ok "opruimen vraagt de projectconfiguratie niet op" ;;
esac

maak_stub "$werkmap" '{"task_id":"t-1"}' completed
# --- de regelnamen in de workflows ----------------------------------------------------------------
# deploy.yml zet de regels en cleanup-preview.yml haalt ze weg, elk met hun eigen kopie van de
# lijst. Lopen die uit elkaar, dan blijft een regel achter op een deployment die niet meer bestaat —
# en dat faalt stil: de resolver slaat zo'n regel over, logt dat, en niemand leest die log.
#
# De lijsten staan als gevouwen blok (`>-`) in het env-blok van beide workflows; de regelnamen zijn
# de vier spaties ingesprongen vervolgregels.
regelset() {
  awk -v sleutel="  $2: >-" '
    $0 == sleutel { bezig = 1; next }
    bezig && /^    [a-z]/ { print $1; next }
    bezig { exit }
  ' "$REPO_ROOT/.github/workflows/$1" | sort | tr '\n' ' '
}

for sleutel in CROSS_DOMAIN_REGELS_UITVRAAG CROSS_DOMAIN_REGELS_EXTERNE_STUBS CROSS_DOMAIN_REGELS_MAGAZIJNEN; do
  zet_set=$(regelset deploy.yml "$sleutel")
  weg_set=$(regelset cleanup-preview.yml "$sleutel")

  if [ -z "$zet_set" ]; then
    fout "deploy.yml draagt geen $sleutel — deze controle meet niets"
  else
    ok "deploy.yml noemt regels onder $sleutel"
  fi

  gelijk "deploy.yml en cleanup-preview.yml noemen dezelfde regels voor $sleutel" "$zet_set" "$weg_set"
done

# De console is bij elke hop de aanroepende kant, dus het magazijnen-project draagt de
# outbound-kant van álle regels. Ontbreekt er één, dan staat de inbound-kant open en het verkeer
# niet — en dat is precies het geval dat geen enkele foutmelding oplevert.
beide_kanten=$(
  {
    regelset deploy.yml CROSS_DOMAIN_REGELS_UITVRAAG
    regelset deploy.yml CROSS_DOMAIN_REGELS_EXTERNE_STUBS
  } | tr ' ' '\n' | grep -v '^$' | sort -u | tr '\n' ' '
)

gelijk "de magazijnen dragen de tegenhanger van elke inbound-regel" \
  "$beide_kanten" "$(regelset deploy.yml CROSS_DOMAIN_REGELS_MAGAZIJNEN)"

# Elke leg van de klaarzet- en de opruim-matrix moet een regelsleutel dragen die ook echt in het
# env-blok van zijn workflow staat; een typfout daarin levert een lege lijst op, en dan zet of ruimt
# die leg stilzwijgend niets.
for wf in deploy.yml cleanup-preview.yml; do
  sleutels=$(sed -n 's/^ *regelsleutel: *//p' "$REPO_ROOT/.github/workflows/$wf" | sort -u)

  if [ -z "$sleutels" ]; then
    fout "$wf draagt geen matrix met regelsleutels — deze controle meet niets"
    continue
  fi

  while read -r sleutel; do
    if [ -n "$(regelset "$wf" "$sleutel")" ]; then
      ok "de matrix-sleutel $sleutel bestaat in het env-blok van $wf"
    else
      fout "de matrix in $wf noemt $sleutel, maar het env-blok kent die niet"
    fi
  done <<<"$sleutels"
done

# En de andere kant: een regel die gezet wordt zonder tegenhanger die hem opruimt, laat na elke
# gesloten PR een regel achter. deploy.yml zet de regels via preview-klaarzetten.sh.
if grep -q 'cross-domain-preview\.sh' "$REPO_ROOT/.github/workflows/cleanup-preview.yml"; then
  ok "cleanup-preview.yml roept cross-domain-preview.sh aan"
else
  fout "cleanup-preview.yml roept cross-domain-preview.sh niet meer aan"
fi

if grep -q 'preview-klaarzetten\.sh' "$REPO_ROOT/.github/workflows/deploy.yml" \
  && grep -q 'cross-domain-preview\.sh' "$REPO_ROOT/.github/scripts/preview-klaarzetten.sh"; then
  ok "deploy.yml zet de regels via preview-klaarzetten.sh en cross-domain-preview.sh"
else
  fout "deploy.yml zet de regels niet meer via preview-klaarzetten.sh en cross-domain-preview.sh"
fi

# --- sourcen ------------------------------------------------------------------------------------
# Deze suite sourcet het script voor patch_body; zou main dan meedraaien, dan zou hij bij het
# sourcen al een echte API aanroepen.
uitvoer=$(ZAD_API_KEY=sleutel bash -c "source '$SCRIPT'" 2>&1)
gelijk "sourcen voert main niet uit" "" "$uitvoer"

echo
if [ "$mislukt" -eq 0 ]; then
  echo "Alle tests geslaagd."
else
  echo "Er zijn tests mislukt."
fi

echo "ASSERTIES=$asserties"

exit "$mislukt"
