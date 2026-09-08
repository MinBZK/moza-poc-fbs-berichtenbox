# Berichtenlijst zonder berichtinhoud

**Status:** Uitgevoerd

Hoort bij [MinBZK/MijnOverheidZakelijk#1053](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1053).

## Context

De magazijn-spec sprak zichzelf tegen over het lijstantwoord van `GET /berichten`. De
beschrijving bij de operatie zei dat een samenvatting *geen* tekstuele inhoud bevat, met
gegevensminimalisatie (AVG art. 5(1)(c)) als onderbouwing. Het schema `BerichtSamenvatting`
zette `inhoud` in `required`, met als onderbouwing dat een consument zijn cache dan in één
lijst-call kan vullen. Het schema won in de uitvoering: `ophaal/BerichtDtoMapper` vulde
`inhoud` op elke samenvatting.

Daarmee beloofde het koppelvlak minimalisatie die niet plaatsvond. De sessiecache van de
berichten-uitvraag haalde bij elke sessie de volledige tekst van élk bericht uit élk
magazijn op en zette die in Redis — ook van berichten die de ondernemer nooit opent.

## Keuze

**De berichtinhoud gaat uit het lijstantwoord.** De onderbouwing bij de operatie blijft
staan en wordt waargemaakt; het schema volgt.

De achterliggende regel, breder dan deze ene spec-regel: *het centrale deel van het stelsel
leest berichtteksten niet vooruit uit de magazijnen*. De tekst blijft bij de bron en wordt
opgehaald op het moment dat de ontvanger het bericht opent.

Overwogen en verworpen: `inhoud` in de lijst laten staan en de AVG-alinea schrappen. Dat
lost de tegenstrijdigheid ook op, maar kiest de kant waarbij het stelsel meer
persoonsgegevens verplaatst en centraal bewaart dan het gebruikt.

## Wat er wél in de samenvatting blijft

`bijlagen[]` (alleen `bijlageId` + `naam`) blijft in de samenvatting. Dat zijn
download-handles, geen berichttekst, en ze horen bij de kopgegevens die een lijst toont.
De operatiebeschrijving claimde ten onrechte dat ook die ontbraken; die claim is
gecorrigeerd in plaats van het veld te verwijderen.

## Gevolgen per plek

| Plek | Wijziging |
|---|---|
| `berichtenmagazijn-api.yaml` | `inhoud` uit `BerichtSamenvatting` (property én `required`); operatie- en schemabeschrijving vertellen één verhaal |
| `berichtenmagazijn/ophaal/BerichtDtoMapper` | Vult `inhoud` niet meer op de samenvatting |
| `fbs-berichtensessiecache` | `Bericht.inhoud` en `MagazijnBericht.inhoud` vervallen; het hash-veld `inhoud` wordt niet meer geschreven of gelezen |
| `berichtenuitvraag/BerichtOphaalService` | Haalt de inhoud bij het openen van een bericht op bij het magazijn (`GET /berichten/{id}`) |
| `berichtenuitvraag/AanmeldService` | Neemt de inhoud uit het CloudEvent niet meer over in de cache |
| `demo/magazijn-simulator` | Volgt de spec; de comment die de tegenstrijdigheid documenteerde vervalt |
| `workspace.dsl` | Relatiebeschrijvingen noemen geen berichtinhoud meer op het lijstpad |

## Prestatie-effect

De berichtenlijst zelf wordt niet trager: die komt onveranderd uit de sessiecache, ook bij
een ondernemer die bij veel organisaties is aangesloten. De fan-out over magazijnen blijft
één lijst-call per magazijn — er komt geen detail-call per bericht bij. Het lijstantwoord
wordt kleiner.

Wat verandert is het openen van één bericht: dat kost nu één aanroep naar het bronmagazijn.
Die aanroep valt samen met de aanroep die het ophalen van een bijlage toch al deed, en loopt
over hetzelfde pad met dezelfde foutafhandeling.

Nieuw gedrag bij storing: is het bronmagazijn onbereikbaar, dan is het bericht niet te
openen (502) terwijl het vroeger uit de cache kwam. De lijst blijft in dat geval wél
zichtbaar. Dat is de prijs van niet-vooruit-kopiëren en is hier bewust betaald.

## Cache-compatibiliteit

Het RediSearch-schema verandert niet: `inhoud` was geen geïndexeerd veld (alleen `onderwerp`
is TEXT). Bestaande cache-entries met een `inhoud`-hash-veld blijven leesbaar — het veld
wordt alleen niet meer uitgelezen. Geen schema-bump nodig.

## Wat er niet verandert voor de berichtenbox

De uitvraag-API blijft gelijk: haar lijstantwoord bevatte al geen berichttekst, en het
detailantwoord bevat die nog steeds. Eén detail is nieuw: het antwoord op een
status-wijziging (`PATCH /berichten/{id}`) draagt geen `inhoud` meer. Dat veld was daar al
optioneel, en die aanroeper heeft de tekst net getoond — een extra magazijn-aanroep per
markeer-als-gelezen zou alleen verkeer kosten.

## Openstaand

- Het aanmeld-CloudEvent (`AangemeldBerichtData`) draagt `inhoud` nog als verplicht veld.
  De uitvraag valideert dat veld nog wel, maar bewaart het niet meer. Het uit het
  notificatie-contract halen is dezelfde afweging, maar raakt een ander koppelvlak;
  voorgelegd aan de opdrachtgever in plaats van hier meegenomen.
- Het magazijn laadt voor een lijstantwoord nog steeds de volledige `inhoud`-kolom uit de
  database, om die daarna weg te gooien. Een projectie op de kopgegevens scheelt werk in de
  database; los van deze wijziging op te pakken.

## Verificatie

- `./mvnw clean verify -pl services/berichtenmagazijn -am`
- `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am`
- `./mvnw clean test -pl services/berichtenuitvraag -am`
- `./mvnw clean test -pl demo/magazijn-simulator -am`
- Contracttests toetsen dat een lijstantwoord geen `inhoud` draagt en dat het detailantwoord
  die wél draagt.
