# Operations: Profiel-service 404-rate alert

## Context

`berichtensessiecache` bepaalt per ontvanger welke magazijnen relevant zijn door de
MOZA Profiel Service te bevragen (`ProfielMagazijnResolver`). Een **404** van de
Profiel-service betekent "deze partij heeft geen profiel" en wordt bewust gemapt op
een lege magazijn-set (succes-pad), zodat het ophalen `OPHALEN_GEREED` met 0 berichten
oplevert i.p.v. een fout.

Die mapping voorkomt een timing-/bestaanslek tussen "ontvanger bekend zonder voorkeur"
en "ontvanger onbekend" — maar maakt een **configuratiefout onzichtbaar**: als de
Profiel-base-path verkeerd staat (drift), geeft de upstream voor *alle* ontvangers een
404, en degradeert het systeem stil naar "iedereen heeft geen voorkeuren".

De enige verdediging tegen die config-misser is een **alert op de 404-rate**. Dit
document borgt de alert-vereiste in de repo (i.p.v. impliciet "staat wel ergens in
Loki").

## Log-signaal

`ProfielMagazijnResolver` logt elke 404 op `WARN`:

```
Profiel-service 404 voor type=<BSN|RSIN|KVK>; geen voorkeuren bekend (mogelijk config-misser)
```

Geen ontvanger-waarde in de regel (PII). De `type=`-dimensie maakt onderscheid
tussen één type dat 404't (mogelijk legitiem) en álle types (vrijwel zeker drift).

## Alert-vereiste

Configureer in de log-aggregator (Loki/CloudWatch) een alert dat afgaat wanneer de
404-rate structureel hoog is. Richtlijn:

- **Match:** WARN-regels met `Profiel-service 404`.
- **Trigger:** ratio `Profiel-service 404` / totaal aantal resolver-calls > ~50% over
  een venster van 5 min, of een absolute spike t.o.v. de baseline.
- **Ernst:** waarschuwing (geen page) — een config-drift blokkeert geen aanlevering
  maar levert wel stil-lege ophaalresultaten op.
- **Runbook:** controleer `quarkus.rest-client.profiel-service.url` / base-path en de
  upstream-deploy; vergelijk met `PROFIEL_SERVICE_URL` per omgeving.

## Voorbeeld (Loki/LogQL, indicatief)

```logql
sum(rate({app="berichtensessiecache"} |= "Profiel-service 404" [5m]))
  /
sum(rate({app="berichtensessiecache"} |~ "Profiel-resolver|Profiel-service" [5m]))
  > 0.5
```

Pas labels/queries aan op de daadwerkelijke aggregator-setup.
