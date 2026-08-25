# Demonstratiecode scheiden van de code van het stelsel

**Status:** Concept — voorstel ter besluitvorming, uitgevoerd ter review

Spike voor MinBZK/MijnOverheidZakelijk#1005. Aanleiding: de reviewbespreking bij het ontwerp van de
magazijn-simulator (`docs/plans/2026-08-21-magazijn-simulator-design.md`, openstaande beslissing 7).

Dit document beantwoordt twee van de drie acceptatiecriteria: er ligt een voorstel met een reden, en
de kosten voor build, CI en deploy staan erin. Het derde criterium — het teambesluit — is aan het
team; de uitkomst hoort onderaan dit document terug te komen, ook als die "we laten het zoals het
is" luidt.

De verhuizing is in dezelfde PR uitgevoerd, zodat het team niet over een beschrijving hoeft te
oordelen maar over de diff. Terugdraaien is één revert; wat er precies gedaan is, staat onder
"Uitvoering".

## De vraag

De repository is openbaar. Wie hem voor het eerst opent, ziet zes Maven-modules onder `services/` en
`libraries/`, waarvan er één (`services/demo-console`) uitsluitend bestaat om een demo te kunnen
geven. Met de magazijn-simulator erbij worden dat er twee, en de tweede is fors: eigen database,
eigen migraties, eigen beheer-API. Aan de mappenstructuur is dat verschil niet te zien.

## Inventaris: wat is vandaag demo-code?

| Pad | Wat | Nooit productie? | Wordt uitgerold? |
|---|---|---|---|
| `services/demo-console` | Bedieningspaneel voor demo's (Maven-module) | ja | nee, vandaag niet — zie hieronder |
| `services/magazijn-simulator` (voorzien, #938) | Gesimuleerde magazijnen (Maven-module) | ja | **ja**, eigen ZAD-component in `mpfm-w3h` |
| `demo/genereer-magazijnen.py`, `demo/smoke.sh`, `demo/podman-*.sh` | Scripts voor de lokale demo-stack | ja | nee |
| `demo/generated/` | Gegenereerde stub-mappings | ja | nee |
| `demo/environment/` | FSC-federatieharness (peers, PKI, contract-bootstrap) | ja | **ja**, het contract-bootstrap-image |
| `wiremock/` | Stub-mappings voor magazijnen, profiel, notificatie, aanmelden | ja | **ja**, `wiremock/externe-stubs` wordt een image (`deploy.yml:358`) |
| `toxiproxy/proxies.json` | Netwerkstoringen voor de demo | ja | nee |
| `compose.yaml`, `compose.podman*.yaml` | Lokale dev- én demo-stack | ja | nee |
| `bruno/` | Handmatige API-requests per service | ja | nee |

Twee dingen vallen op.

**Ten eerste is "demo" vandaag al veel meer dan `services/demo-console`.** Het grootste deel staat
buiten `services/`, op de plek waar je het zou verwachten. Het probleem uit #1005 gaat feitelijk
over twee Maven-modules, niet over de hele repository.

**Ten tweede valt "demo-only" niet samen met "wordt niet uitgerold".** De FSC-harness, de
externe-stubs en straks de simulator draaien alle drie op ZAD: ze horen niet in het stelsel thuis,
maar ze moeten wél gebouwd en gedeployd worden om de keten te laten zien. Dat onderscheid is
belangrijk, want de CI leunt er nu op de verkeerde manier op — zie de kosten hieronder.

## Voorstel

**`demo/` wordt naast `services/` en `libraries/` een derde module-wortel. `services/demo-console`
verhuist naar `demo/demo-console`; de magazijn-simulator landt meteen op `demo/magazijn-simulator`.
De rest van `demo/` blijft staan waar het staat.**

```
libraries/           het stelsel — gedeelde bibliotheken
services/            het stelsel — deployables
demo/                alles wat er is om het stelsel te tonen; nooit productie
  README.md            wat hier staat, en waarom het nooit meegaat naar productie
  demo-console/        Maven-module (verhuisd)
  magazijn-simulator/  Maven-module (nieuw, #938)
  environment/         FSC-federatieharness (ongewijzigd)
  generated/           gegenereerde stub-mappings (ongewijzigd)
  *.sh, genereer-magazijnen.py
```

Waarom zo:

- **Eén regel volstaat om het uit te leggen.** "Staat het onder `demo/`? Dan draait het nooit in
  productie." Een meelezer heeft daar geen tourtje langs zes `pom.xml`'s voor nodig.
- **Het moment is nu of over jaren.** De simulator is het enige nieuwe demo-artefact op de rol.
  Verhuist hij mee, dan kost de operatie twee `git mv`'s en een dag CI-werk. Verhuist hij niet, dan
  is de volgende aanleiding een derde module, en dan verhuizen er drie.
- **Modules en scripts in één wortel is geen probleem.** De plekken die over modules itereren
  (`codeql.yml:106`, `codeql.yml:157`) filteren al op `[ -f "$module/pom.xml" ]`, en de
  JaCoCo-globs (`test.yml:180`, `test.yml:291`) matchen alleen paden met `target/site/jacoco/`. Een
  tussenlaag `demo/modules/` zou alleen `-pl demo/modules/demo-console` opleveren en niets oplossen.
- **De rest van `demo/` verhuizen levert niets op en kost veel.** `fsc-harness-overlays.yml` heeft
  circa tien vaste `demo/environment/…`-paden en `deploy.yml:390` bouwt daar het
  contract-bootstrap-image uit. Die paden staan al onder `demo/`; ze verplaatsen verandert geen
  enkel leesbaarheidsprobleem.

Wat er naast de map bij hoort:

- **`demo/README.md`** — één alinea: wat hier staat, dat het nooit productie draait, en dat
  productiemodules er niet van mogen afhangen. Zonder dat blijft de regel impliciet.
- **De grens machinaal bewaken.** Een controle in `ci-scripts.yml` die faalt zodra een module onder
  `services/` of `libraries/` een `<dependency>` op een `demo/`-module declareert. Circa twintig
  regels shell plus een testcase; zonder die controle is de scheiding een afspraak die alleen in
  review houdt.
- **Geen hernoeming van artifactIds.** `demo-console` en `magazijn-simulator` houden hun naam. Een
  `demo-`-prefix zou de image-namen meeslepen (`fbs-demo-console` → nieuwe ZAD-componentnaam) en de
  map draagt de boodschap al.
- **De tabellen in `README.md` (r. 44-49) en `CLAUDE.md`** krijgen een `demo/`-blok, zodat de
  indeling ook daar leidend is.

## Wat het kost

Regelnummers hieronder verwijzen naar de stand vóór de verhuizing; de symboolnamen ernaast blijven
wél houdbaar.

| Wat | Waar | Werk |
|---|---|---|
| Modulepaden | `pom.xml:21` (+ regel voor de simulator), `relativePath` in de module-poms | triviaal |
| `git mv` + package blijft gelijk | `services/demo-console` → `demo/demo-console` | triviaal |
| Spec-hergebruik simulator | `../berichtenmagazijn/…` wordt `../../services/berichtenmagazijn/…` in `api.spec.file` | triviaal, maar de relatieve verwijzing kruist nu een wortel — noem dat bij de property |
| **Uitrolfilter precies maken** | `wijzigingsfilter.sh:55` (`NIET_DEPLOYBAAR`) | **de echte post — zie hieronder** |
| Test-scope | `wijzigingsfilter.sh:62` (`BUITEN_DEMO_CONSOLE` wordt `^demo/`), `test.yml:144` (shard) | klein, maar let op de drift-val hieronder |
| Zelftoets van het filter | `test-wijzigingsfilter.sh:93`, `:103`, `:154`, `:159`, `:309` | klein |
| CodeQL-volledigheidscontrole | `codeql.yml:106`, `:157`: `libraries/* services/*` → ook `demo/*` | triviaal |
| JaCoCo-globs | `test.yml:180-181`, `:291-292`: `demo/*/…` erbij, anders valt de simulator-coverage stil uit de PR-comment | triviaal |
| Padfilter CI-scripts | `ci-scripts.yml:26` (`services/demo-console/**`) | triviaal |
| Documentatie | `README.md` (r. 44-49), `CLAUDE.md` (modules, bestandstabel, testcommando's), `docs/ontwikkelen.md:32` | klein |
| Nieuw | `demo/README.md`, dependency-controle + testcase | klein |
| Modulepad in de warmup-laag van het fuzz-base-image (bij de uitvoering gevonden; het Dockerfile bewaakt zichzelf, dus dit zou luidruchtig gefaald hebben) | `.clusterfuzzlite/base/Dockerfile:29` | triviaal |

Niet geraakt: `deploy.yml` (de build-matrix noemt alleen `berichtenuitvraag` en `berichtenmagazijn`,
`deploy.yml:293`/`:318`), `.clusterfuzzlite/build.sh:5` (expliciete modulelijst zonder demo-modules)
en de test-shards zelf (`test.yml:144` gebruikt het complement `!services/berichtenmagazijn`, dat een
nieuwe reactor-module per constructie opneemt, ongeacht de map).

Schatting: **ongeveer een dag**, inclusief het draaien van de filter-suite en één PR-review. Het
grootste deel is niet het verplaatsen maar het narekenen van de filters.

### De echte post: `^demo/` betekent nu "raakt de uitrol niet"

`NIET_DEPLOYBAAR` sloot vóór deze PR `^demo/` én `^services/demo-console/` uit: een PR die daar
alleen aankomt, bouwt geen images en rolt geen preview uit (`wijzigingsfilter.sh:44-55`). Dat klopt
zolang niets onder `demo/` een eigen image heeft.

De simulator krijgt dat wél: een eigen ZAD-component in `mpfm-w3h`, met een image uit de deploy
(magazijn-simulator-design, sectie ZAD). Zet je hem onder `demo/` zonder het filter aan te passen,
dan slaat een simulator-only PR zijn eigen imagebuild over — en dat faalt stil, want een
overgeslagen job telt als succes in de required checks. Precies de faalwijze waar dat script tegen
gebouwd is.

Eerlijk gezegd: **die kost ontstaat door de verhuizing.** Op `services/magazijn-simulator` valt de
module vanzelf buiten elke uitsluiting en doet het filter het goede. Het is dus geen verborgen
schuld die we toch al hadden, maar de prijs van de nieuwe indeling.

Twee manieren om hem te betalen:

1. **Padprecies uitsluiten** — `^demo/environment/`, `^demo/generated/`, `^demo/[^/]*\.(sh|py)$`,
   `^demo/demo-console/`. Werkt, maar elke nieuwe demo-module moet er expliciet bij: een lijst die
   stil kan verouderen, en dan is de fout weer een overgeslagen build.
2. **Omkeren naar een allowlist** — noem de paden die een image voeden
   (`services/berichtenuitvraag/`, `services/berichtenmagazijn/`, `demo/magazijn-simulator/`,
   `wiremock/externe-stubs/`, `demo/environment/federatie/contracts/`, plus `pom.xml`, `libraries/`
   en `deploy.yml`) en sluit de rest uit. Dan is "vergeten" zichtbaar als een overbodige build, niet
   als een overgeslagen build — de fail-safe kant.

Aanbeveling: **optie 1**, in dezelfde PR als de verhuizing.

Bij het uitvoeren bleek de eerste versie van dit document optie 2 aan te bevelen met het argument
dat "vergeten" daar een overbodige build kost. Dat is omgekeerd. Bij een allowlist levert een
vergeten pad juist een *overgeslagen* build op — de stille faalwijze, want een overgeslagen job telt
als succes voor branch protection. De uitsluitingsvorm die het script al gebruikt is per constructie
fail-safe: wat er niet in staat, valt door en wordt gebouwd. Alleen de te grove regel `^demo/` moest
weg, niet de vorm.

Uitgevoerd als `DEMO_BUITEN_UITROLPOORT` in `wijzigingsfilter.sh`: `^demo/demo-console/`,
`^demo/environment/` en `^demo/[^/]*\.(sh|py)$`. `demo/generated/` staat er bewust niet bij — die map
is gitignored en haalt dus nooit een bestandenlijst.

### De drift-val bij de test-scope

`BUITEN_DEMO_CONSOLE` wordt na de verhuizing simpelweg `^demo/`. De demo-only-shard in `test.yml:144`
noemt vandaag één module met de hand; met twee demo-modules wordt dat een handmatige lijst, en dat
is de constructie die de commentaren in `test.yml:130-139` juist vermijden ("een module die nergens
genoemd wordt blijft stil ongetest terwijl beide shards groen rapporteren").

Leid de lijst daarom af in de workflow-stap zelf — `for m in demo/*/pom.xml` → `-pl` — in plaats van
hem in te typen. Kost een paar regels shell en verwijdert de drift.

## Alternatieven

**A. Laten zoals het is.** Nul kosten, en verdedigbaar: het gaat vandaag om één module. Maar het
antwoord op "wat is hier het stelsel" blijft dan "lees de poms", en na de simulator staat er een
module van formaat tussen de echte services die nadrukkelijk nooit productie hoort te draaien. Het
argument om te wachten wordt met elke module zwakker, niet sterker.

**B. Alleen naamgeving en documentatie.** Modules laten staan, maar hernoemen naar `demo-console` en
`demo-magazijn-simulator` en het verschil in `README.md` uitleggen. Goedkoop (geen enkel CI-pad
wijzigt) en het lost het leesbaarheidsprobleem grotendeels op. Nadeel: de scheiding is een afspraak
zonder structuur — er is geen plek waar demo-code vanzelf landt, en de volgende demo-module heet
misschien `simulatie-console`. Dit is de beste terugvaloptie als het team de verhuizing te duur
vindt.

**C. Een eigen repository.** Maximaal helder, maar de demo-stack leunt op de spec, de compose-stack
en de FSC-harness van deze repository. Een tweede repository levert versie-koppeling, een tweede
CI-keten en cross-repo-PR's op voor werk dat vandaag in één commit past. Niet doen zolang de demo
zich per commit met het stelsel meebeweegt.

**D. `demo/` wordt module-wortel én de rest van `demo/` verhuist mee naar een eigen indeling.**
Grondiger, maar het raakt circa tien vaste paden in `fsc-harness-overlays.yml` en het
image-bouwpad in `deploy.yml:390` zonder dat een meelezer er iets voor terugkrijgt.

## Blijft de scheiding houdbaar?

Het risico is dat demo-code productiecode aantrekt: een simulator die "even" een entity uit het
magazijn hergebruikt, en dan zit productie vast aan een demo-eis.

De signalen zijn voorzichtig gunstig. `demo/demo-console/pom.xml` heeft bewust géén afhankelijkheid
op `fbs-common` en heeft de elfproef-validatie lokaal geïnlined; de simulator hergebruikt alleen de
OpenAPI-spec van het magazijn, een statisch bronbestand, en genereert daaruit zijn eigen DTO's in een
eigen package. De koppeling die er is, loopt dus via het contract en niet via code.

De prijs daarvan is duplicatie — de elfproef staat twee keer in de repository. Dat is een bewuste
keuze en de aanbevolen dependency-controle bestendigt hem: zonder die controle is de eerstvolgende
`<dependency>` op `fbs-common` in een demo-module een detail in een review, mét die controle is het
een rode build met een uitleg erbij.

## Verificatie na uitvoering

- `./mvnw -B clean test` bouwt zes (straks zeven) modules; de reactor-volgorde noemt de
  demo-modules onder hun nieuwe pad.
- `.github/scripts/test-wijzigingsfilter.sh` groen, mét nieuwe fixtures: een simulator-only wijziging
  levert `deploy=true`, een demo-console-only wijziging `deploy=false` en `demo-only=true`.
- Een PR die alleen `demo/magazijn-simulator/` raakt, bouwt aantoonbaar zijn image; een PR die alleen
  `demo/demo-console/` raakt, bouwt geen enkel image.
- De JaCoCo-comment op een PR toont de simulator-module (bewijst dat de glob klopt).
- CodeQL rapporteert per module bronbestanden, inclusief beide demo-modules.

## Openstaande beslissingen voor het team

1. **Gaan we verhuizen?** Voorstel hierboven, alternatief B als terugvaloptie.
2. **Heet de wortel `demo/`?** Hij bevat straks ook de FSC-harness, die eerder een lokale
   infrastructuur-standaard-in is dan een demo. `demo/` blijft het kortste woord dat klopt voor een
   meelezer; alternatieven zoals `simulatie/` dekken de harness juist slechter.
3. **Vóór of ná stap 1 van de simulator?** Vóór is goedkoper — dan verhuist er één module in plaats
   van twee, en begint de simulator meteen goed. Ná betekent dat het uitrolfilter twee keer om moet.
4. **Krijgt de simulator een Bruno-collectie, en waar?** De conventie is `bruno/<service-naam>/`. Bij
   een demo-module ligt `demo/magazijn-simulator/bruno/` meer voor de hand; dat is dan wel een tweede
   conventie.

## Uitvoering

Gedaan in deze PR, in de volgorde van de kostentabel:

| Wat | Bestand |
|---|---|
| `services/demo-console` → `demo/demo-console` (rename, historie behouden) | — |
| Modulepad + comment over de drie wortels | `pom.xml` |
| `^demo/` vervangen door `DEMO_BUITEN_UITROLPOORT`; `BUITEN_DEMO_CONSOLE` → `BUITEN_DEMO` (`^demo/`) | `.github/scripts/wijzigingsfilter.sh` |
| Fixtures verlegd, plus een nieuwe voor een demo-module mét image en een prefix-buur | `.github/scripts/test-wijzigingsfilter.sh` |
| Demo-shard leidt zijn modulelijst af uit `demo/*/pom.xml`; JaCoCo-globs en artefactpaden uitgebreid | `.github/workflows/test.yml` |
| Modulelus over `demo/*` | `.github/workflows/codeql.yml` |
| Padfilter, nieuwe suite geregistreerd, assertiedrempels bijgesteld | `.github/workflows/ci-scripts.yml` |
| Grensbewaking stelsel ↛ demo, met eigen fixture-suite | `.github/scripts/demo-grens.sh`, `test-demo-grens.sh` |
| Modulepad in de warmup-laag van het fuzz-base-image | `.clusterfuzzlite/base/Dockerfile` |
| Wat er onder `demo/` staat en waarom | `demo/README.md` |
| Paden en de drie wortels | `README.md`, `CLAUDE.md`, `docs/ontwikkelen.md`, `docs/demo-runbook.md` |
| Simulator landt op `demo/magazijn-simulator`; openstaande beslissing 7 afgehandeld | `docs/plans/2026-08-21-magazijn-simulator-design.md` |

Drie dingen die tijdens de uitvoering veranderden ten opzichte van het voorstel:

- **Optie 1 in plaats van optie 2** voor het uitrolfilter; zie de correctie hierboven.
- **`demo-only` en `deploy` mogen nooit samen aanstaan.** Vóór de verhuizing volgde dat uit de
  patronen zelf: alles wat de test-scope naar de demo-modules bracht, viel ook buiten de uitrol. Met
  de padprecieze uitsluiting is die insluiting weg — een demo-module die een image voedt zou de
  previews openzetten terwijl in diezelfde run alleen de demo-modules getest zijn, en de uitrolpoort
  ziet alleen een geslaagde test-check. `classificeer` laat `demo-only` daarom terugvallen op `false`
  zodra de uitrol geraakt wordt, en elke fixture in de suite toetst die invariant.
- **`fuzz=false` bij een demo-only PR.** `FUZZ_RELEVANT` ankert op `^libraries/|^services/`, dus
  demo-code koopt geen fuzz-ronde meer. Dat is juister dan de oude uitkomst: de fuzz-doelen staan
  alleen in de twee services en twee libraries (`.clusterfuzzlite/build.sh`), dus een demo-console-PR
  draaide een ronde die per definitie niets nieuws raakte.

De grensbewaking is geen losse CI-stap geworden maar een assertie in `test-demo-grens.sh` ("de
repository zelf respecteert de grens"). Die suite draait al in `ci-scripts.yml`, dus het scheelt een
stap zonder dekking te verliezen — en het volgt de vorm die `test-wijzigingsfilter.sh` al gebruikt
voor zijn kruiscontroles op schijf.

## Besluit

_Nog te nemen. Vul hier de uitkomst van de teambespreking in, inclusief de datum en de reden — ook
als de uitkomst "we laten het zoals het is" is. De uitvoering staat klaar in de PR; een andere
uitkomst dan "doen" betekent die revert._
