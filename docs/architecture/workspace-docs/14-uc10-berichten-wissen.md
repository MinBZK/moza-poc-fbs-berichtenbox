# Use case 10: Alle berichten van een ondernemer wissen

Een ondernemer laat al zijn berichten wissen, met een beroep op het recht op gegevenswissing.

| | |
|---|---|
| **Actor** | Een ondernemer die een natuurlijk persoon is, met de stelselbeheerder en de verzendende organisaties |
| **Aanleiding** | De ondernemer wil dat al zijn berichten, met bijlagen, uit het stelsel verdwijnen |
| **Resultaat** | Elke organisatie heeft de berichten in haar magazijn gewist, of uitgelegd waarom ze dat (deels) niet mag. De ondernemer krijgt per organisatie de uitkomst, en het wissen is vastgelegd |

## Verloop

1. De ondernemer logt in op het portaal en dient daar één verzoek in. Omdat hij ingelogd is, staat
   vast wie hij is: wissen is onomkeerbaar, dus niemand mag het voor een ander aanvragen.
2. Het portaal geeft het verzoek door aan de stelselbeheerder.
3. Er is geen centrale opslag van berichten, maar een magazijn neemt alleen berichten aan van
   organisaties waarvan de ondernemer berichten via de berichtenbox wil ontvangen (use case 1). De
   stelselbeheerder vraagt de Profiel Service welke organisaties dat zijn, nu en eerder, en stuurt
   het verzoek alleen naar die organisaties. Zo krijgen organisaties zonder relatie met de
   ondernemer zijn gegevens niet.
4. Elke organisatie is verantwoordelijk voor de berichten in haar eigen magazijn, en beslist zelf
   over het verzoek. Ze toetst of een wettelijke plicht of de Archiefwet het wissen beperkt.
5. Mag de organisatie wissen, dan geeft ze haar magazijn opdracht om alle berichten van deze
   ontvanger te wissen, met bijlagen. Het magazijn wist ze en legt dat vast in zijn logboek.
6. De organisatie meldt de stelselbeheerder de uitkomst: gewist, deels gewist met de reden, niet
   gewist met de reden, of geen berichten gevonden. Organisaties aan wie ze de gegevens zelf
   verstrekte, brengt ze op de hoogte.
7. De stelselbeheerder meldt de ondernemer de uitkomst per organisatie, binnen een maand na het
   verzoek.

De uitvraag hoeft niets te wissen: hij bewaart de berichtenlijst alleen zolang de ondernemer
ingelogd is. Wat wel blijft, zijn de regels in de logboeken. Die verdwijnen na hun eigen
bewaartermijn. Ook het bewijs van het wissen is zo'n regel, met het nummer van de ondernemer. De
Notificatie Service kreeg bij elke melding het nummer en het onderwerp; die gegevens vallen onder
haar eigen bewaartermijn, en ook zij hoort van de wissing.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    actor OND as Ondernemer
    participant IL as Interactielaag
    participant SB as Stelselbeheerder
    participant PRO as Profiel Service
    participant ORG as Overheidsorganisatie
    participant MAG as Berichtenmagazijn

    OND->>IL: Logt in en vraagt: wis al mijn berichten
    IL->>SB: Verzoek tot wissen, van deze ingelogde ondernemer
    SB->>PRO: Van welke organisaties ontving deze ondernemer berichten, nu en eerder?
    PRO-->>SB: Organisaties
    loop Per organisatie
        SB->>ORG: Verzoek tot wissen voor deze ondernemer
        ORG->>ORG: Beperkt een wettelijke plicht of de Archiefwet het wissen?
        alt Wissen mag, geheel of deels
            ORG->>MAG: Wis alle berichten van deze ontvanger, met bijlagen
            MAG->>MAG: Wis de berichten
            Note over MAG: Legt het wissen vast in het logboek
            MAG-->>ORG: Gewist, met het aantal berichten
            ORG-->>SB: Gewist, of deels gewist met de reden
        else Wissen mag niet
            ORG-->>SB: Niet gewist, met de reden
        end
    end
    SB-->>OND: Uitkomst per organisatie
```

## Als het misgaat

| Situatie | Wat het stelsel doet | Wat de ondernemer merkt |
|---|---|---|
| Het verzoek komt niet van de ondernemer zelf | Het portaal neemt een verzoek alleen aan van wie ingelogd is, en dan alleen voor zijn eigen berichten | Een ander kan zijn berichten niet laten wissen |
| Een organisatie reageert niet binnen een maand | De stelselbeheerder herinnert haar eraan, en meldt de ondernemer welke organisatie nog niet geantwoord heeft | Een uitkomst met een open plek |
| Een magazijn is niet bereikbaar | De organisatie wist zodra het magazijn weer werkt | De uitkomst van die organisatie komt later |
| Een wettelijke plicht of de Archiefwet verbiedt het wissen | De organisatie wist niet, of alleen een deel | De reden, per organisatie |
| De ontvanger is een rechtspersoon, zoals een bv of nv | Het recht op wissing geldt niet voor de onderneming zelf. Het geldt wel voor personen over wie een bericht gaat | De stelselbeheerder legt dat uit |
| De ondernemer is ingelogd tijdens het wissen | Zijn lijst klopt pas weer na het volgende ophalen | Gewiste berichten staan nog in de lijst, maar zijn niet meer te openen |

## Nog niet gerealiseerd

Deze use case is nog niet gebouwd en nog niet afgesproken.

- Een magazijn heeft geen opdracht om alle berichten van één ontvanger in één keer te wissen. De
  ondernemer kan alleen berichten één voor één verwijderen (use case 8), en dat is markeren, geen
  wissen: het magazijn ruimt ze pas na de bewaartermijn op.
- Er is geen werkwijze voor de stelselbeheerder om een verzoek door te zetten en de uitkomsten te
  verzamelen, en het portaal kent geen verzoek tot wissen.
- Het definitief opruimen staat nog niet in het logboek van het magazijn (use case 8).
- Voor de logboekregels is nog geen bewaartermijn ingesteld.

## Open vragen

- **Loopt het verzoek via de stelselbeheerder, of per organisatie?** De AVG legt het verzoek bij
  elke verantwoordelijke organisatie. Via de stelselbeheerder hoeft de ondernemer niet te weten
  welke organisaties berichten hebben, maar dan krijgt de stelselbeheerder een coördinerende rol
  die nog nergens is vastgelegd.
- **Wie heeft dit recht?** Het hangt aan de rechtsvorm, niet aan het type nummer: een ondernemer
  met een BSN, een eenmanszaak, en de vennoten van een vof of maatschap, of die nu via het
  KVK-nummer of het RSIN zijn aangeschreven.
- **Wat gebeurt er bij een wissing met de logboekregels?** Daar is een bewaartermijn en een
  afspraak voor nodig.
- **Kent de Profiel Service eerdere voorkeuren?** Een organisatie waarvoor de ondernemer zijn
  voorkeur heeft ingetrokken, kan nog berichten van hem hebben. Is die geschiedenis er niet, dan
  moet het verzoek breder rondgestuurd worden, en krijgen organisaties zonder relatie met de
  ondernemer zijn nummer en het feit dat hij een privacyrecht uitoefent.
- **Hoe lang bewaart een magazijn een bericht dat nooit verwijderd is?** Nu ruimt het magazijn
  alleen verwijderde berichten op; een bericht dat de ondernemer laat staan, blijft voor altijd.
- Laat een privacyjurist of de functionaris gegevensbescherming meekijken voordat deze use case
  naar Logius gaat.
