# Maven-cache per scope en Google's Central-mirror in CI

**Status:** Uitgevoerd

## Context

CI-jobs faalden geregeld al bij het inlezen van de POMs met
`Could not transfer artifact … from/to central (https://repo.maven.apache.org/maven2): HTTP Status: 429`.
Lokaal speelde het niet. Gemeten op 2026-11-20, over de eerste pogingen van opnieuw gedraaide runs
sinds 2026-11-06:

- Rond de tien jobs vielen op een 429 van Central, bijna altijd na `maven cache is not found`.
- `setup-java` met `cache: maven` gebruikt één sleutel (hash van alle `pom.xml`'s) voor alle jobs en
  heeft geen terugval. Na elke pom-wijziging, ook een Dependabot-bump, haalden test-shards,
  deploy-builds, detekt en CodeQL tegelijk de hele boom op vanaf gedeelde runner-adressen.
- Caches onder dezelfde sleutel waren 94 MB of ~300 MB: de job die het eerst klaar was (detekt, alleen
  plugins) bewaarde een halve repository, en de andere jobs kregen een "hit" zonder hun dependencies.
- Elke PR en merge-queue-entry bewaarde een eigen kopie: 61 caches, 10,8 GB, boven de grens van
  10 GB per repository. GitHub verdrong daardoor caches die nog in gebruik waren.

## Ontwerpkeuzes

- **Eigen composite action `.github/actions/maven-cache`** in plaats van `cache: maven`, met een
  `scope` per soort job (`test-<shard>`, `detekt`, `codeql`, `build-<service>`, `build-demo-images`).
  Een job vult zo alleen zijn eigen cache. De demo-shard bestaat alleen op demo-only-PR's en leest
  de scope `test-overig`, waar demo/ in valt.
- **Alleen een push, schedule of handmatige run op main bewaart, en zonder `restore-keys`.** Elke
  sleutel begint leeg en bevat alleen wat die pom-stand nodig heeft; met terugval zou de cache bij
  elke bump de oude versie meenemen en blijven groeien. `github.ref` alleen is niet genoeg:
  `pull_request_target` en `workflow_run` hebben ook ref main. PR's en de merge queue herstellen
  alleen, met terugval op dezelfde scope en daarna op elke scope, zodat Maven na een pom-wijziging
  alleen het verschil ophaalt.
- **De Maven-distributie van de wrapper heeft een eigen cache**, met als sleutel alleen
  `maven-wrapper.properties`. `mvnw` haalt hem zonder retry van Central; onder de repository-sleutel
  zou elke pom-wijziging op main hem tien keer tegelijk laten ophalen. Hij komt bewust niet via de
  mirror (`MVNW_REPOURL`), want die kent geen terugval op Central.
- **Google's mirror als extra repository vóór Central**, via een settings-bestand dat de action naar
  `~/.m2/settings.xml` schrijft. Geen `<mirrorOf>`: dan vervangt de mirror Central, en breekt een
  artefact dat de mirror nog niet heeft de build. Alleen CI; lokaal verandert niets. De settings
  tellen mee in de cachesleutel.
- **`checksumPolicy` `fail` op de mirror én op Central** (Central opnieuw gedefinieerd met de id
  uit de super-POM). Een kapot artefact valt zo door naar de volgende repository of laat de build
  falen, in plaats van onder `warn` in de cache van main te belanden.
- **`jdk-handtekening.py` eist per setup-java-stap dat `cache:` uit staat en dat
  `./.github/actions/maven-cache` erna volgt, vóór de eerste `mvnw`/`mvn` in een `run:` en onder
  dezelfde `if:`.** Al die varianten bouwen groen, maar zonder de mirror. Een Maven-aanroep in een
  script dat een `run:` aanroept, ziet de guard niet.
- **`test-maven-cache.sh` toetst wat zonder runner te toetsen is:** het bewaarbesluit (welke
  ref/event-combinaties bewaren, en dat het in `$GITHUB_OUTPUT` landt), dat bewaar- en herstelstap
  per pad dezelfde sleutel en complementaire voorwaarden hebben, de opbouw van de sleutels en
  `restore-keys`, en de settings die de installatiestap neerzet (volgorde, Central, checksum-policy,
  geen `<mirrors>`). Elk daarvan faalt stil als het misgaat.

## Bewust geaccepteerd restrisico

- **Een nieuwe vertrouwde partij.** De mirror is de publieke GCS-bucket `maven-central-eu`, zonder
  garantie van Google. Maven toetst een artefact alleen aan de checksum van dezelfde bron; een
  bucket die artefact én checksum vervalst, wordt niet opgemerkt. Dat raakt ook plugins in de
  jib-builds die met `packages: write` naar ghcr pushen. Bij Central over https geldt hetzelfde
  voor Sonatype; het verschil is wie die positie kan innemen. Een verdwenen bucket blijft stil (alles
  valt terug op Central), en een opnieuw geclaimde bucketnaam zou daarna weer vóór Central staan.
- **Doorwerking via de cache.** Een vervalst artefact dat main binnenhaalt, staat daarna in
  `maven-repo-*` en wordt bij het herstellen niet opnieuw gecontroleerd. Elke run met die exacte
  sleutel erft het tot de pom- of settings-hash wijzigt. PR- en merge-queue-runs zonder exacte
  sleutel herstellen via `restore-keys` de nieuwste cache van de scope (of van elke scope); die
  blijft de vervalste tot main voor die scope een nieuwe sleutel bewaart, en dat gebeurt niet
  zolang die job rood is. Hetzelfde geldt voor de wrapper-distributie: mvnw toetst
  `distributionSha256Sum` alleen bij de download, en die cache leeft tot de volgende wrapper-bump.
  Incidentstappen, in deze volgorde:
  1. Verwijder de caches. Anders herstelt de fix-PR in stap 2 via `restore-keys` juist de
     vervalste cache, in de merge queue ook in de jib-builds met `packages: write`.
     `gh cache delete` kent geen prefix:
     `gh cache list --key maven-repo- --limit 1000 --json id -q '.[].id' | xargs -r -n1 gh cache delete`,
     en hetzelfde voor `maven-wrapper-`.
  2. Haal de bron uit `.github/actions/maven-cache/settings.xml` en merge dat; merge in dit venster
     niets anders naar main. Andere PR-branches halen tot de merge nog bij de oude bron.
  3. Verwijder de caches opnieuw: een push naar main tussen stap 1 en 2 kan vanuit de oude bron
     opnieuw bewaard hebben.
  4. Draai main opnieuw, zodat elke scope een schone cache bewaart.
- **Een rode job op main bewaart niets** (`actions/cache` bewaart alleen bij succes), en main heeft
  geen terugval. Zolang een scope op main rood is, haalt elke main-run die boom opnieuw op en lezen
  PR's een oudere pom-stand. Bewaren bij falen zou een halve repository onder de exacte sleutel
  vastzetten, precies wat dit ontwerp oplost; de oplossing is main groen maken.
- **Een mirror die verbindingen aanneemt en dan blijft hangen**, is niet getest en kost per artefact
  de resolver-time-out. Een kortere time-out helpt nauwelijks: honderden artefacten keer een paar
  seconden is nog steeds een vastgelopen job.
- Structureel te dichten met Maven Resolver *trusted checksums* (ingecheckte hashes per artefact,
  los van de bron). Dat is een eigen stuk werk.

## Verificatie

- Lokaal, lege repository, `-pl libraries/fbs-kern -am compile` met de CI-settings:
  628 downloads van `google-maven-central`, 0 van `central`.
- Mirror die alles mist (404): alle 628 vallen terug op `central`, build slaagt.
- Mirror onbereikbaar (connection refused): idem, build slaagt.
- Met `checksumPolicy` `fail` op beide repositories: nog steeds 628 van de mirror, en bij een
  404-mirror 628 van Central.
- `test-jdk-handtekening.sh` (53 asserties) en `test-maven-cache.sh` (40) groen. Over drie rondes
  zijn 43 mutaties van guard, action en settings geprobeerd; alle worden gevangen.
- Na de merge op main: de eerste push bewaart per scope `maven-repo-Linux-<scope>-<hash>` en één
  `maven-wrapper-Linux-<hash>`; PR's tonen daarna `Cache restored from key: maven-…` en geen 429
  meer.
- Na de merge: de oude `setup-java-Linux-x64-maven-*`-caches verdwijnen na 7 dagen zonder gebruik,
  of direct met
  `gh cache list --key setup-java-Linux-x64-maven- --limit 1000 --json id -q '.[].id' | xargs -r -n1 gh cache delete`. Open PR's die niet op main zijn bijgewerkt, blijven
  ze tot dan aanmaken.
