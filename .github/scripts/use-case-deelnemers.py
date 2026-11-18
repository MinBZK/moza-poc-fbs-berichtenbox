#!/usr/bin/env python3
"""Legt elk Mermaid-sequence diagram in de workspace-documentatie tegen het C4-model.

    use-case-deelnemers.py <workspace.dsl> <workspace-docs-map>

De diagrammen van de use cases staan als Mermaid in de markdown naast het model, niet in het model
zelf: een Structurizr-`dynamic`-view kent geen alternatieven of herhaling, en juist de foutpaden
zijn wat een use case moet laten zien. Het risico daarvan is dat de deelnemers uit de pas gaan
lopen met het model. Dit script houdt ze gelijk, voor elk sequence diagram in elke `*.md` van de
map:

- elke `participant` en `actor` draagt als label exact de naam van een element uit het model, of
  die naam zonder toevoeging tussen haakjes. "Berichtenmagazijn" staat zo voor
  "Berichtenmagazijn (BBO-gehost)" én "Berichtenmagazijn (eigen gehost)": de koppelvlakken van een
  magazijn zijn stelselafspraken, dus een use case geldt voor elk magazijn, niet voor één hosting;
- elke pijl, notitie en (de)activatie noemt alleen gedeclareerde deelnemers. Mermaid maakt een
  ongedeclareerde deelnemer stil aan, en die zou dan langs de naamcontrole glippen;
- elke use-case-pagina (`*-uc<n>-*.md`) heeft minstens één sequence diagram.

Het script leest een strikte deelverzameling van Mermaid: één statement per regel, de kop
`sequenceDiagram` op een eigen regel, en geen directives, frontmatter, boxen, `;` of `#`. Mermaid
kent daar elk een eigen leesregel voor, en elke leesregel die de guard niet precies zo nabootst, is
een plek waar een deelnemer ongezien kan staan. Wat buiten de deelverzameling valt, en elke regel
die het script niet herkent, is daarom een fout. Vindt het script geen use-case-pagina of geen
enkel element, dan faalt het ook: dan meet het niets.
"""

import re
import sys
from pathlib import Path

# `container "Naam"` zonder identifier ertussen: zo vallen view-definities als
# `container bboMagazijn "Sleutel"` buiten de vangst.
ELEMENT = re.compile(r'\b(?:person|softwareSystem|container|component)\s+"((?:[^"\\]|\\.)+)"')
VERKORT = re.compile(r"^(.+?)\s+\([^()]*\)$")
USE_CASE_PAGINA = re.compile(r"^\d+-uc\d+-.+\.md$")
FENCE_OPEN = re.compile(r"^\s*(`{3,}|~{3,})\s*mermaid\b", re.IGNORECASE)
FENCE = re.compile(r"^\s*(`{3,}|~{3,})\s*$")

SEQUENCE = re.compile(r"\bsequenceDiagram\b", re.IGNORECASE)
KOP = re.compile(r"^sequenceDiagram$", re.IGNORECASE)
# Tekens waar Mermaid een statement of regel anders afbreekt dan "één statement per regel": `;`
# scheidt statements, `#` opent commentaar of een entity, `%%{` een directive, en de rest zijn
# regelscheidingen die JavaScript wel kent en een regel-voor-regel-lezing niet.
BUITEN_DEELVERZAMELING = re.compile(r"[;#  \x0b\x0c\x85]|%%\{")
DECLARATIE = re.compile(r"^(?:create\s+)?(participant|actor)\s+(.+?)(?:\s+as\s+(.+?))?$", re.IGNORECASE)
PIJL = r"(?:<<-->>|<<->>|-->>|->>|-->|->|--x|-x|--\)|-\))"
PIJLREGEL = re.compile(rf"^(.+?)\s*{PIJL}[+-]?\s*(.+?)\s*:")
NOTITIE = re.compile(r"^note\s+(?:over|left\s+of|right\s+of)\s+([^:]+?)\s*:", re.IGNORECASE)
NOEMT_EEN = re.compile(r"^(?:activate|deactivate|destroy|links?|properties|details)\s+([^:]+?)\s*(?::.*)?$", re.IGNORECASE)
# Een sleutelwoord telt alleen met witruimte of het regeleinde erna: `Partner->>X: y` is voor Mermaid
# een pijl van een deelnemer die Partner heet, en `alt->>X: y` kan Mermaid niet lezen. Alleen met een
# spatie of dubbele punt erna is `title` een titel, en `accTitle`/`accDescr` alleen met `:`.
STRUCTUUR = re.compile(
    r"^(?:(?:end|autonumber|alt|else|opt|loop|par|par_over|and|critical|option|break|rect)(?=\s|$).*"
    r"|title(?:\s.*|:.*)|acctitle\s*:.*|accdescr\s*:.*)$",
    re.IGNORECASE,
)
NIET_ONDERSTEUND = re.compile(r"^(?:box|accdescr\s*\{)", re.IGNORECASE)


def regels_van(tekst: str) -> list[str]:
    """Splitst alleen op een regeleinde, zoals Mermaid en Structurizr: `splitlines()` splitst ook op
    tekens als U+2028, en dan ziet de guard twee regels waar Mermaid er één leest."""
    return tekst.replace("\r\n", "\n").replace("\r", "\n").split("\n")


def zonder_commentaar(dsl: str) -> list[str]:
    """Laat `//`- en `#`-regels en `/* */`-blokken weg, zodat een uitgecommentarieerd element niet
    meetelt. Dit volgt de parser van Structurizr: commentaar is per regel. Een regel die met `/*`
    begint én op `*/` eindigt, is één commentaarregel, ook binnen een blok. Een regel die alleen met
    `/*` begint, opent een (geneste) context, en een regel die op `*/` eindigt, sluit de binnenste.
    Zo opent een `/*` binnen een string (`"services/*/pom.xml"`) niets."""
    regels: list[str] = []
    diepte = 0

    for regel in regels_van(dsl):
        kaal = regel.strip()

        if re.match(r"^(?://|#)", kaal) or (kaal.startswith("/*") and kaal.endswith("*/")):
            continue

        if kaal.startswith("/*"):
            diepte += 1
        elif diepte:
            diepte -= kaal.endswith("*/")
        else:
            regels.append(regel)

    return regels


def lees_model(dsl: Path) -> set[str]:
    """De namen van de elementen in het model, plus per naam met een toevoeging tussen haakjes ook
    de naam zonder die toevoeging."""
    namen = {element.group(1) for regel in zonder_commentaar(dsl.read_text(encoding="utf-8"))
             for element in ELEMENT.finditer(regel)}
    return namen | {kort.group(1) for naam in namen if (kort := VERKORT.match(naam))}


def mermaid_blokken(tekst: str) -> tuple[list[tuple[int, list[str]]], int | None]:
    """Geeft per Mermaid-blok de regel van de opening en de regels erbinnen, plus de regel van
    een blok dat nooit sluit: markdown rendert dat tot het einde van de pagina."""
    blokken: list[tuple[int, list[str]]] = []
    huidig: list[str] | None = None
    fence = ""
    start = 0

    for nummer, regel in enumerate(regels_van(tekst), start=1):
        if huidig is None:
            opening = FENCE_OPEN.match(regel)

            if opening:
                huidig = []
                fence = opening.group(1)
                start = nummer
        else:
            sluiting = FENCE.match(regel)

            if sluiting and sluiting.group(1)[0] == fence[0] and len(sluiting.group(1)) >= len(fence):
                blokken.append((start, huidig))
                huidig = None
            else:
                huidig.append(regel)

    return blokken, (start if huidig is not None else None)


def toets_blok(bestand: str, start: int, regels: list[str], model: set[str]) -> tuple[list[str], int] | None:
    """Toetst één Mermaid-blok. `None` als het geen sequence diagram is; een blok dat het woord
    `sequenceDiagram` noemt maar niet in de ondersteunde vorm begint, is een fout, niet iets anders."""
    if not any(SEQUENCE.search(regel) for regel in regels):
        return None

    fouten: list[str] = []
    gedeclareerd: set[str] = set()
    genoemd: list[tuple[str, str]] = []
    labels = 0
    kop = next(
        (i for i, regel in enumerate(regels)
         if regel.strip() and (not regel.strip().startswith("%%") or regel.strip().startswith("%%{"))),
        None,
    )

    if kop is None or not KOP.match(regels[kop].strip()):
        regel = start + 1 + (kop or 0)
        fouten.append(f"{bestand}:{regel}: een sequence diagram begint met `sequenceDiagram` op een eigen regel, met alleen lege regels of `%%`-commentaar ervoor")
        return fouten, 0

    for offset, regel in enumerate(regels, start=1):
        statement = regel.strip()
        waar = f"{bestand}:{start + offset}"

        if offset <= kop + 1 or not statement or (statement.startswith("%%") and not statement.startswith("%%{")):
            continue

        declaratie = DECLARATIE.match(statement)
        pijlregel = PIJLREGEL.match(statement)
        notitie = NOTITIE.match(statement)
        noemt = NOEMT_EEN.match(statement)

        if BUITEN_DEELVERZAMELING.search(statement) or NIET_ONDERSTEUND.match(statement):
            fouten.append(f"{waar}: buiten de ondersteunde Mermaid (geen `;`, `#`, directive, box of meerregelige accDescr): '{statement}'")
        elif declaratie:
            alias = declaratie.group(2)
            label = declaratie.group(3) or alias
            gedeclareerd.add(alias)
            labels += 1

            if label not in model:
                fouten.append(f"{waar}: {declaratie.group(1).lower()} '{label}' is geen element uit het model")
        elif STRUCTUUR.match(statement):
            continue
        elif pijlregel:
            genoemd += [(pijlregel.group(1), waar), (pijlregel.group(2), waar)]
        elif notitie:
            genoemd += [(d.strip(), waar) for d in notitie.group(1).split(",")]
        elif noemt:
            genoemd.append((noemt.group(1), waar))
        else:
            fouten.append(f"{waar}: regel niet herkend, en kan dus een deelnemer bevatten: '{statement}'")

    for deelnemer, waar in genoemd:
        if deelnemer not in gedeclareerd:
            fouten.append(f"{waar}: '{deelnemer}' is niet gedeclareerd als participant of actor")

    if labels == 0:
        fouten.append(f"{bestand}:{start}: sequence diagram zonder deelnemers")

    return fouten, labels


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("gebruik: use-case-deelnemers.py <workspace.dsl> <workspace-docs-map>", file=sys.stderr)
        return 2

    dsl, docs = Path(argv[1]), Path(argv[2])

    if not dsl.is_file() or not docs.is_dir():
        print(f"FOUT: {dsl} is geen bestand of {docs} is geen map.", file=sys.stderr)
        return 2

    model = lees_model(dsl)

    if not model:
        print(f"FOUT: geen enkel element gevonden in {dsl}; deze controle meet niets.")
        return 1

    fouten: list[str] = []
    diagrammen = 0
    labels = 0
    pagina_s = 0

    for pagina in sorted(docs.glob("*.md")):
        blokken, open_blok = mermaid_blokken(pagina.read_text(encoding="utf-8"))
        sequence = 0

        if open_blok is not None:
            fouten.append(f"{pagina}:{open_blok}: Mermaid-blok zonder afsluitende fence")

        for start, regels in blokken:
            uitkomst = toets_blok(str(pagina), start, regels, model)

            if uitkomst is None:
                continue

            sequence += 1
            fouten += uitkomst[0]
            labels += uitkomst[1]

        diagrammen += sequence

        if USE_CASE_PAGINA.match(pagina.name):
            pagina_s += 1

            if not sequence:
                fouten.append(f"{pagina}: use-case-pagina zonder sequence diagram")

    # Zonder use-case-pagina meet de controle niets. Zijn er wel pagina's maar geen diagrammen, dan
    # staat elke pagina hierboven al als fout genoteerd.
    if pagina_s == 0:
        print(f"FOUT: geen use-case-pagina (*-uc<n>-*.md) gevonden in {docs}; deze controle meet niets.")
        return 1

    if fouten:
        print("FOUT: de sequence diagrams wijken af van het model:")

        for fout in fouten:
            print(f"  {fout}")

        return 1

    print(f"{pagina_s} use-case-pagina's, {diagrammen} sequence diagrams, {labels} deelnemers: alle in het model.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
