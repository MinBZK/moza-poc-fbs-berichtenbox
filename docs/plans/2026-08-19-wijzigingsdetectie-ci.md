# Wijzigingsdetectie in de CI: één gedeeld filter

**Status:** Uitgevoerd

## Context

De CI bepaalt per pull request wat er moet draaien: code-checks, bouwen en uitrollen, de
test-scope en de fuzz-ronde. Die beslissing stond in vier workflows in kopie (`deploy.yml`,
`test.yml`, `detekt.yml`, `cflite_pr.yml`), geborgd door niets meer dan een
"LET OP: moet gelijk blijven"-comment. Op een PR naar `main` draait alleen de detectie van
`deploy.yml`; een eenzijdige wijziging elders had daar geen effect en viel dus niet op.

Aanleiding was PR #217: die raakte alleen `CLAUDE.md` en `.gitignore` en kocht daarmee de
volledige keten. Gemeten op de echte runs: 17 draaiende jobs, 966 runner-seconden, vier
container-images gebouwd én gepusht, en drie ZAD-previews (~8 pods elk, ruim 21 uur open).
`CLAUDE.md` viel weg via `\.md$`, maar `.gitignore` matchte geen enkel uitsluitingspatroon.

## Structuur

`.github/scripts/wijzigingsfilter.sh` beantwoordt de vier vragen op één plek en levert
`sleutel=waarde` op stdout, diagnostiek op stderr. De vier workflows roepen hetzelfde script aan.
`.github/scripts/test-wijzigingsfilter.sh` bevat de unittests; `.github/workflows/ci-scripts.yml`
draait shellcheck en die tests.

## Ontwerpkeuzes

**Fail-safe valt altijd naar draaien.** Een via `if` overgeslagen job rapporteert `skipped`, en
branch protection telt dat als succes. Elke twijfel — geen PR-context, onbereikbare API, lege
lijst, grep-fout — levert daarom "alles draaien" op. `grep` geeft 1 bij geen match en 2 bij een
echte fout; alleen de 1 mag tot overslaan leiden.

**De aanroeper valideert de uitkomst.** De workflows bufferen de uitvoer, filteren op de vier
sleutelnamen en eisen vier unieke sleutels vóór ze publiceren. Zonder die controle publiceert een
script dat halverwege afbreekt een halve set; een ontbrekende `deploy` leest als leeg, en
`deploy.yml` toetst met `== 'true'` — een lege waarde betekent daar dus "niet uitrollen", stil en
groen.

**Wat wel en niet uitgerold wordt.** `deploy.yml` staat bewust níét in de uitsluiting: dat bestand
bepaalt de uitrol, dus daar is een preview het bewijs dat de wijziging klopt. `cleanup-preview.yml`
staat er wél in, want die code draait pas bij het sluiten van een PR, tegen de basisbranch — een
preview bewijst daar niets. `.github/scripts/` valt eveneens buiten de uitrol: die scripts zitten
in geen enkel image.

**Documentatie en repo-meta tellen niet als code.** De patronen staan in `NIET_CODE`; ze dekken
naast documentatie ook de repo-meta zonder eigen categorie. `.claude/` hoort daar ondanks de
uitvoerbare hooks bij: die draaien op een ontwikkelmachine en geen enkele check in deze keten dekt
ze, dus draaien levert er niets over op.

**De patronen dragen kennis over bestanden buiten het script.** De lijst met toets-workflows
verwijst naar bestandsnamen in `.github/workflows/`. Wie er een hernoemt of toevoegt, raakt dat
script niet, en een fixture-test ziet dat nooit. Daarom kruiscontroleren de tests de lijst tegen de
schijf: elke naam moet bestaan, en elke workflow op schijf moet ingedeeld zijn. Om diezelfde reden
heeft `ci-scripts.yml` geen pad-filter — anders vuurt die controle op de verkeerde PR.

## Verificatie

- Handmatige mutatie-analyse (patronen ontankeren, `--paginate` en het `--jq`-filter wijzigen, het
  uitvoerbaar-bit wissen, de `EVENT`-tak weghalen, de validatie in één workflow laten afwijken):
  geen van die mutaties overleeft de suite nog.
- `shellcheck -x -S warning` schoon, `actionlint` schoon.
- Wat de uitkomst `run=false, deploy=false` in de praktijk kost, is te zien op een PR die alleen
  documentatie raakte (run 32239200150): 5 draaiende jobs, 26 runner-seconden, nul images, nul
  previews. Die PR viel onder het oude filter al weg via `\.md$`; repo-meta valt sinds deze
  wijziging in diezelfde klasse. Op een gemergde PR is het effect nog niet gemeten.

## Bekend restrisico

Een gefaalde `changes`-**job** laat `gate` en de drie `deploy-preview-*` wegvallen als `skipped`,
en die tellen als succes. De fail-safe in deze wijziging zit op stap-niveau; de required checks
hangen op job-niveau. Dat is een bestaande bouwfout, geen gevolg van deze wijziging, en wordt
opgepakt met de altijd-rapporterende `uitrol-poort` plus een aanpassing van de required contexts.

## Nog niet opgepakt

- CodeQL mist `cache: maven`; `Autobuild` is daardoor 278 van 365 seconden.
- Previews hebben geen TTL en worden pas bij het sluiten van de PR opgeruimd.
- De fuzz-job bouwt 338 seconden om 60 seconden te fuzzen.
- `bruno/` en `.github/scripts/` kosten nog een volledige Maven-testronde terwijl geen enkele
  Maven-build- of teststap ze leest (`ci-scripts.yml` dekt de scripts al apart). Pas verbreden
  nadat `ci-scripts` een required check is, anders merget een
  scripts-only PR met vrijwel niets als poortwachter.
