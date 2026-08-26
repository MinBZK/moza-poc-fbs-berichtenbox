#!/usr/bin/env bash
# De lijst met Maven-modules onder demo/. Eén bron voor iedereen die hem nodig heeft: de
# grensbewaking (demo-grens.sh) en de demo-shard van test.yml. Een tweede, ingetypte lijst zou
# stil verouderen — en een module die nergens genoemd wordt blijft dan ongetest terwijl alles
# groen rapporteert.
#
# De lijst wordt twee keer opgehaald en vergeleken: uit de reactor (de <module>-regels van de
# root-pom) en van schijf. Wijkt dat af, dan is er een module toegevoegd zonder registratie of
# omgekeerd — allebei situaties waarin de afgeleide lijst niet meer klopt en stilte de verkeerde
# uitkomst is.
#
# Contract: modulepaden (één per regel, gesorteerd) op stdout, diagnostiek op stderr, exitcode 1
# zodra de lijst niet betrouwbaar is.
set -euo pipefail

DEMO_MODULES_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${REPO_ROOT:="$(cd "$DEMO_MODULES_HERE/../.." && pwd)"}"

# Alle modulepaden uit de reactor, transitief. Via de XML-parser en niet met een regex: een
# gespreide <module>-regel zou anders onzichtbaar blijven en een uitgecommentarieerde juist
# meetellen. Transitief, want een module mag zélf modules declareren en die staan niet in de
# root-pom — wie alleen daar kijkt, mist precies de module die niemand mist.
reactor_modules() {
  python3 "$DEMO_MODULES_HERE/pom-artifactids.py" --reactor "$REPO_ROOT/pom.xml"
}

reactor_demo_modules() {
  local alle

  # Niet `reactor_modules | grep …` als één pipeline: vindt grep niets, dan geeft de pipeline 1 en
  # doden `pipefail` en `set -e` het script ín de assignment van de aanroeper — vóór de tak die de
  # bruikbare melding draagt. Een lege uitkomst is hier een geldige meting, geen fout.
  alle=$(reactor_modules) || return 1

  printf '%s\n' "$alle" | { grep '^demo/' || true; } | sort
}

schijf_demo_modules() {
  # Zonder deze controle sterft het script op de mislukte `find` — met `pipefail` en `set -e` valt
  # dat samen tot exitcode 1 zonder één regel uitleg, en dat is precies de situatie (root-pom al
  # om, map nog niet verplaatst) waarin de lezer die uitleg nodig heeft.
  if [ ! -d "$REPO_ROOT/demo" ]; then
    echo "FOUT: $REPO_ROOT/demo bestaat niet — de demo-modulelijst is niet op te maken." >&2

    return 1
  fi

  # `target/` uitsluiten: een build kan daar pom-kopieën achterlaten, en die zijn geen module.
  # Status vasthouden en stderr laten staan: een onleesbare submap levert gedeeltelijke uitvoer én
  # een foutstatus, en genegeerd gaat die halve boom door voor een volledige meting.
  local gevonden
  if ! gevonden=$(find "$REPO_ROOT/demo" -name target -prune -o -name pom.xml -print); then
    echo "FOUT: demo/ is niet volledig te doorzoeken — de modulelijst is niet betrouwbaar." >&2

    return 1
  fi

  printf '%s\n' "$gevonden" \
    | while IFS= read -r pom; do
        [ -n "$pom" ] || continue
        # Prefix strippen met bash en niet met sed: REPO_ROOT gaat daar ongeëscapeerd een reguliere
        # expressie in, en een metateken in het pad maakt de vergelijking stil onbruikbaar.
        pom=${pom#"$REPO_ROOT/"}
        printf '%s\n' "${pom%/pom.xml}"
      done \
    | sort
}

demo_modules() {
  local uit_reactor uit_schijf

  uit_reactor=$(reactor_demo_modules)
  uit_schijf=$(schijf_demo_modules)

  # Leeg betekent "niets vastgesteld", niet "er zijn er geen": een kapotte sed, een verschoven
  # werkmap of een lege demo-root geeft dezelfde lege lijst als een correcte meting.
  if [ -z "$uit_reactor" ]; then
    echo "FOUT: geen enkele <module>demo/…</module> in $REPO_ROOT/pom.xml — de demo-modulelijst meet niets." >&2

    return 1
  fi

  if [ "$uit_reactor" != "$uit_schijf" ]; then
    echo "FOUT: de demo-modules in de reactor en op schijf lopen uiteen." >&2
    echo "  reactor: $(tr '\n' ' ' <<<"$uit_reactor")" >&2
    echo "  schijf:  $(tr '\n' ' ' <<<"$uit_schijf")" >&2

    return 1
  fi

  printf '%s\n' "$uit_reactor"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  demo_modules
fi
