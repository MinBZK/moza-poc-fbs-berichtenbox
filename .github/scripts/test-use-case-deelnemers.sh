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
# declaratie lijkt, uitgecommentarieerde elementen, een accolade binnen een string en een tab: geen
# daarvan mag een elementnaam opleveren of de nesting verstoren.
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
            kluis = container "Kluis (oud)"
        }
        /* nog een blokcommentaar */
        bank = softwareSystem "Bank" {
            grootboek = container "Grootboek"
            koel = container "Voorraad (koel)" {
                thermo = component "Thermometer"
            }
        }
        noord = softwareSystem "Filiaal (noord)" {
            rek = container "Rek"
        }
        zuid = softwareSystem "Filiaal (zuid)" {
            bak = container "Bak"
        }
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

# voeg_in <docs> <regel>: zet een regel in het diagram, direct na de eerste pijl.
voeg_in() {
  printf '%s\n' "$2" > "$werkmap/regel"
  sed -i "/^    K->>KA: Betaalt$/r $werkmap/regel" "$1/01-uc1-betalen.md"
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

# 7. Ongedeclareerde deelnemer in een pijl, per pijlsoort.
for pijl in '->>' '-->>' '->' '-->' '-x' '--x' '-)' '--)' '->>+' '-->>-' '<<->>' '<<-->>'; do
  docs=$(nieuwe_docs "ongedeclareerd-$(printf '%s' "$pijl" | od -An -tx1 | tr -d ' \n')")
  voeg_in "$docs" "    KA${pijl}ZZ: Stiekem"
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

# 11a. Mermaid is hoofdletterongevoelig: een sleutelwoord in hoofdletters ontloopt de controle niet.
for regel in 'Participant ZZ as Onbekend' 'ACTOR ZZ as Onbekend'; do
  docs=$(nieuwe_docs "hoofdletters-${regel%% *}")
  voeg_in "$docs" "    $regel"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "'$regel' wordt getoetst" 1 "'Onbekend' is geen element uit het model"
done

docs=$(nieuwe_docs hoofdletters-box)
sed -i 's/box Winkel/Box Webwinkel/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "'Box' wordt getoetst" 1 "box 'Webwinkel' is geen element uit het model"

# 11b. Een id mag spaties bevatten, in een declaratie en in een pijl.
docs=$(nieuwe_docs spatie-in-pijl)
voeg_in "$docs" "    KA->>Bestaat Niet: y"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "spatie in een ongedeclareerde ontvanger" 1 "'Bestaat Niet' is niet gedeclareerd"

docs=$(nieuwe_docs spatie-in-afzender)
voeg_in "$docs" "    Bestaat Niet->>KA: y"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "spatie in een ongedeclareerde afzender" 1 "'Bestaat Niet' is niet gedeclareerd"

docs=$(nieuwe_docs spatie-in-declaratie)
voeg_in "$docs" "    participant Bestaat Niet"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "spatie in een declaratie zonder alias" 1 "participant 'Bestaat Niet' is geen element uit het model"

# 11c. Een `;` scheidt statements: ook het tweede wordt getoetst.
docs=$(nieuwe_docs puntkomma)
voeg_in "$docs" "    KA->>K: a; KA->>ZZ: stiekem"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "statement na een puntkomma wordt getoetst" 1 "'ZZ' is niet gedeclareerd"

# 11d. Een uitgecommentarieerd element telt niet.
for naam in Oud Ouder Hekje Midden Diep Verder; do
  docs=$(nieuwe_docs "commentaar-$naam")
  voeg_in "$docs" "    participant OU as $naam"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "uitgecommentarieerd element '$naam' telt niet" 1 "participant '$naam' is geen element uit het model"
done

# 11e. Een deelnemer in een box hoort in het model bij dat element; dieper genest mag.
docs=$(nieuwe_docs verkeerde-box)
sed -i 's/participant VO as Voorraad/participant VO as Grootboek/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "deelnemer in een box van een ander systeem faalt" 1 "'Grootboek' staat in box 'Winkel'"

docs=$(nieuwe_docs dieper-in-box)
sed -i 's/participant VO as Voorraad/participant VO as Bonprinter/; s/^    participant Bonprinter$/    participant BP as Voorraad/; s/KA-)Bonprinter/KA-)BP/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "component van een container in de box mag" 0 "alle in het model"

docs=$(nieuwe_docs na-de-box)
voeg_in "$docs" "    participant GB as Grootboek"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "na de box geldt de box niet meer" 0 "alle in het model"

for kleur in 'Aqua' 'transparent' 'rgb(1, 2, 3)' 'rgba(1, 2, 3, 0.5)' 'hsl(1, 2%, 3%)'; do
  docs=$(nieuwe_docs "box-kleur-$(printf '%s' "$kleur" | od -An -tx1 | tr -d ' \n')")
  sed -i "s/box Winkel/box $kleur Winkel/" "$docs/01-uc1-betalen.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "box met kleur '$kleur' herkent het element" 0 "alle in het model"
done

# Geen CSS-kleur: Mermaid toont het woord als deel van de titel, en na een `#` niets meer.
for voor in 'Oude' '#ffcc00'; do
  docs=$(nieuwe_docs "box-geen-kleur-$(printf '%s' "$voor" | od -An -tx1 | tr -d ' \n')")
  sed -i "s/box Winkel/box $voor Winkel/" "$docs/01-uc1-betalen.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "box '$voor Winkel' is geen element" 1 "box '$voor Winkel' is geen element uit het model"
done

# Een verkorte naam mag ook als deelnemer in een box staan.
docs=$(nieuwe_docs verkort-in-box)
voeg_in "$docs" "    participant KL as Kluis"
sed -i 's/^        participant VO as Voorraad$/        participant VO as Voorraad\n        participant KL as Kluis/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "'Kluis' voor 'Kluis (oud)' in box 'Winkel'" 0 "alle in het model"

# Een onderdeel van 'Voorraad (koel)' is geen onderdeel van 'Voorraad': die naam is al een element.
docs=$(nieuwe_docs verkort-voorouder-bestaat)
sed -i 's/box Winkel/box Voorraad/; s/participant KA as Kassa/participant KA as Thermometer/; s/^        participant VO as Voorraad$//; s/KA->>VO: Boek af/KA->>KA: Boek af/; s/VO-->>KA: Afgeboekt/KA-->>KA: Afgeboekt/; s/Note over KA,VO: Samen/Note over KA: Samen/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "'Thermometer' hoort niet in box 'Voorraad'" 1 "'Thermometer' staat in box 'Voorraad'"

# Strings met `\"`, `{` of `/*` en blokcommentaar op één regel verstoren de nesting niet.
docs=$(nieuwe_docs dsl-strings)
sed -i 's/participant VO as Voorraad/participant VO as Grootboek/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "nesting blijft kloppen na strings met accolades en globs" 1 "'Grootboek' staat in box 'Winkel'"

docs=$(nieuwe_docs dsl-na-blokcommentaar)
voeg_in "$docs" "    participant BA as Bank"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "element tussen twee blokcommentaren telt mee" 0 "alle in het model"

# 11f. Exacte naam: geen hoofdletterverschil, geen deel van een naam.
for label in voorraad Voor; do
  docs=$(nieuwe_docs "exact-$label")
  sed -i "s/participant VO as Voorraad/participant VO as $label/" "$docs/01-uc1-betalen.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "label '$label' is niet exact 'Voorraad'" 1 "participant '$label' is geen element uit het model"
done

docs=$(nieuwe_docs exact-box)
sed -i 's/box Winkel/box winkel/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "box 'winkel' is niet exact 'Winkel'" 1 "box 'winkel' is geen element uit het model"

# 11g. Notities links en rechts, (de)activatie.
for regel in 'Note right of ZZ: z' 'note left of ZZ: z' 'activate ZZ' 'deactivate ZZ' 'destroy ZZ' \
             'Title->>ZZ: x' 'TITLE->>ZZ: x' 'accDescr->>ZZ: x' 'accTitle->>ZZ: x' \
             'Partner->>ZZ: x' 'Alternatief->>ZZ: x' 'AutonumberX->>ZZ: x' \
             '%%{init: {}}%% KA->>ZZ: x' '%%{init: {}}%% KA->>ZZ: x %%{init: {}}%%'; do
  docs=$(nieuwe_docs "noemt-$(printf '%s' "$regel" | od -An -tx1 | tr -d ' \n')")
  voeg_in "$docs" "    $regel"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "'$regel' met een ongedeclareerde deelnemer faalt" 1 "'ZZ' is niet gedeclareerd"
done

# 11h. Een tweede declaratie van dezelfde alias wordt ook getoetst: Mermaid toont het laatste label.
docs=$(nieuwe_docs herdeclaratie)
voeg_in "$docs" "    participant KA as Onbekend"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "herdeclaratie met een onbekend label faalt" 1 "participant 'Onbekend' is geen element uit het model"

# 11i. Een regel die de guard niet kent, faalt in plaats van stil overgeslagen te worden.
docs=$(nieuwe_docs onherkend)
voeg_in "$docs" "    KA=>>ZZ: x"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "onherkende regel faalt" 1 "regel niet herkend"

# 11j. Wat wel mag: create, een pijl vóór de declaratie, structuur, commentaar, een leeg Mermaid-blok.
docs=$(nieuwe_docs mag-wel)
voeg_in "$docs" "    create participant NW as Voorraad"
sed -i 's/^    actor K as Klant$/    K->>KA: Eerst\n    actor K as Klant/' "$docs/01-uc1-betalen.md"
cat >> "$docs/01-uc1-betalen.md" <<'EOF'

```mermaid
```
EOF
sed -i 's/^    KA->>KA: Controleer$/    KA->>NW: Nieuw\n    alt Gelukt\n        autonumber\n        %% commentaar\n    else Mislukt\n        rect rgb(200, 200, 200)\n            KA->>VO: Herstel\n        end\n    end\n    title Betalen\n    title: Betalen\n    accTitle: Betalen\n    accDescr: De klant betaalt\n    K<<->>KA: Over en weer\n    Note over K, KA: Met spatie\n    link KA: Kassa @ https:\/\/example.org\n    links KA: {"Kassa": "https:\/\/example.org"}\n    properties KA: {"class": "kassa"}\n    details KA: {"x": 1}\n    %%{init: {}}%% KA->>VO: Na een directive/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "create, volgorde, structuur, titels, links en een leeg blok mogen" 0 "alle in het model"

# 11k. Elk diagram heeft zijn eigen deelnemers: een tweede diagram erft ze niet.
docs=$(nieuwe_docs scope-per-blok)
cat >> "$docs/01-uc1-betalen.md" <<'EOF'

```mermaid
sequenceDiagram
    participant VO as Voorraad
    KA->>VO: Zonder eigen declaratie
```
EOF
draai "$werkmap/workspace.dsl" "$docs"
verwacht "tweede diagram zonder eigen declaratie faalt" 1 "01-uc1-betalen.md:23: 'KA' is niet gedeclareerd"

# 11l. Andere schrijfwijzen van een Mermaid-blok worden ook gevonden, ook op een gewone pagina.
for opening in '~~~mermaid' '````mermaid' '```mermaid title="x"' '```Mermaid'; do
  docs=$(nieuwe_docs "fence-$(printf '%s' "$opening" | od -An -tx1 | tr -d ' \n')")
  sluiting=${opening%%[mM]*}
  printf '# Overzicht\n\n%s\nsequenceDiagram\n    participant X as Onbekend\n%s\n' "$opening" "$sluiting" > "$docs/00-overzicht.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "blok met opening '$opening' wordt getoetst" 1 "participant 'Onbekend' is geen element uit het model"
done

# Een kortere of andere fence sluit het blok niet: wat erna komt, hoort er nog bij.
docs=$(nieuwe_docs fence-ongelijk)
printf '# Overzicht\n\n````mermaid\nsequenceDiagram\n    participant K as Klant\n```\n    participant X as Onbekend\n````\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "kortere fence sluit het blok niet" 1 "participant 'Onbekend' is geen element uit het model"

# 11m. Wat vóór `sequenceDiagram` mag staan.
for voor in '%%{init: {"theme": "dark"}}%%' $'---\ntitle: Betalen\n---' ''; do
  docs=$(nieuwe_docs "voorloop-$(printf '%s' "$voor" | od -An -tx1 | tr -d ' \n' | cut -c1-20)")
  printf '# Overzicht\n\n```mermaid\n%s\nsequenceDiagram\n    participant X as Onbekend\n```\n' "$voor" > "$docs/00-overzicht.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "diagram met voorloop '${voor%%$'\n'*}' wordt getoetst" 1 "participant 'Onbekend' is geen element uit het model"
done

# Alles op de regel van de kop telt mee, ook als er een directive vóór de kop staat.
for kop in 'sequenceDiagram participant X as Onbekend' 'sequenceDiagram; participant X as Onbekend' \
           '%%{init: {}}%% sequenceDiagram' $'%%{\n  init: {}\n}%%\nsequenceDiagram'; do
  docs=$(nieuwe_docs "kop-$(printf '%s' "$kop" | od -An -tx1 | tr -d ' \n' | cut -c1-40)")
  printf '# Overzicht\n\n```mermaid\n%s\n    participant K as Klant\n    participant X as Onbekend\n```\n' "$kop" > "$docs/00-overzicht.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "diagram met kop '${kop%%$'\n'*}' wordt getoetst" 1 "participant 'Onbekend' is geen element uit het model"
done

docs=$(nieuwe_docs kop-twee-statements)
printf '# Overzicht\n\n```mermaid\nsequenceDiagram participant K as Klant; participant X as Onbekend\n```\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "tweede statement op de kopregel wordt getoetst" 1 "participant 'Onbekend' is geen element uit het model"

docs=$(nieuwe_docs kop-na-commentaar)
printf '# Overzicht\n\n```mermaid\n%%%% een opmerking\nsequenceDiagram\n    participant X as Onbekend\n```\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "commentaarregel vóór de kop" 1 "participant 'Onbekend' is geen element uit het model"

# Een directive over meerdere regels houdt de regelnummers erna gelijk.
docs=$(nieuwe_docs directive-regelnummer)
printf '# Overzicht\n\n```mermaid\nsequenceDiagram\n    %%%%{\n      init: {}\n    }%%%%\n    participant X as Onbekend\n```\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "regelnummer na een directive over meerdere regels" 1 "00-overzicht.md:8: participant 'Onbekend'"

# Ook als tweede blok op een use-case-pagina, waar een overgeslagen blok niet als ontbrekend opvalt.
docs=$(nieuwe_docs kop-tweede-blok)
printf '\n```mermaid\nsequenceDiagram; participant X as Onbekend\n```\n' >> "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "tweede blok met statement op de kop wordt getoetst" 1 "01-uc1-betalen.md:21: participant 'Onbekend'"

# Een fence van een ander teken sluit het blok niet; een ingesprongen blok telt ook.
docs=$(nieuwe_docs fence-ander-teken)
printf '# Overzicht\n\n```mermaid\nsequenceDiagram\n    participant K as Klant\n~~~\n    participant X as Onbekend\n```\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "tilde-fence sluit een backtick-blok niet" 1 "participant 'Onbekend' is geen element uit het model"

docs=$(nieuwe_docs fence-ingesprongen)
printf '# Overzicht\n\n- stap\n\n  ```mermaid\n  sequenceDiagram\n      participant X as Onbekend\n  ```\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "ingesprongen blok in een lijst wordt getoetst" 1 "participant 'Onbekend' is geen element uit het model"

# Zonder woord direct na `%%{` is het voor Mermaid geen directive: de regels ertussen tellen mee.
docs=$(nieuwe_docs geen-directive)
printf '# Overzicht\n\n```mermaid\nsequenceDiagram\n    participant K as Klant\n    %%%%{ - begin\n    Onbekend->>K: x\n    %%%% einde }%%%%\n```\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "regels tussen een schijn-directive worden getoetst" 1 "'Onbekend' is niet gedeclareerd"

# Mermaid splitst alleen op een regeleinde, niet op U+2028: wat erachter staat, hoort bij het label.
docs=$(nieuwe_docs regelscheiding)
voeg_in "$docs" "    participant A as Klant"$' '"%% x"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "U+2028 splitst het label niet" 1 "is geen element uit het model"

# Een meerregelige accDescr zou regels verbergen; de guard weigert hem.
docs=$(nieuwe_docs accdescr-blok)
voeg_in "$docs" "    accDescr {"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "meerregelige accDescr wordt geweigerd" 1 "meerregelige accDescr niet ondersteund"

# Boxkleuren zoals Mermaid ze splitst: alleen een geldige kleur valt weg, ook bij een lege box.
docs=$(nieuwe_docs box-kleur-lege-box)
voeg_in "$docs" "    end"
voeg_in "$docs" "    box Aqua Webwinkel"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "lege box met kleur en een onbekende naam faalt" 1 "box 'Aqua Webwinkel' is geen element uit het model"

for label in 'rgbx Winkel' 'rgb(1, 2, 3) Kluis (oud)' 'rgb(1, 2, 3) x) Winkel'; do
  docs=$(nieuwe_docs "box-schijnkleur-$(printf '%s' "$label" | od -An -tx1 | tr -d ' \n')")
  sed -i "s/box Winkel/box $label/" "$docs/01-uc1-betalen.md"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "box '$label': Mermaid toont het hele label" 1 "box '$label' is geen element uit het model"
done

# 11n. Een blok dat nooit sluit, of een diagram zonder deelnemers, meet niets.
docs=$(nieuwe_docs niet-gesloten)
printf '# Overzicht\n\n```mermaid\nsequenceDiagram\n    participant K as Klant\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "blok zonder afsluitende fence faalt" 1 "00-overzicht.md:3: Mermaid-blok zonder afsluitende fence"

docs=$(nieuwe_docs leeg-diagram)
printf '# Overzicht\n\n```mermaid\nsequenceDiagram\n```\n' > "$docs/00-overzicht.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "sequence diagram zonder deelnemers faalt" 1 "00-overzicht.md:3: sequence diagram zonder deelnemers"

# 11p. De naam zonder toevoeging tussen haakjes staat voor elk element met die naam.
docs=$(nieuwe_docs verkort)
voeg_in "$docs" "    participant FI as Filiaal"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "'Filiaal' staat voor 'Filiaal (noord)' en 'Filiaal (zuid)'" 0 "alle in het model"

# Alleen de laatste toevoeging valt weg: 'Toonbank (oud) (2)' wordt 'Toonbank (oud)', niet 'Toonbank'.
docs=$(nieuwe_docs verkort-laatste)
voeg_in "$docs" "    participant TB as Toonbank (oud)"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "alleen de laatste toevoeging tussen haakjes valt weg" 0 "alle in het model"

for label in 'Filiaal (oost)' 'Fili' 'Filiaal (noord' 'Toonbank'; do
  docs=$(nieuwe_docs "verkort-fout-$(printf '%s' "$label" | od -An -tx1 | tr -d ' \n')")
  voeg_in "$docs" "    participant FI as $label"
  draai "$werkmap/workspace.dsl" "$docs"
  verwacht "'$label' is geen element en geen verkorte naam" 1 "participant '$label' is geen element uit het model"
done

# In een box met de verkorte naam mag een onderdeel van elk van die elementen staan, maar niets anders.
docs=$(nieuwe_docs verkorte-box)
sed -i 's/box Winkel/box Filiaal/; s/participant KA as Kassa/participant KA as Rek/; s/participant VO as Voorraad/participant VO as Bak/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "onderdelen van 'Filiaal (noord)' en 'Filiaal (zuid)' in box 'Filiaal'" 0 "alle in het model"

docs=$(nieuwe_docs verkorte-box-fout)
sed -i 's/box Winkel/box Filiaal/; s/participant KA as Kassa/participant KA as Rek/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "'Voorraad' hoort niet in box 'Filiaal'" 1 "'Voorraad' staat in box 'Filiaal'"

# Een verkorte naam die zelf een element is, blijft dat element: 'Voorraad' is niet ook 'Voorraad (koel)'.
docs=$(nieuwe_docs verkort-bestaat)
sed -i 's/box Winkel/box Bank/; s/participant KA as Kassa/participant KA as Grootboek/' "$docs/01-uc1-betalen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "'Voorraad' hoort niet in box 'Bank', ook al staat 'Voorraad (koel)' daar" 1 "'Voorraad' staat in box 'Bank'"

# 11o. De telling klopt bij meerdere pagina's en meerdere diagrammen per pagina.
docs=$(nieuwe_docs tellers)
cp "$docs/01-uc1-betalen.md" "$docs/02-uc2-ruilen.md"
sed -n '/^```mermaid$/,/^```$/p' "$docs/01-uc1-betalen.md" >> "$docs/02-uc2-ruilen.md"
draai "$werkmap/workspace.dsl" "$docs"
verwacht "telling over pagina's en diagrammen" 0 "2 use-case-pagina's, 3 sequence diagrams, 15 deelnemers en boxen: alle in het model."

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

draai "$werkmap/workspace.dsl" "$werkmap/bestaat-niet"
verwacht "ontbrekende docs-map is een gebruiksfout" 2 "is geen map"

draai "$werkmap/workspace.dsl" "$werkmap/leeg" extra
verwacht "drie argumenten is een gebruiksfout" 2 "gebruik:"

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
