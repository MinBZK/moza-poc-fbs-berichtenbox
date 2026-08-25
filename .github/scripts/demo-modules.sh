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

reactor_demo_modules() {
  sed -n 's:.*<module>\(demo/[^<]*\)</module>.*:\1:p' "$REPO_ROOT/pom.xml" | sort
}

schijf_demo_modules() {
  # `target/` uitsluiten: een build kan daar pom-kopieën achterlaten, en die zijn geen module.
  find "$REPO_ROOT/demo" -name target -prune -o -name pom.xml -print 2>/dev/null \
    | sed "s:^$REPO_ROOT/::; s:/pom\.xml$::" \
    | sort
}

demo_modules() {
  local uit_reactor uit_schijf

  uit_reactor=$(reactor_demo_modules)
  uit_schijf=$(schijf_demo_modules)

  # Leeg betekent "niets vastgesteld", niet "er zijn er geen": een kapotte sed, een verschoven
  # werkmap of een lege demo-wortel geeft dezelfde lege lijst als een correcte meting.
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
