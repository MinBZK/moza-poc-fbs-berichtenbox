#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Bouwstenen voor charts/fundament-testcluster.sh: parsers, manifests en values zonder
# bijwerkingen, zodat ze zonder cluster te toetsen zijn (test-lib.sh). Wordt gesourcet; zet daarom
# zelf geen shell-opties.

# Versie en hash uit de uitvoer van `functl plugin publish`, als "<versie> <hash>". De hash is wat
# een PluginInstallation pint; een afgekapte hash zou een installatie opleveren die nooit start.
publicatie_lees() {
  local regel

  regel=$(grep -E '^published plugin=' <<<"$1" | tail -1)
  [ -n "$regel" ] || return 1

  local versie hash
  versie=$(sed -nE 's/.* version=([^ ]+).*/\1/p' <<<"$regel")
  hash=$(sed -nE 's/.* hash=(sha256:[0-9a-f]{64})( .*|$)/\1/p' <<<"$regel")

  [ -n "$versie" ] && [ -n "$hash" ] || return 1
  echo "$versie $hash"
}

# Soort fout uit de uitvoer van functl: geen-listing, niet-ingelogd of onbekend. Na een nieuwe
# uitrol van het platform zijn de API-keys weg; dat moet als "maak een nieuwe key" bij de gebruiker
# aankomen en niet als een kale RPC-fout.
functl_fout_soort() {
  case "$1" in
    *"no listing named"*) echo geen-listing ;;
    *unauthenticated* | *401*) echo niet-ingelogd ;;
    *) echo onbekend ;;
  esac
}

# Slaagt als een PluginInstallation (JSON van kubectl) draait en gereed is.
plugin_staat_klaar() {
  jq -e '.status.phase == "Running" and .status.ready == true' <<<"$1" >/dev/null 2>&1 || return 1
}

# Slaagt als een bestaande sandbox van deze opstelling kan zijn: Kubernetes 1.36 en de https-poort
# op loopback. Een sandbox van `just plugins cluster-create` heeft die mapping niet; die stil
# gebruiken zou een Gateway opleveren die van buiten onbereikbaar is.
#   $1 serverversie (kubectl version), $2 uitvoer van `docker port <serverlb>`, $3 de https-poort
sandbox_past() {
  local versie=$1 mappings=$2 poort=$3

  case "$versie" in
    v1.36.*) ;;
    *)
      echo "De sandbox draait Kubernetes $versie; deze opstelling vraagt 1.36." >&2
      return 1
      ;;
  esac

  if ! grep -qxE "443/tcp -> 127\.0\.0\.1:$poort" <<<"$mappings"; then
    echo "De sandbox heeft geen mapping 127.0.0.1:$poort -> 443 (HTTPS_POORT=$poort)." >&2
    return 1
  fi
}

# Slaagt als niemand op 127.0.0.1:<poort> luistert. Ook een luisteraar op alle adressen bezet hem.
poort_vrij() {
  ! ss -ltnH "sport = :$1" | grep -q .
}
