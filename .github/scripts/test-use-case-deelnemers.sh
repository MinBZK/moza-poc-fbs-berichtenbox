#!/usr/bin/env bash
# Unittests voor use-case-deelnemers.py: op handgemaakte fixtures, en op de echte documentatie van
# deze repo. Dat laatste deel vangt wat een fixture nooit ziet: dat de use cases zelf aan de eis
# voldoen, en dat de guard rood wordt zodra een deelnemer in een echt diagram een andere naam krijgt.

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$HERE/../.." && pwd)
readonly HERE REPO_ROOT
readonly SCRIPT="$HERE/use-case-deelnemers.py"

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

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

# draai <dsl> <docs>: zet STATUS en UITVOER.
draai() {
  STATUS=0
  UITVOER=$(python3 -I "$SCRIPT" "$@" 2>&1) || STATUS=$?
}

verwacht() {
  local wat=$1 status=$2 naald=$3

  if [ "$STATUS" -ne "$status" ]; then
    fout "$wat — exit $STATUS, verwacht $status. Uitvoer: $UITVOER"
    return
  fi

  case "$UITVOER" in
    *"$naald"*) ok "$wat" ;;
    *) fout "$wat — '$naald' ontbreekt in: $UITVOER" ;;
  esac
}

# Een model met twee views waarvan de sleutel op een elementnaam lijkt: die mogen niet meetellen.
cat > "$werkmap/workspace.dsl" <<'EOF'
workspace {
    model {
        klant = person "Klant"
        winkel = softwareSystem "Winkel" {
            kassa = container "Kassa" {
                bon = component "Bonprinter"
            }
            voorraad = container "Voorraad"
        }
        klant -> kassa "Betaalt"
    }
    views {
        container winkel "Magazijn" {
            include *
        }
        component kassa "Schap" {
            include *
        }
    }
}
EOF

# nieuwe_docs <naam>: lege docs-map met een gezonde use-case-pagina en een pagina zonder use case.
nieuwe_docs() {
  local map="$werkmap/$1"

  mkdir -p "$map"
  cat > "$map/01-uc1-betalen.md" <<'EOF'
# Use case 1: Betalen

```mermaid
sequenceDiagram
    actor K as Klant
    box Winkel
        participant KA as Kassa
        participant VO as Voorraad
    end
    participant Bonprinter
    K->>KA: Betaalt
    KA->>VO: Boek af
    VO-->>KA: Afgeboekt
    KA-)Bonprinter: Print
    KA->>KA: Controleer
    Note over KA,VO: Samen
    Note right of K: Wacht
```
EOF
  printf '# Overzicht\n\nGeen diagram hier, en dat mag.\n' > "$map/00-overzicht.md"
  echo "$map"
}

# 1. Gezonde fixture.
docs=$(nieuwe_docs gezond)
draai "$werkmap/workspace.dsl" "$docs"
verwacht "gezonde fixture slaagt" 0 "1 use-case-pagina's, 1 sequence diagrams, 5 deelnemers en boxen"

# 2. Label dat geen element is.
docs=$(nieuwe_docs onbekend-label)
sed -i 's/participant KA as Kassa/participant KA as Kassa 2/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "onbekend participant-label faalt" 1 "participant 'Kassa 2' is geen element uit het model"

# 3. Een view-sleutel is geen element.
docs=$(nieuwe_docs view-sleutel)
sed -i 's/participant VO as Voorraad/participant VO as Magazijn/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "view-sleutel telt niet als element" 1 "participant 'Magazijn' is geen element uit het model"

# 4. Actor met een onbekende naam.
docs=$(nieuwe_docs onbekende-actor)
sed -i 's/actor K as Klant/actor K as Klant van de winkel/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "onbekende actor faalt" 1 "actor 'Klant van de winkel' is geen element uit het model"

# 5. Box met een onbekende naam.
docs=$(nieuwe_docs onbekende-box)
sed -i 's/box Winkel/box Webwinkel/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "onbekende box faalt" 1 "box 'Webwinkel' is geen element uit het model"

# 6. Participant zonder alias: de id is het label.
docs=$(nieuwe_docs id-als-label)
sed -i 's/participant Bonprinter/participant Printer/; s/KA-)Bonprinter/KA-)Printer/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "id zonder alias wordt als label getoetst" 1 "participant 'Printer' is geen element uit het model"

# Een alias die toevallig een elementnaam is, maakt een onbekend label niet goed: het label telt.
docs=$(nieuwe_docs alias-is-element)
sed -i 's/participant KA as Kassa/participant Kassa as Kasregister/; s/K->>KA: Betaalt/K->>Kassa: Betaalt/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "label telt, ook als de alias een elementnaam is" 1 "participant 'Kasregister' is geen element uit het model"

# 7. Ongedeclareerde deelnemer in een bericht, per pijlsoort.
for pijl in '->>' '-->>' '->' '-->' '-x' '--x' '-)' '--)' '->>+' '-->>-'; do
  docs=$(nieuwe_docs "ongedeclareerd-$(printf '%s' "$pijl" | od -An -tx1 | tr -d ' \n')")
  printf '    KA%sZZ: Stiekem\n' "$pijl" > "$werkmap/regel"
  sed -i "/^    K->>KA: Betaalt$/r $werkmap/regel" "$docs/01-uc1-betalen.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "ongedeclareerde ontvanger met pijl $pijl faalt" 1 "'ZZ' is niet gedeclareerd"
done

docs=$(nieuwe_docs ongedeclareerde-afzender)
sed -i 's/K->>KA: Betaalt/XX->>KA: Betaalt/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "ongedeclareerde afzender faalt" 1 "'XX' is niet gedeclareerd"

# 8. Ongedeclareerde deelnemer in een notitie, ook als tweede van twee.
docs=$(nieuwe_docs notitie)
sed -i 's/Note over KA,VO: Samen/Note over KA,QQ: Samen/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "ongedeclareerde deelnemer in notitie faalt" 1 "'QQ' is niet gedeclareerd"

# 9. Use-case-pagina zonder sequence diagram, naast een gezonde.
docs=$(nieuwe_docs zonder-diagram)
printf '# Use case 2: Ruilen\n\nNog geen diagram.\n' > "$docs/02-uc2-ruilen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "use-case-pagina zonder diagram faalt" 1 "02-uc2-ruilen.md: use-case-pagina zonder sequence diagram"

# 10. Een ander soort Mermaid-diagram telt niet als sequence diagram.
docs=$(nieuwe_docs ander-diagram)
sed -i 's/^sequenceDiagram$/flowchart LR/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "flowchart in plaats van sequence diagram faalt" 1 "use-case-pagina zonder sequence diagram"

# 11. Meerdere fouten worden allemaal gemeld, met regelnummer.
docs=$(nieuwe_docs meerdere)
sed -i 's/box Winkel/box Webwinkel/; s/K->>KA: Betaalt/XX->>KA: Betaalt/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "eerste van meerdere fouten gemeld" 1 "01-uc1-betalen.md:6: box 'Webwinkel'"
verwacht "tweede van meerdere fouten gemeld" 1 "01-uc1-betalen.md:11: 'XX' is niet gedeclareerd"

# 12. Niets te meten: lege map, alleen niet-use-case-pagina's, of een model zonder elementen.
mkdir -p "$werkmap/leeg"
draai "$werkmap/workspace.dsl" "$werkmap/leeg"
verwacht "lege docs-map faalt" 1 "deze controle meet niets"

mkdir -p "$werkmap/geen-uc"
printf '# Overzicht\n' > "$werkmap/geen-uc/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$werkmap/geen-uc"
verwacht "map zonder use-case-pagina faalt" 1 "deze controle meet niets"

printf 'workspace {\n    model {\n    }\n}\n' > "$werkmap/leeg.dsl"
draai "$werkmap/leeg.dsl" "$(nieuwe_docs leeg-model)"
verwacht "model zonder elementen faalt" 1 "geen enkel element gevonden"

# 13. Gebruik.
draai "$werkmap/workspace.dsl"
verwacht "één argument is een gebruiksfout" 2 "gebruik:"

draai "$werkmap/bestaat-niet.dsl" "$werkmap/leeg"
verwacht "ontbrekend model is een gebruiksfout" 2 "is geen bestand"

# 14. De echte documentatie.
readonly ECHT_DSL="$REPO_ROOT/docs/architecture/workspace.dsl"
readonly ECHT_DOCS="$REPO_ROOT/docs/architecture/workspace-docs"

draai "$ECHT_DSL" "$ECHT_DOCS"
verwacht "de use cases van deze repo voldoen" 0 "alle in het model"

aantal_uc=$(find "$ECHT_DOCS" -maxdepth 1 -name '*-uc*-*.md' | wc -l)

case "$UITVOER" in
  "$aantal_uc use-case-pagina's"*) ok "elke use-case-pagina van deze repo is meegeteld ($aantal_uc)" ;;
  *) fout "verwacht $aantal_uc use-case-pagina's, uitvoer: $UITVOER" ;;
esac

# Hernoem in een kopie één echte deelnemer: de guard moet dat zien.
cp -r "$ECHT_DOCS" "$werkmap/echt"
eerste=$(find "$werkmap/echt" -maxdepth 1 -name '*-uc*-*.md' | sort | head -n 1)
sed -i '0,/participant \([A-Z]*\) as \(.*\)$/s//participant \1 as \2 (hernoemd)/' "$eerste"
draai "$ECHT_DSL" "$werkmap/echt"
verwacht "hernoemde deelnemer in een echte use case faalt" 1 "(hernoemd)' is geen element uit het model"

echo "ASSERTIES=$asserties"
exit "$mislukt"
