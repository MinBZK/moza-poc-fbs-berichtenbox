#!/usr/bin/env bash
# Beoordeelt na afloop van een deploy-run of er is uitgerold, óf dat de uitrol terecht uitbleef.
#
# Bestaat omdat `gate` en de uitrol-jobs zichzelf legitiem mogen overslaan: valt de
# wijzigingsdetectie om, dan slaan ze allemaal over en is de run groen zonder dat er iets is
# uitgerold. Elke onbepaalde uitkomst is hier daarom een fout, geen stilte.
#
# Contract: alle invoer via de omgeving, diagnostiek op stdout, de exitcode is het oordeel.
#   EVENT     github.event_name
#   REF       github.ref
#   CANCELLED github.cancelled() — of de RUN is afgebroken
#   CHANGES   resultaat van de job `changes`
#   DEPLOY    output `deploy` van die job
#   GATE      resultaat van de job `gate`
#   NEEDS     toJSON(needs) — de resultaten van de bouw- en uitrol-jobs worden hieruit afgeleid,
#             zodat er één plek is die synchroon moet blijven met de needs-lijst in plaats van
#             een handgeschreven expressie per job.
set -Eeuo pipefail

# Aantal uitrol-jobs per as: drie previews op een PR, drie test-deployments op een push. Vast
# getal zodat een verdwenen job een fout oplevert in plaats van een kortere lus die groen meldt
# over minder jobs dan er zijn; test-uitrol-poort.sh kruist het met de jobs in deploy.yml.
VERWACHT_AANTAL=3

fout() {
  echo "::error::$1"

  exit 1
}

# Vangnet voor alles wat niet via `fout` loopt: een ontbrekende variabele, een omgevallen jq of
# grep. Zonder dit eindigt zo'n afbreking wel non-zero maar zonder annotatie, en dus zonder
# aanwijzing in de checks-samenvatting.
onverwacht() {
  echo "::error::uitrol-poort.sh brak af op regel $2 met exitcode $1 — het oordeel is onbepaald."

  exit "$1"
}

# Resultaten per voorvoegsel, als regels `jobnaam=resultaat`. Faalt jq, dan is het needs-object
# onbruikbaar en mag dat niet als "geen jobs gevonden" doorgaan.
#
# De status teruggeven en hier géén `fout` aanroepen: deze functie draait in een
# command-substitutie, dus daar zou de `exit` alleen de subshell doden en zou de foutmelding de
# teruggegeven waarde wórden — de aanroeper leest dan een onbruikbare regel als resultaat en meldt
# iets over deploy.yml terwijl het probleem het workflow-contract is.
resultaten() {
  jq -r --arg p "$1" 'to_entries[] | select(.key | startswith($p)) | "\(.key)=\(.value.result)"' \
    <<<"$NEEDS"
}

telling() {
  [ -n "$1" ] || { echo 0; return; }

  grep -c . <<<"$1"
}

# Alle jobs op de gekozen as moeten hetzelfde resultaat hebben; de eerste afwijking is fataal.
eis_as() {
  local regels=$1 verwacht=$2 toelichting=$3 naam resultaat

  local aantal
  aantal=$(telling "$regels")

  if [ "$aantal" -ne "$VERWACHT_AANTAL" ]; then
    fout "$aantal uitrol-jobs gevonden in plaats van $VERWACHT_AANTAL — de needs van de poort lopen uit de pas met deploy.yml."
  fi

  while IFS='=' read -r naam resultaat; do
    [ "$resultaat" = "$verwacht" ] \
      || fout "Uitrol-job '$naam' eindigde als '$resultaat' terwijl '$verwacht' verwacht was$toelichting."
  done <<<"$regels"
}

beoordeel() {
  # Een afgebroken run bewijst niets, en is aan de job-resultaten niet te herkennen: `gate` en de
  # uitrol-jobs dragen zelf `!cancelled()`, dus bij een annulering vóór hun start rapporteren ze
  # 'skipped' — niet te onderscheiden van de legitieme "niets uit te rollen"-uitkomst.
  if [ "$CANCELLED" != false ]; then
    fout "De run is afgebroken — deze run bewijst niets over de uitrol."
  fi

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

  local as stille_as

  case "$EVENT" in
    push)
      [ "$REF" = refs/heads/main ] \
        || fout "Push op '$REF' terwijl alleen main de test-deployments uitrolt."

      # De detectie valt op elk niet-PR-event terug op alles-aan, dus `deploy=false` betekent
      # hier dat ze is omgevallen — en dan bewijst een uitgebleven uitrol niets.
      [ "$DEPLOY" = true ] \
        || fout "Op een push hoort de detectie deploy=true te geven, niet '$DEPLOY' — detectie omgevallen."

      as=deploy-test-
      stille_as=deploy-preview-
      ;;
    pull_request)
      as=deploy-preview-
      stille_as=deploy-test-
      ;;
    *)
      fout "Onbekend event '$EVENT' — de poort kan niet bepalen welke uitrol-jobs hoorden te draaien."
      ;;
  esac

  # De andere as hoort volledig stil te zijn. Draaide daar tóch iets, dan matcht de `if` van die
  # jobs breder dan bedoeld en rolt een event uit dat dat niet hoort te doen.
  local stil uitrol

  stil=$(resultaten "$stille_as") \
    || fout "NEEDS is geen bruikbare toJSON(needs)-uitvoer — het oordeel is onbepaald."

  eis_as "$stil" skipped " op de as die bij dit event niet hoort te draaien"

  uitrol=$(resultaten "$as") \
    || fout "NEEDS is geen bruikbare toJSON(needs)-uitvoer — het oordeel is onbepaald."

  if [ "$DEPLOY" = true ]; then
    [ "$GATE" = success ] \
      || fout "De kwaliteitspoort eindigde als '$GATE' — er is niet uitgerold."

    # De bouw-jobs staan niet op de uitrol-as, maar zijn wél de gebruikelijke oorzaak van een
    # overgeslagen uitrol: valt een build om of wijkt hij voor een nieuwere commit, dan slaan de
    # uitrol-jobs over via hun eigen `if`. Zonder hun stand wijst de melding naar het gevolg.
    local bouw
    bouw=$(resultaten build) \
      || fout "NEEDS is geen bruikbare toJSON(needs)-uitvoer — het oordeel is onbepaald."

    eis_as "$uitrol" success " (bouw: $(tr '\n' ' ' <<<"$bouw"))"

    echo "Alle $VERWACHT_AANTAL uitrol-jobs geslaagd."
  else
    # Zonder uitrol slaat `gate` zichzelf over. Draaide hij tóch en viel hij om, dan is dat een
    # fout — ook al hoefde er niets uitgerold te worden.
    [ "$GATE" = skipped ] || [ "$GATE" = success ] \
      || fout "De kwaliteitspoort eindigde als '$GATE'."

    eis_as "$uitrol" skipped " (deploy=$DEPLOY)"

    echo "Geen uitrolbare wijziging (deploy=$DEPLOY) — geen uitrol verwacht, en er draaide er ook geen."
  fi
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen. De trap
# hangt aan die tak, zodat sourcen de aanroeper niet omver trekt.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  trap 'onverwacht "$?" "$LINENO"' ERR

  # `set -u` beëindigt de shell op een ontbrekende variabele zónder de ERR-trap te draaien, dus
  # zonder annotatie. Het contract met de workflow wordt daarom expliciet gecontroleerd.
  for naam in EVENT REF CANCELLED CHANGES DEPLOY GATE NEEDS; do
    [ -n "${!naam+gezet}" ] \
      || fout "Omgevingsvariabele $naam ontbreekt — het contract met deploy.yml is verbroken."
  done

  beoordeel
fi
