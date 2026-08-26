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

import os
import sys
import xml.etree.ElementTree as ET


def lokale_naam(tag: str) -> str:
    """De tagnaam zonder namespace: een pom draagt xmlns, dus de tag heet '{...}artifactId'."""
    return tag.rsplit("}", 1)[-1]


def lees(pad: str) -> ET.ElementTree:
    """Parseert een pom, of stopt met een melding: een onleesbare pom is geen lege uitkomst."""
    try:
        return ET.parse(pad)
    except (ET.ParseError, OSError) as fout:
        print(f"FOUT: {pad} is niet als XML te lezen: {fout}", file=sys.stderr)
        raise SystemExit(1)


def modulepaden(pom: str) -> list[tuple[str, bool]]:
    """De <module>-paden van één pom, met per pad of het bestaan ervan afdwingbaar is.

    Modules uit een <profile> tellen mee: een profiel met `activeByDefault` of een
    file-activation draait zonder `-P`, dus Maven bouwt zo'n module gewoon — en dan hoort hij ook
    onder de controles te vallen. Maar het bestaan ervan is niet af te dwingen: een release-only
    profiel mag naar een map wijzen die er in een gewone checkout niet is. Vandaar het onderscheid;
    ontbreekt een module uit het gewone <modules>-blok, dan is dat een echte reactor-fout die Maven
    zelf ook meldt.
    """
    gevonden: list[tuple[str, bool]] = []
    wortel = lees(pom).getroot()

    for blok in wortel:
        if lokale_naam(blok.tag) == "modules":
            gevonden += [(pad, True) for pad in module_elementen(blok)]

        if lokale_naam(blok.tag) == "profiles":
            for profiel in blok:
                for profielblok in profiel:
                    if lokale_naam(profielblok.tag) == "modules":
                        gevonden += [(pad, False) for pad in module_elementen(profielblok)]

    return gevonden


def module_elementen(blok: ET.Element) -> list[str]:
    """De niet-lege <module>-teksten binnen één <modules>-blok."""
    return [
        element.text.strip()
        for element in blok
        if lokale_naam(element.tag) == "module" and element.text and element.text.strip()
    ]


def reactor(wortel_pom: str) -> list[str]:
    """Alle modulepaden van de reactor, transitief.

    Maven laat een module zélf modules declareren, en zo'n geneste module staat niet in de
    root-pom. Wie alleen daar kijkt, mist hem — en dan valt hij buiten élke controle in deze keten
    terwijl de build hem gewoon meeneemt. Vandaar de hele boom aflopen in plaats van één niveau.
    """
    basis = os.path.dirname(os.path.abspath(wortel_pom)) or "."
    te_doen = [os.path.abspath(wortel_pom)]
    gezien: set[str] = set()
    gevonden: list[str] = []

    while te_doen:
        pom = te_doen.pop(0)

        # Eerst alle declaraties van déze pom samenvoegen: staat een module zowel in <modules> als
        # in een profiel, dan telt de strengste eis. Zonder die samenvoeging bepaalt de volgorde
        # in het XML-bestand of een ontbrekende module fataal is of wordt overgeslagen.
        verplichtheid: dict[str, bool] = {}

        for module, verplicht in modulepaden(pom):
            # Maven lost `${…}` op tegen de properties; de ruwe XML laat dan niet zien welke module
            # er gebouwd wordt. Zo'n pad is niet statisch te volgen, en stil doorlaten zou die
            # module buiten élke controle in de keten houden.
            if "${" in module:
                print(f"FOUT: {pom} gebruikt property-interpolatie in een modulepad ({module}); die is niet statisch te volgen.", file=sys.stderr)
                raise SystemExit(1)

            map_pad = os.path.normpath(os.path.join(os.path.dirname(pom), module))
            module_pom = map_pad if map_pad.endswith(".xml") else os.path.join(map_pad, "pom.xml")
            verplichtheid[module_pom] = verplichtheid.get(module_pom, False) or verplicht

        for module_pom, verplicht in verplichtheid.items():
            if module_pom in gezien:
                continue

            if not os.path.isfile(module_pom):
                # Bewust niet in `gezien`: een module die hier optioneel is, kan verderop in de boom
                # uit een gewoon <modules>-blok komen, en die declaratie hoort dan alsnog te falen.
                if not verplicht:
                    print(f"Overgeslagen: {pom} declareert {module_pom} in een profiel, maar die bestaat niet.", file=sys.stderr)

                    continue

                print(f"FOUT: {pom} declareert module {module_pom}, maar die bestaat niet.", file=sys.stderr)
                raise SystemExit(1)

            gezien.add(module_pom)
            gevonden.append(os.path.relpath(os.path.dirname(module_pom), basis))
            te_doen.append(module_pom)

    return sorted(gevonden)


def main() -> int:
    modi = ("--alle", "--eigen", "--modules", "--reactor", "--packaging")

    if len(sys.argv) != 3 or sys.argv[1] not in modi:
        print(f"gebruik: {sys.argv[0]} {'|'.join(modi)} <pom.xml>", file=sys.stderr)
        return 2

    modus, pad = sys.argv[1], sys.argv[2]

    if modus == "--reactor":
        for module in reactor(pad):
            print(module)

        return 0

    boom = lees(pad)

    if modus == "--modules":
        for module, _ in modulepaden(pad):
            print(module)

        return 0

    if modus == "--packaging":
        # Default-packaging is `jar`; Maven laat het element weg als het dat is.
        for kind in boom.getroot():
            if lokale_naam(kind.tag) == "packaging" and kind.text and kind.text.strip():
                print(kind.text.strip())

                return 0

        print("jar")

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
