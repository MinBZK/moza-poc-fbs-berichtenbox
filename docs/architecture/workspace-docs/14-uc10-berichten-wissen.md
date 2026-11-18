# Use case 10: Alle berichten van een ontvanger wissen

Een burger of ondernemer (de ontvanger) laat alle eigen berichten wissen, met een beroep op het recht
op gegevenswissing.

| | |
|---|---|
| **Actor** | Een ontvanger die een natuurlijk persoon is: elke burger, en een ondernemer zonder rechtspersoonlijkheid. Met de stelselbeheerder en de verzendende organisaties |
| **Aanleiding** | De ontvanger wil dat alle eigen berichten, met bijlagen, uit het stelsel verdwijnen |
| **Resultaat** | Elke organisatie heeft de berichten in haar magazijn gewist, of uitgelegd waarom ze dat (deels) niet mag. De ontvanger krijgt per organisatie de uitkomst, en het wissen is vastgelegd |

## Verloop

1. De ontvanger logt in op het portaal en dient daar één verzoek in. Na het inloggen staat vast wie
   het verzoek doet: wissen is onomkeerbaar, dus niemand mag het voor een ander aanvragen.
2. Het portaal geeft het verzoek door aan de stelselbeheerder.
3. Er is geen centrale opslag van berichten, maar een magazijn neemt alleen berichten aan van
   organisaties waarvan de ontvanger berichten via de berichtenbox wil ontvangen (use case 1). De
   stelselbeheerder vraagt de Profiel Service welke organisaties dat zijn, nu en eerder, en stuurt
   het verzoek alleen naar die organisaties. Zo krijgen organisaties zonder relatie met de
   ontvanger diens gegevens niet.
4. Elke organisatie is verantwoordelijk voor de berichten in haar eigen magazijn, en beslist zelf
   over het verzoek. Ze toetst of een wettelijke plicht of de Archiefwet het wissen beperkt.
5. Mag de organisatie wissen, dan geeft ze haar magazijn opdracht om alle berichten van deze
   ontvanger te wissen, met bijlagen. Het magazijn wist ze en legt dat vast in zijn logboek.
6. De organisatie meldt de stelselbeheerder de uitkomst: gewist, deels gewist met de reden, niet
   gewist met de reden, of geen berichten gevonden. Organisaties aan wie ze de gegevens zelf
   verstrekte, brengt ze op de hoogte.
7. De stelselbeheerder meldt de ontvanger de uitkomst per organisatie, binnen een maand na het
   verzoek.

De uitvraag hoeft niets te wissen: die bewaart de berichtenlijst alleen zolang de ontvanger
ingelogd is. Wat wel blijft, zijn de regels in de logboeken. Die verdwijnen na hun eigen
bewaartermijn. Ook het bewijs van het wissen is zo'n regel, met het nummer van de ontvanger. De
Notificatie Service kreeg bij elke melding het nummer en het onderwerp; die gegevens vallen onder
haar eigen bewaartermijn, en ook zij hoort van de wissing.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    actor ONT as Ontvanger
    participant IL as Interactielaag
    participant SB as Stelselbeheerder
    participant PRO as Profiel Service
    participant ORG as Overheidsorganisatie
    participant MAG as Berichtenmagazijn

    ONT->>IL: Logt in en vraagt: wis al mijn berichten
    IL->>SB: Verzoek tot wissen, van deze ingelogde ontvanger
    SB->>PRO: Van welke organisaties ontving deze ontvanger berichten, nu en eerder?
    PRO-->>SB: Organisaties
    loop Per organisatie
        SB->>ORG: Verzoek tot wissen voor deze ontvanger
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
    SB-->>ONT: Uitkomst per organisatie
```

## Als het misgaat

| Situatie | Wat het stelsel doet | Wat de ontvanger merkt |
|---|---|---|
| Het verzoek komt niet van de ontvanger zelf | Het portaal neemt een verzoek alleen aan van wie ingelogd is, en dan alleen voor de eigen berichten | Een ander kan deze berichten niet laten wissen |
| Een organisatie reageert niet binnen een maand | De stelselbeheerder herinnert haar eraan, en meldt de ontvanger welke organisatie nog niet geantwoord heeft | Een uitkomst met een open plek |
| Een magazijn is niet bereikbaar | De organisatie wist zodra het magazijn weer werkt | De uitkomst van die organisatie komt later |
| Een wettelijke plicht of de Archiefwet verbiedt het wissen | De organisatie wist niet, of alleen een deel | De reden, per organisatie |
| Alleen voor ondernemers: de ontvanger is een rechtspersoon, zoals een bv of nv | Een bv of nv heeft het recht op wissing niet zelf (AVG, overweging 14). Personen over wie een bericht gaat hebben het wel, voor de gegevens over hen, zoals de directeur van een eenpersoons-bv. Een jurist toetst dit per geval | De stelselbeheerder legt dat uit. Zo'n persoon kan zelf een verzoek doen |
| De ontvanger is ingelogd tijdens het wissen | De lijst klopt pas weer na het volgende ophalen | Gewiste berichten staan nog in de lijst, maar zijn niet meer te openen |

## Nog niet gerealiseerd

Deze use case is nog niet gebouwd en nog niet afgesproken ([#1596](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1596)).

- Een magazijn heeft geen opdracht om alle berichten van één ontvanger in één keer te wissen. De
  ontvanger kan alleen berichten één voor één verwijderen (use case 8), en dat is markeren, geen
  wissen: het magazijn ruimt ze pas na de bewaartermijn op ([#1596](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1596)).
- Er is geen werkwijze voor de stelselbeheerder om een verzoek door te zetten en de uitkomsten te
  verzamelen, en het portaal kent geen verzoek tot wissen ([#1596](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1596)).
- Het definitief opruimen staat nog niet in het logboek van het magazijn (use case 8) ([#549](https://github.com/MinBZK/MijnOverheidZakelijk/issues/549)).
- Voor de logboekregels is nog geen bewaartermijn ingesteld ([#1595](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1595)).

## Open vragen

- **Loopt het verzoek via de stelselbeheerder, of per organisatie?** De AVG legt het verzoek bij
  elke verantwoordelijke organisatie. Via de stelselbeheerder hoeft de ontvanger niet te weten
  welke organisaties berichten hebben, maar dan krijgt de stelselbeheerder een coördinerende rol
  die nog nergens is vastgelegd.
- **Wie heeft dit recht?** Elke burger. Bij ondernemers hangt het aan de rechtsvorm, niet aan het
  type nummer: een ondernemer met een BSN, een eenmanszaak, en de vennoten van een vof of maatschap,
  of die nu via het KVK-nummer of het RSIN zijn aangeschreven.
- **Wat gebeurt er bij een wissing met de logboekregels?** Daar is een bewaartermijn en een
  afspraak voor nodig.
- **Kent de Profiel Service eerdere voorkeuren?** Een organisatie waarvoor de ontvanger de
  voorkeur heeft ingetrokken, kan nog berichten hebben. Is die geschiedenis er niet, dan moet het
  verzoek breder rondgestuurd worden, en krijgen organisaties zonder relatie met de ontvanger diens
  nummer en het feit dat die een privacyrecht uitoefent.
- **Hoe lang bewaart een magazijn een bericht dat nooit verwijderd is?** Nu ruimt het magazijn
  alleen verwijderde berichten op; een bericht dat de ontvanger laat staan, blijft voor altijd ([#1597](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1597)).
- Een privacyjurist of de functionaris gegevensbescherming moet deze use case nog beoordelen.
