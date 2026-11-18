# Use case 5: Bericht lezen

Een burger of ondernemer (de ontvanger) opent een bericht en leest het.

| | |
|---|---|
| **Actor** | Een ontvanger (burger of ondernemer) met een geopende berichtenbox |
| **Aanleiding** | De ontvanger klikt op een bericht in de lijst |
| **Resultaat** | De ontvanger ziet de tekst van het bericht, met de lijst van bijlagen, en het bericht staat als gelezen in het magazijn |

## Verloop

1. De ontvanger klikt op een bericht. Het portaal vraagt het bericht op bij de uitvraag.
2. De uitvraag kijkt of het bericht in de opgehaalde lijst van deze ontvanger staat.
3. De uitvraag vraagt de tekst op bij het magazijn van de organisatie die het bericht stuurde. De
   tekst staat alleen in het magazijn, en wordt bij elk openen opnieuw opgehaald.
4. Het magazijn controleert of het bericht bij deze ontvanger hoort, en geeft de tekst.
5. De uitvraag geeft het bericht aan het portaal, en het portaal toont het.
6. Het portaal markeert het bericht als gelezen, op dezelfde manier als het verplaatsen in use
   case 7.

Uitvraag en magazijn leggen het lezen allebei vast in hun eigen logboek.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    actor ONT as Ontvanger
    participant IL as Interactielaag
    participant FBS as Berichten Uitvraag Systeem
    participant MAG as Berichtenmagazijn

    ONT->>IL: Opent een bericht
    IL->>FBS: Geef het bericht
    FBS->>FBS: Staat dit bericht in de lijst van deze ontvanger?
    FBS->>MAG: Geef de tekst van het bericht, voor deze ontvanger
    MAG->>MAG: Hoort het bericht bij deze ontvanger?
    Note over MAG: Legt het lezen vast in het logboek
    MAG-->>FBS: Tekst
    Note over FBS: Legt het lezen vast in het logboek
    FBS-->>IL: Bericht met tekst en bijlagen
    IL-->>ONT: Toont het bericht
    IL->>FBS: Markeer als gelezen (zie use case 7)
```

## Als het misgaat

| Situatie | Wat het stelsel doet | Wat de ontvanger merkt |
|---|---|---|
| Het bericht bestaat niet, of is van een ander | De uitvraag geeft in beide gevallen hetzelfde antwoord, zodat niemand kan uitproberen welke berichten van anderen bestaan | "Dit bericht is niet (meer) beschikbaar" |
| De ontvanger heeft het bericht zelf verwijderd (use case 8) | De uitvraag onthoudt dat tot de ontvanger uitlogt | "U hebt dit bericht verwijderd" |
| Het bericht is buiten de berichtenbox om uit het magazijn verdwenen | Het magazijn kent het niet meer. Het bericht blijft in de lijst staan tot het volgende ophalen | "Dit bericht is niet (meer) beschikbaar" |
| Het magazijn is niet bereikbaar, of antwoordt niet op tijd | De uitvraag geeft het bericht niet | "Probeer het straks nog eens" |
| Er zijn nog geen berichten opgehaald, of het ophalen is mislukt | De uitvraag weigert | Het portaal haalt eerst (opnieuw) op |
| Het markeren als gelezen mislukt | Het bericht blijft ongelezen | Het bericht blijft als ongelezen in de lijst staan |

## Nog niet gerealiseerd

- Wat in het overzicht voor alle use cases staat.
- Het magazijn toetst een verzoek nog niet aan het beleid van de organisatie, inclusief
  machtigingen. Nu controleert het alleen of de gebruiker de ontvanger van het bericht is ([#418](https://github.com/MinBZK/MijnOverheidZakelijk/issues/418)).
- De logboeken leggen niet vast welk bericht gelezen is, en de logregel van de uitvraag legt niet
  vast of het openen lukte ([#1592](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1592), [#1381](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1381)). Zie use case 9.
