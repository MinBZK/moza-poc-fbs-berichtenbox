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
bestanden uit de CodeQL-database.

**Verwachting weerlegd door de meting.** Vooraf was de aanname dat de analyse-stap met die
omvang mee zou schalen en richting ~40 s zou zakken. Run `32259136441` laat 61 s zien, tegen
53/65/70 s in de baseline: geen meetbaar verschil. De analyse-stap wordt kennelijk gedomineerd
door het evalueren van de query-suite, niet door het aantal bronbestanden. De winst van deze
wijziging zit dus volledig in de build-stap.

## Ontwerpkeuzes

**Testcode valt bewust buiten de analyse.** Dit is een echte inperking: bevindingen in
testcode verdwijnen. Verdedigbaar omdat detekt testcode al uitsluit (parent-pom:
`<excludes>**/target/**,**/src/test/**</excludes>`) en testcode niet in productie draait.

De uitzondering die er wél toe doet is een hardcoded credential in een test. Die klasse hoort
bij secret scanning met push protection, niet bij een SAST-tool — vandaar het punt in de
verificatie hieronder.

**Geen `build-mode: none`.** Buildless extractie ondersteunt Kotlin niet.

**`-T 1C` (parallelle reactor), maar pas na meting.** De module-graaf is breed: alleen
`fbs-common` is upstream van de rest. De volgorde was bewust: eerst de bouwstap alleen meten
(landde op 4:21, boven de grens), dán pas parallelliseren. De CodeQL-extractor traceert
compiler-processen, en of die tracing onder een parallelle reactor volledig blijft, is niet iets
om aan te nemen — zie de telling onder Resultaat, die het bevestigt.

**`clean` blijft staan.** Op een verse runner is het een no-op (gemeten: 0 s). Het staat er
zodat het commando ook lokaal doet wat de naam belooft.

**`-Dmaven.test.skip=true` blijft staan**, hoewel `compile` de testfasen sowieso niet bereikt.
Het is een vangrail: wie de fase later naar `verify` zou optrekken, krijgt dan niet ongemerkt
de testcompilatie terug.

**Wat níet weggaat:** de 90 s `kotlin:compile` van berichtenmagazijn. Zonder tracing kost die
compilatie 6 s (gemeten in test-run `32255054287`); de overige 84 s ís de extractie. Dat is de
harde vloer van deze analyse en geen doelwit voor optimalisatie.

## Twee vangrails tegen stil minder analyseren

Een reactor die per ongeluk minder bouwt, eindigt groen — CodeQL uploadt dan een vrijwel lege
SARIF en de PR ziet er veilig uit terwijl er niets is gekeken. De CodeQL-actie vangt alleen het
geval "nul bestanden" af, niet "één module in plaats van zes".

**Vóór de analyse:** een stap die faalt als een module geen `.class`-bestanden opleverde. Die
kapt een gekrompen reactor af vóór er 60 s analyse in gaat.

**Na de analyse:** een stap die per module telt hoeveel bronbestanden er in het source-archive
van de database staan, en faalt bij nul. Die vangt het geval dat wél compileert maar niet in de
database belandt — precies het risico van parallelle tracing.

Waarom een telling en niet het aantal bevindingen: de repo heeft **nul** CodeQL-bevindingen
(`results_count: 0`). Nul komt er dus uit met én zonder complete database; als signaal is dat
waardeloos. De bestandstelling is dat wel, en is bovendien de meetlat voor toekomstige ingrepen
in de bouwstap.

## Verificatie — uitgevoerd

1. **Baseline:** medianen over 15 runs van de oude workflow, alle van 19-08-2026 (dezelfde
   codebase). Niet één vóór/ná-paar: de runner-spreiding is ~25%.
2. **Na de wijziging:** 7 runs met alleen de bouwstap, daarna 5 runs met `-T 1C`.
3. **Vergeleken:** totaaltijd, build-stap en analyse-stap apart, aantal downloads, aantal
   geëxtraheerde bronbestanden per module. Zie Resultaat.
4. **Extractie identiek:** 188 bestanden, per module gelijk, in alle parallelle runs.
5. **Compenserende maatregel bevestigd, met één kanttekening.** Secret scanning en push
   protection staan aan (`secret_scanning` en `secret_scanning_push_protection`: `enabled`).
   Maar `secret_scanning_non_provider_patterns` staat **uit**, en dat is juist de categorie die
   hier telt: provider-patronen herkennen tokens met een vaste vorm (AWS, GitHub, Stripe),
   non-provider-patronen de generieke geheimen — private keys, connection strings, wachtwoorden
   in auth-headers. Een Redis-wachtwoord of JDBC-URL in een test valt in die tweede groep.

   De compensatie voor het uitsluiten van testcode is daarmee zwakker dan gedacht. Overweging
   voor het team: non-provider-patronen aanzetten. Dat is een repo-instelling, geen
   codewijziging, en valt buiten deze PR.
6. **Build-waarschuwingen:** geen nieuwe. Bekende baseline: twee Kotlin-waarschuwingen in
   `BerichtStatusRepository.kt:72` over `java.lang.Boolean`. Die stonden er al.

## Resultaat

Medianen over 15 baseline-runs (oude workflow, alle van 19-08-2026, dus dezelfde codebase) en
5 runs op de nieuwe workflow:

| | Baseline (n=15) | Nieuw (n=5) | Verschil |
|---|---|---|---|
| Totaal | **362 s** (276–373) | **261 s** (260–284) | −101 s (−28%) |
| Build | **268 s** (200–282) | **179 s** (172–189) | −89 s (−33%) |
| Analyse | **66 s** (53–70) | **59 s** (58–64) | −7 s (−11%) |

Oftewel **6:02 → 4:21** op de mediaan.

Onderliggend bewijs uit het build-log:

| | Baseline | Nieuw |
|---|---|---|
| Downloads | 1962 | **0** (volledige cache-hit, 94 MB) |
| Reactor-tijd | 4:27 | **2:49** |
| `test-compile` / `jandex` / `jar` / `quarkus:build` | aanwezig | **afwezig** |

De build-stap kwam uit op de schatting (179 s tegen ~167 s geschat). De analyse-stap zakte 7 s
— binnen de spreiding van de baseline en ver van de ~25 s die verwacht was; zie de correctie
bij oorzaak 3.

Kanttekening bij de spreiding: de baseline is duidelijk tweetoppig (drie runs rond 280 s, de
rest rond 360 s) door runner-variatie. Vier van de vijf nieuwe metingen zijn herhalingen van
dezelfde run, dus die vijf onderschatten mogelijk de runner-variatie. De build-vergelijking
staat los daarvan: 0 tegen 1962 downloads is geen ruis.

### Parallelle reactor

Met alleen de bouwstap bleef de mediaan op 4:21 steken, boven de zelfgestelde grens. Daarom
draait de reactor parallel (`-T 1C`). Medianen over 5 runs:

| Variant | n | Totaal | Build | Analyse |
|---|---|---|---|---|
| `autobuild` (baseline) | 15 | 362 s (276–373) | 268 s (200–282) | 66 s (53–70) |
| `compile` + cache | 7 | 261 s (204–284) | 177 s (130–189) | 59 s (51–64) |
| `compile` + cache + `-T 1C` | 5 | **235 s** (187–251) | **155 s** (119–160) | 59 s (46–61) |

**6:02 → 3:55 op de mediaan, een winst van 127 s (35%).** Daarmee haalt de analyse het
criterium van maximaal 4 minuten.

De parallelle winst (22 s op de build) is kleiner dan de ~44 s die het kritieke pad suggereert.
Verklaring: de extractie is CPU-gebonden en de runner heeft 4 vCPU's, dus modules die naast
elkaar draaien concurreren om dezelfde kernen. Wat overlapt zijn vooral de korte modules en de
I/O, niet het rekenwerk van berichtenmagazijn.

### Waarom `1C` en niet meer

`-T 2C` gemeten over 3 runs: build-mediaan 158 s, totaal 245 s — tegen 155 s en 235 s bij `1C`.
Geen verbetering, binnen de ruis zelfs iets slechter. Dat past bij de verklaring hierboven: de
extractie is CPU-gebonden en de runner heeft 4 vCPU's, dus meer threads dan kernen levert alleen
context-switches op. De bestandstelling bleef ook hier 188.

### Waarom geen sharding over meerdere jobs

De test- en fuzz-workflows sharden wel, dus de vraag ligt voor de hand. Doorgerekend op de
gemeten modultijden pakt het hier slecht uit, omdat één module (`berichtenmagazijn`, 104 s van de
169 s reactor) de vloer bepaalt en elke extra job opnieuw init én analyse betaalt (~74 s vast):

| Shard | Build | Job totaal |
|---|---|---|
| `fbs-common` + `berichtenmagazijn` | 144 s | 229 s |
| overige modules (`fbs-common` dubbel gebouwd) | 73 s | 158 s |

Wandkloktijd = de traagste shard = 229 s, tegen 235 s nu: **6 seconden winst voor 65% meer
runnertijd** (387 s tegen 235 s). In een wijziging die overbodig rekenwerk wil wegnemen is dat de
verkeerde ruil. Sharding werkt bij de tests omdat het werk daar écht deelbaar is en de vaste
kosten per job klein zijn ten opzichte van de looptijd; hier is precies het omgekeerde waar.

Daar komt bij dat twee shards twee SARIF-uploads met eigen `category` betekenen, met gevolgen
voor de alert-deduplicatie en de Scorecard-SAST-check.

Wie de wandkloktijd verder omlaag wil, moet bij de 84 s extractor-overhead op
`berichtenmagazijn` zijn. Sharding raakt die niet — die zit binnen één module.

### Extractie blijft volledig

Het risico van parallelle tracing — stil code kwijtraken uit de database — is gemeten, niet
aangenomen. De telling van geëxtraheerde bronbestanden is in vier parallelle runs identiek aan
de sequentiële referentie (run `32343961951`), tot op de module:

| Module | Sequentieel | `-T 1C` (4 runs) |
|---|---|---|
| libraries/fbs-common | 36 | 36 |
| libraries/fbs-berichtensessiecache | 20 | 20 |
| libraries/fbs-magazijnregister | 4 | 4 |
| services/berichtenmagazijn | 63 | 63 |
| services/berichtenuitvraag | 38 | 38 |
| services/demo-console | 27 | 27 |
| **Totaal** | **188** | **188** |

Kanttekening bij de tijdmetingen: de runner-vloot is duidelijk tweetoppig (één op de vijf runs
is ~25% sneller), en de reruns binnen een set kunnen daardoor correleren. De medianen boven
gaan over sets waarin die snelle runner in beide gevallen precies één keer voorkomt. De
bestandstelling heeft dat probleem niet — die is deterministisch.

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
