#!/usr/bin/env python3
"""Leest artifactId's uit een pom, voor de grensbewaking in demo-grens.sh.

Een echte XML-parser en geen regex: Maven sluit op XML-vorm, een regex op tekstvorm. Dat verschil
is een bypass-generator — een gespreid element, witruimte in de tag, een attribuut, een
CDATA-sectie of een entity levert een dependency op die Maven gewoon resolvet en die een regex niet
ziet. De parser sluit die vormen alle in één keer.

Uitvoer: één artifactId per regel op stdout, diagnostiek op stderr, exitcode 1 bij een pom die niet
te lezen is. Een onleesbare pom is nadrukkelijk geen lege uitkomst: stilte betekent hier "niets
gemeten" en dat mag nooit als "niets gevonden" doorgaan.
"""

import sys
import xml.etree.ElementTree as ET


def lokale_naam(tag: str) -> str:
    """De tagnaam zonder namespace: een pom draagt xmlns, dus de tag heet '{...}artifactId'."""
    return tag.rsplit("}", 1)[-1]


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in ("--alle", "--eigen"):
        print(f"gebruik: {sys.argv[0]} --alle|--eigen <pom.xml>", file=sys.stderr)
        return 2

    modus, pad = sys.argv[1], sys.argv[2]

    try:
        boom = ET.parse(pad)
    except (ET.ParseError, OSError) as fout:
        print(f"FOUT: {pad} is niet als XML te lezen: {fout}", file=sys.stderr)
        return 1

    # --eigen kijkt alleen naar de directe kinderen van <project>, zodat het <parent>-blok buiten
    # beeld blijft; --alle loopt de hele boom af.
    elementen = boom.iter() if modus == "--alle" else list(boom.getroot())

    for element in elementen:
        if lokale_naam(element.tag) != "artifactId" or not element.text:
            continue

        naam = element.text.strip()

        if naam:
            print(naam)

            if modus == "--eigen":
                break

    return 0


if __name__ == "__main__":
    sys.exit(main())
