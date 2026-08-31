#!/usr/bin/env python3
"""Genereert de artefacten voor 'veel magazijnen' uit één getal n.

Drie bestanden, uit één bron, zodat beide kanten van de keten niet uit elkaar kunnen lopen:

  * het register voor de berichtenuitvraag  — welke OIN op welk adres te bereiken is;
  * de set voor de magazijn-simulator       — welke magazijnen hij voorstelt, en met welk volgnummer;
  * de profiel-stubs voor vier ondernemers  — bij hoeveel organisaties elk van hen berichten heeft.

Het volgnummer bepaalt het gedrag van een magazijn (traag, haperend, onbereikbaar); die verdeling
staat in de simulator zelf, niet hier. Dit script schrijft alleen het nummer.

Draai dit VÓÓR `docker compose --profile demo up`:
    DEMO_MAGAZIJNEN=98 python3 demo/genereer-magazijnen.py
"""
import json
import os
import sys
from pathlib import Path

BASIS = Path(__file__).resolve().parent / "generated"

# De twee echte magazijnen. Ze staan in élke ondernemer: de gesimuleerde magazijnen komen erbij,
# ze vervangen niets. Alleen de echte dragen aanleveren, bijlagen, notificaties en FSC.
MAGAZIJN_A = "00000000000000100000"
MAGAZIJN_B = "00000001823288444000"
ECHTE_MAGAZIJNEN = [MAGAZIJN_A, MAGAZIJN_B]

# Waar de simulator te bereiken is. Lokaal is dat de containernaam; op de gedeelde omgeving moet hier
# een configuratie-expressie staan in plaats van een adres — `${MAGAZIJN_SIMULATOR_URL}` — want de
# inhoud van een attachment wordt daar ongewijzigd gemount en zou anders in elke preview het adres
# van `test` noemen. Die variabele komt daar uit een alias, en aliassen kennen de deployment-naam
# wél; SmallRye vult hem in bij het lezen van het register.
SIMULATOR_URL = os.environ.get("SIMULATOR_URL", "http://magazijn-simulator:8092")

# Vier ondernemers, van klein naar extreem. De sets zijn genest: elke grotere bevat de kleinere
# helemaal. Het verschil in wachttijd komt daardoor puur door de extra organisaties en niet doordat
# er andere organisaties in het spel zijn.
#
# De laatste is bewust extreem — geen echte ondernemer heeft honderd aangesloten organisaties. Hij
# bestaat om het gedrag van de keten in de breedte zichtbaar te maken, niet om realisme te tonen.
#
# De identificatienummers volgen de bestaande demo-persona's; alleen de grootste is nieuw. Lopen er
# elders standaard-persona's (proeftuin), dan zijn die leidend en hoeft hier alleen de koppeling te
# verschuiven — de groottes zijn wat telt, niet de namen.
ONDERNEMERS = [
    ("kleine-eenmanszaak", "BSN", "999993653", 3),
    ("klein-bedrijf", "KVK", "12345678", 15),
    ("grootbedrijf", "KVK", "90000001", 45),
    ("landelijk-concern", "KVK", "90000003", 100),
]

# Namen van echte uitvoeringsorganisaties voor de eerste magazijnen, zodat een demo herkenbaar is
# in plaats van een rij "Demo-magazijn 37". De staart wordt gevuld met gemeenten — dat is ook in
# werkelijkheid waar de lange staart zit.
ORGANISATIES = [
    "Belastingdienst", "Kamer van Koophandel", "Rijksdienst voor Ondernemend Nederland",
    "UWV", "Sociale Verzekeringsbank", "RDW", "Kadaster", "Centraal Bureau voor de Statistiek",
    "Nederlandse Voedsel- en Warenautoriteit", "Inspectie Leefomgeving en Transport",
    "Autoriteit Consument en Markt", "Dienst Uitvoering Onderwijs", "Justis",
    "Rijkswaterstaat", "Staatstoezicht op de Mijnen", "Nederlandse Emissieautoriteit",
    "Autoriteit Persoonsgegevens", "Inspectie SZW", "Douane", "Waterschap Rivierenland",
]

GEMEENTEN = [
    "Amsterdam", "Rotterdam", "Den Haag", "Utrecht", "Eindhoven", "Groningen", "Tilburg",
    "Almere", "Breda", "Nijmegen", "Apeldoorn", "Haarlem", "Arnhem", "Enschede", "Amersfoort",
    "Zaanstad", "Haarlemmermeer", "Den Bosch", "Zwolle", "Zoetermeer", "Leiden", "Leeuwarden",
    "Maastricht", "Dordrecht", "Ede", "Alphen aan den Rijn", "Westland", "Alkmaar", "Emmen",
    "Delft", "Venlo", "Deventer", "Sittard-Geleen", "Helmond", "Oss", "Amstelveen", "Hilversum",
    "Heerlen", "Hengelo", "Purmerend", "Roosendaal", "Schiedam", "Lelystad", "Almelo", "Vlaardingen",
    "Gouda", "Spijkenisse", "Assen", "Bergen op Zoom", "Capelle aan den IJssel", "Veenendaal",
    "Katwijk", "Zeist", "Nieuwegein", "Roermond", "Doetinchem", "Hoorn", "Kerkrade", "Vlissingen",
    "Middelburg", "Barneveld", "Woerden", "Ridderkerk", "Rijswijk", "Weert", "Hoogeveen",
    "Terneuzen", "Zutphen", "Harderwijk", "Wijchen", "Beverwijk", "Houten", "Waalwijk", "Meppel",
    "Tiel", "Culemborg", "Rheden", "Sneek", "Uden", "Kampen",
]


def oin(index: int) -> str:
    """De OIN van gesimuleerd magazijn `index`. Ook het pad-segment waarop hij bereikbaar is."""
    return f"0000000900000000{index:04d}"


def naam(index: int) -> str:
    """Een herkenbare organisatienaam; de eerste twintig echt, daarna gemeenten."""
    if index <= len(ORGANISATIES):
        return ORGANISATIES[index - 1]

    staart = index - len(ORGANISATIES) - 1

    if staart < len(GEMEENTEN):
        return f"Gemeente {GEMEENTEN[staart]}"

    return f"Gemeente {GEMEENTEN[staart % len(GEMEENTEN)]} {staart // len(GEMEENTEN) + 1}"


def register_regels(n: int) -> list:
    """Het magazijnregister van de uitvraag: per OIN waar hij te bereiken is en hoe hij heet."""
    regels = []

    for i in range(1, n + 1):
        regels.append(f'magazijnen."{oin(i)}".url={SIMULATOR_URL}/magazijn/{oin(i)}')
        regels.append(f'magazijnen."{oin(i)}".naam={naam(i)}')

    return regels


def simulator_regels(n: int) -> list:
    """De set die de simulator voorstelt. Het volgnummer bepaalt daar zijn gedrag."""
    regels = []

    for i in range(1, n + 1):
        regels.append(f'magazijnsimulator.magazijnen."{oin(i)}".naam={naam(i)}')
        regels.append(f'magazijnsimulator.magazijnen."{oin(i)}".index={i}')

    return regels


def scopes(fanout: int) -> list:
    """De organisaties waar deze ondernemer berichten van ontvangt: eerst de echte, dan de rest."""
    gesimuleerd = [oin(i) for i in range(1, fanout - len(ECHTE_MAGAZIJNEN) + 1)]

    return [
        {"partij": {"identificatieType": "OIN", "identificatieNummer": o}}
        for o in ECHTE_MAGAZIJNEN + gesimuleerd
    ]


def profiel(volgnummer: int, soort: str, nummer: str, fanout: int) -> dict:
    """Een WireMock-mapping die de profielservice nabootst voor één ondernemer.

    Voorrang 1 wint van de handgeschreven persona-stubs (5) in `wiremock/demo-profiel/mappings/`.
    Die dragen alleen de twee echte magazijnen — dat is waar de demo-console zelf voor aanlevert, en
    dat moet blijven werken zonder dat dit script gedraaid is. De volledige fan-out komt hiervandaan.
    """
    return {
        "priority": 1,
        "request": {"method": "GET", "urlPathPattern": f"/api/profielservice/v1/{soort}/{nummer}"},
        "response": {
            "status": 200,
            "headers": {"Content-Type": "application/json"},
            "jsonBody": {
                "partijId": 900 + volgnummer,
                "identificaties": [],
                "voorkeuren": [
                    {
                        "id": 1,
                        "voorkeurType": "OntvangViaBerichtenbox",
                        "waarde": "true",
                        "scopes": scopes(fanout),
                    }
                ],
                "contactgegevens": [],
            },
        },
    }


def main() -> None:
    gevraagd = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("DEMO_MAGAZIJNEN", "98")
    n = int(gevraagd)

    grootste = max(fanout for _, _, _, fanout in ONDERNEMERS)
    minimaal = grootste - len(ECHTE_MAGAZIJNEN)

    # Weigeren en niet stilzwijgend inkorten. De ondernemers zijn vaste bereiken; met een kleiner n
    # zou de grootste een profiel krijgen met scopes naar magazijnen die niet bestaan. De uitvraag
    # slaat die over met een waarschuwing, dus de demo zou gewoon draaien — met een fan-out die
    # niemand heeft ingesteld en niemand ziet.
    if n < minimaal:
        raise SystemExit(
            f"n={n} is te klein: de grootste ondernemer heeft {grootste} organisaties, waarvan "
            f"{len(ECHTE_MAGAZIJNEN)} echt, dus er zijn minstens {minimaal} gesimuleerde magazijnen "
            f"nodig. Wil je bewust kleiner draaien, verklein dan ook de ondernemers in dit script."
        )

    profiel_dir = BASIS / "profiel"
    profiel_dir.mkdir(parents=True, exist_ok=True)

    (BASIS / "magazijnen-register.properties").write_text("\n".join(register_regels(n)) + "\n")
    (BASIS / "magazijn-simulator.properties").write_text("\n".join(simulator_regels(n)) + "\n")

    # Álles opruimen, niet alleen wat deze versie zelf schrijft. Een eerdere versie van dit script
    # schreef andere bestandsnamen op dezelfde URL en met dezelfde voorrang; die map is git-ignored,
    # dus zo'n wees blijft staan bij wie de vorige versie heeft gedraaid. WireMock breekt een
    # gelijkspel in voorrang op volgorde van inlezen, en dan wint willekeurig de oude of de nieuwe —
    # met een fan-out die niemand heeft ingesteld en een foutmelding die de verkeerde kant op wijst.
    for oud in profiel_dir.glob("*.json"):
        oud.unlink()

    for volgnummer, (naampje, soort, nummer, fanout) in enumerate(ONDERNEMERS, start=1):
        bestand = profiel_dir / f"ondernemer-{naampje}.json"
        bestand.write_text(json.dumps(profiel(volgnummer, soort, nummer, fanout), indent=2))

    ondernemers = ", ".join(f"{naampje}={fanout}" for naampje, _, _, fanout in ONDERNEMERS)

    print(f"Gegenereerd: {n} gesimuleerde magazijnen in {BASIS}")
    print(f"Ondernemers (fan-out incl. de twee echte magazijnen): {ondernemers}")


if __name__ == "__main__":
    main()
