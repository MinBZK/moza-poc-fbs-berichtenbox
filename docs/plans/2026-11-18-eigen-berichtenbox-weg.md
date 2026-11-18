# De eigen berichtenbox van de demo verwijderen

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#1055.

## Context

De demo had twee berichtenboxen: die van de proeftuin, in een frame naast het bedieningspaneel, en
een eenvoudige eigen pagina in `demo/demo-console` (`berichtenbox.{html,js,css}`). Het issue vroeg
eerst om te bepalen waar die eigen pagina thuishoort; in de discussie op het issue is besloten haar
te verwijderen (opdrachtgever, 2026-11-09). De aanleiding om haar te houden, dat de proeftuin een
aangemeld bericht niet vanzelf liet zien, verviel met MinBZK/MijnOverheidZakelijk#1025: de proeftuin
houdt `_volgen` open en een aangemeld bericht verschijnt daar zonder nieuwe ophaalronde.

Wat alleen de eigen pagina liet zien (uitkomst per organisatie, BSN-persona's, een ophaalronde op
commando) is elders belegd of bewust losgelaten: de berichtenbox van de ondernemer is de proeftuin,
en straks het MOZa-portaal (MinBZK/MijnOverheidZakelijk#1345, #1453).

## Snede

1. **Weghalen** (deze PR): de pagina, alles wat alleen voor haar bestond, en de documentatie die op
   haar knoppen leunde.
   - `berichtenbox.{html,js,css}`, de verwijzing in `index.html`, `BerichtenboxVolgenTest`.
   - `uitvraagBasis` uit `GET /api/demo/omgeving`, met `OmgevingConfig.uitvraagBasis()` en
     `UITVRAAG_BASIS`. `UITVRAAG_URL` blijft: daarmee stuurt de console server-side de
     ontdubbeling-webhook.
   - CORS op de uitvraag voor de console-origin (`:8095` lokaal, `democonsole` in de ZAD-regex). Het
     paneel roept de uitvraag niet vanuit de browser aan. Proeftuin (`:8096`) en demo-proxy
     (`:8097`) houden hun regel, want schrijfverzoeken dragen hun origin.
   - Runbook en `verify-zad.md`: elk scenario speelt in de berichtenbox van de proeftuin.
2. **`ontvanger` uit het omgevingsantwoord** (gestapelde PR): na het weghalen leest geen enkele
   browserpagina van de console het identificatienummer van een persona nog, dus het hoeft er niet
   meer heen. Eigen, smallere persona-vorm in de console; `/api/demo/personas` van de personadienst
   houdt `ontvanger`, want daarmee start de proeftuin een sessie.

## Volgorde ten opzichte van de sessies

Sinds MinBZK/moza-poc-fbs-berichtenbox#421 eist de uitvraag een inlogsessie. De gepinde proeftuin
kent die nog niet; dat doet MinBZK/moza-poc#180. Tot die PR gemerged is en de proeftuin-pin bij is,
is de eigen pagina de enige berichtenbox van de demo die werkt. Deze PR mag daarom pas na die
pin-bump mergen. Live getest is hij met de preview-image van MinBZK/moza-poc#180 via
`PROEFTUIN_IMAGE`.

## Ontwerpkeuzes

- **Niet verplaatsen naar een eigen module of repository.** Het issue noemde die opties; ze vervielen
  met het besluit tot verwijderen.
- **De BSN-persona's blijven in de personadienst.** Ze kosten niets, de keten blijft BSN-ontvangers
  kennen, en de proeftuin of een andere box kan ze later weer tonen. Het runbook speelt de scenario's
  met KVK-persona's.
- **De inlogmuur-test loopt alle pagina's van de console af** in plaats van een vaste lijst, zodat
  een nieuwe pagina vanzelf meetelt nu de tweede rij uit die lijst wegvalt.
- **De plannen onder `docs/plans/`** die de pagina beschrijven blijven staan: ze leggen vast waarom
  ze er was.

## Verificatie

- `./mvnw clean verify -pl demo/demo-console -am`.
- Lokaal de demo-stack (hostnet) met de proeftuin van MinBZK/moza-poc#180
  (`ghcr.io/minbzk/moza-poc/preview:pr-180-2d9bad0`), in een headless browser via CDP:
  - het paneel toont het frame, `berichtenbox.html` geeft 404;
  - eerste bezoek: sessie, 409, `_ophalen`, `_volgen`;
  - *Bericht plaatsen*: het bericht staat na ~3,6 s in de lijst, zonder één verzoek van de
    berichtenbox — dus via de aanmelding en `_volgen`, niet via een nieuwe ronde;
  - *Tweemaal hetzelfde event sturen*: precies één bericht erbij;
  - herladen start geen nieuwe ronde; sessies van de persona beëindigen plus een klik wel;
  - scenario 3 (magazijn A uit) en 9 (profiel uit, na 30 s) tonen hun melding;
  - CORS: een PATCH met de origin van de demo-proxy slaagt, met die van de console (`:8095`) 403.
- Scenario 15 bleek in de proeftuin niet als afkap-melding te zien: die toont één melding, en bij
  Grootbedrijf wint "niet geleverd". Het runbook wijst daarvoor naar de netwerk-tab en de log.
- Na de merge op ZAD: de CORS-regex van de uitvraag en de alias `UITVRAAG_BASIS` op `democonsole`
  in Operations Manager bijwerken; de repo beschrijft ze alleen.
