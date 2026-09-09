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

Een structurele misconfiguratie uit zich daarmee als een foutmelding richting de gebruiker
én als een ERROR in de log. De alert hieronder is gewone storingsdetectie op dat ERROR.

## Log-signaal

De resolver draait in-process in **berichtenuitvraag** (`quarkus.application.name`), niet
als eigen dienst; log-queries filteren dus op die applicatie en niet op de library-naam.

| Situatie | Niveau | Regel |
|----------|--------|-------|
| Partij zonder profiel | `DEBUG` | `Profiel-service meldt geen profiel voor type=<BSN\|RSIN\|KVK>; ontvanger heeft nog geen voorkeuren` |
| Storing / misconfiguratie | `ERROR` | `Profiel-service 404 die geen 'partij niet gevonden' is voor type=<…> (<duiding>) — behandeld als storing` |

Het normale geval staat bewust op `DEBUG`: het treedt op bij elke ophaalactie van elke
gebruiker zonder voorkeuren, en zou op een hoger niveau echte storingen laten ondersneeuwen.

Geen ontvanger-waarde in beide regels (PII). `<duiding>` benoemt wát er niet klopte en is
begrensd en gesaniteerd; het rauwe antwoordlichaam gaat de log niet in, want een upstream
mag daar het identificatienummer in echoën. De duidingen en wat ze betekenen:

| Duiding | Waarschijnlijke oorzaak |
|---------|--------------------------|
| `zonder lichaam` | Een 404 van een tussenliggende voorziening of een verkeerd pad — de dienst zelf antwoordt altijd mét problem+json. |
| `geen problem+json met een title` | Er zit iets tussen dat een eigen foutpagina teruggeeft (HTML, platte tekst). |
| `title='…'` | De dienst is bereikt, maar antwoordde met een ánder niet-gevonden-geval. |
| `lichaam onleesbaar (cause=…)` | Niet de upstream maar onze eigen client: elke opt-out wordt dan een valse storing. |
| `lichaam te groot (… tekens)` | Een defecte upstream of een foutpagina van formaat. |

De `type=`-dimensie onderscheidt één type dat faalt van álle types samen.

## Alert-vereiste

Configureer in de log-aggregator (Loki/CloudWatch) een alert op het storings-signaal:

- **Match:** ERROR-regels met `Profiel-service 404 die geen`.
- **Trigger:** elk voorkomen boven een lage drempel (bijvoorbeeld meer dan een handvol per
  5 minuten). Dit is geen ruis-signaal: het gaat uitsluitend om 404's die de resolver zelf
  al als storing behandelt.
- **Ernst:** waarschuwing. De aanlevering blijft werken; het ophalen faalt zichtbaar, dus
  gebruikers en beheer merken het los van deze alert ook.
- **Runbook:** lees eerst de duiding uit de tabel hierboven. Bij `zonder lichaam` of `geen
  problem+json`: controleer `quarkus.rest-client.profiel-service.url` en het pad
  (`POST /api/profielservice/v1/partij`), vergelijk met `PROFIEL_SERVICE_URL` per omgeving,
  en controleer of er een outway of gateway tussen zit die wel de oude `GET`-route kent maar
  deze `POST` niet routeert. Bij `lichaam onleesbaar`: kijk naar onze eigen client, niet naar
  het adres.

Een alert op het `DEBUG`-signaal is niet nodig voor het individuele geval. Let wel: een
schema-drift waarbij de dienst 200 antwoordt zónder opt-in-voorkeuren levert óók een lege
berichtenbox op, en dát pad heeft geen alert — zie de `totaal == 0`-tak in
`ProfielMagazijnResolver`.

## Voorbeeld (Loki/LogQL, indicatief)

```logql
sum(rate({app="berichtenuitvraag"} |= "Profiel-service 404 die geen" [5m])) > 0
```

Pas labels/queries aan op de daadwerkelijke aggregator-setup.
