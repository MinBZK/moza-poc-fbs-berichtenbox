# Uitrol-poort: één check die niet stil kan overslaan

**Status:** Uitgevoerd

## Aanleiding

`gate` en de zes uitrol-jobs in `deploy.yml` mogen zichzelf legitiem overslaan. Een overgeslagen
job rapporteert `skipped`, en dat telt als succes voor branch protection. Valt de
wijzigingsdetectie om, dan verdwijnt de hele keten erachter in `skipped` en is de pull request
groen en samenvoegbaar terwijl er niets is uitgerold. De enige rode job is `changes` zelf, en die
staat niet in de required contexts — een reviewer ziet dus geen enkel signaal.

Dit is het restrisico dat in `2026-08-19-wijzigingsdetectie-ci.md` is benoemd: de fail-safe daar
zit op stap-niveau, de required checks hangen op job-niveau.

## Ontwerpkeuzes

**`always()`, niet `!cancelled()` en niet de impliciete `success()`.** De impliciete vorm zou de
job overslaan zodra een need faalt — precies wat hij moet beoordelen. `cancelled()` is run-breed,
dus met `!cancelled()` zou een handmatig afgebroken run deze check op `skipped` (= succes) zetten
en daarmee certificeren dat er is uitgerold. Prijs: bij zo'n afbreking start de job alsnog een
runner.

**De stappen dragen `always()` opnieuw.** De job-brede `always()` houdt bij een afbreking alleen
de runner aan; een stap zonder `always()`/`cancelled()` in zijn eigen `if:` wordt dan alsnog
overgeslagen. Zonder die herhaling blijft bij een annulering enkel de `CANCELLED`-stap over, blijft
het oordeel liggen en eindigt de job groen — het gat dat `CANCELLED` juist moet dichten. De
kruiscontrole met `deploy.yml` bewaakt dit per stap, dus ook voor stappen die er later bij komen.

**Annulering komt als expliciete invoer binnen, niet uit de job-resultaten.** `gate` en de
uitrol-jobs dragen zelf `!cancelled()`. Wordt de run afgebroken vóórdat ze starten, dan
rapporteren ze `skipped` — dezelfde vorm als de legitieme "niets uit te rollen"-uitkomst. Zonder
een aparte `CANCELLED`-invoer zou de poort een afgebroken docs-only run groen verklaren.
`cancelled()` mag niet in een env-expressie staan, dus een stap met `if: cancelled()` zet de
waarde om.

**De resultaten komen uit `toJSON(needs)`.** Negen overgetypte `needs.<job>.result`-expressies
zijn negen plekken die synchroon moeten blijven met de `needs`-lijst; `needs.<onbekend>.result`
levert bovendien een lege string op. Eén afleiding plus een harde controle dat er precies drie
uitrol-jobs per as gevonden zijn. Dat de `needs`-lijst zélf compleet is, bewaakt de
kruiscontrole met `deploy.yml` in de unittests — `toJSON` kan dat per definitie niet zien.

**De as die bij het event niet hoort te draaien moet volledig stil zijn.** Draait daar tóch een
job, dan matcht zijn `if` breder dan bedoeld.

**De bouw-jobs staan in de `needs` voor de diagnose, niet voor het oordeel.** Een gevallen of
verdrongen build laat de uitrol-jobs overslaan; zonder hun stand in de melding wijst de fout naar
het gevolg in plaats van naar de oorzaak.

**Het oordeel staat in een script, niet in een inline `run:`-blok.** `deploy.yml` draait niet op
een gestapelde PR (`pull_request: branches: [main]`), dus inline zou de logica die de merge
bewaakt pas ná de merge naar main voor het eerst uitgevoerd worden. Als script valt hij onder
shellcheck en onder de bash-unittests, en die draaien via `ci-scripts.yml` op élke PR.

## Verificatie

- Mutatie-analyse op `uitrol-poort.sh`: de CANCELLED-controle, de push-invariant, de
  `deploy`-validatie, de kardinaliteitscontrole, de stille-as-controle, de EVENT-default, de
  gate-controles op beide takken, de bouw-diagnose en het omdraaien van de eindvergelijking
  worden allemaal door de suite gedood. `beoordeel || true` overleeft, maar is een equivalente
  mutant: `fout()` gebruikt `exit`, en `||` onderschept geen `exit`.
- Naast de exitcode toetst elke assertie de foutmelding, zodat de diagnostiek niet stil kan
  verdwijnen.
- Onbruikbare invoer (`NEEDS` leeg, geen JSON, `null`, een array) en ontbrekende
  omgevingsvariabelen eindigen non-zero mét `::error::`-annotatie.
- `shellcheck -x -S warning` schoon, `actionlint` schoon, beide suites groen (63 + 70 asserties).
- De job zelf draait niet op deze PR: `deploy.yml` komt niet op een gestapelde PR. Zijn oordeel
  wordt hier wél uitgevoerd, via de unittests.

## Nog te doen: branch protection

De required contexts op `main` staan buiten de repo en moeten met de hand om. Huidige set:

```
Analyze (kotlin), deploy-preview-uitvraag, deploy-preview-externe-stubs,
deploy-preview-magazijnen, checks-detekt / detekt-gate, checks-fuzz / PR,
checks-pins / infra-image-pins, checks-test / test
```

Toe te voegen: `uitrol-poort` en `ci-scripts`. Beide zijn standalone jobs, dus hun context is de
kale jobnaam — het `<caller-job> / <job>`-voorvoegsel krijgen alleen aangeroepen workflows.

De drie `deploy-preview-*` kunnen daarnaast blijven staan: ze zijn geen vervanging van elkaar.
`uitrol-poort` dekt het geval waarin ze alle drie legitiem overslaan; zij dekken het geval waarin
`uitrol-poort` zelf niet rapporteert. Zolang geen van beide is toegevoegd, faalt `uitrol-poort`
netjes rood zonder ook maar één merge te blokkeren.
