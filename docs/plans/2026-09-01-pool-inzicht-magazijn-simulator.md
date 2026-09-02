# Zicht op de connection pool van de magazijn-simulator

**Status:** Uitgevoerd

## Aanleiding

De simulator draait op een pool van 120 connections terwijl de database van de gedeelde omgeving er
twintig toelaat. Toch komen de berichten binnen de timeout binnen. Vandaag is niet te zien waaróm:
Agroal verzamelt zonder `enable-metrics` geen tellers, en er is niets dat ze zou tonen. De vraag —
worden er connections opgezet, hoeveel staan er te wachten, hoe lang — is nu niet te beantwoorden.

## Wat het wordt

Eén regel in de log, elk interval, en alleen als er iets veranderd is sinds de vorige:

```
pool: 18 in gebruik, 2 vrij, 7 wachtend van max 120 | piek 20 | opgezet 20, vernietigd 0
      | wachten gem 41ms, langst 380ms, totaal 12,4s
```

Dat dekt precies de drie dingen die de vraag stelt. **Opzetten:** `opgezet`/`vernietigd` tellen wat
Agroal werkelijk bij de database heeft aangevraagd — is dat er nooit meer dan twintig, dan zit de
grens niet in de pool maar in de gelijktijdigheid. **Vrijgeven:** `in gebruik` tegenover `vrij` en
`piek` laten zien of connections teruggegeven worden of blijven hangen. **Wachten:** `wachtend` is de
rij op dit moment, de drie wachttijden vertellen hoe erg dat was. `max` staat erbij omdat juist het
verschil tussen de ingestelde 120 en wat de database toelaat de vraag is.

Alleen loggen bij verandering is geen kosmetiek: onder last verandert er elke tick iets en krijg je
het verloop, en zodra het stil is houdt de regel op — anders loopt een demo van een half uur vol met
identieke regels en valt het moment dat ertoe doet niet meer op.

## Aanpak

1. `quarkus.datasource.jdbc.enable-metrics=true`. Zonder die vlag geeft Agroal overal nul terug.
2. `quarkus-scheduler` erbij, zoals de demo-console die al gebruikt voor zijn proxy-reconcile.
3. Drie kleine stukken, zodat de beslissing toetsbaar is zonder database:
   - `Poolmoment` — de tellers als gewone waarden, met de opmaak van de regel en het oordeel of hij
     verschilt van de vorige. Pure logica, dus unittests.
   - `Poolmonitor` — leest `AgroalDataSource.getMetrics()`, bouwt een `Poolmoment`, vergelijkt met de
     vorige en logt. Gedekt door een `@QuarkusTest` tegen de echte pool van Dev Services; een mock
     zou hier precies het enige wegnemen wat er te toetsen valt, namelijk of de tellers kloppen.
4. Interval configureerbaar: `magazijnsimulator.pool.log-interval`, standaard `5s`. Op `off` staat de
   meting uit — dezelfde afspraak die Quarkus zelf voor `@Scheduled` hanteert.

## Wat het niet wordt

Geen endpoint, geen leak-detectie, geen regel per aanroep. Die laatste zou bij een fan-out van
honderd honderden regels per ophaalronde geven; de periodieke regel vertelt hetzelfde verhaal zonder
de demo onleesbaar te maken. Ook geen Micrometer: dat is een extensie erbij voor een dashboard dat
deze omgeving niet heeft.

## Verificatie

- Unittests op de opmaak en op het oordeel "verschilt van de vorige", inclusief de grenzen: eerste
  meting, ongewijzigde meting, alleen de wachtrij veranderd.
- Een `@QuarkusTest` die de monitor tegen de echte pool laat meten en de tellers plausibel vindt.
- Handmatig: een vulronde draaien en de regels in de log volgen.
