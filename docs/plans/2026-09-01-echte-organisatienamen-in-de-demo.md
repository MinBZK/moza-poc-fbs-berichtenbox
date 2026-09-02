# De magazijnen dragen de naam van hun organisatie

**Status:** Uitgevoerd

## Aanleiding

In de Berichtenbox verschijnen de twee echte magazijnen als "Magazijn A" en "Magazijn B". Dat zijn
infrastructuurnamen; de demo vertelt een verhaal over een ondernemer die post krijgt van RVO en de
Belastingdienst. De naam die de ondernemer ziet hoort de organisatie te zijn, niet de installatie.

Tegelijk dragen twee gesimuleerde magazijnen (`…0001` en `…0003`) diezelfde twee organisaties als
naam. Dat botst zodra beide in één lijst staan: dezelfde afzender lijkt dan twee keer voor te komen,
met verschillende berichten.

## Waar de naam vandaan komt

De Berichtenbox toont wat de uitvraag in zijn ophaal-events meestuurt (`berichtenbox.js`:
`magazijnNamen`, gevuld uit `magazijn-bevraging-*`). Die naam komt uit het magazijnregister —
`magazijnen."<OIN>".naam` — en niet uit het magazijn zelf. Eén plek dus, per soort magazijn:

| Magazijn | Naam staat in |
|---|---|
| De twee echte | `services/berichtenuitvraag/src/main/resources/application.properties` |
| De 98 gesimuleerde | `ORGANISATIES`/`GEMEENTEN` in `demo/genereer-magazijnen.py`, via het gegenereerde register |

Het bedieningspaneel noemt ze al bij hun organisatie ("Magazijn A — RVO"); alleen de keten zelf niet.

## Aanpak

1. **De echte twee krijgen hun organisatienaam:** `00000000000000100000` wordt `RVO`,
   `00000001823288444000` wordt `Belastingdienst`. Die twee vormen — en niet de voluit geschreven
   namen — omdat de rest van de demo ze al zo gebruikt: de sjablonen in `GeneratorProducer`, de
   ondertekening van de berichten in `basis.json` en de kopjes in het paneel.
2. **De gesimuleerde twee krijgen een andere organisatie:** `Belastingdienst` en `Rijksdienst voor
   Ondernemend Nederland` vallen uit `ORGANISATIES` en worden `Centraal Justitieel Incassobureau` en
   `Rijksdienst voor Identiteitsgegevens` — even echte uitvoeringsorganisaties, geen overlap met de
   twee die de demo zelf draait. De lijst houdt zijn lengte en volgorde, dus alleen de naam van
   magazijn 1 en 3 verandert; hun OIN, gedrag en volgnummer blijven wat ze waren.
3. **De documentatie volgt** waar zij "Magazijn A" als voorbeeldwaarde toont.

## Gevolg voor de gedeelde omgeving

De namen van de gesimuleerde magazijnen staan in twee met de hand geüploade ZAD-attachments
(`magazijnen-register` op `uitvraag`, `magazijn-simulator-set` op `magazijnsimulator`). Een
codewijziging bereikt die niet: zolang ze niet opnieuw gegenereerd en geüpload zijn, blijft ZAD de
oude namen tonen en staan Belastingdienst en RVO daar dubbel in de lijst. Dat is handwerk, en het
hoort bij deze wijziging vermeld te worden in plaats van stilzwijgend te wachten.

De naam van de twee echte magazijnen zit wél in het image van de uitvraag en reist dus vanzelf mee.

## Verificatie

- `demo/genereer-magazijnen.py` draaien en nagaan dat geen enkele gesimuleerde naam voorkomt in de
  namen van de twee echte magazijnen.
- De testsuites van de uitvraag en het magazijnregister: geen enkele hangt aan de displaynaam.
- Na uitrol: een ophaalronde in de Berichtenbox toont RVO en Belastingdienst als afzender.
