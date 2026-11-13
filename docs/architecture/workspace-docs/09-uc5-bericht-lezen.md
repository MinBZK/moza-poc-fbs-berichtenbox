# Use case 5: Bericht lezen

Een ondernemer opent een bericht en leest het.

| | |
|---|---|
| **Actor** | Een ondernemer met een geopende berichtenbox |
| **Aanleiding** | De ondernemer klikt op een bericht in de lijst |
| **Resultaat** | De ondernemer ziet de tekst van het bericht, met de lijst van bijlagen. Markeert het portaal het daarna als gelezen, dan staat het ook zo in het magazijn |

## Verloop

1. De ondernemer klikt op een bericht. Het portaal vraagt het bericht op bij de uitvraag, met het
   berichtnummer en de organisatie die het stuurde.
2. De uitvraag kijkt of het bericht in de opgehaalde lijst van deze ondernemer staat, en haalt
   daaruit de kopgegevens en de bijlagen.
3. De uitvraag zoekt het magazijn van die organisatie op, en vraagt daar de tekst op. De tekst staat
   alleen in het magazijn en wordt bij elk openen opnieuw opgehaald.
4. Het magazijn controleert of het bericht bij deze ondernemer hoort, en geeft de tekst.
5. De uitvraag voegt kopgegevens en tekst samen en geeft het bericht aan het portaal.
6. Het portaal markeert het bericht als gelezen, met een aparte opdracht (zie use case 7). Het
   openen zelf verandert niets aan het bericht.

Uitvraag en magazijn leggen het lezen allebei vast in hun eigen logboek.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    actor OND as Ondernemer / Zakelijke gebruiker
    participant IL as Interactielaag
    participant FBS as Berichten Uitvraag Systeem
    participant MAG as Berichtenmagazijn

    OND->>IL: Opent een bericht
    IL->>FBS: Geef bericht (berichtnummer, organisatie)
    FBS->>FBS: Staat dit bericht in de lijst van deze ondernemer?
    FBS->>FBS: Zoek het magazijn van deze organisatie op
    FBS->>MAG: Geef de tekst van het bericht, voor deze ondernemer
    MAG->>MAG: Zoek het bericht en controleer of het bij deze ondernemer hoort
    Note over MAG: Legt het lezen vast in het logboek
    MAG-->>FBS: Tekst en status
    FBS-->>IL: Bericht met tekst en bijlagen
    Note over FBS: Legt het lezen vast in het logboek
    IL-->>OND: Toont het bericht
    IL->>FBS: Markeer als gelezen (zie use case 7)
```

## Als het misgaat

| Situatie | Wat het stelsel doet | Wat de ondernemer merkt |
|---|---|---|
| Het bericht staat niet in de lijst van deze ondernemer: het bestaat niet, of het is van een ander | De uitvraag geeft in beide gevallen hetzelfde antwoord, zodat niemand kan aftasten welke berichten van anderen bestaan | "Dit bericht is niet (meer) beschikbaar" |
| De ondernemer heeft het bericht zelf verwijderd (use case 8), in deze of een andere lopende sessie | De uitvraag weet dat tot de laatste sessie van de ondernemer eindigt | "U hebt dit bericht verwijderd" |
| Het bericht is buiten de berichtenbox om uit het magazijn verdwenen, bijvoorbeeld door de organisatie zelf | Het magazijn kent het niet meer. De uitvraag laat het tot het volgende ophalen in de lijst staan | "Dit bericht is niet (meer) beschikbaar" |
| Het magazijn is onbereikbaar, of antwoordt niet binnen 10 seconden | De uitvraag probeert het niet opnieuw | "Probeer het straks nog eens" |
| Het magazijn geeft een onleesbaar of leeg antwoord | De uitvraag geeft het bericht niet door | "Probeer het straks nog eens" |
| Uitvraag en magazijn zijn het oneens over de eigenaar | Het magazijn weigert | Een melding dat hij geen toegang heeft |
| Er is nog niet opgehaald, of het ophalen is mislukt | De uitvraag weigert | Het portaal haalt eerst (opnieuw) op |
| Het portaal markeert het bericht niet als gelezen | Het bericht blijft ongelezen | Het bericht blijft vet in de lijst |

## Doelarchitectuur

- **Autorisatie.** In het doelbeeld toetst het magazijn elk verzoek aan het beleid van de
  organisatie (AuthZEN), inclusief machtigingen. Nu controleert het alleen of de ondernemer de
  ontvanger van het bericht is.
- **Identiteit en pseudoniem.** Zie use case 2: het magazijn krijgt nu het identificatienummer, in
  het doelbeeld een pseudoniem en een token.

## Technische verwijzing

| Stap | Koppelvlak | Opmerking |
|---|---|---|
| Uitvraag | `GET /api/v1/berichten/{berichtId}?magazijnId=` | `404 bericht-onbekend` (bestaat niet, is van een ander, of `magazijnId` onbekend), `410 bericht-verwijderd` (eigen verwijdering, tombstone per ontvanger tot de laatste sessie eindigt), `409`/`503` als de ophaalronde niet klaar of mislukt is, `502 keten-fout` bij timeout, transportfout of onleesbaar antwoord |
| Magazijn | `GET /api/v1/berichten/{berichtId}` met `X-Ontvanger` | `404` voor een onbekend of verwijderd bericht, `403` voor andermans bericht (`BerichtAutorisatie.vereisOntvanger`) |
| Routering | `MagazijnRouter` via het `Magazijnregister` | Connect-timeout 2s, read-timeout 10s. Geen circuit breaker of retry op dit pad |
| Gelezen | `PATCH /api/v1/berichten/{berichtId}` met `status: gelezen` | Door het portaal, niet door het openen |
| Logboek | Uitvraag `uitvraag-bericht`, magazijn `ophalen-bericht` | Geen van beide legt het berichtnummer vast. De uitvraag legt een 4xx vast met status `Unset`, net als een geslaagde opening (`LogboekWeigering` kondigt de exceptie aan); het magazijn legt een 4xx vast als `Error`. Alleen een magazijnregel zonder fout toont dus dat de tekst verstrekt is |
