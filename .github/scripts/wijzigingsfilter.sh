#!/usr/bin/env bash
# Bepaalt uit de bestandenlijst van een PR wát er moet draaien: code-checks, bouwen en uitrollen,
# de test-scope en de fuzz-ronde. Eén plek voor die vier vragen, want ze werden op drie plekken
# los beantwoord (deploy.yml, test.yml, detekt.yml) met dezelfde patronen. Op een PR naar main
# draait alleen de detectie van deploy.yml, dus een eenzijdige wijziging elders had daar geen
# effect en viel niet op — precies de drift die dit script onmogelijk maakt.
#
# Contract: uitkomsten (`sleutel=waarde`) op stdout, diagnostiek op stderr. De aanroeper hangt
# stdout aan $GITHUB_OUTPUT; wat hij niet als job-output declareert, blijft ongebruikt.
#
# Fail-safe is de kern: een overgeslagen job rapporteert 'skipped' en dat telt als succes voor
# branch protection. Elke twijfel — geen PR-context, onbereikbare API, grep-fout — valt daarom
# terug op draaien, nooit op overslaan.
set -euo pipefail

# Raakt code noch uitrol: documentatie en repo-meta. Basis voor alle drie de uitsluitingsfilters
# hieronder, zodat een nieuw meta-bestand op één plek landt.
#
# `.gitignore` hoorde hier vanaf het begin bij en ontbrak: een PR die alleen die regel en een
# stuk documentatie raakte, kocht twee testruns, detekt, vier images en drie previews.
NIET_CODE='^docs/|\.md$|^\.claude/|^\.github/ISSUE_TEMPLATE/|^\.github/dependabot\.yml$|^\.editorconfig$|^\.gitignore$|^\.gitattributes$|^LICENSE$|^apis\.json$|^publiccode\.yml$'

# Raakt de uitgerolde applicatie niet. Strenger dan NIET_CODE, want dit is de enige post die
# échte clustercapaciteit kost (pods, volumes, ingress) in plaats van alleen runnertijd.
# Uitgesloten: de Bruno-collectie, de demo-stack, demo-console, de fuzz-configuratie en de
# workflows die uitsluitend toetsen. Zonder dat filter kostte een PR aan bijvoorbeeld de
# fuzz-configuratie tóch twee jib-builds plus drie previews — uitrollen van een image dat per
# definitie gelijk is aan main. demo-console zit in geen enkel uitgerold image: het staat niet in
# de build-matrix van deploy.yml en heeft geen ZAD-component.
#
# deploy.yml zelf en de zad-actions staan er bewust NIET bij: die bepalen de uitrol, dus juist
# daar is een preview het bewijs dat de wijziging klopt.
#
# Het contract-bootstrap-image komt óók uit demo/, maar wordt alleen op push naar main uitgerold;
# op een PR bouwt en draait fsc-harness-overlays.yml hem. Een uitzondering hier zou dus de hele
# deploy-keten (twee jib-images, de stubs en drie previews) openzetten voor een wijziging die daar
# niets mee te maken heeft.
NIET_DEPLOYBAAR="$NIET_CODE|^bruno/|^demo/|^services/demo-console/|^\.clusterfuzzlite/|^\.github/workflows/(test|detekt|codeql|scorecard|architecture|pin-consistency|cflite_pr|cflite_batch|cflite_cron|fsc-harness-overlays|fuzz-base-image)\.yml\$"

# demo-console heeft bewust geen afhankelijkheid op fbs-common of een andere reactor-module (zie
# services/demo-console/pom.xml) — het enige blad in de module-graaf zonder koppeling. Raakt de PR
# verder niets buiten services/demo-console/ en demo/ (de Python-generator + smoke.sh, die alleen
# de demo-stack aansturen), dan hoeven berichtenmagazijn/berichtenuitvraag/libraries niet mee
# gebouwd en getest te worden.
BUITEN_DEMO_CONSOLE="$NIET_CODE|^services/demo-console/|^demo/"

# Allowlist in plaats van een uitsluiting: alleen bronnen die de fuzz-doelen of hun build raken.
FUZZ_RELEVANT='^libraries/|^services/|^pom\.xml$|^\.clusterfuzzlite/'

# `grep` geeft 1 bij geen match en 2 bij een echte fout; alleen 1 mag tot overslaan leiden, want
# een overgeslagen job telt als succes in de required checks. Een echte fout valt daarom terug op
# wél draaien.
#
# Herestring in plaats van een pipe: `grep -q` sluit de pipe bij de eerste match, en met
# `pipefail` promoveert die SIGPIPE tot een mislukte pipeline — waarna het filter stilzwijgend zou
# besluiten dat er niets te doen valt.
grep_fail_safe() {
  local bestanden=$1
  shift

  local rc=0
  grep -q "$@" <<<"$bestanden" || rc=$?

  case "$rc" in
    0) return 0 ;;
    1) return 1 ;;
    *)
      echo "::warning::Filter kon niet beoordelen (grep rc=$rc) — uitkomst fail-safe op draaien." >&2
      return 0
      ;;
  esac
}

# Beoordeelt een bestandenlijst (één pad per regel) en schrijft de vier uitkomsten naar stdout.
# $2 = 'true' voor een PR van een bot: dan valt alléén het uitrollen af.
classificeer() {
  local bestanden=$1
  local bot_pr=${2:-false}

  if grep_fail_safe "$bestanden" -vE "$NIET_CODE"; then
    echo "run=true"
  else
    echo "Alleen documentatie en repo-meta gewijzigd — code-checks en deploy overgeslagen." >&2
    echo "run=false"
  fi

  if [ "$bot_pr" = "true" ]; then
    echo "deploy=false"
  elif grep_fail_safe "$bestanden" -vE "$NIET_DEPLOYBAAR"; then
    echo "deploy=true"
  else
    echo "Geen wijziging die de images of de uitrol raakt — bouwen en deployen overgeslagen." >&2
    echo "deploy=false"
  fi

  if grep_fail_safe "$bestanden" -vE "$BUITEN_DEMO_CONSOLE"; then
    echo "demo-only=false"
  else
    echo "Uitsluitend demo-console geraakt — test-job scoped naar die module." >&2
    echo "demo-only=true"
  fi

  if grep_fail_safe "$bestanden" -E "$FUZZ_RELEVANT"; then
    echo "fuzz=true"
  else
    echo "Geen fuzz-relevante wijzigingen — fuzzing overgeslagen." >&2
    echo "fuzz=false"
  fi
}

# Alles aan zonder PR-bestandenlijst om tegen af te zetten (push naar main, workflow_dispatch),
# en ook wanneer die lijst niet op te halen is.
alles_aan() {
  echo "run=true"
  echo "deploy=true"
  echo "demo-only=false"
  echo "fuzz=true"
}

main() {
  if [ "${EVENT:-}" != "pull_request" ]; then
    alles_aan
    return 0
  fi

  local bot_pr=false

  # Het type komt van GitHub zelf (enum), niet uit door de indiener bepaalde tekst.
  if [ "${PR_AUTHOR_TYPE:-}" = "Bot" ]; then
    # Luid loggen: een overgeslagen deploy laat de required checks 'skipped' (= succes)
    # rapporteren, dus een onterechte overslag moet in de run-samenvatting zichtbaar zijn.
    # Alleen het uitrollen valt af — zad-actions weigert een bot-PR zelf (`skip-bot-prs`), dus de
    # preview komt er toch niet en de images zouden nooit een pod bereiken. De code-checks draaien
    # wél: een dependency-bump hoort getoetst te worden, en welke checks nodig zijn volgt uit
    # dezelfde bestandsanalyse als bij een gewone PR.
    echo "::notice::PR van een bot — zad-actions deployt die niet, dus bouwen we ook niet." >&2
    bot_pr=true
  fi

  local bestanden
  if ! bestanden=$(gh api --paginate "repos/$REPO/pulls/$PR/files" --jq '.[].filename'); then
    echo "::warning::Kon gewijzigde bestanden niet ophalen — alles draait fail-safe." >&2
    alles_aan
    return 0
  fi

  echo "Gewijzigde bestanden:" >&2
  printf '%s\n' "$bestanden" >&2

  classificeer "$bestanden" "$bot_pr"
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
