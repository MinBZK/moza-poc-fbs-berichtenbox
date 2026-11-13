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

# Een model met twee views waarvan de definitie (`container winkel "Magazijn"`) op een element-
# declaratie lijkt, uitgecommentarieerde elementen in elke vorm die Structurizr kent, strings met
# `\"`, `{` en `/*`, en een tab: geen daarvan mag een elementnaam opleveren of er een laten vallen.
cat > "$werkmap/workspace.dsl" <<'EOF'
workspace {
    model {
        klant = person "Klant"
        // oud = container "Oud"
        # hekje = container "Hekje"
        /* ouder = container "Ouder" {
        } */
        /*
        midden = container "Midden"
        /* genest, op één regel */
        /*
        diep = container "Diep"
        */
        // een regel die eindigt op */
        verder = container "Verder"
        */
        /* opent toch */ want de regel eindigt niet op het sluitteken
        inline = container "Inline"
        sluit */ niet, want de regel eindigt er niet op
        midsluit = container "Midsluit"
        */
        /* los blokcommentaar op één regel */
        winkel = softwareSystem "Winkel" "Toont \"{\" in de kassa" {
            properties {
                "bron" "urn:{oin:winkel"
                "build" "services/*/pom.xml"
                "ook" "libraries/*/pom.xml"
            }
            kassa = container "Kassa" {
                bon = component "Bonprinter"
            }
            voorraad = container	"Voorraad"
            kast = container "Kast \"oud\""
        }
        /* nog een blokcommentaar */
        bank = softwareSystem "Bank"
        noord = softwareSystem "Filiaal (noord)"
        zuid = softwareSystem "Filiaal (zuid)"
        toonbank = softwareSystem "Toonbank (oud) (2)"
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
    participant KA as Kassa
    participant VO as Voorraad
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

# voeg_in <docs> <regel>: zet een regel in het diagram, direct na de eerste pijl.
voeg_in() {
  printf '%s\n' "$2" > "$werkmap/regel"
  sed -i "/^    K->>KA: Betaalt$/r $werkmap/regel" "$1/01-uc1-betalen.md"
}

# overzicht <docs> <inhoud van het Mermaid-blok>: een gewone pagina met één blok.
overzicht() {
  printf '# Overzicht\n\n```mermaid\n%s\n```\n' "$2" > "$1/00-overzicht.md"
}

# 1. Gezonde fixture.
docs=$(nieuwe_docs gezond)
draai "$werkmap/workspace.dsl" "$docs"
verwacht "gezonde fixture slaagt" 0 "1 use-case-pagina's, 1 sequence diagrams, 4 deelnemers: alle in het model."

# 2. Label dat geen element is, ook bij hoofdletterverschil of een deel van een naam.
for label in 'Kassa 2' 'kassa' 'Kas' 'Magazijn'; do
  docs=$(nieuwe_docs "onbekend-label-$(printf '%s' "$label" | od -An -tx1 | tr -d ' \n')")
  sed -i "s/participant KA as Kassa/participant KA as $label/" "$docs/01-uc1-betalen.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "label '$label' is geen element (een view-sleutel telt niet)" 1 "participant '$label' is geen element uit het model"
done

# Een naam met `\"` erin is de hele naam, niet het stuk tot de eerste `"`.
docs=$(nieuwe_docs escape-in-naam)
voeg_in "$docs" '    participant KT as Kast \'
draai "$werkmap/workspace.dsl" "$docs"
verwacht "een naam stopt niet bij een ge-escapet aanhalingsteken" 1 "participant 'Kast \\' is geen element uit het model"

docs=$(nieuwe_docs onbekende-actor)
sed -i 's/actor K as Klant/actor K as Klant van de winkel/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "onbekende actor faalt" 1 "actor 'Klant van de winkel' is geen element uit het model"

# 3. Participant zonder alias: de id is het label; een alias die een elementnaam is, telt niet.
docs=$(nieuwe_docs id-als-label)
sed -i 's/participant Bonprinter/participant Printer/; s/KA-)Bonprinter/KA-)Printer/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "id zonder alias wordt als label getoetst" 1 "participant 'Printer' is geen element uit het model"

docs=$(nieuwe_docs alias-is-element)
sed -i 's/participant KA as Kassa/participant Kassa as Kasregister/; s/K->>KA: Betaalt/K->>Kassa: Betaalt/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "label telt, ook als de alias een elementnaam is" 1 "participant 'Kasregister' is geen element uit het model"

# 4. Mermaid is hoofdletterongevoelig en staat spaties in een id toe.
for regel in 'Participant ZZ as Onbekend' 'ACTOR ZZ as Onbekend' 'participant Bestaat Niet'; do
  docs=$(nieuwe_docs "declaratie-$(printf '%s' "$regel" | od -An -tx1 | tr -d ' \n')")
  voeg_in "$docs" "    $regel"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "'$regel' wordt getoetst" 1 "is geen element uit het model"
done

# 5. Ongedeclareerde deelnemer in een pijl, per pijlsoort, als ontvanger en als afzender.
for pijl in '->>' '-->>' '->' '-->' '-x' '--x' '-)' '--)' '->>+' '-->>-' '<<->>' '<<-->>'; do
  docs=$(nieuwe_docs "ongedeclareerd-$(printf '%s' "$pijl" | od -An -tx1 | tr -d ' \n')")
  voeg_in "$docs" "    KA${pijl}ZZ: Stiekem"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "ongedeclareerde ontvanger met pijl $pijl faalt" 1 "'ZZ' is niet gedeclareerd"
done

for regel in 'XX->>KA: Betaalt' 'Bestaat Niet->>KA: y' 'KA->>Bestaat Niet: y'; do
  docs=$(nieuwe_docs "afzender-$(printf '%s' "$regel" | od -An -tx1 | tr -d ' \n' | cut -c1-30)")
  voeg_in "$docs" "    $regel"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "'$regel' met een ongedeclareerde deelnemer faalt" 1 "is niet gedeclareerd"
done

# 6. Notities, (de)activatie, en namen die met een sleutelwoord beginnen.
for regel in 'Note over KA,ZZ: Samen' 'Note right of ZZ: z' 'note left of ZZ: z' 'activate ZZ' 'deactivate ZZ' \
             'destroy ZZ' 'link ZZ: a @ https://example.org' 'Title->>ZZ: x' 'TITLE->>ZZ: x' 'accDescr->>ZZ: x' \
             'accTitle->>ZZ: x' 'Partner->>ZZ: x' 'Alternatief->>ZZ: x' 'AutonumberX->>ZZ: x' 'alt->>ZZ: x'; do
  docs=$(nieuwe_docs "noemt-$(printf '%s' "$regel" | od -An -tx1 | tr -d ' \n' | cut -c1-40)")
  voeg_in "$docs" "    $regel"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "'$regel' met een ongedeclareerde deelnemer faalt" 1 "'ZZ' is niet gedeclareerd"
done

# 7. Een tweede declaratie van dezelfde alias wordt ook getoetst: Mermaid toont het laatste label.
docs=$(nieuwe_docs herdeclaratie)
voeg_in "$docs" "    participant KA as Onbekend"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "herdeclaratie met een onbekend label faalt" 1 "participant 'Onbekend' is geen element uit het model"

# 8. Een regel die de guard niet kent, faalt in plaats van stil overgeslagen te worden.
docs=$(nieuwe_docs onherkend)
voeg_in "$docs" "    KA=>>ZZ: x"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "onherkende regel faalt" 1 "regel niet herkend"

# 9. Buiten de deelverzameling: elk van deze vormen kent in Mermaid een eigen leesregel.
for regel in 'KA->>K: a; KA->>ZZ: stiekem' 'accTitle: Betalen; participant ZZ as Klant' \
             'Note over K: zie #1' 'KA->>K: zie #35; nummer' '%%{init: {}}%% KA->>VO: x' \
             'box Winkel' 'Box Winkel' 'accDescr {' "participant A as Klant"$' '"%% x" \
             "participant A as Klant"$' '"x"; do
  docs=$(nieuwe_docs "buiten-$(printf '%s' "$regel" | od -An -tx1 | tr -d ' \n' | cut -c1-40)")
  voeg_in "$docs" "    $regel"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "'${regel:0:30}' valt buiten de ondersteunde Mermaid" 1 "buiten de ondersteunde Mermaid"
done

# Een directive of frontmatter vóór de kop, of iets op de regel van de kop, is geen ondersteunde kop.
for blok in $'%%{init: {}}%%\nsequenceDiagram\n    participant K as Klant' \
            $'%%{\n  init: {}\n}%%\nsequenceDiagram\n    participant K as Klant' \
            $'---\ntitle: Betalen\n---\nsequenceDiagram\n    participant K as Klant' \
            $'sequenceDiagram participant K as Klant' \
            $'sequenceDiagram;\n    participant K as Klant' \
            $'%%{init: {}}%% sequenceDiagram\n    participant K as Klant' \
            $'flowchart LR\n    A --> B\n%% sequenceDiagram in een ander diagram\nsequenceDiagram'; do
  docs=$(nieuwe_docs "kop-$(printf '%s' "$blok" | od -An -tx1 | tr -d ' \n' | cut -c1-40)")
  overzicht "$docs" "$blok"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "kop '${blok%%$'\n'*}' wordt geweigerd" 1 "begint met \`sequenceDiagram\` op een eigen regel"
done

# 10. Wat wel mag: create, een pijl vóór de declaratie, structuur, titels, commentaar, een leeg blok.
docs=$(nieuwe_docs mag-wel)
voeg_in "$docs" "    create participant NW as Voorraad"
sed -i 's/^    actor K as Klant$/    K->>KA: Eerst\n    actor K as Klant/' "$docs/01-uc1-betalen.md"
sed -i 's/^sequenceDiagram$/%% voorafgaand commentaar\n\nSEQUENCEDIAGRAM/' "$docs/01-uc1-betalen.md"
cat >> "$docs/01-uc1-betalen.md" <<'EOF'

```mermaid
```
EOF
sed -i 's/^    KA->>KA: Controleer$/    KA->>NW: Nieuw\n    alt Gelukt\n        autonumber\n        %% commentaar, ook met ; en #\n    else Mislukt\n        rect rgb(200, 200, 200)\n            KA->>VO: Herstel\n        end\n    end\n    loop Elke dag\n        par Tegelijk\n            K->>KA: a\n        and\n            KA->>K: b\n        end\n    end\n    title Betalen\n    title: Betalen\n    accTitle: Betalen\n    accDescr: De klant betaalt\n    K<<->>KA: Over en weer\n    Note over K, KA: Met spatie\n    link KA: Kassa @ https:\/\/example.org\n    links KA: {"Kassa": "https:\/\/example.org"}\n    properties KA: {"class": "kassa"}\n    details KA: {"x": 1}/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "create, volgorde, structuur, titels, links en een leeg blok mogen" 0 "alle in het model"

# 11. Elk diagram heeft zijn eigen deelnemers: een tweede diagram erft ze niet.
docs=$(nieuwe_docs scope-per-blok)
cat >> "$docs/01-uc1-betalen.md" <<'EOF'

```mermaid
sequenceDiagram
    participant VO as Voorraad
    KA->>VO: Zonder eigen declaratie
```
EOF
draai "$werkmap/workspace.dsl" "$docs"
verwacht "tweede diagram zonder eigen declaratie faalt, met regelnummer" 1 "01-uc1-betalen.md:21: 'KA' is niet gedeclareerd"

# 12. Meerdere fouten worden allemaal gemeld, met regelnummer.
docs=$(nieuwe_docs meerdere)
sed -i 's/participant KA as Kassa/participant KA as Webwinkel/; s/K->>KA: Betaalt/XX->>KA: Betaalt/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "eerste van meerdere fouten gemeld" 1 "01-uc1-betalen.md:6: participant 'Webwinkel'"
verwacht "tweede van meerdere fouten gemeld" 1 "01-uc1-betalen.md:9: 'XX' is niet gedeclareerd"

# 13. Use-case-pagina zonder sequence diagram; een ander soort diagram telt niet.
docs=$(nieuwe_docs zonder-diagram)
printf '# Use case 2: Ruilen\n\nNog geen diagram.\n' > "$docs/02-uc2-ruilen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "use-case-pagina zonder diagram faalt" 1 "02-uc2-ruilen.md: use-case-pagina zonder sequence diagram"

docs=$(nieuwe_docs ander-diagram)
sed -i 's/^sequenceDiagram$/flowchart LR/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "flowchart in plaats van sequence diagram faalt" 1 "use-case-pagina zonder sequence diagram"

# 14. Een uitgecommentarieerd element telt niet, in elke vorm van commentaar.
for naam in Oud Ouder Hekje Midden Diep Verder Inline Midsluit; do
  docs=$(nieuwe_docs "commentaar-$naam")
  voeg_in "$docs" "    participant OU as $naam"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "uitgecommentarieerd element '$naam' telt niet" 1 "participant '$naam' is geen element uit het model"
done

# Na strings met `\"`, `{` en `/*` en na blokcommentaar tellen de elementen gewoon mee.
docs=$(nieuwe_docs na-commentaar)
voeg_in "$docs" "    participant BA as Bank"
voeg_in "$docs" "    participant BO as Bonprinter"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "elementen na strings en blokcommentaar tellen mee" 0 "alle in het model"

# 15. Andere schrijfwijzen van een Mermaid-blok worden ook gevonden, ook op een gewone pagina.
for opening in '~~~mermaid' '````mermaid' '```mermaid title="x"' '```Mermaid'; do
  docs=$(nieuwe_docs "fence-$(printf '%s' "$opening" | od -An -tx1 | tr -d ' \n')")
  sluiting=${opening%%[mM]*}
  printf '# Overzicht\n\n%s\nsequenceDiagram\n    participant X as Onbekend\n%s\n' "$opening" "$sluiting" > "$docs/00-overzicht.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "blok met opening '$opening' wordt getoetst" 1 "participant 'Onbekend' is geen element uit het model"
done

# Een kortere fence of een fence van een ander teken sluit het blok niet; een ingesprongen blok telt.
for blok in $'````mermaid\nsequenceDiagram\n    participant K as Klant\n```\n    participant X as Onbekend\n````' \
            $'```mermaid\nsequenceDiagram\n    participant K as Klant\n~~~\n    participant X as Onbekend\n```' \
            $'- stap\n\n  ```mermaid\n  sequenceDiagram\n      participant X as Onbekend\n  ```'; do
  docs=$(nieuwe_docs "fence-sluit-$(printf '%s' "$blok" | od -An -tx1 | tr -d ' \n' | cut -c1-40)")
  printf '# Overzicht\n\n%s\n' "$blok" > "$docs/00-overzicht.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "blok '${blok%%$'\n'*}' wordt tot de juiste fence gelezen" 1 "participant 'Onbekend' is geen element uit het model"
done

# 16. Een blok dat nooit sluit, of een diagram zonder deelnemers, meet niets.
docs=$(nieuwe_docs niet-gesloten)
printf '# Overzicht\n\n```mermaid\nsequenceDiagram\n    participant K as Klant\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "blok zonder afsluitende fence faalt" 1 "00-overzicht.md:3: Mermaid-blok zonder afsluitende fence"

docs=$(nieuwe_docs leeg-diagram)
overzicht "$docs" "sequenceDiagram"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "sequence diagram zonder deelnemers faalt" 1 "00-overzicht.md:3: sequence diagram zonder deelnemers"

# 17. De naam zonder toevoeging tussen haakjes staat voor elk element met die naam.
for label in 'Filiaal' 'Toonbank (oud)'; do
  docs=$(nieuwe_docs "verkort-$(printf '%s' "$label" | od -An -tx1 | tr -d ' \n')")
  voeg_in "$docs" "    participant FI as $label"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "verkorte naam '$label'" 0 "alle in het model"
done

for label in 'Filiaal (oost)' 'Fili' 'Filiaal (noord' 'Toonbank'; do
  docs=$(nieuwe_docs "verkort-fout-$(printf '%s' "$label" | od -An -tx1 | tr -d ' \n')")
  voeg_in "$docs" "    participant FI as $label"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "'$label' is geen element en geen verkorte naam" 1 "participant '$label' is geen element uit het model"
done

# 18. De telling klopt bij meerdere pagina's en meerdere diagrammen per pagina.
docs=$(nieuwe_docs tellers)
cp "$docs/01-uc1-betalen.md" "$docs/02-uc2-ruilen.md"
sed -n '/^```mermaid$/,/^```$/p' "$docs/01-uc1-betalen.md" >> "$docs/02-uc2-ruilen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "telling over pagina's en diagrammen" 0 "2 use-case-pagina's, 3 sequence diagrams, 12 deelnemers: alle in het model."

# 19. Niets te meten: lege map, alleen niet-use-case-pagina's, of een model zonder elementen.
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

# 20. Gebruik.
draai "$werkmap/workspace.dsl"
verwacht "één argument is een gebruiksfout" 2 "gebruik:"

draai "$werkmap/bestaat-niet.dsl" "$werkmap/leeg"
verwacht "ontbrekend model is een gebruiksfout" 2 "is geen bestand"

draai "$werkmap/workspace.dsl" "$werkmap/bestaat-niet"
verwacht "ontbrekende docs-map is een gebruiksfout" 2 "is geen map"

draai "$werkmap/workspace.dsl" "$werkmap/leeg" extra
verwacht "drie argumenten is een gebruiksfout" 2 "gebruik:"

# 21. De echte documentatie.
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
