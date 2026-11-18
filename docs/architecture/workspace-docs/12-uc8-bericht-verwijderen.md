# Use case 8: Bericht verwijderen

Een burger of ondernemer (de ontvanger) verwijdert een bericht.

| | |
|---|---|
| **Actor** | Een ontvanger (burger of ondernemer) met een geopende berichtenbox |
| **Aanleiding** | De ontvanger wil een bericht niet meer in de berichtenbox hebben |
| **Resultaat** | Het bericht is uit de berichtenbox verdwenen en komt niet terug. Het magazijn bewaart het nog tot de bewaartermijn verstreken is, en ruimt het dan definitief op |

## Verloop

1. De ontvanger verwijdert een bericht. Het portaal geeft dat door aan de uitvraag.
2. De uitvraag stuurt het verwijderen naar het magazijn van de organisatie die het bericht stuurde.
3. Het magazijn controleert of het bericht bij deze ontvanger hoort, en markeert het als
   verwijderd. Vanaf dan komt het in geen enkele lijst meer voor en is het niet meer op te vragen.
4. De uitvraag haalt het bericht uit de lijst, en onthoudt tot de ontvanger uitlogt dat het bericht
   zelf verwijderd is.
5. Na de bewaartermijn ruimt het magazijn het bericht definitief op, met de bijlagen, en legt dat
   vast in zijn logboek. De bewaartermijn is nu zeven jaar na ontvangst én zeven jaar na het
   verwijderen.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    actor ONT as Ontvanger
    participant IL as Interactielaag
    participant FBS as Berichten Uitvraag Systeem
    participant MAG as Berichtenmagazijn

    ONT->>IL: Verwijdert een bericht
    IL->>FBS: Verwijder het bericht
    FBS->>MAG: Verwijder het bericht voor deze ontvanger
    MAG->>MAG: Hoort het bericht bij deze ontvanger? Markeer het als verwijderd
    Note over MAG: Legt het verwijderen vast in het logboek
    MAG-->>FBS: Verwijderd
    FBS->>FBS: Haal het bericht uit de lijst en onthoud dat het verwijderd is
    Note over FBS: Legt het verwijderen vast in het logboek
    FBS-->>IL: Verwijderd
    IL-->>ONT: Het bericht is weg

    Note over MAG: Later: na de bewaartermijn
    MAG->>MAG: Ruim het bericht definitief op, met bijlagen
    Note over MAG: Legt het opruimen vast in het logboek
```

## Als het misgaat

| Situatie | Wat het stelsel doet | Wat de ontvanger merkt |
|---|---|---|
| Het bericht bestaat niet, of is van een ander | Er verandert niets. Het antwoord is in beide gevallen hetzelfde, zodat niemand kan uitproberen welke berichten van anderen bestaan | "Dit bericht is niet (meer) beschikbaar" |
| Het bericht is al verwijderd | Er verandert niets | Niets: het bericht is weg |
| Het magazijn is niet bereikbaar | Er verandert niets | "Probeer het straks nog eens" |
| Het magazijn heeft verwijderd, maar de lijst bijwerken lukt niet | De uitvraag probeert het nog eens. Lukt dat niet, dan krijgt de stelselbeheerder een waarschuwing | Een foutmelding, terwijl het bericht wel verwijderd is. Bij het volgende ophalen is het weg |
| De ontvanger opent het bericht na uitloggen, bijvoorbeeld via een oude link | De uitvraag weet niet meer dat het bericht zelf verwijderd is | "Dit bericht is niet (meer) beschikbaar" |
| Het definitief opruimen lukt een keer niet | Het magazijn probeert het de volgende nacht opnieuw | Niets |

## Nog niet gerealiseerd

- Is het bericht van een ander, dan antwoordt het magazijn nu anders dan bij een onbekend bericht.
  Zo valt af te leiden dat het bericht bestaat ([#1594](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1594)).
- Het magazijn legt het definitief opruimen nog niet vast in zijn logboek, alleen in zijn
  technische log, en dan zonder de ontvanger ([#549](https://github.com/MinBZK/MijnOverheidZakelijk/issues/549), [#1489](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1489)).

## Open vragen

- Kan een organisatie een bericht intrekken? De stelselafspraken kennen dat nog niet. Een zelf
  gehost magazijn kan het intern regelen; voor de ontvanger verdwijnt het bericht dan bij het
  volgende ophalen, zonder melding.
- Alle berichten van een ontvanger in één keer wissen, en hoe lang een bericht bewaard blijft dat
  nooit verwijderd is: zie use case 10 ([#1596](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1596), [#1597](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1597)).
