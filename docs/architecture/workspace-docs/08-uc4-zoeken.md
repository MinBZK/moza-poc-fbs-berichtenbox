# Use case 4: Zoeken in de eigen berichten

Een ondernemer zoekt in de eigen berichten.

| | |
|---|---|
| **Actor** | Een ondernemer met een geopende berichtenbox |
| **Aanleiding** | De ondernemer zoekt een bericht terug, bijvoorbeeld "aanslag" of "subsidie" |
| **Resultaat** | De ondernemer ziet de berichten waarvan het onderwerp de zoekterm bevat, nieuwste eerst |

## Verloop

1. De ondernemer typt een zoekterm in het portaal.
2. Het portaal vraagt de uitvraag om te zoeken.
3. De uitvraag zoekt in de berichtenlijst die bij het ophalen (use case 2) is samengesteld. Hij zoekt
   alleen in het onderwerp: de tekst van een bericht staat niet in de uitvraag, maar in het magazijn
   van de organisatie.
4. De uitvraag geeft de eerste 20 treffers, nieuwste eerst, en zegt welke organisaties bij het
   ophalen niet leverden.
5. Het portaal toont de treffers.

De magazijnen worden bij het zoeken niet bevraagd.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    actor OND as Ondernemer / Zakelijke gebruiker
    participant IL as Interactielaag
    box Berichten Uitvraag Systeem
        participant UV as Berichten Uitvraag Service
        participant SC as Berichtensessiecache
    end

    OND->>IL: Zoekt op een term
    IL->>UV: Zoek in de berichten van deze ondernemer
    UV->>SC: Loopt de sessie?
    SC-->>UV: Ja, en verlengd
    UV->>SC: Zoek in het onderwerp van de opgehaalde berichten
    SC-->>UV: Treffers, en welke organisaties niet leverden
    UV-->>IL: De eerste 20 treffers, nieuwste eerst
    IL-->>OND: Toont de treffers
```

## Als het misgaat

| Situatie | Wat het stelsel doet | Wat de ondernemer merkt |
|---|---|---|
| De zoekterm is korter dan 2 of langer dan 200 tekens | De uitvraag weigert | Een melding dat de zoekterm niet geldig is |
| Er is nog niet opgehaald, of het ophalen loopt nog | De uitvraag weigert | Het portaal wacht op het ophalen |
| Het ophalen is mislukt | De uitvraag weigert, ook als er nog een oude lijst is | "Probeer het opnieuw"; het portaal haalt opnieuw op |
| Een organisatie leverde bij het ophalen niet | Haar berichten zitten niet in de zoekresultaten | De treffers zeggen welke organisatie ontbreekt |
| Er zijn meer dan 20 treffers | De uitvraag geeft alleen de eerste 20, zonder verder bladeren | De ondernemer moet zijn zoekterm verfijnen |
| De zoekterm staat alleen in de tekst van een bericht | Geen treffer | Het bericht wordt niet gevonden |
| De sessie is verlopen | De uitvraag weigert | "Uw sessie is verlopen. Log opnieuw in." |

Tekens als `*`, `-` of `|` in de zoekterm hebben geen bijzondere betekenis: de uitvraag zoekt er
letterlijk op.

## Doelarchitectuur

Het doelbeeld wijkt hier niet af. Zoeken in de tekst van berichten zou betekenen dat die tekst
centraal komt te staan, of dat elk magazijn bij elke zoekvraag bevraagd wordt. Het model kiest
geen van beide: de tekst blijft bij de organisatie, en zoeken blijft beperkt tot de kopgegevens die
de uitvraag tijdens de sessie al heeft.

## Technische verwijzing

| Stap | Koppelvlak | Opmerking |
|---|---|---|
| Zoeken | `GET /api/v1/berichten/_zoeken?q=` | `q` 2 tot 200 tekens, anders `400`. `409 nog-niet-opgehaald` of `ophalen-bezig`, `503 ophalen-mislukt`. Alleen een `self`-link, geen paginering |
| Zoekindex | RediSearch-index over de berichten in de sessiecache | Alleen `onderwerp` is full-text doorzoekbaar. Elk niet-alfanumeriek teken in `q` wordt geëscaped |
| Logboek | `uitvraag-zoeken` | De zoekterm wordt niet vastgelegd |
