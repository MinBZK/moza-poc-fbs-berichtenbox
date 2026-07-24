#!/usr/bin/env python3
"""Genereert de artefacten voor 'veel magazijnen' (fase 6) uit één getal n:
register-regels voor de uitvraag, de profiel-stub voor persona Grootbedrijf, en n
WireMock-mappings. Schrijft naar demo/generated/ (staat in .gitignore).

Routering per stub gaat via de HOSTNAAM (docker-netwerk-alias mNN → dezelfde WireMock-
container), NIET via een pad-prefix: de uitvraag-rest-client bouwt de client met
baseUri(.../mNN) maar laat dat subpad vallen en roept elk magazijn op /api/v1/berichten
aan. Het enige per-stub-onderscheid dat de client meestuurt is de Host-header — daar matcht
WireMock op. n is daarom begrensd op het aantal aliassen in compose.yaml (m01..m50).

Draai dit VÓÓR `docker compose --profile demo up`:  DEMO_MAGAZIJN_STUBS=12 python3 demo/genereer-magazijnen.py
"""
import json
import os
import sys
from pathlib import Path

KVK_GROOTBEDRIJF = "90000001"
MAX_STUBS = 50  # aantal docker-netwerk-aliassen (m01..m50) in compose.yaml
BASIS = Path(__file__).resolve().parent / "generated"


def oin(i: int) -> str:
    return f"0000000900000000{i:04d}"


def host(i: int) -> str:
    return f"m{i:02d}"


def bericht(i: int) -> dict:
    o = oin(i)
    return {
        "berichtId": f"00000000-0000-0000-0000-{i:012d}",
        "afzender": o,
        "ontvanger": {"type": "KVK", "waarde": KVK_GROOTBEDRIJF},
        "onderwerp": f"Demo-magazijn {i} — mededeling",
        "inhoud": f"Demo-bericht uit demo-magazijn {i} (OIN {o}).",
        "publicatietijdstip": f"2026-07-{(i % 27) + 1:02d}T09:00:00Z",
        # Expliciet meesturen: MagazijnBericht heeft deze velden als non-null met een Kotlin-default,
        # maar Jackson past die default niet toe bij een afwezig veld — dan gaat er null naar de
        # constructor en faalt de deserialisatie. De echte magazijnen sturen ze altijd mee.
        "aantalBijlagen": 0,
        "bijlagen": [],
        "status": {"gelezen": False},
    }


def mapping(i: int) -> dict:
    return {
        "priority": 5,
        "request": {
            "method": "GET",
            "urlPath": "/api/v1/berichten",
            # Per-stub-routering op de Host-header (mNN), want het pad is voor alle stubs gelijk.
            "headers": {
                "Host": {"matches": f"{host(i)}(:8080)?"},
                "X-Ontvanger": {"matches": ".+"},
            },
        },
        "response": {
            "status": 200,
            "headers": {"Content-Type": "application/json"},
            "jsonBody": {"berichten": [bericht(i)]},
        },
    }


def profiel(n: int) -> dict:
    scopes = [{"partij": {"identificatieType": "OIN", "identificatieNummer": oin(i)}} for i in range(1, n + 1)]
    return {
        "priority": 1,
        "request": {"method": "GET", "urlPathPattern": f"/api/profielservice/v1/KVK/{KVK_GROOTBEDRIJF}"},
        "response": {
            "status": 200,
            "headers": {"Content-Type": "application/json"},
            "jsonBody": {
                "partijId": 900,
                "identificaties": [],
                "voorkeuren": [{"id": 1, "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true", "scopes": scopes}],
                "contactgegevens": [],
            },
        },
    }


def main() -> None:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else int(os.environ.get("DEMO_MAGAZIJN_STUBS", "12"))

    if n < 1:
        raise SystemExit(f"n moet >= 1 zijn, was {n}")

    if n > MAX_STUBS:
        raise SystemExit(f"n ({n}) > {MAX_STUBS}: verhoog de aliassen (m01..) in compose.yaml eerst")

    mappings_dir = BASIS / "magazijn-stubs-mappings"
    profiel_dir = BASIS / "profiel"
    mappings_dir.mkdir(parents=True, exist_ok=True)
    profiel_dir.mkdir(parents=True, exist_ok=True)

    # Oude mappings opruimen zodat een kleiner n geen stubs van een vorige run laat staan.
    for oud in mappings_dir.glob("m*.json"):
        oud.unlink()

    regels = []

    for i in range(1, n + 1):
        (mappings_dir / f"m{i:02d}.json").write_text(json.dumps(mapping(i), indent=2))
        regels.append(f'magazijnen."{oin(i)}".url=http://{host(i)}:8080')
        regels.append(f'magazijnen."{oin(i)}".naam=Demo-magazijn {i}')

    (BASIS / "magazijnen-stubs.properties").write_text("\n".join(regels) + "\n")
    (profiel_dir / "grootbedrijf-kvk.json").write_text(json.dumps(profiel(n), indent=2))

    print(f"Gegenereerd: {n} stub-magazijnen in {BASIS}")


if __name__ == "__main__":
    main()
