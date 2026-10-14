# Simulator-modus ONVOLLEDIG — implementatieplan

**Status:** Uitgevoerd

**Doel:** het afkapsignaal ([MinBZK/MijnOverheidZakelijk#1072](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1072))
is zonder configuratiewijziging te zien bij een persona in de demo. Tot nu toe kon dat alleen door
`BERICHTENSESSIECACHE_MAX_BERICHTEN_PER_MAGAZIJN` te verlagen, en dan kapt de uitvraag élke
organisatie van élke persona af.

## Context

De uitvraag meldt een organisatie als afgekapt zodra die een bruikbaar totaal (`totalElements`) noemt
dat hoger is dan wat er geleverd werd, of zodra de eigen bovengrens (500) geraakt wordt. Het
bedieningspaneel zet 27 berichten per ontvanger per magazijn klaar (de simulator staat er hoogstens
tweehonderd per keer toe) en de simulator noemt altijd het echte totaal, dus geen persona komt daar.

## Ontwerp

- **Nieuwe `GedragModus.ONVOLLEDIG`:** het magazijn antwoordt vlot en levert alle berichten uit zijn
  lijst, maar noemt in `totalElements` ook een archief dat de lijst niet levert
  (`Gedrag.ONVOLLEDIG_ARCHIEF` = 480; bij de 27 berichten van de standaardvulling "27 van 507"). Alleen als er voor die
  ontvanger iets staat: geen post blijft geen post.
  - Bewust geen modus die berichten weglaat: die zou de getallen van de mappen-demo (Vergunningen 87)
    verschuiven en een gat in de lijst maken dat in de demo niets toevoegt. Het afkap-pad in de
    uitvraag is hetzelfde: een bruikbaar totaal boven het geleverde.
  - Alleen op de lijst (`GET /berichten`); detail, bijlage, patch en delete gedragen zich normaal.
  - Latentie en foutstatus zoals `NORMAAL` (zelfde bezwaar-regel); telt in de mappen-demo als
    leverend, zodat Vergunningen 87 blijft.
- **Verdeling:** volgnummer 17 (Autoriteit Persoonsgegevens) wordt ONVOLLEDIG. Valt binnen de
  persona's met 45 en 100 organisaties (Grootbedrijf, Landelijk Concern, Demo-onderneming 4), niet
  binnen de kleine persona's — zelfde redenering als WEIGERT en MALFORMED. 72 normaal → 71.
- **Console:** `MODUS_VOLGORDE` in `bediening.js` krijgt de modus (de consistentietest eist dat).
- **Docs:** simulator-README (modus-tabel en verdeling), demo-runbook (scenario: de melding bij
  Grootbedrijf, ook na **Vernieuw**), mappen-tabel (Vergunningen: ook de onvolledige).

## Stappen

1. `GedragModus.ONVOLLEDIG`, `standaardVoor`, `bezwaarTegenModus`, `ONVOLLEDIG_ARCHIEF`.
2. `BerichtService.lijst` telt het archief op bij `totalElements`.
3. `GedragVerdeling` index 17; `DemoMappen.LEVEREND`.
4. Tests: gedrag-invarianten, verdeling (aantallen per modus), lijst-endpoint (totaal, lege lijst,
   detail onaangetast), en een uitvraag-keten die het magazijn als afgekapt ziet als dat binnen bereik
   is van de bestaande simulator-tests.
5. Console `MODUS_VOLGORDE`; README; runbook.

## Verificatie

- `./mvnw clean test -pl demo/magazijn-simulator -am`
- `./mvnw clean test -pl demo/demo-console -am`
- `./mvnw detekt:check`
