#!/usr/bin/env python3
"""Legt elk Mermaid-sequence diagram in de workspace-documentatie tegen het C4-model.

    use-case-deelnemers.py <workspace.dsl> <workspace-docs-map>

De diagrammen van de use cases staan als Mermaid in de markdown naast het model, niet in het model
zelf: een Structurizr-`dynamic`-view kent geen alternatieven of herhaling, en juist de foutpaden
zijn wat een use case moet laten zien. Het risico daarvan is dat de deelnemers uit de pas gaan
lopen met het model. Dit script houdt ze gelijk, voor elk sequence diagram in elke `*.md` van de
map:

- elke `participant`, `actor` en `box` draagt als label exact de naam van een element uit het model,
  of die naam zonder toevoeging tussen haakjes. "Berichtenmagazijn" staat zo voor
  "Berichtenmagazijn (BBO-gehost)" én "Berichtenmagazijn (eigen gehost)": de koppelvlakken van een
  magazijn zijn stelselafspraken, dus een use case geldt voor elk magazijn, niet voor één hosting;
- een deelnemer binnen een `box` is in het model een onderdeel van het element dat de box noemt;
- elke pijl, notitie en (de)activatie noemt alleen gedeclareerde deelnemers. Mermaid maakt een
  ongedeclareerde deelnemer stil aan, en die zou dan langs de naamcontrole glippen;
- elke use-case-pagina (`*-uc<n>-*.md`) heeft minstens één sequence diagram.

Het script leest zoals de sequence-lexer van Mermaid: hoofdletterongevoelig, `;` als scheiding
tussen statements, spaties in een id, directives overal. Een regel die het niet herkent, is een
fout en wordt niet overgeslagen: wat niet gelezen wordt, kan een deelnemer bevatten. Vindt het
script geen use-case-pagina of geen enkel element, dan faalt het ook: dan meet het niets.
"""

import re
import sys
from pathlib import Path

STRING = r'"(?:[^"\\]|\\.)*"'
# `container "Naam"` zonder identifier ertussen: zo vallen view-definities als
# `container bboMagazijn "Sleutel"` buiten de vangst.
ELEMENT = re.compile(r'\b(?:person|softwareSystem|container|component)\s+"((?:[^"\\]|\\.)+)"')
VERKORT = re.compile(r"^(.+?)\s+\([^()]*\)$")
USE_CASE_PAGINA = re.compile(r"^\d+-uc\d+-.+\.md$")
FENCE_OPEN = re.compile(r"^\s*(`{3,}|~{3,})\s*mermaid\b", re.IGNORECASE)
FENCE = re.compile(r"^\s*(`{3,}|~{3,})\s*$")

# Een directive (`%%{init: ...}%%`) mag overal staan, ook over meerdere regels. Mermaid haalt hem
# weg vóór het lezen en voegt wat ervoor en erna staat samen tot één regel. Dit is de regex van
# Mermaid zelf: zonder woord direct na `%%{` is het geen directive maar gewoon een `%%`-regel, en
# een directive zonder `}%%` loopt door tot het einde van het diagram.
DIRECTIVE = re.compile(r"%%\{\s*(?:\w+\s*:|\w+)\s*(?:\w+|(?:(?!\}%%).|\n)*)?\s*(?:\}%%)?", re.I | re.A)
SEQUENCE_KOP = re.compile(r"^sequenceDiagram\b\s*;?\s*(.*)$", re.IGNORECASE)
DECLARATIE = re.compile(r"^(?:create\s+)?(participant|actor)\s+(.+?)(?:\s+as\s+(.+?))?$", re.IGNORECASE)
BOX = re.compile(r"^box(?:\s+(.*))?$", re.IGNORECASE)
PIJL = r"(?:<<-->>|<<->>|-->>|->>|-->|->|--x|-x|--\)|-\))"
PIJLREGEL = re.compile(rf"^(.+?)\s*{PIJL}[+-]?\s*(.+?)\s*:")
NOTITIE = re.compile(r"^note\s+(?:over|left\s+of|right\s+of)\s+([^:]+?)\s*:", re.IGNORECASE)
NOEMT_EEN = re.compile(r"^(?:activate|deactivate|destroy|links?|properties|details)\s+([^:]+?)\s*(?::.*)?$", re.IGNORECASE)
STRUCTUUR = re.compile(
    r"^(?:end|autonumber\b.*|(?:alt|else|opt|loop|par|par_over|and|critical|option|break|rect)\b.*"
    # Alleen met een spatie of dubbele punt erna is `title` een titel, en `accDescr` alleen met `:` of
    # `{`: `Title->>X: y` is voor Mermaid een pijl van een deelnemer die Title heet.
    r"|title(?:\s.*|:.*)|acctitle\s*:.*|accdescr\s*:.*)$",
    re.IGNORECASE,
)
# Een meerregelige `accDescr {` slokt in Mermaid alles op tot de `}`. Die regels overslaan zou een
# nieuwe plek openen waar een deelnemer ongezien staat, dus de guard weigert de vorm.
ACCDESCR_BLOK = re.compile(r"^accdescr\s*\{", re.IGNORECASE)
# Een kleur vóór het label van een box (`box Aqua Winkel`, `box rgb(1,2,3) Winkel`), gesplitst zoals
# `parseBoxData` in Mermaid dat doet: het eerste woord, of alles tot de laatste `)`. Is dat geen
# geldige kleur, dan hoort het bij de titel. Een `#` opent in Mermaid commentaar, dus `#ffcc00` is
# geen kleur maar het einde van de regel.
BOX_KLEUR = re.compile(r"^((?:rgba?|hsla?)\s*\(.*\)|\w*)(.*)$", re.A)
KLEURFUNCTIE = re.compile(r"(?:rgba?|hsla?)\s*\([^()]*\)", re.I)
CSS_KLEUREN = frozenset("""
    aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet
    brown burlywood cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue
    darkcyan darkgoldenrod darkgray darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange
    darkorchid darkred darksalmon darkseagreen darkslateblue darkslategray darkslategrey darkturquoise
    darkviolet deeppink deepskyblue dimgray dimgrey dodgerblue firebrick floralwhite forestgreen
    fuchsia gainsboro ghostwhite gold goldenrod gray green greenyellow grey honeydew hotpink indianred
    indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan
    lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen
    lightskyblue lightslategray lightslategrey lightsteelblue lightyellow lime limegreen linen magenta
    maroon mediumaquamarine mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue
    mediumspringgreen mediumturquoise mediumvioletred midnightblue mintcream mistyrose moccasin
    navajowhite navy oldlace olive olivedrab orange orangered orchid palegoldenrod palegreen
    paleturquoise palevioletred papayawhip peachpuff peru pink plum powderblue purple rebeccapurple
    red rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue
    slateblue slategray slategrey snow springgreen steelblue tan teal thistle tomato transparent
    turquoise violet wheat white whitesmoke yellow yellowgreen
""".split())


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


def lees_model(dsl: Path) -> dict[str, set[str]]:
    """Geeft per elementnaam de namen van de elementen waarin het genest staat."""
    model: dict[str, set[str]] = {}
    stapel: list[str | None] = []

    for regel in zonder_commentaar(dsl.read_text(encoding="utf-8")):
        element = ELEMENT.search(regel)

        if element:
            model.setdefault(element.group(1), set()).update(n for n in stapel if n)

        # Een accolade binnen een string (`"urn:{oin}"`, ook na een `\"`) opent geen blok.
        for teken in re.sub(STRING, '""', regel):
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
        kort = VERKORT.match(naam)

        if kort and kort.group(1) not in model:
            verkort.setdefault(kort.group(1), set()).update(voorouders)

    uitgebreid = {naam: set(voorouders) for naam, voorouders in {**model, **verkort}.items()}

    for naam, voorouders in model.items():
        for voorouder in list(voorouders):
            kort = VERKORT.match(voorouder)

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


def zonder_directives(regels: list[str]) -> list[tuple[str, int]]:
    """Haalt de directives weg zoals Mermaid: wat ervoor en erna staat, wordt één regel. Geeft per
    regel de index van de oorspronkelijke regel waarop hij begint, zodat een melding naar de goede
    regel wijst."""
    tekst = "\n".join(regels)
    uit: list[tuple[str, int]] = []
    huidig = ""
    begin = 0
    regel = 0
    vorige = 0

    for directive in [*DIRECTIVE.finditer(tekst), None]:
        stuk = tekst[vorige:directive.start() if directive else len(tekst)]

        for n, deel in enumerate(stuk.split("\n")):
            if n:
                uit.append((huidig, begin))
                huidig = ""
                regel += 1
                begin = regel

            huidig += deel

        if directive:
            # De regeleindes binnen een directive vallen weg: de regel loopt door, de telling niet.
            regel += directive.group().count("\n")
            vorige = directive.end()

    uit.append((huidig, begin))
    return uit


def sequence_statements(regels: list[str], start: int) -> list[tuple[str, int]] | None:
    """De statements van een sequence diagram met hun regelnummer, of `None` als het blok geen
    sequence diagram is. Vóór de kop mogen lege regels, `%%`-commentaar en YAML-frontmatter staan;
    wat na `sequenceDiagram` op dezelfde regel volgt, is al een statement."""
    gelezen = zonder_directives(regels)
    i = 0

    if next((r.strip() for r, _ in gelezen if r.strip()), "") == "---":
        eerste = next(j for j, (r, _) in enumerate(gelezen) if r.strip())
        i = next((j for j in range(eerste + 1, len(gelezen)) if gelezen[j][0].strip() == "---"), len(gelezen)) + 1

    while i < len(gelezen) and (not gelezen[i][0].strip() or gelezen[i][0].strip().startswith("%%")):
        i += 1

    kop = SEQUENCE_KOP.match(gelezen[i][0].strip()) if i < len(gelezen) else None

    if kop is None:
        return None

    uit = [(deel.strip(), start + gelezen[i][1] + 1) for deel in kop.group(1).split(";")]

    for tekst, index in gelezen[i + 1:]:
        uit += [(deel.strip(), start + index + 1) for deel in tekst.split(";")]

    return uit


def box_element(label: str, model: dict[str, set[str]]) -> str | None:
    """Het element dat een boxlabel noemt, met een eventuele kleur ervoor weggelaten zoals Mermaid
    dat doet; `None` als de titel die Mermaid toont geen element is."""
    gesplitst = BOX_KLEUR.match(label)
    titel = label

    if gesplitst:
        kleur, rest = gesplitst.group(1), gesplitst.group(2).strip()

        if kleur.lower() in CSS_KLEUREN or KLEURFUNCTIE.fullmatch(kleur):
            titel = rest

    return titel if titel in model else None


def toets_blok(bestand: str, start: int, statements: list[tuple[str, int]], model: dict[str, set[str]]) -> tuple[list[str], int]:
    fouten: list[str] = []
    gedeclareerd: set[str] = set()
    genoemd: list[tuple[str, str]] = []
    in_box = False
    box: str | None = None
    labels = 0

    for statement, regel in statements:
        waar = f"{bestand}:{regel}"

        if not statement or statement.startswith("%%"):
            continue

        declaratie = DECLARATIE.match(statement)
        nieuwe_box = BOX.match(statement)
        pijlregel = PIJLREGEL.match(statement)
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
        elif ACCDESCR_BLOK.match(statement):
            fouten.append(f"{waar}: meerregelige accDescr niet ondersteund; gebruik `accDescr: ...`")
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
            statements = sequence_statements(regels, start)

            if statements is None:
                continue

            sequence += 1
            blokfouten, bloklabels = toets_blok(str(pagina), start, statements, model)
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
