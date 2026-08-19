# CI: CodeQL-autobuild vervangen door een minimale bouwstap

**Status:** Uitgevoerd

Hoort bij MinBZK/MijnOverheidZakelijk#976.

## Aanleiding

De CodeQL-analyse draait op elke wijziging en kost ruim zes minuten, waarvan het overgrote
deel naar het klaarzetten van de code gaat in plaats van naar het zoeken naar kwetsbaarheden.

Gemeten op drie geslaagde runs van 19-08-2026 (`32255054287`, `32250085413`, `32245582177`):

| Run | Totaal | Init | Autobuild | Analyse |
|---|---|---|---|---|
| `32255054287` | 4:36 | 14 s | 3:20 | 53 s |
| `32250085413` | 6:08 | 18 s | 4:38 | 65 s |
| `32245582177` | 6:10 | 23 s | 4:29 | 70 s |

De spreiding is ~25% bij identieke code — runner-variatie. Eén meetpaar vóór/ná bewijst dus
niets; de verificatie hieronder werkt met medianen over meerdere runs.

## Waar de tijd heen gaat

Uit de per-goal tijdstempels van het reactor-log van run `32250085413`:

| Onderdeel | Gemeten |
|---|---|
| Opnieuw ophalen van dependencies | 1962 downloads, 68 s met download-activiteit |
| `kotlin:test-compile` (6 modules) | 45 s |
| `generate-code-tests` + `jar` + `quarkus:build` | 16 s |
| `kotlin:compile` berichtenmagazijn | 90 s |

`github/codeql-action/autobuild` draait voor Maven:

```
./mvnw clean package -f pom.xml -B -V -e -DskipTests -Dmaven.test.skip.exec ...
```

`-DskipTests` slaat alleen de surefire-*uitvoering* over, niet `kotlin:test-compile`. En
`package` sleept `jandex`, `jar` en `quarkus:build` mee. Dat is werk waarvan de
veiligheidsanalyse niets leest.

## Drie oorzaken, drie ingrepen

### 1. Geen dependency-cache

`test.yml`, `detekt.yml` en `deploy.yml` hebben alle drie `setup-java` met `cache: maven`;
`.clusterfuzzlite/base/Dockerfile` warmt zijn Maven-repository in een Docker-laag. `codeql.yml`
was de enige Maven-draaiende workflow zonder enige vorm van hergebruik.

Bewijs uit twee logs van dezelfde dag: CodeQL-run **1962** `Downloaded from`-regels, test-run
**2**. De cache is 94 MB en herstelt in 1,3 s; de hele `setup-java`-stap kost 2–3 s.

**Winst: ~50–65 s tegen ~3 s kosten.**

### 2. Meer bouwen dan de analyse leest

Per module gemeten wat `package` extra doet bovenop `compile`:

| Module | test-compile | generate-code-tests + jar + quarkus:build |
|---|---|---|
| fbs-common | 9 s | 1 s |
| fbs-magazijnregister | 1 s | — |
| fbs-berichtensessiecache | 13 s | 3 s |
| berichtenmagazijn | 15 s | 6 s |
| berichtenuitvraag | 5 s | 4 s |
| demo-console | 2 s | 2 s |
| **Totaal** | **45 s** | **16 s** |

**Winst: ~61 s.**

### 3. Testcode is twee derde van de geanalyseerde source

```
src/main:  152 bestanden, 13.437 regels Kotlin
src/test:  196 bestanden, 27.282 regels Kotlin
```

Door bij `compile` te stoppen verdwijnt niet alleen de 45 s compilatie, maar ook ~67% van de
bestanden uit de CodeQL-database. De analyse-stap (53–70 s) schaalt met die omvang, dus daar
valt de tweede helft van de winst. Die winst is beredeneerd, niet gemeten — de verificatie
hieronder toetst hem expliciet.

## Ontwerpkeuzes

**Testcode valt bewust buiten de analyse.** Dit is een echte inperking: bevindingen in
testcode verdwijnen. Verdedigbaar omdat detekt testcode al uitsluit (parent-pom:
`<excludes>**/target/**,**/src/test/**</excludes>`) en testcode niet in productie draait.

De uitzondering die er wél toe doet is een hardcoded credential in een test. Die klasse hoort
bij secret scanning met push protection, niet bij een SAST-tool — vandaar het punt in de
verificatie hieronder.

**Geen `build-mode: none`.** Buildless extractie ondersteunt Kotlin niet.

**`-T 1C` (parallelle reactor) nu niet.** De module-graaf is breed: alleen `fbs-common` is
upstream van de rest, dus het kritieke pad zou van ~200 s naar ~135 s kunnen. Maar de
CodeQL-extractor traceert compiler-processen, en of die tracing onder een parallelle reactor
volledig blijft, moet gemeten worden — niet aangenomen. Eerst de basiswinst meten. Landt de
mediaan boven 4 minuten, dan is dit de volgende stap, met een expliciete controle op het
aantal bevindingen per module.

**`clean` blijft staan.** Op een verse runner is het een no-op (gemeten: 0 s). Het staat er
zodat het commando ook lokaal doet wat de naam belooft.

**`-Dmaven.test.skip=true` blijft staan**, hoewel `compile` de testfasen sowieso niet bereikt.
Het is een vangrail: wie de fase later naar `verify` zou optrekken, krijgt dan niet ongemerkt
de testcompilatie terug.

**Wat níet weggaat:** de 90 s `kotlin:compile` van berichtenmagazijn. Zonder tracing kost die
compilatie 6 s (gemeten in test-run `32255054287`); de overige 84 s ís de extractie. Dat is de
harde vloer van deze analyse en geen doelwit voor optimalisatie.

## Vangrail tegen stil minder analyseren

Een reactor die per ongeluk minder bouwt, eindigt groen — CodeQL uploadt dan een vrijwel lege
SARIF en de PR ziet er veilig uit terwijl er niets is gekeken. De CodeQL-actie vangt alleen
het geval "nul bestanden" af, niet "één module in plaats van zes".

Daarom controleert een stap ná de build dat elke module `.class`-bestanden opleverde. Die
draait tussen build en analyse, zodat een onvolledige build de analyse niet eens bereikt.

## Verificatie

1. Baseline: mediaan van 5 CodeQL-runs op ongewijzigde `main` (nu: ~6:05).
2. Baseline-SARIF vastleggen, gesplitst naar `src/main` en `src/test`, per module.
3. Na de wijziging 5 runs op een triviale wijziging.
4. Vergelijken: mediaan totaaltijd, build-stap, **analyse-stap apart** (toetst de aanname uit
   oorzaak 3), aantal downloads, aantal geëxtraheerde bestanden.
5. De `src/main`-bevindingen moeten identiek zijn aan de baseline. Elk verschil daar is een
   regressie, geen winst.
6. Bevestigen dat secret scanning met push protection aanstaat — dat dekt de enige
   bevindingsklasse die door deze wijziging echt wegvalt.
7. Build-output nalopen op nieuwe waarschuwingen. Bekende baseline: twee Kotlin-waarschuwingen
   in `BerichtStatusRepository.kt:72` over `java.lang.Boolean`. Die stonden er al.

## Projectie

| Stap | Nu | Na deze wijziging |
|---|---|---|
| Set up + checkout | 3 s | 3 s |
| setup-java + cache | — | 3 s |
| Init CodeQL | 18 s | 18 s |
| Build | 278 s | ~167 s |
| Analyse | 65 s | ~40 s (aanname) |
| Overig | 4 s | 4 s |
| **Totaal** | **6:08** | **~3:55** |

Zonder de analyse-winst landt het op ~4:20. Dan is `-T 1C` alsnog nodig.

## Lokale validatie vooraf

Op `main` (commit `0a735de6`) gedraaid:

```
./mvnw -B -ntp -e clean compile -Dmaven.test.skip=true    → BUILD SUCCESS (7 modules)
./mvnw -B -ntp -T 1C clean compile -Dmaven.test.skip=true → BUILD SUCCESS (6 threads)
```

Geverifieerd in de output:

- Alle modules compileren; downstream-modules lezen `target/classes` van upstream — er bestond
  geen enkele jar.
- De 19 gegenereerde OpenAPI-Java-bestanden worden gegenereerd én gecompileerd, dus die blijven
  in beeld voor de analyse.
- `target/test-classes` leeg, geen jars, geen `jandex`, geen `quarkus:build`.
