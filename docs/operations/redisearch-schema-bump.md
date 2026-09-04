# Operations: RediSearch schema-bump

## Context

`berichtensessiecache` indexeert berichten in een RediSearch-index
(`berichten-idx-v2`) voor zoek- en filter-queries (`GET /berichten/_zoeken?q=...`,
`GET /berichten/_zoeken?q=...`). Het schema wordt gedefinieerd in
`RedisBerichtenCache.init()`:

```kotlin
CreateArgs()
    .onHash()
    .prefixes("bericht:v2:")
    .indexedField("onderwerp", FieldType.TEXT)
    .indexedField("afzender", FieldType.TAG)
    .indexedField("ontvanger", FieldType.TAG)
    .indexedField("ontvangerType", FieldType.TAG)
    .indexedField("publicatietijdstip", FieldType.TAG)
    .indexedField("map", FieldType.TAG)
```

De index-naam draagt dezelfde versie als de prefix waarop hij filtert. Dat is geen cosmetica:
verandert alleen de prefix, dan blijft de bestaande index staan (zie het bootstrap-gedrag
hieronder) en indexeert hij de nieuwe hashes niet. Met de versie in de naam maakt elke nieuwe pod
zijn eigen index aan en is een handmatige drop niet nodig bij een prefix-wijziging.

## Bootstrap-gedrag (idempotent)

Bij pod-start doet `init()`:

1. `FT._LIST` om te checken of de index bestaat.
2. Index bestaat → **laat ongemoeid**, log `INFO`.
3. Index bestaat niet → `FT.CREATE` met bovenstaand schema.

Reden: meerdere replicas delen één Redis. Een drop+create bij elke pod-start
zou andere replicas tijdelijk laten falen met "Unknown index name" én bestaande
berichthashes uit de index halen tot de eerstvolgende `store()`. Idempotente
bootstrap voorkomt deze race tijdens rolling-restart.

**Gevolg:** een wijziging in `CreateArgs()` (nieuw veld, ander prefix, ander
field-type) wordt **niet automatisch** toegepast. De index houdt het oude
schema tot een operator handmatig de schema-bump uitvoert.

## Procedure bij schema-wijziging

Voorbeeld-scenario: er wordt een nieuw indexed field `magazijnId` (TAG)
toegevoegd aan `CreateArgs()` in een PR.

### 1. Voorbereiding

- Plan een **maintenance-window** (≥ 5 minuten, zonder actieve gebruikers).
  RediSearch-queries falen tijdens stappen 3-4; vermijd peak-hours.
- Verifieer dat de **PR met de schema-wijziging is gedeployd** in alle replicas
  vóór stap 3 — anders schrijven pods met het oude schema nieuwe berichthashes
  in de nieuw aangemaakte index met het oude veld-set.
- Communiceer de window met de aanleverende partijen (search-endpoint
  retourneert tijdelijk 503).

### 2. Identificeer Redis-endpoint

Productie-cluster: zie `REDIS_HOSTS` env-var op `berichtensessiecache`-pods.
Acceptatie: idem. Lokaal/dev gebruikt `compose.yaml`.

```sh
kubectl exec -it <berichtensessiecache-pod> -- env | grep REDIS_HOSTS
```

### 3. Drop oude index

Vanaf een pod met `redis-cli` of via `redis-cli` op de Redis-host:

```sh
redis-cli -h <REDIS_HOST> -p 6379 FT.DROPINDEX berichten-idx-v2
```

**Niet `FT.DROPINDEX berichten-idx-v2 DD`** — de `DD`-flag verwijdert ook de
onderliggende `bericht:v2:*`-hashes. Dat is onnodig: alleen het index-schema
wordt opnieuw gebouwd, de berichten zelf blijven via TTL geldig en worden door
de stap-4-create automatisch opnieuw geïndexeerd.

Verifieer dat de drop is geslaagd:

```sh
redis-cli -h <REDIS_HOST> -p 6379 FT._LIST
# Verwachte output: lege lijst (of zonder berichten-idx-v2)
```

### 4. Trigger create via pod-restart

```sh
kubectl rollout restart deployment/berichtensessiecache
```

De eerstvolgende pod die start ziet `FT._LIST` zonder `berichten-idx-v2`,
voert `FT.CREATE` uit met het **nieuwe** schema, en logt:

```
INFO  RediSearch index 'berichten-idx-v2' aangemaakt
```

Volgende pods zien de index bestaan en doen niets — idempotent.

### 5. Verifieer nieuwe schema-velden

```sh
redis-cli -h <REDIS_HOST> -p 6379 FT.INFO berichten-idx-v2
```

Check dat het nieuwe veld (bv. `magazijnId`) in de `attributes`-sectie staat.

### 6. Re-index bestaande berichten (optioneel)

RediSearch herindexeert hashes automatisch wanneer ze worden gewijzigd
(`HSET`/`HDEL`). Bestaande berichten worden pas opnieuw geïndexeerd op het
nieuwe veld wanneer ze opnieuw worden geschreven door `store()` (na de
volgende ophaalsessie).

Bij urgent volledig herindexen: laat een gerichte cache-warming-flow lopen, of
accepteer dat oude berichten op het nieuwe veld pas vindbaar zijn na natural
TTL-rotatie (default 60s sessie-TTL).

### 7. Communiceer afronding

Search-endpoint is weer beschikbaar. Sluit het maintenance-window.

## Foutscenario's

| Symptoom | Oorzaak | Herstel |
|---|---|---|
| `FT.DROPINDEX` returnt `(error) Unknown index name` | Index bestaat niet (al gedropt) | Stap 4: laat pods opnieuw starten; create wordt automatisch gedaan |
| Pods loggen `RediSearch index 'berichten-idx-v2' kon niet worden aangemaakt` | Redis is overbelast of permissie-issue | Check Redis-logs, verifieer `quarkus.redis.hosts`-credentials |
| Search retourneert 0 resultaten op zojuist opgeslagen bericht | Bericht is geschreven vóór de create | Wacht tot volgende `store()`-aanroep of trigger handmatig een ophaalsessie |
| Pods loggen `Kan RediSearch indexen niet opvragen (Redis onbereikbaar bij startup?)` | Redis-cluster niet bereikbaar bij pod-start | Standard troubleshooting: netwerk, DNS, credentials |

## Toegepaste schema-bumps

| Wijziging | Aanleiding | Actie nodig |
|---|---|---|
| `ontvangerType` (TAG) toegevoegd; zoek/filter worden type-aware (`@ontvanger:{..} @ontvangerType:{..}`) | Getypeerde ontvanger + cross-type-isolatie (#625, #648) | Eenmalig deze procedure (drop + restart). **Pre-productie:** cache mag leeglopen; geen maintenance-window nodig. |
| Sleutel-prefix `bericht:v1:` → `bericht:v2:`, index-naam `berichten-idx` → `berichten-idx-v2`; hash-veld `afzenderNaam` toegevoegd aan de opslag en aan de samenvatting-projectie | Elk bericht draagt een verplichte weergavenaam van zijn organisatie (#1065) | **Geen handmatige stap.** De index-naam bumpte mee, dus elke nieuwe pod maakt `berichten-idx-v2` zelf aan terwijl oude pods op `berichten-idx` blijven werken — een rolling deploy verloopt zonder venster. `v1`-entries missen het nieuwe veld en worden door de nieuwe prefix niet meer gelezen; ze verlopen via hun eigen TTL. Opruimtaak achteraf: `FT.DROPINDEX berichten-idx` (zonder `DD`) zodra alle pods op de nieuwe versie draaien. |

**Wat er gebeurt als de index-naam níet meebumpt bij een prefix-wijziging** (de reden dat hij dat
hier wél doet): de bootstrap laat een bestaande index ongemoeid, dus die blijft op de oude prefix
filteren. Nieuwe hashes worden dan nooit geïndexeerd en `_zoeken` en de gefilterde lijst geven
**stil nul resultaten** — HTTP 200 met een lege lijst, geen fout, geen logregel. Draaien er
tegelijk nog oude pods, dan komen hún hashes wél uit de index en missen die het nieuwe veld:
`documentToSamenvatting` gooit dan `CacheCorruptedException` → HTTP 500 met een melding die naar
datacorruptie wijst in plaats van naar een overgeslagen schema-bump. Anders dan de
`ontvangerType`-bump hierboven is dat fail-*silent*, niet fail-*closed*.

**Veiligheid tijdens de transitie (`ontvangerType`):** draait de type-aware filter op een
nog-niet-gebumpte index (zonder `ontvangerType`-veld), dan levert RediSearch **lege resultaten
óf een query-fout** (afhankelijk van de versie) — in beide gevallen géén cross-type-match
(fail-*closed*). Het effect is dus hooguit "tijdelijk niets vindbaar / 5xx tot de bump", nooit
"verkeerde berichten zichtbaar".

## Gegevensbescherming (DPIA / verwerkingsregister)

De sessiecache bewaart de ontvanger-identificatie (incl. **BSN/RSIN**) in klare vorm: als
hash-veld `ontvanger` (+ `ontvangerType`) en in de lijst-JSON-blob als canonieke `"TYPE:waarde"`.
Dit is functioneel noodzakelijk (eigenaar-checks, zoeken) en conform de logging-regels (de waarde
mag in Redis, nooit in applicatie-logs). Borg in de DPIA / het verwerkingsregister dat hiervoor
versleuteling at-rest én in transport actief is, dat de **toegang tot de Redis-instance**
beperkt is (authenticatie/ACL + netwerksegmentatie, least-privilege) en zo nodig wordt
geaudit, en dat de bewaartermijn via de sessie-TTL geminimaliseerd blijft (BIO 9.x / AVG
art. 32). Dit is een procesactie voor het team, geen codewijziging.

## Schema-versie-bijhouden

Geen automatische versie-tracking in Redis (zoals Flyway voor PostgreSQL).
Houd schema-wijzigingen bij in `CHANGELOG.md` of via PR-titel-conventie zodat
deploy-team weet wanneer deze procedure nodig is.

Bij twijfel: vergelijk `FT.INFO berichten-idx-v2`-output met
`RedisBerichtenCache.init()` `CreateArgs(...)` — afwijking betekent schema-bump
nodig.
