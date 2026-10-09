#!/usr/bin/env bash
# shellcheck shell=bash
#
# Gedeeld wachten op een taak van Operations Manager, voor cross-domain-preview.sh en
# preview-klaarzetten.sh.
#
# Beide sturen een wijziging in, krijgen een taak-id terug en moeten daarna weten hoe die taak
# afliep. Het opvragen zelf is het deel dat hier staat: één mislukte GET zegt niets over de taak, en
# een stap die daarop afbreekt, laat een deployment achter die wél is aangemaakt maar waarvan niemand
# de uitkomst kent. Wat een eindtoestand betékent, verschilt per script en blijft daar.
#
# Dit bestand wordt gesourcet en definieert alleen functies; het doet uit zichzelf niets. Het
# aanroepende script levert `$CURL` en een `fout` die de stap afbreekt.

# Hoeveel opvragingen er op rij mogen mislukken. Een hik van Operations Manager duurt seconden; een
# fout die na twintig rondes nog staat, gaat binnen de wachttijd niet meer over, en dan is twintig
# seconden wachten genoeg om dat vast te stellen.
readonly ZAD_TAAK_MAX_MISLUKT=20

# De wachttijd per taak, in rondes én in seconden. De rondes begrenzen de lus; de klok begrenst wat
# trage of hangende opvragingen daar bovenop zouden leggen.
readonly ZAD_TAAK_RONDES=120
readonly ZAD_TAAK_WACHTTIJD=120

# Uitkomst van de laatste opvraging en van het wachten.
zad_taak_antwoord=""
zad_taak_status=""
zad_taak_reden=""

# Vraagt de taak één keer op. Zet `zad_taak_antwoord` bij succes en `zad_taak_reden` bij een fout.
# Exitcode: 0 = antwoord binnen, 1 = mislukt maar herhalen heeft zin, 2 = herhalen heeft geen zin.
#
# Zonder `-f`, met de statuscode achter de body: `-f` vouwt elke HTTP-fout samen tot exitcode 22, en
# dan is een 503 niet van een 401 te onderscheiden — niet voor de lus en niet voor wie de log leest.
zad_taak_vraag_op() {
  local api_url=$1 api_key=$2 taak=$3 ruw rc=0 http

  # `--max-time` zodat een verbinding die openblijft zonder antwoord één ronde kost en niet de rest
  # van de job.
  ruw=$("$CURL" -s --max-time 10 -w '\n%{http_code}' \
    -H "X-API-Key: $api_key" "$api_url/tasks/$taak") || rc=$?

  if [ "$rc" -ne 0 ]; then
    zad_taak_reden="curl-exitcode $rc"

    return 1
  fi

  http=${ruw##*$'\n'}

  case "$http" in
    2[0-9][0-9])
      zad_taak_antwoord=${ruw%$'\n'*}

      return 0
      ;;
    401 | 403)
      # De sleutel wordt geweigerd. Dat verandert niet door het nog eens te vragen.
      zad_taak_reden="HTTP $http"

      return 2
      ;;
    [1-5][0-9][0-9])
      # Ook 404: de taak is net aangemaakt, en een antwoord van vlak daarvoor mag hem nog missen.
      # Blijft hij weg, dan vangt de grens op mislukkingen-op-rij dat alsnog af.
      zad_taak_reden="HTTP $http"

      return 1
      ;;
    *)
      zad_taak_reden="geen HTTP-status in het antwoord"

      return 1
      ;;
  esac
}

# Wacht tot de taak één van de opgegeven eindtoestanden heeft en zet dan `zad_taak_status` en
# `zad_taak_antwoord`; het oordeel over die toestand is aan de aanroeper. Breekt de stap af als de
# taak niet op te vragen blijft of niet op tijd klaar is.
#
# <onbekend> en <niet-klaar> zijn de zinsdelen waarmee de melding zegt wat er nu onzeker is — de
# netwerkregel of de deployment.
#
# Gebruik: zad_wacht_op_taak <api-url> <api-key> <taak> <onbekend> <niet-klaar> <eindtoestand>...
zad_wacht_op_taak() {
  local api_url=$1 api_key=$2 taak=$3 onbekend=$4 niet_klaar=$5
  shift 5

  local einde=$((SECONDS + ZAD_TAAK_WACHTTIJD)) mislukt=0 rc eindtoestand

  # Elke seconde, niet elke twee: de taak zelf duurt tientallen seconden en zit op het kritieke pad
  # van de preview-uitrol, dus de halve wachttijd na afloop telt en één extra GET niet.
  for _ in $(seq "$ZAD_TAAK_RONDES"); do
    [ "$SECONDS" -lt "$einde" ] || break

    rc=0
    zad_taak_vraag_op "$api_url" "$api_key" "$taak" || rc=$?

    case "$rc" in
      0)
        mislukt=0
        zad_taak_status=$(jq -r '.status // ""' <<<"$zad_taak_antwoord" 2>/dev/null) || zad_taak_status=""

        for eindtoestand in "$@"; do
          [ "$zad_taak_status" != "$eindtoestand" ] || return 0
        done
        ;;
      2)
        fout "Taak $taak niet op te vragen ($zad_taak_reden); $onbekend. Opnieuw proberen verandert dit antwoord niet."
        ;;
      *)
        mislukt=$((mislukt + 1))
        echo "Taak $taak opvragen mislukt ($zad_taak_reden), $mislukt op rij; nieuwe poging." >&2

        [ "$mislukt" -lt "$ZAD_TAAK_MAX_MISLUKT" ] \
          || fout "Taak $taak niet op te vragen: $mislukt opvragingen op rij mislukt (laatste: $zad_taak_reden); $onbekend."
        ;;
    esac

    sleep 1
  done

  # De wachttijd is om. Liep het opvragen op dat moment nog mis, dan is dát de melding: "nog niet
  # klaar" zou een uitspraak over de taak zijn die niemand heeft kunnen doen. Met het aantal erbij:
  # één mislukking in de laatste ronde is iets anders dan een reeks.
  [ "$mislukt" -eq 0 ] \
    || fout "Taak $taak niet op te vragen binnen twee minuten (laatste $mislukt opvragingen mislukt: $zad_taak_reden); $onbekend."

  fout "Taak $taak was na twee minuten nog niet klaar; $niet_klaar."
}
