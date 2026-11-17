# Use case 11: Een magazijn is langere tijd onbereikbaar

Het magazijn van een organisatie ligt langere tijd plat. De ondernemer mist daardoor berichten.

| | |
|---|---|
| **Actor** | De ondernemer, de stelselbeheerder en de organisatie van het magazijn |
| **Aanleiding** | Een magazijn antwoordt niet meer, bijvoorbeeld door een storing of onderhoud |
| **Resultaat** | De ondernemer ziet de berichten van de andere organisaties, en weet welke organisatie ontbreekt. De organisatie weet van de storing. Na het herstel ziet de ondernemer weer al zijn berichten |

## Verloop

1. De ondernemer opent zijn berichtenbox (use case 2). Het magazijn antwoordt niet. De ondernemer
   ziet de berichten van de andere organisaties, en de melding welke organisatie niet leverde.
2. Faalt het magazijn steeds, dan slaat de uitvraag het telkens even over. Zo blijft de
   berichtenbox voor iedereen snel.
3. De stelselbeheerder ziet dat het magazijn al een tijd niet levert, en meldt dat aan de
   organisatie.
4. De organisatie herstelt haar magazijn. Berichten die ze in die tijd wilde versturen, kon het
   magazijn niet aannemen (use case 1). Ze levert die opnieuw aan, of stuurt ze langs een andere
   weg.
5. Na het herstel haalt de uitvraag bij de volgende keer inloggen ook de berichten van dit magazijn
   weer op.

Vraagt de ondernemer de stelselbeheerder om toch bij zijn berichten te kunnen, dan kan die niet
helpen: er is geen kopie van de berichten buiten het magazijn. Alleen de organisatie zelf kan een
bericht langs een andere weg bij de ondernemer krijgen.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    actor OND as Ondernemer
    participant IL as Interactielaag
    participant FBS as Berichten Uitvraag Systeem
    participant MAG as Berichtenmagazijn
    participant SB as Stelselbeheerder
    participant ORG as Overheidsorganisatie

    OND->>IL: Opent de berichtenbox
    IL->>FBS: Haal de berichten op
    FBS->>MAG: Berichten van deze ondernemer
    Note over MAG: Antwoordt niet
    FBS-->>IL: Berichten van de andere organisaties, en welke organisatie niet leverde
    IL-->>OND: Toont de berichtenbox, met de melding

    FBS-)SB: Dit magazijn levert al een tijd niet
    SB->>ORG: Uw magazijn is niet bereikbaar
    ORG->>MAG: Herstelt het magazijn
    opt Berichten die in die tijd niet aangenomen zijn
        ORG->>MAG: Levert ze opnieuw aan (use case 1)
    end

    OND->>IL: Opent later opnieuw de berichtenbox
    IL->>FBS: Haal de berichten op
    FBS->>MAG: Berichten van deze ondernemer
    MAG-->>FBS: Berichten
    FBS-->>IL: Alle berichten
    IL-->>OND: Toont de berichtenbox
```

## Als het misgaat

| Situatie | Wat het stelsel doet | Wat de ondernemer merkt |
|---|---|---|
| De ondernemer had al een melding van een bericht uit dit magazijn | De uitvraag kan het bericht niet openen | Hij heeft een melding van een bericht dat hij niet kan openen, en krijgt "Probeer het straks nog eens" |
| Het magazijn antwoordt traag in plaats van niet | De uitvraag wacht hooguit tien seconden, en behandelt het dan als niet geleverd | Als hierboven |
| Het magazijn van een organisatie is niet bekend bij de uitvraag | De uitvraag bevraagt het niet | Berichten van die organisatie ontbreken (zie use case 2) |

## Nog niet gerealiseerd

- De uitvraag waarschuwt de stelselbeheerder nog niet als een magazijn langere tijd niet levert.
  De stelselbeheerder merkt het nu pas als iemand het meldt.
- De uitvraag legt alleen vast hoeveel organisaties niet leverden, niet welke.

## Open vragen

- Moet het stelsel een kopie van berichten bewaren buiten het magazijn, voor als een magazijn lang
  plat ligt? Dat botst met het uitgangspunt dat er geen centrale opslag van berichten is.
- Wat gebeurt er met termijnen in een bericht dat de ondernemer door een storing niet kon openen?
  Dat is aan de organisatie, maar de stelselafspraken kunnen er iets over zeggen.
- Moet de ondernemer een melding krijgen als zijn berichten weer beschikbaar zijn?
