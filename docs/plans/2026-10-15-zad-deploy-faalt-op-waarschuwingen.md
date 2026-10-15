# Een uitrol die half lukt, is niet groen — implementatieplan

**Status:** Concept

**Doel:** een deploy-job in `deploy.yml` faalt zodra Operations Manager meldt dat de uitrol wel is
uitgevoerd maar dat componenten ongezond zijn. Nu gaat zo'n job groen, en ziet niemand het tot een
afhankelijke deployment erover struikelt.

Issue: nog aan te maken in `MinBZK/MijnOverheidZakelijk` (na akkoord van de opdrachtgever).

## Aanleiding

PR #376, run 38705795285 (2026-10-15). Tijdens een storing op het cluster (Kyverno-webhook in
time-out, `rcr.rijksapps.nl` gaf 503) rolde `deploy-preview-uitvraag` om 08:35 uit. OM antwoordde:

```
"headline": "The operation succeeded, but some components are unhealthy.",
"details": ["uitvraag (image_pull): Registry kon de container image niet leveren"]
```

De job meldde "Deployment successful" en ging groen. `pr-376-redis` en de Services van `uitvraag`
en `toxiproxy-aanmeld` zijn nooit aangemaakt. Pas `deploy-preview-magazijnen` faalde, op een
readiness-time-out van de democonsole (Redis onbereikbaar), en wees zo naar het verkeerde project.
Drie keer `gh run rerun --failed` hielp niet: de groene uitvraag-job draaide nooit opnieuw.

## Waar de controle ontbreekt

- `RijksICTGilde/zad-actions/deploy` (v4.3.2) roept `zad --output json deployment create …` aan en
  kijkt alleen naar de exitcode. `zadctl` kent `--strict` ("Exit non-zero when an operation succeeds
  but reports warnings"), maar de action geeft die vlag niet mee en heeft er geen input voor; ook
  `main` van zad-actions niet. De waarschuwingen gaan in JSON-modus naar stderr en komen alleen als
  platte tekst in het log, zonder annotatie.
- Ons eigen vangnet staat uit: `wait-for-ready: 'false'` op elke preview-job, omdat die wachtlus één
  health-pad op álle componenten afvraagt en Redis daar nooit op antwoordt (`deploy.yml:889-894`).
- `uitrol-poort` leest alleen job-uitkomsten en erft het gat.

Dit raakt alle tien `zad-actions/deploy`-aanroepen in `deploy.yml`, ook die naar `test`.

## Ontwerp

### 1. Upstream: input `fail-on-warnings` in `zad-actions/deploy`

- Nieuwe input `fail-on-warnings`, default `'false'` (bestaande aanroepers merken niets), gevalideerd
  op `true`/`false`.
- `true` zet `--strict` vóór het subcommando: `zad --output json --strict deployment create …`.
- De stderr van de create gaat naar een tijdelijk bestand en wordt daarna ongewijzigd doorgegeven
  aan het log. Het warnings-object (`json.dumps(indent=2)`, dus `{` en `}` op een eigen regel)
  wordt eruit gelicht en met `jq` gelezen.
- Faalt de create op `--strict`, dan meldt de action per waarschuwing `headline`, `details` en
  `next_steps` als `::error::`. Zonder die vertaling kwam de bestaande `report_zad_error` uit op
  "failed with no HTTP status code", want stdout is dan een geslaagd taakresultaat.
- Zonder `fail-on-warnings` worden dezelfde waarschuwingen `::warning::`-annotaties. Zo staan ze
  in de samenvatting van de run, niet alleen diep in het log.
- Smoke-tests (`.github/workflows/smoke.yml`, stub `tests/stubs/zad`): de stub krijgt
  `ZAD_STUB_CREATE_STDERR` en `ZAD_STUB_CREATE_EXIT`, en een `--strict` in de argv wordt
  gelogd. Drie gevallen:
  - met `fail-on-warnings: 'true'` faalt de stap, wordt geen `url` gepubliceerd, en is `--strict`
    meegegeven;
  - zonder die input slaagt de stap, en wordt `--strict` níet meegegeven;
  - een ongeldige waarde faalt bij de invoercontrole.
- README-tabel en CHANGELOG (`[Unreleased]` → `Added`).

PR vanaf een fork; wij hebben geen schrijfrechten op de repo.

### 2. Deze repo, na de release van zad-actions

- De pin van `zad-actions/deploy` in `deploy.yml` naar de nieuwe release (SHA + versie-comment),
  en `fail-on-warnings: 'true'` op alle tien aanroepen.
- `docs/operations/zad-gitops.md`, bij de valkuilen: `gh run rerun --failed` draait een groene
  deploy-job niet opnieuw. Na een storing die meerdere projecten raakte, draai je de hele run
  opnieuw.
- Dependabot volgt de actions-groep, maar de pin bijwerken en de input zetten moeten in één PR
  landen. De input bestaat pas in de nieuwe versie.

## Afweging

Een tijdelijke 503 van het register die zichzelf herstelt, maakt een deploy-job voortaan rood.
Dat is bewust: "groen maar draait niet" kost meer, zie de aanleiding. Een rerun van zo'n job is
goedkoop. Alternatief was een eigen `zadctl deployment describe`-wachtstap na elke deploy-stap:
tien keer meer code met polling, en die stap zou overdoen wat OM al meldt.

## Verificatie

- Upstream: de smoke-suite is groen, en de drie nieuwe gevallen falen met de wijziging teruggedraaid
  (de stap slaagt dan ondanks de waarschuwing).
- Hier: `actionlint` en `ci-workflow-reviewer` op `deploy.yml`. Een preview-run op de PR moet groen
  zijn. Een echte waarschuwing is niet op afroep te maken, dus het rode pad steunt op de upstream
  smoke-test.
