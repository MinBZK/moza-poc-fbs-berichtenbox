#!/usr/bin/env python3
"""Bewaakt drie eisen aan elke `actions/setup-java`-stap: de handtekening van de JDK wordt
geverifieerd, de stap zet zijn eigen Maven-cache niet aan, en later in dezelfde job volgt
`./.github/actions/maven-cache`.

De vlag `verify-signature` staat in de action zelf niet vast aan: laat je hem weg bij een
distributie zonder ondersteuning, dan verifieert de action niets en blijft de run groen. Afwezig
is daarmee niet "standaard veilig" maar "stil onbeschermd", en dat is precies de vorm die in een
review over het hoofd wordt gezien — een nieuwe workflow kopieert een bestaand blok zonder de
regel, en niemand ziet er iets aan.

De cache loopt via `.github/actions/maven-cache`. Eén `cache: maven` ernaast werkt gewoon en valt
daardoor in geen run op, maar bewaart weer per PR een kopie onder één sleutel voor alle jobs —
precies wat de 10 GB van de repository vulde en de caches liet verdringen. Een job zonder de
action, of met de action vóór setup-java, bouwt evengoed groen, maar zonder de Central-mirror:
dan keren de 429's terug zonder herkenbare oorzaak.

Een echte YAML-parser en geen regex. Dezelfde reden als bij `workflow-jobs.py` voor workflows en
`pom-artifactids.py` voor pom's: GitHub sluit op YAML-vorm en een patroon op tekstvorm. Een
gequoteerde sleutel, een anchor of een `#`-commentaar achter de waarde levert een stap op die
GitHub gewoon draait en die een patroon niet ziet.

De dure faalwijze is niet "rood terwijl het goed is" maar "groen terwijl er niets gemeten is".
Nul workflows, nul setup-java-stappen of een onleesbare workflow faalt daarom hard, in plaats van
als "alles in orde" door te gaan.

Contract: bevindingen op stdout, diagnostiek op stderr, exitcode 1 zodra er één bevinding is of
zodra de meting onbetrouwbaar is.

Gebruik: jdk-handtekening.py [map-met-workflows]
"""

import pathlib
import sys

import yaml

ACTION = "actions/setup-java"
CACHE_ACTION = "./.github/actions/maven-cache"


def waar(waarde: object) -> bool:
    """Of `verify-signature` de verificatie daadwerkelijk aanzet.

    PyYAML levert `true` als bool, maar een gequoteerde `'true'` als string; Actions behandelt
    beide gelijk. Een `${{ ... }}`-expressie telt bewust NIET als aan: wat daar uit komt is hier
    niet te zien, en een expressie die naar false evalueert laat de verificatie geruisloos vallen.
    """
    if isinstance(waarde, bool):
        return waarde

    return isinstance(waarde, str) and waarde.strip().lower() == "true"


def cache_waarde(met: dict) -> object:
    """De `cache`-input, ongeacht hoofdletters: de runner geeft inputs door als `INPUT_<NAAM>`, dus
    `Cache: maven` zet de cache net zo goed aan als `cache: maven`."""
    return next((waarde for sleutel, waarde in met.items() if str(sleutel).lower() == "cache"), None)


def setup_java_stappen(workflow: dict) -> list[tuple[str, int, dict, bool]]:
    """Elke setup-java-stap als (job-id, stapnummer, with-blok, maven-cache-action volgt).

    Ook een ongepinde `uses: actions/setup-java` (zonder `@`) telt mee: die hoort de vlag net zo
    goed te dragen, en hem hier overslaan zou de guard laten zwijgen over de slechtste variant.
    """
    gevonden = []

    for job_id, job in (workflow.get("jobs") or {}).items():
        # Een job die een reusable workflow aanroept heeft geen `steps`; die is hier niets.
        stappen = [
            (nummer, stap, stap.get("uses").split("@", 1)[0].strip())
            for nummer, stap in enumerate(job.get("steps") or [], start=1)
            if isinstance(stap, dict) and isinstance(stap.get("uses"), str)
        ]

        for nummer, stap, action in stappen:
            if action != ACTION:
                continue

            met = stap.get("with")
            # Ná setup-java en niet ervoor: setup-java schrijft zijn eigen ~/.m2/settings.xml, en
            # die zou de mirror-instellingen van de action dan overschrijven.
            cache_volgt = any(later > nummer and naam == CACHE_ACTION for later, _, naam in stappen)
            gevonden.append((job_id, nummer, met if isinstance(met, dict) else {}, cache_volgt))

    return gevonden


def controleer(map_pad: pathlib.Path) -> int:
    bevindingen = []
    stappen_totaal = 0

    workflows = sorted(
        pad
        for pad in map_pad.iterdir()
        if pad.is_file() and pad.suffix in (".yml", ".yaml")
    )

    if not workflows:
        print(f"FOUT: geen enkele workflow in {map_pad} — deze guard meet niets.")

        return 1

    for pad in workflows:
        try:
            with pad.open(encoding="utf-8") as bestand:
                inhoud = yaml.safe_load(bestand)
        except (OSError, yaml.YAMLError) as fout:
            # Niet overslaan: een workflow die niet te lezen is, verkleint de zoekruimte
            # stilzwijgend, en dan meldt de guard "in orde" over een stap die hij nooit zag.
            print(f"FOUT: {pad.name} is niet te lezen ({fout}) — die workflow is niet gecontroleerd.")

            return 1

        if not isinstance(inhoud, dict):
            print(f"FOUT: {pad.name} levert geen YAML-mapping op — die workflow is niet gecontroleerd.")

            return 1

        for job_id, nummer, met, cache_volgt in setup_java_stappen(inhoud):
            stappen_totaal += 1

            if not waar(met.get("verify-signature")):
                bevindingen.append(
                    f"FOUT: {pad.name} job '{job_id}' stap {nummer} roept {ACTION} aan zonder "
                    "`verify-signature: true` — de JDK-download wordt dan niet op herkomst gecontroleerd."
                )

            # Een lege waarde zet de cache in setup-java uit; al het andere, ook een expressie,
            # telt als aan.
            if cache_waarde(met) not in (None, ""):
                bevindingen.append(
                    f"FOUT: {pad.name} job '{job_id}' stap {nummer} zet `cache:` aan op {ACTION} — "
                    f"gebruik `{CACHE_ACTION}` voor de Maven-cache."
                )

            if not cache_volgt:
                bevindingen.append(
                    f"FOUT: {pad.name} job '{job_id}' stap {nummer}: na {ACTION} volgt geen "
                    f"`{CACHE_ACTION}` — de job draait dan zonder cache en zonder Central-mirror."
                )

    if stappen_totaal == 0:
        # Geen enkele setup-java betekent dat de guard niets meer bewaakt: de stappen zijn
        # verplaatst, hernoemd of vervangen. Stil groen worden is hier de verkeerde uitkomst.
        print(f"FOUT: geen enkele {ACTION}-stap gevonden — deze guard bewaakt niets meer (verplaatst of vervangen?).")

        return 1

    if bevindingen:
        print("\n".join(bevindingen))

        return 1

    print(
        f"OK: alle {stappen_totaal} {ACTION}-stap(pen) verifiëren de handtekening van de JDK, "
        f"zetten `cache:` niet aan en worden gevolgd door {CACHE_ACTION}."
    )

    return 0


def main() -> int:
    map_pad = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".github/workflows")

    if not map_pad.is_dir():
        print(f"FOUT: {map_pad} is geen map — deze guard meet niets.")

        return 1

    return controleer(map_pad)


if __name__ == "__main__":
    sys.exit(main())
