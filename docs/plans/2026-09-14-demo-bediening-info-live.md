# Demo-bediening: Info-blad voorop, met live gegevens

**Status:** Uitgevoerd

## Context

Het Info-blad van de demo-bediening stond achteraan en bestond uit knoppen die elk één `GET` deden
en de ruwe JSON in de meldingsbalk zetten. Wie tijdens een demo wilde zien hoe de stack erbij stond,
moest dus eerst naar het laatste tabblad en daar per onderdeel klikken. De toestandsbalk bovenaan
las diezelfde gegevens al elke vijf seconden, maar toonde ze alleen als korte chips.

## Wijziging

- Info is het eerste tabblad en staat open zolang er geen bewaarde stand is.
- Vijf blokken, elk met een tijdlabel ("bijgewerkt 12 s geleden") en een ↻:

| Blok | Bron | Bijwerken |
|---|---|---|
| Echte magazijnen | `/api/demo/status` | mee met de poll van de toestandsbalk (5 s) |
| Gesimuleerde magazijnen | `/api/demo/simulator/magazijnen` (met berichten per magazijn uit het beheerpad van de simulator) | elke 30 s en na elke actie, alleen terwijl het blad in beeld is |
| Stroom | `/api/demo/tempo` | mee met de poll van de toestandsbalk (5 s) |
| Storingen | `/api/demo/storing` | idem |
| Componenten | `/api/demo/bereikbaarheid` | idem |
| Knoppen in deze omgeving | `/api/demo/omgeving` | bij het inrichten; ↻ leest opnieuw via `richtIn(true)` |
| Persona's | `/api/demo/omgeving` | idem; tabel met per persona of er een echt magazijn voor is |

- De uitlees-knoppen en hun samenvatters (`berichten`, `storingen`, `omgeving`, `personas`,
  `simulator-magazijnen`) zijn weg; geen andere knop gebruikte ze.

## Ontwerpkeuzes

- **Geen eigen uitlezing voor de toestand-blokken.** Ze lezen mee met `verversToestand`, zodat blok en
  chip nooit een ander getal tonen. Hun ↻ is daarom `verversToestand(true)` en werkt alle drie bij.
- **Cache in `sessionStorage`**, zoals de rest van de paneelstand: overleeft een refresh, niet het
  sluiten van het tabblad. Eén schrijfplek (`bewaarInfo`), en alleen inhoud die door `bewaarbaar` ging.
- **Persona's alleen als label in de cache.** Het omgevingsantwoord draagt per persona een BSN of
  KVK-nummer; `omgevingInfo` neemt alleen de labels over.
- **Een mislukte uitlezing wist niets.** Het blok houdt de laatste stand en het tijdlabel zegt dat de
  laatste poging mislukte — tijdens een storing is die stand juist nodig.
- **Tekenen alleen bij een ander antwoord**, zodat een schermlezer niet elke vijf seconden bovenaan de
  lijst begint. Het tijdlabel draagt geen `aria-live`.
- **De simulatortabel staat statisch in de opmaak**; alleen de rijen worden vervangen, zodat een
  uitgeklapte lijst bij een verversing open blijft.

## Verificatie

- `./mvnw clean test -pl demo/demo-console -am`, met de nieuwe `PaneelInfoTest` (tabvolgorde, blokken
  in opmaak en script, ritme, cache zonder persona-nummers, geen `innerHTML`, modusvolgorde gelijk aan
  `GedragModus`).
- Handmatig in de demo-stack: blad opent voorop, blokken vullen zich, refresh toont de bewaarde stand,
  ↻ draait tot het antwoord er is.
