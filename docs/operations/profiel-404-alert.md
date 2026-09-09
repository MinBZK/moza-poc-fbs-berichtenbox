# Operations: Profiel-service 404-alert

## Context

`berichtensessiecache` bepaalt per ontvanger welke magazijnen relevant zijn door de
MOZA Profiel Service te bevragen (`ProfielMagazijnResolver`). Die dienst antwoordt met
**404** in twee volstrekt verschillende situaties:

1. **Deze partij heeft nog geen profiel.** Het antwoord draagt een
   `application/problem+json`-lichaam met `title: "Partij niet gevonden"`. Dit is normaal
   gedrag voor wie nog niets heeft vastgelegd.
2. **Een storing of een verkeerd ingestelde koppeling** — een verschoven adres, een
   tussenliggende voorziening die de aanvraag niet kwijt kan. Zo'n 404 draagt dat lichaam
   niet, of een lichaam van een ander niet-gevonden-geval.

De resolver scheidt die twee: alleen (1) mapt naar een lege magazijn-set (succes-pad,
`OPHALEN_GEREED` met 0 berichten). Alles wat niet ondubbelzinnig als (1) te lezen is, geldt
als storing en levert een `ProfielServiceFoutException` op — de gebruiker ziet een fout in
plaats van een misleidend lege berichtenbox, en de aggregatie overschrijft de bestaande
cache niet met een lege lijst.

Daarmee is een structurele misconfiguratie niet langer stil: ze uit zich als een
foutmelding richting de gebruiker en als een ERROR in de log. De alert hieronder is dus
geen compenserende maatregel meer voor een blinde vlek, maar gewone storingsdetectie.

## Log-signaal

`ProfielMagazijnResolver` logt de twee gevallen verschillend:

| Situatie | Niveau | Regel |
|----------|--------|-------|
| Partij zonder profiel | `DEBUG` | `Profiel-service meldt geen profiel voor type=<BSN\|RSIN\|KVK>; ontvanger heeft nog geen voorkeuren` |
| Storing / misconfiguratie | `ERROR` | `Profiel-service 404 zonder herkenbaar 'partij niet gevonden'-antwoord voor type=<…> (lichaam='…') — behandeld als storing` |

Het normale geval staat bewust op `DEBUG`: het treedt op bij elke ophaalactie van elke
gebruiker zonder voorkeuren, en zou op een hoger niveau echte storingen laten ondersneeuwen.

Geen ontvanger-waarde in beide regels (PII). Het gelogde lichaam komt ongevalideerd van
buiten en wordt afgekapt en van control-chars ontdaan (`veiligLogFragment`). De
`type=`-dimensie onderscheidt één type dat faalt van álle types samen.

## Alert-vereiste

Configureer in de log-aggregator (Loki/CloudWatch) een alert op het storings-signaal:

- **Match:** ERROR-regels met `Profiel-service 404 zonder herkenbaar`.
- **Trigger:** elk voorkomen boven een lage drempel (bijvoorbeeld meer dan een handvol per
  5 minuten). Anders dan bij de oude rate-alert is dit geen ruis-signaal: het gaat hier
  uitsluitend om 404's die de resolver zelf al als storing behandelt.
- **Ernst:** waarschuwing. De aanlevering blijft werken; het ophalen faalt zichtbaar, dus
  gebruikers en beheer merken het los van deze alert ook.
- **Runbook:** controleer `quarkus.rest-client.profiel-service.url` en het pad
  (`POST /api/profielservice/v1/partij`), vergelijk met `PROFIEL_SERVICE_URL` per omgeving,
  en controleer of er een outway of gateway tussen zit die het pad niet kent.

Een alert op het `DEBUG`-signaal is niet nodig: een plotselinge stijging van "geen
voorkeuren" is geen storing van deze keten.

## Voorbeeld (Loki/LogQL, indicatief)

```logql
sum(rate({app="berichtensessiecache"} |= "Profiel-service 404 zonder herkenbaar" [5m])) > 0
```

Pas labels/queries aan op de daadwerkelijke aggregator-setup.
