#!/usr/bin/env bash
# Fixture-tests voor proeftuin-pin.sh. Dat script eindigt altijd groen en draagt zijn hele oordeel in
# vier GITHUB_OUTPUT-sleutels; de aanroeper (pin-consistency.yml en proeftuin-pin-pr.sh) handelt daar
# blind naar. De dure faalwijze is daarom niet "rood", maar een status die om de verkeerde reden is
# gekozen: een onbereikbaar ghcr dat als "ontbreekt" langskomt, meldt een oorzaak die nooit is
# vastgesteld, en een verouderd-melding zonder bruikbare regel levert een PR die niemand kan duiden.
#
# Elk geval toetst daarom alle vier de sleutels, niet alleen `status`. Alleen `status` toetsen laat
# een lege `verwachte_regel` of een achtergebleven `verwachte_tag` ongemerkt passeren, en juist die
# twee velden worden verderop ongelezen doorgegeven.
#
# De buitenwereld staat in drie stubs: `curl` (het ghcr-token, de manifest-HEAD en de GitHub-API),
# en `proeftuin-image.sh` via REPO_ROOT, zodat de gepinde referentie per geval te dicteren is.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/proeftuin-pin.sh"

fails=0
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# --- de stubs -----------------------------------------------------------------------------------

mkdir -p "$WERKMAP/bin"

# Eén curl voor drie soorten aanroepen. De manifest-tak schrijft echte headers in het `-D`-bestand:
# het gemeten script leest de digest dáár uit, dus een stub die alleen een code teruggeeft zou de
# "200 zonder Docker-Content-Digest"-tak onbereikbaar maken — precies de tak die een tag zonder
# vastgestelde inhoud van een ontbrekende tag onderscheidt.
cat > "$WERKMAP/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail

alle=" $* "
url=""
headers=""
met_formaat=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    -D) headers=$2; shift 2 ;;
    -w) met_formaat=1; shift 2 ;;
    -o|-H) shift 2 ;;
    https://*) url=$1; shift ;;
    *) shift ;;
  esac
done

printf '%s\n' "$url" >> "$CURL_LOG"

case "$url" in
  https://ghcr.io/token*)
    # Leeg antwoord = de tokenaanroep zelf faalde; het gemeten script hoort dat als "niets
    # vastgesteld" te behandelen en niet als een tag die niet bestaat.
    [ -n "${GHCR_TOKEN_JSON:-}" ] || exit 22

    printf '%s\n' "$GHCR_TOKEN_JSON"
    ;;
  https://ghcr.io/v2/*/manifests/*)
    # Zonder `-I` haalt het gemeten script elk manifest binnen in plaats van alleen de headers; een
    # stub die dat negeert, laat die regressie stil slagen.
    case "$alle" in
      *" -I "*) ;;
      *) echo "stub: manifest-aanroep zonder -I" >&2; exit 64 ;;
    esac

    rest=${url#https://ghcr.io/v2/}
    pad=${rest%%/manifests/*}
    tag=${rest##*/manifests/}
    antwoord=$(awk -v sleutel="$pad:$tag" \
      '$1 == sleutel { print $2, $3; gevonden = 1; exit } END { if (!gevonden) print "404", "" }' \
      "$MANIFESTEN")
    code=${antwoord%% *}
    digest=${antwoord#* }

    {
      printf 'HTTP/2 %s \r\n' "$code"
      printf 'content-type: application/vnd.oci.image.index.v1+json\r\n'

      if [ -n "$digest" ]; then
        printf 'Docker-Content-Digest: %s\r\n' "$digest"
      fi

      printf '\r\n'
    } > "$headers"

    printf '%s' "$code"
    ;;
  https://api.github.com/repos/*/commits/*)
    [ -n "${COMMIT_JSON:-}" ] || exit 22

    printf '%s\n' "$COMMIT_JSON"
    ;;
  https://api.github.com/repos/*)
    # `-L` zou een hernoemd repository stilzwijgend volgen en de 301 nooit laten zien; die 301 is
    # juist het signaal waar de bron-weg-status op hangt.
    case "$alle" in
      *" -L "*) echo "stub: repo-aanroep met -L" >&2; exit 64 ;;
      *) ;;
    esac

    if [ "$met_formaat" -eq 1 ]; then
      printf '%s' "${REPO_HTTP:-200}"
    else
      [ -n "${REPO_JSON:-}" ] || exit 22

      printf '%s\n' "$REPO_JSON"
    fi
    ;;
  *)
    echo "stub: onbekende URL '$url'" >&2
    exit 2
    ;;
esac
STUB

chmod +x "$WERKMAP/bin/curl"
export PATH="$WERKMAP/bin:$PATH"

# --- de vaste gegevens --------------------------------------------------------------------------

# Acht keer hetzelfde blok van acht tekens: een digest is 64 hex-tekens, en een met de hand getelde
# literal is in een fixture de plek waar een tikfout ongemerkt blijft — hier zou hij de contracttest
# onderaan laten falen op iets dat niets met het gemeten script te maken heeft.
digest_blok() {
  local blok=$1

  printf 'sha256:%s%s%s%s%s%s%s%s' "$blok" "$blok" "$blok" "$blok" "$blok" "$blok" "$blok" "$blok"
}

DIGEST_MAIN=$(digest_blok 9f1a2b3c)
DIGEST_PIN=$(digest_blok 4d5e6f70)
DIGEST_ANDERS=$(digest_blok 81920a3b)
DIGEST_PREVIEW=$(digest_blok c5d6e7f8)

KOP=6e0751e9a8b4c2d0f1e3a5b7c9d1e3f5a7b9c1d3
VERWACHTE_TAG=sha-6e0751e
PAD=minbzk/moza-poc

# --- de opzet per geval -------------------------------------------------------------------------

# Elk geval zijn eigen map, aanroepenlogboek, manifest-tabel en GITHUB_OUTPUT: een geval dat op de
# resten van een vorige leunt, meet de vorige.
#
# De schakelaars gaan hier expliciet terug naar hun default. Een toewijzing vóór een functie-aanroep
# blijft in bash staan ná die aanroep, dus zonder deze reset zou één geval met een 502 elk volgend
# geval meeslepen.
nieuw_geval() {
  GEVAL="$WERKMAP/$1"
  REPO_STUB="$GEVAL/repo"
  IMAGE_STUB="$REPO_STUB/.github/scripts/proeftuin-image.sh"

  mkdir -p "$REPO_STUB/.github/scripts"

  export CURL_LOG="$GEVAL/curl-log"
  export MANIFESTEN="$GEVAL/manifesten"
  : > "$CURL_LOG"
  : > "$MANIFESTEN"
  : > "$GEVAL/output"

  export REPO_HTTP=200
  export REPO_JSON='{"default_branch":"main","name":"moza-poc"}'
  export COMMIT_JSON="{\"sha\":\"$KOP\"}"
  export GHCR_TOKEN_JSON='{"token":"stub-pull-token"}'
}

# De referentie die compose.yaml zou dragen. Het gemeten script roept proeftuin-image.sh aan en leest
# alleen stdout; wat dáár uit komt is de enige invoer die het over de pin heeft.
pin() {
  cat > "$IMAGE_STUB" <<EOF
#!/usr/bin/env bash
printf '%s\n' "$1"
EOF

  chmod +x "$IMAGE_STUB"
}

geen_pin() {
  cat > "$IMAGE_STUB" <<'EOF'
#!/usr/bin/env bash
echo "compose.yaml is niet te lezen" >&2
exit 1
EOF

  chmod +x "$IMAGE_STUB"
}

# `manifest <pad> <tag> <code> [digest]` — zonder digest schrijft de stub een antwoord zónder
# Docker-Content-Digest-header. Een tag die niet in de tabel staat, bestaat niet (404).
manifest() {
  printf '%s %s %s\n' "$1:$2" "$3" "${4:-}" >> "$MANIFESTEN"
}

# `|| CODE=$?` en niet `set +e`: een onverwachte fout elders in de suite moet nog steeds afbreken.
# BRON_REPO en MAIN_PAD blijven bewust op hun default: die twee zijn de productiewaarden waar de
# aangeboden regel uit opgebouwd wordt, en die hoort deze suite te meten in plaats van te dicteren.
uitvoeren() {
  CODE=0
  UITVOER=$(REPO_ROOT="$REPO_STUB" GITHUB_OUTPUT="$GEVAL/output" bash "$SCRIPT" 2>&1) || CODE=$?
}

sleutel() { sed -n "s/^$1=//p" "$GEVAL/output" | tail -1; }

gelijk() {
  if [ "$2" = "$3" ]; then
    ok "$1"
  else
    fout "$1 (verwacht '$3', kreeg '$2'; uitvoer: $UITVOER)"
  fi
}

# Alle vier de sleutels plus de exitcode. Het script hoort ook bij een vastgestelde storing groen te
# eindigen: een rode run zou de aanroepende workflow laten afbreken vóór hij de status kan lezen.
verwacht() {
  local wat=$1 status=$2 tag=$3 regel=$4 huidig=$5

  gelijk "$wat — eindigt groen" "$CODE" 0
  gelijk "$wat — status" "$(sleutel status)" "$status"
  gelijk "$wat — verwachte_tag" "$(sleutel verwachte_tag)" "$tag"
  gelijk "$wat — verwachte_regel" "$(sleutel verwachte_regel)" "$regel"
  gelijk "$wat — huidige_referentie" "$(sleutel huidige_referentie)" "$huidig"
}

aangeroepen() {
  if grep -qF "$2" "$CURL_LOG"; then
    ok "$1"
  else
    fout "$1 (aanroepen: $(tr '\n' '|' < "$CURL_LOG"))"
  fi
}

niet_aangeroepen() {
  if grep -qF "$2" "$CURL_LOG"; then
    fout "$1 (aanroepen: $(tr '\n' '|' < "$CURL_LOG"))"
  else
    ok "$1"
  fi
}

meldt() {
  if grep -qF "$2" <<<"$UITVOER"; then
    ok "$1"
  else
    fout "$1 (uitvoer: $UITVOER)"
  fi
}

# --- de pin staat goed --------------------------------------------------------------------------

nieuw_geval pin-is-bij
pin "ghcr.io/$PAD:latest@$DIGEST_MAIN"
manifest "$PAD" "$VERWACHTE_TAG" 200 "$DIGEST_MAIN"
uitvoeren
verwacht "een pin op de digest van de main-commit" \
  ok "$VERWACHTE_TAG" "" "ghcr.io/$PAD:latest@$DIGEST_MAIN"
niet_aangeroepen "een pin die bij is zoekt latest niet meer op" "/manifests/latest"

# Een tag draagt zijn digest niet bij zich. Wordt die niet eerst opgezocht, dan vergelijkt het script
# een tag met een digest — die zijn nooit gelijk, en dan meldt deze guard elke dag opnieuw
# "verouderd" voor een pin die gewoon bij is.
nieuw_geval tag-pin-zonder-digest
pin "ghcr.io/$PAD:latest"
manifest "$PAD" latest 200 "$DIGEST_MAIN"
manifest "$PAD" "$VERWACHTE_TAG" 200 "$DIGEST_MAIN"
uitvoeren
verwacht "een tag-pin waarvan de digest bij de main-commit hoort" \
  ok "$VERWACHTE_TAG" "" "ghcr.io/$PAD:latest"
aangeroepen "de gepinde tag wordt eerst naar een digest opgezocht" "/manifests/latest"

# De sha-tag komt uit de standaardtak die GitHub noemt, niet uit een vastgelegde "main": hernoemt de
# proeftuin zijn standaardtak, dan hoort deze controle mee te bewegen in plaats van stil op een
# onbestaande tak te blijven vragen.
nieuw_geval andere-standaardtak
pin "ghcr.io/$PAD:latest@$DIGEST_MAIN"
manifest "$PAD" "$VERWACHTE_TAG" 200 "$DIGEST_MAIN"
REPO_JSON='{"default_branch":"ontwikkel"}'
uitvoeren
verwacht "een andere standaardtak levert dezelfde uitkomst" \
  ok "$VERWACHTE_TAG" "" "ghcr.io/$PAD:latest@$DIGEST_MAIN"
aangeroepen "de commit wordt op de gemelde standaardtak opgevraagd" "/commits/ontwikkel"

# --- de pin loopt achter ------------------------------------------------------------------------

nieuw_geval verouderd-latest-vorm
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
manifest "$PAD" "$VERWACHTE_TAG" 200 "$DIGEST_MAIN"
manifest "$PAD" latest 200 "$DIGEST_MAIN"
uitvoeren
verwacht "een achterlopende pin terwijl latest dezelfde digest draagt" \
  verouderd "$VERWACHTE_TAG" "    image: ghcr.io/$PAD:latest@$DIGEST_MAIN" \
  "ghcr.io/$PAD:latest@$DIGEST_PIN"
REGEL_LATEST_VORM=$(sleutel verwachte_regel)

# `latest` is alleen een leesbare aanduiding bij de digest die er nú op staat. Wijst hij ergens
# anders heen, dan zou de latest-vorm een andere image aanbieden dan de main-commit waarover deze
# melding gaat.
nieuw_geval verouderd-latest-wijkt-af
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
manifest "$PAD" "$VERWACHTE_TAG" 200 "$DIGEST_MAIN"
manifest "$PAD" latest 200 "$DIGEST_ANDERS"
uitvoeren
verwacht "een latest die naar een andere digest wijst" \
  verouderd "$VERWACHTE_TAG" "    image: ghcr.io/$PAD:$VERWACHTE_TAG@$DIGEST_MAIN" \
  "ghcr.io/$PAD:latest@$DIGEST_PIN"

# De latest-lookup mag de melding niet meeslepen: de bevinding is al vastgesteld op de sha-tag, en
# `latest` bepaalt alleen nog de vorm van de aangeboden regel.
nieuw_geval verouderd-latest-onbereikbaar
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
manifest "$PAD" "$VERWACHTE_TAG" 200 "$DIGEST_MAIN"
manifest "$PAD" latest 500
uitvoeren
verwacht "een onbereikbare latest-lookup levert de sha-vorm" \
  verouderd "$VERWACHTE_TAG" "    image: ghcr.io/$PAD:$VERWACHTE_TAG@$DIGEST_MAIN" \
  "ghcr.io/$PAD:latest@$DIGEST_PIN"
REGEL_SHA_VORM=$(sleutel verwachte_regel)

nieuw_geval verouderd-latest-weg
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
manifest "$PAD" "$VERWACHTE_TAG" 200 "$DIGEST_MAIN"
manifest "$PAD" latest 404
uitvoeren
verwacht "een verdwenen latest-tag levert de sha-vorm" \
  verouderd "$VERWACHTE_TAG" "    image: ghcr.io/$PAD:$VERWACHTE_TAG@$DIGEST_MAIN" \
  "ghcr.io/$PAD:latest@$DIGEST_PIN"

# --- de main-commit heeft nog geen image --------------------------------------------------------

nieuw_geval ontbreekt
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
manifest "$PAD" "$VERWACHTE_TAG" 404
uitvoeren
verwacht "een main-commit zonder image" \
  ontbreekt "$VERWACHTE_TAG" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"

# --- de pin staat op nog niet gemergd werk ------------------------------------------------------

# Hun preview-images liggen in een ánder repository. Vergelijken met hun main heeft daar geen
# betekenis: die tag hóórt af te wijken en verdwijnt zodra hun PR sluit.
nieuw_geval preview
pin "ghcr.io/$PAD/preview:pr-1-abc"
manifest "$PAD/preview" pr-1-abc 200 "$DIGEST_PREVIEW"
uitvoeren
verwacht "een pin op hun preview-repository" \
  preview "" "" "ghcr.io/$PAD/preview:pr-1-abc"
niet_aangeroepen "een preview-pin wordt niet met hun main vergeleken" "api.github.com"

# --- de pin zelf deugt niet ---------------------------------------------------------------------

nieuw_geval pin-onvindbaar
pin "ghcr.io/$PAD:sha-2f3a4b5"
manifest "$PAD" sha-2f3a4b5 404
uitvoeren
verwacht "een gepinde tag die niet meer bestaat" \
  pin-onvindbaar "" "" "ghcr.io/$PAD:sha-2f3a4b5"
niet_aangeroepen "een onvindbare pin leidt niet tot een main-vergelijking" "api.github.com"

nieuw_geval geen-pin
geen_pin
uitvoeren
verwacht "een compose.yaml zonder leesbare referentie" geen-pin "" "" ""
niet_aangeroepen "zonder pin wordt het register niet bevraagd" "ghcr.io"

# Een referentie buiten ghcr.io is niet "fout", maar wel buiten wat deze controle kan nagaan — en dat
# is iets anders dan een tag die niet bestaat.
nieuw_geval ander-register
pin "docker.io/$PAD:latest"
uitvoeren
verwacht "een referentie buiten ghcr.io" \
  oncontroleerbaar "" "" "docker.io/$PAD:latest"
meldt "een referentie buiten ghcr.io noemt de oorzaak" "kent alleen dat register"
niet_aangeroepen "een referentie buiten ghcr.io bevraagt ghcr niet" "ghcr.io"

# --- het bron-repository ------------------------------------------------------------------------

nieuw_geval bron-hernoemd
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
REPO_HTTP=301
uitvoeren
verwacht "een hernoemd bron-repository (301)" \
  bron-weg "" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"

nieuw_geval bron-verdwenen
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
REPO_HTTP=404
uitvoeren
verwacht "een verdwenen bron-repository (404)" \
  bron-weg "" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"

# --- er is niets vastgesteld --------------------------------------------------------------------

# 401, 429 en 5xx zeggen alle drie dat de controle zélf niet kon draaien. Als "ontbreekt" of
# "pin-onvindbaar" doorgeven zou een oorzaak melden die niemand heeft vastgesteld, en de aanroeper
# handelt daarnaar: bij `ontbreekt` blijft een openstaande pin-PR staan alsof hun bouw nog loopt.
for ghcr_code in 401 429 500; do
  nieuw_geval "ghcr-$ghcr_code"
  pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
  manifest "$PAD" "$VERWACHTE_TAG" "$ghcr_code"
  uitvoeren
  verwacht "ghcr antwoordt $ghcr_code op de verwachte tag" \
    oncontroleerbaar "$VERWACHTE_TAG" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"
  meldt "ghcr $ghcr_code komt met code en al in de log" "ghcr antwoordde '$ghcr_code'"
done

# De tag bestaat, maar we weten niet waarnaar hij wijst. Zonder deze tak zou een lege digest als
# vergelijkingswaarde doorlopen en elke pin eeuwig "verouderd" heten.
nieuw_geval verwachte-tag-zonder-digest
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
manifest "$PAD" "$VERWACHTE_TAG" 200
uitvoeren
verwacht "een 200 zonder Docker-Content-Digest op de verwachte tag" \
  oncontroleerbaar "$VERWACHTE_TAG" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"

nieuw_geval gepinde-tag-onbereikbaar
pin "ghcr.io/$PAD:latest"
manifest "$PAD" latest 500
uitvoeren
verwacht "een onbereikbare lookup van de gepinde tag" \
  oncontroleerbaar "" "" "ghcr.io/$PAD:latest"

nieuw_geval gepinde-tag-zonder-digest
pin "ghcr.io/$PAD:latest"
manifest "$PAD" latest 200
uitvoeren
verwacht "een 200 zonder Docker-Content-Digest op de gepinde tag" \
  oncontroleerbaar "" "" "ghcr.io/$PAD:latest"

nieuw_geval token-aanroep-stuk
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
GHCR_TOKEN_JSON=""
uitvoeren
verwacht "een mislukte tokenaanroep" \
  oncontroleerbaar "" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"

# Een antwoord zonder token is geen token: zonder de `-e` op jq zou de string "null" als bearer
# meegaan en elke lookup daarna stranden op een fout die met de tag niets te maken heeft.
nieuw_geval token-antwoord-zonder-token
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
GHCR_TOKEN_JSON='{"foutmelding":"denied"}'
uitvoeren
verwacht "een tokenantwoord zonder token" \
  oncontroleerbaar "" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"

nieuw_geval github-502
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
REPO_HTTP=502
uitvoeren
verwacht "GitHub antwoordt 502 op het repo-endpoint" \
  oncontroleerbaar "" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"
meldt "een 502 komt met code en al in de log" "GitHub antwoordde '502'"

nieuw_geval geen-standaardtak
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
REPO_JSON='{"name":"moza-poc"}'
uitvoeren
verwacht "een repo-antwoord zonder standaardtak" \
  oncontroleerbaar "" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"

nieuw_geval geen-commit-sha
pin "ghcr.io/$PAD:latest@$DIGEST_PIN"
COMMIT_JSON='{"message":"Not Found"}'
uitvoeren
verwacht "een commit-antwoord zonder sha" \
  oncontroleerbaar "" "" "ghcr.io/$PAD:latest@$DIGEST_PIN"

# --- contracttest met proeftuin-pin-pr.sh -------------------------------------------------------

# De aangeboden regel gaat ongelezen door naar proeftuin-pin-pr.sh, dat hem tegen zijn eigen
# vormcontrole houdt en bij afkeuring niets aanbiedt. Die koppeling staat in geen van beide scripts
# vastgelegd: een extra spatie of een andere volgorde in de ene laat de andere stil stoppen met
# aanbieden, terwijl beide suites groen blijven. Sourcen voert de main van dat script niet uit.
regel_geaccepteerd() {
  local wat=$1 regel=$2

  # shellcheck source=.github/scripts/proeftuin-pin-pr.sh
  if ( source "$HERE/proeftuin-pin-pr.sh"; regel_is_welgevormd "$regel" ); then
    ok "$wat"
  else
    fout "$wat (regel: '$regel')"
  fi
}

regel_geaccepteerd "proeftuin-pin-pr.sh accepteert de latest-vorm" "$REGEL_LATEST_VORM"
regel_geaccepteerd "proeftuin-pin-pr.sh accepteert de sha-vorm" "$REGEL_SHA_VORM"

if [ "$fails" -eq 0 ]; then
  echo "Alle tests geslaagd."
fi

echo "ASSERTIES=$geslaagd"

if [ "$fails" -ne 0 ]; then
  echo "$fails assertie(s) gefaald." >&2
  exit 1
fi
