# CI-testshards opsplitsen

**Status:** Uitgevoerd

## Context

`test.yml` verdeelde de reactor over twee shards: `berichtenmagazijn` en het complement
(`!services/berichtenmagazijn`). De uitrol wacht op de traagste shard, en dat was niet meer de
magazijn-shard maar het complement. Gemeten op main (run 34469960562, 2026-09-10):

| Shard | Job | Reactor | Grootste posten |
|-------|-----|---------|-----------------|
| `test-berichtenmagazijn` | 4:34 | 4:24 | berichtenmagazijn 3:49, fbs-common 0:34 |
| `test-overig` | 6:14 | 6:00 | fbs-berichtensessiecache 2:12, magazijn-simulator 1:39, berichtenuitvraag 1:02, fbs-common 0:30, demo-console 0:23, demo-personas 0:10 |

## Afhankelijkheden

- fbs-berichtensessiecache → fbs-common, fbs-magazijnregister
- berichtenuitvraag → fbs-common, fbs-magazijnregister, fbs-berichtensessiecache
- berichtenmagazijn → fbs-common
- demo-console → demo-personas; demo-personas en magazijn-simulator gebruiken niets uit de reactor

## Ontwerp

Drie shards, geen enkele boven de vloer van berichtenmagazijn:

| Shard | `-pl` | Verwachte reactor-tijd |
|-------|-------|------------------------|
| `berichtenmagazijn` | `services/berichtenmagazijn` | ~4:25 (ongewijzigd, de vloer) |
| `berichtenuitvraag` | fbs-common, fbs-magazijnregister, fbs-berichtensessiecache, berichtenuitvraag | ~3:50 |
| `overig` | complement van beide shards hierboven | ~2:15 |

Keuzes:

- **Bibliotheken expliciet in de uitvraag-shard.** `-am` trekt ze toch mee, maar zo hangt hun
  dekking niet aan de afhankelijkheden van de service.
- **Complement blijft.** Een nieuwe reactor-module valt er per constructie in. Het complement sluit
  nu vijf modules uit; het nieuwe gat — een uitsluiting die geen andere shard noemt — bewaakt
  `test-wijzigingsfilter.sh`, dat de JSON-literals uit de matrix-expressie leest.
- **Uitsluiting wint van `-am`** (geverifieerd met `./mvnw -o validate -pl '!libraries/fbs-common,…' -am`
  op Maven 3.9.16). Gaat een complement-module een uitgesloten bibliotheek gebruiken, dan faalt die
  shard op een onvindbaar artefact. Geen workflow draait `install`, dus de Maven-cache vult dat niet
  stil aan.
- **fbs-common dubbel** (in de magazijn-shard via `-am`): ~30s runnertijd, bewust geaccepteerd.
- **Geen splitsing binnen berichtenmagazijn.** Die module blijft de vloer; verdere winst vraagt
  sharding op testklasse, met eigen coverage-samenvoeging. Buiten deze wijziging.

## Verificatie

- `test-wijzigingsfilter.sh` groen; de shard-controle is van één naar drie asserties gegaan
  (demo-shard aanwezig, precies één complement, elke uitsluiting elders genoemd).
- Mutatietests op kopieën van `test.yml`: uitvraag-shard weg, complement weg, demo-shard weg,
  sessiecache niet meer genoemd, tweede complement — elk levert een FAIL.
- `./mvnw -o validate` toont de verwachte reactor per nieuwe shard.
- shellcheck en actionlint groen.
- Jobduur per shard na de eerste CI-run in de PR-beschrijving.
