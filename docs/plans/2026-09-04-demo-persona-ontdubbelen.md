# Eén beschrijving van een demo-persona

**Status:** Uitgevoerd

Issue: [MinBZK/MijnOverheidZakelijk#1071](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1071)

## Context

De demo-identiteit stond twee keer beschreven: `DemoPersona` in `demo-personas` (zes velden, zes
`require`-eisen plus de `Identificatiecheck`) en `Persona` in
`demo-console/generator/AanleverModel.kt` (vijf velden, geen eisen). `GeneratorProducer` schreef de
ene op de andere over, met `label` → `naam` als enige verschil naast het weggelaten `bron`.

Die kopie kostte drie invarianten: `DemoPersona` weigert een lege id, een lege label en een
ongeldig identificatienummer, `Persona` accepteerde alle drie. De generator had er daarom eigen
`require`-regels voor teruggezet — dezelfde controle, tweede plek, en alleen voor wie via de
generator binnenkomt.

## Wijziging

`DemoBerichtGenerator` werkt rechtstreeks op `DemoPersona`; `Persona` is geschrapt.

`Doelpersona` blijft: dat is geen kopie van de identiteit maar het paneel-contract, dat het
identificatienummer bewust niet draagt.

### Invarianten in het init-blok van de generator

Behouden — dit is kennis die alleen de generator heeft:

| Eis | Waarom niet elders |
|-----|--------------------|
| minstens één persona | `doelgroep()` mag niet leeg zijn; de personadienst staat een lege set niet toe, maar deze constructor wel |
| id's uniek | de configuratie sleutelt op id en kan geen twee leveren, deze constructor wel |
| persona heeft magazijnen | `DemoPersona` staat nul magazijnen expliciet toe (Grootbedrijf). `metMagazijnen()` filtert ze normaal weg, maar de constructor neemt ze aan |
| magazijn-OIN in `organisaties` | `MagazijnKennisUitInrichting` toetst tegen `demo.magazijnen` (aanlever-URL's), de generator tegen zijn sjablonen-map — twee verschillende verzamelingen |
| organisatie heeft sjablonen | pre-existent, en strikt genomen een invariant van `Organisatie` zelf; blijft staan waar hij stond, want die opruiming staat los van deze wijziging |

Vervallen — staat in het init-blok van `DemoPersona` en is daar getoetst:

- id niet leeg
- label niet leeg
- `Identificatiecheck.valideer(type, waarde)`

Met de lege id weg vervalt ook de `forEachIndexed`: de melding kan de persona weer bij zijn id
noemen in plaats van bij zijn positie.

### Aanroepers

- `GeneratorProducer` geeft `personaService.metMagazijnen()` ongewijzigd door.
- `naam` heet overal `label`.

## Verificatie

- `./mvnw clean test -pl demo/demo-console -am` en `-pl demo/demo-personas -am`
- `PersonaConfiguratieTest` (`@QuarkusTest`) bouwt de generator uit de échte configuratie en dekt
  daarmee acceptatiecriterium "het opvoeren en de keuzelijsten werken ongewijzigd".
- De vervallen generator-tests (lege id, lege naam, elfproef) hebben hun tegenhanger in
  `DemoPersonaTest`; de controle verdwijnt niet, alleen de tweede plek.
