#!/usr/bin/env python3
"""Leest artifactId's en modulepaden uit een pom, voor de grensbewaking in demo-grens.sh.

Een echte XML-parser en geen regex: Maven sluit op XML-vorm, een regex op tekstvorm. Dat verschil
is een bypass-generator — een gespreid element, witruimte in de tag, een attribuut, een
CDATA-sectie of een entity levert een dependency op die Maven gewoon resolvet en die een regex niet
ziet. De parser sluit die vormen alle in één keer.

Uitvoer: één waarde per regel op stdout, diagnostiek op stderr, exitcode 1 bij een pom die niet
te lezen is. Een onleesbare pom is nadrukkelijk geen lege uitkomst: stilte betekent hier "niets
gemeten" en dat mag nooit als "niets gevonden" doorgaan.
"""

import sys
import xml.etree.ElementTree as ET


def lokale_naam(tag: str) -> str:
    """De tagnaam zonder namespace: een pom draagt xmlns, dus de tag heet '{...}artifactId'."""
    return tag.rsplit("}", 1)[-1]


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in ("--alle", "--eigen", "--modules"):
        print(f"gebruik: {sys.argv[0]} --alle|--eigen|--modules <pom.xml>", file=sys.stderr)
        return 2

    modus, pad = sys.argv[1], sys.argv[2]

    try:
        boom = ET.parse(pad)
    except (ET.ParseError, OSError) as fout:
        print(f"FOUT: {pad} is niet als XML te lezen: {fout}", file=sys.stderr)
        return 1

    if modus == "--modules":
        for element in boom.iter():
            if lokale_naam(element.tag) == "module" and element.text and element.text.strip():
                print(element.text.strip())

        return 0

    # --eigen kijkt alleen naar de directe kinderen van <project>, zodat het <parent>-blok buiten
    # beeld blijft; --alle loopt de hele boom af.
    elementen = boom.iter() if modus == "--alle" else list(boom.getroot())

    for element in elementen:
        if lokale_naam(element.tag) != "artifactId" or not element.text:
            continue

        naam = element.text.strip()

        if not naam:
            continue

        # Maven sluit op de effective POM: `${demo.art}` wordt daar een gewone naam, en dan is een
        # dependency op demo-code niet meer aan de ruwe XML te zien. Zo'n artifactId is dus niet
        # statisch te controleren, en stil doorlaten is precies de uitkomst die deze controle moet
        # uitsluiten. Falen dus, met de vindplaats erbij.
        if "${" in naam:
            print(
                f"FOUT: {pad} gebruikt property-interpolatie in een artifactId ({naam});"
                " die is niet statisch te controleren.",
                file=sys.stderr,
            )
            return 1

        print(naam)

        if modus == "--eigen":
            break

    return 0


if __name__ == "__main__":
    sys.exit(main())
