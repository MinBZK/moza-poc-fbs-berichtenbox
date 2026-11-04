#!/usr/bin/env bash
# Controleert dat elke bind-mount in één gerenderde podman-merge een gedeeld SELinux-label draagt.
#
# Leest de merge-JSON (`docker compose config --format json`) van stdin. Op een host met SELinux
# enforcing (Fedora, RHEL) leest een container een bind-mount zonder label niet, ook al kloppen de
# bestandsrechten: de stack start, en faalt pas op `permission denied` op `/pki` of een configfile.
#
#   docker compose -f … config --format json | .github/scripts/selinux-label-guard.sh <label>
#
# `z` en niet `Z`: de federatie mount dezelfde group-CA in meerdere peers, en een privé-label geeft
# alleen de laatst gestarte container toegang. Dus `Z` telt hier als fout, niet als "ook goed".
#
# Los van merge-guard.sh, want dat script toetst de eisen van een gedeelde netns en draait alleen
# op hostnet-merges. Het label is in bridge net zo nodig, en juist daar vervangt geen overlay de
# mount uit de podman-overlay — een ontbrekend label is in de hostnet-merge dan onzichtbaar.
set -euo pipefail

LABEL="${1:?usage: selinux-label-guard.sh <label>}"
[ "$#" -eq 1 ] || { echo "FOUT: selinux-label-guard.sh verwacht precies één argument." >&2; exit 2; }

MERGED="$(cat)"
[ -n "$MERGED" ] || { echo "FOUT: ${LABEL} — lege merge op stdin."; exit 1; }

if ! binds=$(jq -r '[.services[] | (.volumes // [])[] | select(.type == "bind")] | length' <<<"$MERGED"); then
  echo "FOUT: ${LABEL} — de merge is geen leesbare compose-JSON."
  exit 1
fi

# Nul bind-mounts is geen "alles gelabeld" maar "niets gemeten": elke opstelling die deze guard
# draait mount minstens één configfile, dus dan is de merge leeg of het schema gewijzigd.
if [ "$binds" -eq 0 ]; then
  echo "FOUT: ${LABEL} — geen enkele bind-mount in de merge; deze controle meet niets."
  exit 1
fi

zonder=$(jq -r '.services | to_entries[] | .key as $svc
  | (.value.volumes // [])[] | select(.type == "bind")
  | select((.bind.selinux // "") != "z")
  | "\($svc): \(.source) -> \(.target) (label: \(.bind.selinux // "<geen>"))"' <<<"$MERGED")

if [ -n "$zonder" ]; then
  echo "FOUT: ${LABEL} — bind-mount zonder gedeeld SELinux-label (zet \`:z\` in de podman-overlay):"
  printf '%s\n' "$zonder" | sed 's/^/  /'
  exit 1
fi

echo "${LABEL}: ${binds}/${binds} bind-mounts met \`:z\`."
