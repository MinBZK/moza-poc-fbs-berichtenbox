# Ontdubbeling met elke persona uit de berichtenbox

**Status:** Uitgevoerd

## Context

De demo-console heeft een eigen berichtenbox naast die van de proeftuin, en die eigen berichtenbox
gaat weg (MinBZK/MijnOverheidZakelijk#1055). De knop *Tweemaal hetzelfde event sturen*
(scenario 13) werkt nu alleen met een persona met een BSN. Alleen de eigen berichtenbox toont zo'n
persona, dus zodra die weg is, is het scenario nergens meer te zien. Besloten in #1055: de
ontdubbeling moet te spelen zijn met elke persona die in de berichtenbox te kiezen is.

De uitvraag staat dat al toe: `POST /api/v1/aanmeldingen` neemt een ontvanger van het type BSN,
RSIN, KVK of OIN aan. De beperking zit alleen in de console:

- `OntdubbelingResource` weigert een persona zonder BSN met een 400;
- `OntdubbelingService` zet het type van de ontvanger vast op `BSN`;
- `bediening.js` toont in de keuzelijst alleen persona's met een BSN.

## Ontwerpkeuzes

- **De service krijgt een getypeerde ontvanger** (`Ontvanger(type, waarde)`, die al bestaat voor
  het event) in plaats van een kaal BSN. Het type komt uit de persona-inrichting, die het bij het
  opstarten al toetst (`Identificatiecheck`). De console valideert het dus niet nog een keer.
- **De keuzelijst blijft `personas` uit `GET /api/demo/omgeving` lezen, zonder filter.** Die
  lijst komt uit dezelfde `PersonaService` als `/api/demo/personas`, waar de proeftuin zijn
  persona's vandaan haalt. Dus elke persona die daar te kiezen is, staat hier ook in. Niet
  `berichtPersonas`: de ontdubbeling levert niet aan een magazijn, dus een persona zonder magazijn
  kan haar ook spelen.
- **Het paneel heeft `ontvanger` niet meer nodig.** De veldcontrole van die lijst eist alleen nog
  `id`, net als bij `berichtPersonas`.
- **De eis "minstens één persona met een BSN"** in `PersonaConfiguratieTest` vervalt: alleen de
  ontdubbeling had die nodig.
- **Het runbook blijft in deze wijziging ongemoeid.** De zin "kies een persona met een BSN" in §7
  staat direct naast een regel die MinBZK/moza-poc-fbs-berichtenbox#421 herschrijft, en wie als
  tweede landt, zou daarop een conflict krijgen. Wat er staat blijft waar, want een BSN-persona
  werkt nog steeds; de beperking verdwijnt mee bij het herschrijven van het runbook in #1055. Om
  dezelfde reden blijft de KDoc van `OntdubbelingService` staan zoals hij is.

## Stappen

1. Tests eerst, en die zien falen:
   - `OntdubbelingServiceTest`: het event draagt type en waarde van de ontvanger, voor BSN, KVK en
     RSIN.
   - `PaneelContractTest`: elke persona uit `personas` levert met zijn eigen ontvanger een 200 op,
     en de lijst bevat minstens twee types. Het testdubbel neemt de ontvanger op in plaats van het
     nummer.
   - `PaneelPadenTest`: `vulPersonas` filtert de lijst niet.
   - `PersonaMetCijfersTest`: volgt het nieuwe testdubbel.
2. `OntdubbelingService`, `OntdubbelingResource` en `bediening.js` aanpassen.
3. `PersonaConfiguratieTest`: de BSN-eis eruit.

## Verificatie

- `./mvnw clean test -pl demo/demo-console -am`
- `./mvnw detekt:check -pl demo/demo-console`
- `node --check demo/demo-console/src/main/resources/META-INF/resources/bediening.js`
- `git merge-tree` van deze branch tegen `feature/943-1a-sessies`: er mag geen conflict uitkomen.
