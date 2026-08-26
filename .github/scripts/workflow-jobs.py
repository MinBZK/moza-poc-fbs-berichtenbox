#!/usr/bin/env python3
"""Leest jobs uit een GitHub Actions-workflow, voor de uitrol-poort-controle in test-uitrol-poort.sh.

Een echte YAML-parser en geen regex. Dezelfde reden als bij `pom-artifactids.py` voor XML: GitHub
sluit op YAML-vorm en een regex op tekstvorm, en dat verschil is een bypass-generator. Een
gequoteerde jobnaam, een `#`-commentaar achter de sleutel, een blok-scalar of een YAML-anchor
levert telkens een job op die GitHub gewoon draait en die een patroon niet ziet — en dan
certificeert de uitrol-poort een uitrol die hij nooit beoordeeld heeft.

Modi:
  --jobs      alle job-id's
  --uitrol    de job-id's die de zad-actions-deploy draaien, ook via een lokale reusable workflow
  --runs      de inhoud van elk `run:`-blok, zodat een controle bewijs uit uitvoerbare stappen haalt
              en niet uit een commentaarregel die toevallig hetzelfde pad noemt
  --outputs   de outputs van één job als `sleutel=waarde` (job-id via de omgevingsvariabele JOB)

Uitvoer: één job-id per regel op stdout, diagnostiek op stderr, exitcode 1 zodra de workflow niet te
lezen is of een gevolgde reusable workflow ontbreekt. Stilte is hier geen geldige uitkomst.
"""

import os
import sys

import yaml

DEPLOY_ACTIE = "RijksICTGilde/zad-actions/deploy@"


def lees(pad: str) -> dict:
    try:
        with open(pad, encoding="utf-8") as bestand:
            inhoud = yaml.safe_load(bestand)
    except (OSError, yaml.YAMLError) as fout:
        print(f"FOUT: {pad} is niet als YAML te lezen: {fout}", file=sys.stderr)
        raise SystemExit(1)

    if not isinstance(inhoud, dict) or not isinstance(inhoud.get("jobs"), dict):
        print(f"FOUT: {pad} bevat geen jobs-blok — deze controle meet niets.", file=sys.stderr)
        raise SystemExit(1)

    return inhoud["jobs"]


def draait_deploy(job: dict, pad: str) -> bool:
    """Of deze job de zad-actions-deploy draait, rechtstreeks of via een lokale reusable workflow."""
    for stap in job.get("steps") or []:
        if isinstance(stap, dict) and DEPLOY_ACTIE in str(stap.get("uses", "")):
            return True

    verwijzing = str(job.get("uses", ""))

    if not verwijzing.startswith("./"):
        return False

    # Een reusable workflow uit deze repository: doorvolgen, want de deploy-stap staat dan in dát
    # bestand. Ontbreekt het, dan is de uitkomst onbekend en niet "geen uitrol".
    doel = os.path.join(os.path.dirname(os.path.abspath(pad)), "..", "..", verwijzing[2:])
    doel = os.path.normpath(doel)

    if not os.path.isfile(doel):
        print(f"FOUT: {pad} verwijst naar {verwijzing}, maar dat bestand bestaat niet.", file=sys.stderr)
        raise SystemExit(1)

    return any(draait_deploy(hulpjob, doel) for hulpjob in lees(doel).values() if isinstance(hulpjob, dict))


def runs(jobs: dict) -> list[str]:
    """De inhoud van elk `run:`-blok in de workflow."""
    gevonden = []

    for job in jobs.values():
        if not isinstance(job, dict):
            continue

        for stap in job.get("steps") or []:
            if isinstance(stap, dict) and stap.get("run"):
                gevonden.append(str(stap["run"]))

    return gevonden


def main() -> int:
    modi = ("--jobs", "--uitrol", "--runs", "--outputs")

    if len(sys.argv) != 3 or sys.argv[1] not in modi:
        print(f"gebruik: {sys.argv[0]} {'|'.join(modi)} <workflow.yml>", file=sys.stderr)
        return 2

    modus, pad = sys.argv[1], sys.argv[2]
    jobs = lees(pad)

    if modus == "--runs":
        for blok in runs(jobs):
            print(blok)

        return 0

    if modus == "--outputs":
        naam = os.environ.get("JOB", "")
        job = jobs.get(naam)

        if not isinstance(job, dict):
            print(f"FOUT: {pad} heeft geen job '{naam}' — deze controle meet niets.", file=sys.stderr)
            return 1

        for sleutel, waarde in (job.get("outputs") or {}).items():
            print(f"{sleutel}={waarde}")

        return 0

    for naam, job in jobs.items():
        if modus == "--jobs":
            print(naam)
        elif isinstance(job, dict) and draait_deploy(job, pad):
            print(naam)

    return 0


if __name__ == "__main__":
    sys.exit(main())
