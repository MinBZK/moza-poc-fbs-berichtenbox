#!/usr/bin/env python3
"""Controleert dat de sequence diagrams van de use cases alleen elementen uit het C4-model noemen.

    use-case-deelnemers.py <workspace.dsl> <workspace-docs-map>

De diagrammen staan als Mermaid in de markdown naast het model, niet in het model zelf: een
Structurizr-`dynamic`-view kent geen alternatieven of herhaling, en juist de foutpaden zijn wat een
use case moet laten zien. Het risico daarvan is dat de deelnemers uit de pas gaan lopen met het
model. Dit script houdt ze gelijk:

- elke `participant`, `actor` en `box` draagt als label exact de naam van een element uit het model;
- elk bericht en elke notitie noemt alleen gedeclareerde deelnemers. Mermaid maakt een
  ongedeclareerde deelnemer stil aan, en die zou dan langs de naamcontrole glippen;
- elke use-case-pagina (`*-uc<n>-*.md`) heeft minstens één sequence diagram.

Vindt het script geen use-case-pagina of geen enkel element, dan faalt het: dan meet het niets.
"""

import re
import sys
from pathlib import Path

# `container "Naam"` zonder identifier ertussen: zo vallen view-definities als
# `container bboMagazijn "Sleutel"` buiten de vangst.
ELEMENT = re.compile(r'\b(?:person|softwareSystem|container|component)\s+"([^"]+)"')
USE_CASE_PAGINA = re.compile(r"^\d+-uc\d+-.+\.md$")
BLOK_START = re.compile(r"^\s*```mermaid\s*$")
BLOK_EINDE = re.compile(r"^\s*```\s*$")
DECLARATIE = re.compile(r"^\s*(?:create\s+)?(participant|actor)\s+(\S+)(?:\s+as\s+(.+?))?\s*$")
BOX = re.compile(r"^\s*box\s+(.+?)\s*$")
PIJL = r"(?:-->>|->>|-->|->|--x|-x|--\)|-\))"
BERICHT = re.compile(rf"^\s*([^\s:]+?)\s*{PIJL}[+-]?\s*([^\s:]+?)\s*:")
NOTITIE = re.compile(r"^\s*note\s+(?:over|left of|right of)\s+([^:]+?)\s*:", re.IGNORECASE)


def elementnamen(dsl: Path) -> set[str]:
    return set(ELEMENT.findall(dsl.read_text(encoding="utf-8")))


def mermaid_blokken(tekst: str) -> list[tuple[int, list[str]]]:
    """Geeft per Mermaid-blok het regelnummer van de openingsregel en de regels erbinnen."""
    blokken: list[tuple[int, list[str]]] = []
    huidig: list[str] | None = None
    start = 0

    for nummer, regel in enumerate(tekst.splitlines(), start=1):
        if huidig is None:
            if BLOK_START.match(regel):
                huidig = []
                start = nummer
        elif BLOK_EINDE.match(regel):
            blokken.append((start, huidig))
            huidig = None
        else:
            huidig.append(regel)

    return blokken


def toets_blok(bestand: str, start: int, regels: list[str], namen: set[str]) -> tuple[list[str], int]:
    fouten: list[str] = []
    gedeclareerd: set[str] = set()
    labels = 0

    for offset, regel in enumerate(regels, start=1):
        waar = f"{bestand}:{start + offset}"
        declaratie = DECLARATIE.match(regel)
        box = BOX.match(regel)

        if declaratie:
            alias = declaratie.group(2)
            label = declaratie.group(3) or alias
            gedeclareerd.add(alias)
            labels += 1

            if label not in namen:
                fouten.append(f"{waar}: {declaratie.group(1)} '{label}' is geen element uit het model")
        elif box:
            labels += 1

            if box.group(1) not in namen:
                fouten.append(f"{waar}: box '{box.group(1)}' is geen element uit het model")

    for offset, regel in enumerate(regels, start=1):
        waar = f"{bestand}:{start + offset}"
        bericht = BERICHT.match(regel)
        notitie = NOTITIE.match(regel)

        if bericht:
            genoemd = [bericht.group(1), bericht.group(2)]
        elif notitie:
            genoemd = [d.strip() for d in notitie.group(1).split(",")]
        else:
            continue

        for deelnemer in genoemd:
            if deelnemer not in gedeclareerd:
                fouten.append(f"{waar}: '{deelnemer}' is niet gedeclareerd als participant of actor")

    return fouten, labels


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("gebruik: use-case-deelnemers.py <workspace.dsl> <workspace-docs-map>", file=sys.stderr)
        return 2

    dsl, docs = Path(argv[1]), Path(argv[2])

    if not dsl.is_file() or not docs.is_dir():
        print(f"FOUT: {dsl} is geen bestand of {docs} is geen map.", file=sys.stderr)
        return 2

    namen = elementnamen(dsl)

    if not namen:
        print(f"FOUT: geen enkel element gevonden in {dsl}; deze controle meet niets.")
        return 1

    fouten: list[str] = []
    diagrammen = 0
    labels = 0
    pagina_s = 0

    for pagina in sorted(docs.glob("*.md")):
        sequence = [
            (start, regels)
            for start, regels in mermaid_blokken(pagina.read_text(encoding="utf-8"))
            if regels and regels[0].strip() == "sequenceDiagram"
        ]

        if USE_CASE_PAGINA.match(pagina.name):
            pagina_s += 1

            if not sequence:
                fouten.append(f"{pagina}: use-case-pagina zonder sequence diagram")

        for start, regels in sequence:
            diagrammen += 1
            blokfouten, bloklabels = toets_blok(str(pagina), start, regels, namen)
            fouten.extend(blokfouten)
            labels += bloklabels

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

    print(f"{pagina_s} use-case-pagina's, {diagrammen} sequence diagrams, {labels} deelnemers en boxen: alle in het model.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
