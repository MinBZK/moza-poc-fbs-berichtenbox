#!/usr/bin/env python3
"""Controleert dat de sequence diagrams van de use cases alleen elementen uit het C4-model noemen.

    use-case-deelnemers.py <workspace.dsl> <workspace-docs-map>

De diagrammen staan als Mermaid in de markdown naast het model, niet in het model zelf: een
Structurizr-`dynamic`-view kent geen alternatieven of herhaling, en juist de foutpaden zijn wat een
use case moet laten zien. Het risico daarvan is dat de deelnemers uit de pas gaan lopen met het
model. Dit script houdt ze gelijk:

- elke `participant`, `actor` en `box` draagt als label exact de naam van een element uit het model,
  of die naam zonder toevoeging tussen haakjes. "Berichtenmagazijn" staat zo voor
  "Berichtenmagazijn (BBO-gehost)" én "Berichtenmagazijn (eigen gehost)": de koppelvlakken van een
  magazijn zijn stelselafspraken, dus een use case geldt voor elk magazijn, niet voor één hosting;
- een deelnemer binnen een `box` is in het model een onderdeel van het element dat de box noemt;
- elke pijl, notitie en (de)activatie noemt alleen gedeclareerde deelnemers. Mermaid maakt een
  ongedeclareerde deelnemer stil aan, en die zou dan langs de naamcontrole glippen;
- elke use-case-pagina (`*-uc<n>-*.md`) heeft minstens één sequence diagram.

Een regel die het script niet herkent, is een fout en wordt niet overgeslagen: wat niet gelezen
wordt, kan een deelnemer bevatten. Vindt het script geen use-case-pagina of geen enkel element,
dan faalt het ook: dan meet het niets.
"""

import re
import sys
from pathlib import Path

# `container "Naam"` zonder identifier ertussen: zo vallen view-definities als
# `container bboMagazijn "Sleutel"` buiten de vangst.
ELEMENT = re.compile(r'\b(?:person|softwareSystem|container|component)\s+"([^"]+)"')
USE_CASE_PAGINA = re.compile(r"^\d+-uc\d+-.+\.md$")
FENCE_OPEN = re.compile(r"^\s*(`{3,}|~{3,})\s*mermaid\b", re.IGNORECASE)
FENCE = re.compile(r"^\s*(`{3,}|~{3,})\s*$")

# De sequence-lexer van Mermaid is hoofdletterongevoelig, en een id mag spaties bevatten.
DECLARATIE = re.compile(r"^(?:create\s+)?(participant|actor)\s+(.+?)(?:\s+as\s+(.+?))?$", re.IGNORECASE)
BOX = re.compile(r"^box(?:\s+(.*))?$", re.IGNORECASE)
PIJL = r"(?:<<-->>|<<->>|-->>|->>|-->|->|--x|-x|--\)|-\))"
BERICHT = re.compile(rf"^(.+?)\s*{PIJL}[+-]?\s*(.+?)\s*:")
NOTITIE = re.compile(r"^note\s+(?:over|left\s+of|right\s+of)\s+([^:]+?)\s*:", re.IGNORECASE)
NOEMT_EEN = re.compile(r"^(?:activate|deactivate|destroy|links?|properties|details)\s+([^:]+?)\s*(?::.*)?$", re.IGNORECASE)
STRUCTUUR = re.compile(
    r"^(?:end|autonumber\b.*|(?:alt|else|opt|loop|par|par_over|and|critical|option|break|rect)\b.*"
    r"|title\b.*|acctitle\s*:.*|accdescr\b.*)$",
    re.IGNORECASE,
)
# Een kleur vóór het label van een box (`box Aqua Winkel`, `box rgb(1,2,3) Winkel`).
BOX_KLEUR = re.compile(r"^(?:rgba?\([^)]*\)|hsla?\([^)]*\)|#[0-9a-fA-F]{3,8}|[A-Za-z]+)\s+(.+)$")


def zonder_commentaar(dsl: str) -> str:
    """Haalt `/* */`-, `//`- en `#`-commentaar weg, zodat een uitgecommentarieerd element niet meetelt."""
    dsl = re.sub(r"/\*.*?\*/", "", dsl, flags=re.S)
    return "\n".join(r for r in dsl.splitlines() if not re.match(r"^\s*(?://|#)", r))


def lees_model(dsl: Path) -> dict[str, set[str]]:
    """Geeft per elementnaam de namen van de elementen waarin het genest staat."""
    model: dict[str, set[str]] = {}
    stapel: list[str | None] = []

    for regel in zonder_commentaar(dsl.read_text(encoding="utf-8")).splitlines():
        element = ELEMENT.search(regel)

        if element:
            model.setdefault(element.group(1), set()).update(n for n in stapel if n)

        # Een accolade binnen een string (`"urn:{oin}"`) opent geen blok.
        for teken in re.sub(r'"[^"]*"', '""', regel):
            if teken == "{":
                stapel.append(element.group(1) if element else None)
                element = None
            elif teken == "}" and stapel:
                stapel.pop()

    return met_verkorte_namen(model)


def met_verkorte_namen(model: dict[str, set[str]]) -> dict[str, set[str]]:
    """Voegt per naam met een toevoeging tussen haakjes ook de naam zonder die toevoeging toe. Een
    verkorte naam staat voor al die elementen tegelijk, en een onderdeel van één ervan is ook een
    onderdeel van de verkorte naam. Een verkorte naam die zelf al een element is, blijft dat element."""
    verkort: dict[str, set[str]] = {}

    for naam, voorouders in model.items():
        kort = re.match(r"^(.+?)\s+\([^()]*\)$", naam)

        if kort and kort.group(1) not in model:
            verkort.setdefault(kort.group(1), set()).update(voorouders)

    uitgebreid = {naam: set(voorouders) for naam, voorouders in {**model, **verkort}.items()}

    for naam, voorouders in model.items():
        for voorouder in list(voorouders):
            kort = re.match(r"^(.+?)\s+\([^()]*\)$", voorouder)

            if kort and kort.group(1) in verkort:
                uitgebreid[naam].add(kort.group(1))

    return uitgebreid


def mermaid_blokken(tekst: str) -> tuple[list[tuple[int, list[str]]], int | None]:
    """Geeft per Mermaid-blok de regel van de opening en de regels erbinnen, plus de regel van
    een blok dat nooit sluit: markdown rendert dat tot het einde van de pagina."""
    blokken: list[tuple[int, list[str]]] = []
    huidig: list[str] | None = None
    fence = ""
    start = 0

    for nummer, regel in enumerate(tekst.splitlines(), start=1):
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


def diagramtype(regels: list[str]) -> tuple[str, int]:
    """Het eerste woord na lege regels, `%%`-regels en YAML-frontmatter, met de index erna."""
    i = 0
    begonnen = False

    while i < len(regels):
        regel = regels[i].strip()

        if regel == "---" and not begonnen:
            # Frontmatter staat alleen vóór de eerste inhoud.
            einde = next((j for j in range(i + 1, len(regels)) if regels[j].strip() == "---"), len(regels))
            i = einde + 1
            begonnen = True
        elif not regel or regel.startswith("%%"):
            i += 1
        else:
            return regel.split()[0], i + 1

    return "", len(regels)


def box_element(label: str, model: dict[str, set[str]]) -> str | None:
    if label in model:
        return label

    met_kleur = BOX_KLEUR.match(label)

    if met_kleur and met_kleur.group(1) in model:
        return met_kleur.group(1)

    return None


def statements(regels: list[str], eerste: int, start: int) -> list[tuple[str, int]]:
    """Splitst op `;`, de scheiding tussen statements in Mermaid, met het regelnummer erbij."""
    uit: list[tuple[str, int]] = []

    for offset, regel in enumerate(regels[eerste:], start=eerste + 1):
        uit += [(deel.strip(), start + offset) for deel in regel.split(";")]

    return uit


def toets_blok(bestand: str, start: int, regels: list[str], eerste: int, model: dict[str, set[str]]) -> tuple[list[str], int]:
    fouten: list[str] = []
    gedeclareerd: set[str] = set()
    genoemd: list[tuple[str, str]] = []
    in_box = False
    box: str | None = None
    labels = 0

    for statement, regel in statements(regels, eerste, start):
        waar = f"{bestand}:{regel}"

        if not statement or statement.startswith("%%"):
            continue

        declaratie = DECLARATIE.match(statement)
        nieuwe_box = BOX.match(statement)
        bericht = BERICHT.match(statement)
        notitie = NOTITIE.match(statement)
        noemt = NOEMT_EEN.match(statement)

        if declaratie:
            alias = declaratie.group(2)
            label = declaratie.group(3) or alias
            gedeclareerd.add(alias)
            labels += 1

            if label not in model:
                fouten.append(f"{waar}: {declaratie.group(1).lower()} '{label}' is geen element uit het model")
            elif box and box not in model[label]:
                fouten.append(f"{waar}: '{label}' staat in box '{box}', maar is in het model geen onderdeel daarvan")
        elif nieuwe_box:
            # Een box bevat alleen declaraties; de eerstvolgende `end` sluit hem.
            labels += 1
            in_box = True
            box_label = (nieuwe_box.group(1) or "").strip()
            box = box_element(box_label, model)

            if box is None:
                fouten.append(f"{waar}: box '{box_label}' is geen element uit het model")
        elif statement.lower() == "end" and in_box:
            in_box = False
            box = None
        elif STRUCTUUR.match(statement):
            continue
        elif bericht:
            genoemd += [(bericht.group(1), waar), (bericht.group(2), waar)]
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
            soort, eerste = diagramtype(regels)

            if soort.lower() != "sequencediagram":
                continue

            sequence += 1
            blokfouten, bloklabels = toets_blok(str(pagina), start, regels, eerste, model)
            fouten += blokfouten
            labels += bloklabels

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

    print(f"{pagina_s} use-case-pagina's, {diagrammen} sequence diagrams, {labels} deelnemers en boxen: alle in het model.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
