#!/usr/bin/env bash
# Beoordeelt na afloop van een deploy-run of er is uitgerold, óf dat de uitrol terecht uitbleef.
#
# Bestaat omdat `gate` en de uitrol-jobs zichzelf legitiem mogen overslaan: een overgeslagen job
# rapporteert 'skipped' en dat telt als succes voor branch protection. Valt de wijzigingsdetectie
# om, dan vallen ze allemaal weg als 'skipped' en is de run groen en samenvoegbaar terwijl er
# niets is uitgerold en niets bewaakt. Deze ene beoordeling mag daarom nooit stil overslaan en
# nooit stil slagen: elke onbepaalde uitkomst is een fout, geen stilte.
#
# Contract: alle invoer via de omgeving, diagnostiek op stdout, de exitcode is het oordeel.
#   EVENT   github.event_name
#   REF     github.ref
#   CHANGES resultaat van de job `changes`
#   DEPLOY  output `deploy` van die job
#   GATE    resultaat van de job `gate`
#   NEEDS   toJSON(needs) — de uitrol-resultaten worden hieruit afgeleid in plaats van per job
#           overgetypt, zodat een hernoemde of toegevoegde uitrol-job niet buiten de beoordeling
#           valt zonder dat iemand het merkt.
set -euo pipefail

# Aantal uitrol-jobs per as: drie previews op een PR, drie test-deployments op een push. Vast
# getal zodat een verdwenen job een fout oplevert in plaats van een kortere lus die groen meldt
# over minder jobs dan er zijn; test-uitrol-poort.sh kruist het met de jobs in deploy.yml.
VERWACHT_AANTAL=3

fout() {
  echo "::error::$1"

  exit 1
}

beoordeel() {
  # `changes` draait onvoorwaardelijk; elke andere uitkomst dan success betekent dat de keten
  # erachter is weggevallen zonder dat iemand dat besloot.
  if [ "$CHANGES" != success ]; then
    fout "De wijzigingsdetectie eindigde als '$CHANGES' — uitrol onbepaald, niet stil overgeslagen."
  fi

  # Leeg of iets anders dan een boolean betekent "geen besluit", niet "niets uitrollen". Zonder
  # deze controle valt een ontbrekende output in de niets-verwacht-tak en wordt de poort groen op
  # precies de storing die hij moet vangen.
  case "$DEPLOY" in
    true | false) ;;
    *) fout "De uitrol-uitkomst is '$DEPLOY' in plaats van true/false — detectie onbepaald." ;;
  esac

  local voorvoegsel

  case "$EVENT" in
    push)
      [ "$REF" = refs/heads/main ] \
        || fout "Push op '$REF' terwijl alleen main de test-deployments uitrolt."

      # De deploy-test-jobs raadplegen de detectie niet — hun `if` toetst alleen event en ref.
      # Op een push hoort de detectie dus onvoorwaardelijk true te geven; doet ze dat niet, dan
      # is ze omgevallen en bewijst een uitgebleven uitrol niets.
      [ "$DEPLOY" = true ] \
        || fout "Op een push hoort de detectie deploy=true te geven, niet '$DEPLOY' — detectie omgevallen."

      voorvoegsel=deploy-test-
      ;;
    pull_request)
      voorvoegsel=deploy-preview-
      ;;
    *)
      fout "Onbekend event '$EVENT' — de poort kan niet bepalen welke uitrol-jobs hoorden te draaien."
      ;;
  esac

  local uitrol
  uitrol=$(jq -r --arg p "$voorvoegsel" \
    'to_entries[] | select(.key | startswith($p)) | "\(.key)=\(.value.result)"' <<<"$NEEDS")

  local aantal
  aantal=$(grep -c . <<<"$uitrol" || true)

  if [ "$aantal" -ne "$VERWACHT_AANTAL" ]; then
    fout "$aantal uitrol-jobs met voorvoegsel '$voorvoegsel' gevonden in plaats van $VERWACHT_AANTAL — de needs van de poort lopen uit de pas met deploy.yml."
  fi

  # Een afgebroken run heeft niets bewezen: handmatig geannuleerd, of een job die door zijn
  # concurrency-groep wijkt voor een nieuwere commit. Groen melden zou liegen; de opvolgende run
  # oordeelt opnieuw.
  case "$GATE $uitrol" in
    *cancelled*) fout "Een job is geannuleerd — deze run is afgebroken en bewijst niets." ;;
  esac

  local verwacht

  if [ "$DEPLOY" = true ]; then
    [ "$GATE" = success ] \
      || fout "De kwaliteitspoort eindigde als '$GATE' — er is niet uitgerold."

    verwacht=success
  else
    # Zonder uitrol slaat `gate` zichzelf over. Draaide hij tóch en viel hij om, dan is dat een
    # fout — ook al hoefde er niets uitgerold te worden.
    [ "$GATE" = skipped ] || [ "$GATE" = success ] \
      || fout "De kwaliteitspoort eindigde als '$GATE'."

    verwacht=skipped
  fi

  local naam resultaat

  while IFS='=' read -r naam resultaat; do
    [ "$resultaat" = "$verwacht" ] \
      || fout "Uitrol-job '$naam' eindigde als '$resultaat' terwijl '$verwacht' verwacht was (deploy=$DEPLOY)."
  done <<<"$uitrol"

  if [ "$verwacht" = skipped ]; then
    echo "Geen uitrolbare wijziging (deploy=$DEPLOY) — geen uitrol verwacht, en er draaide er ook geen."
  else
    echo "Alle $aantal uitrol-jobs geslaagd."
  fi
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  beoordeel
fi
