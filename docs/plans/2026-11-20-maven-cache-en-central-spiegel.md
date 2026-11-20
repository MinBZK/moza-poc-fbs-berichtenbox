# Maven-cache per scope en Google's Central-spiegel in CI

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
  Een job vult zo alleen zijn eigen cache.
- **Alleen main bewaart, en zonder `restore-keys`.** Elke sleutel begint leeg en bevat alleen wat die
  pom-stand nodig heeft; met terugval zou de cache bij elke bump de oude versie meenemen en blijven
  groeien. PR's en de merge queue herstellen alleen, met terugval op dezelfde scope en daarna op
  elke scope, zodat Maven na een pom-wijziging alleen het verschil ophaalt.
- **Google's spiegel als extra repository vóór Central**, via een settings-bestand dat de action naar
  `~/.m2/settings.xml` schrijft. Geen `<mirrorOf>`: dan vervangt de spiegel Central, en breekt een
  artefact dat de spiegel nog niet heeft de build. Alleen CI; lokaal verandert niets.
- **De Maven-distributie van de wrapper blijft van Central komen.** `MVNW_REPOURL` kent geen
  terugval, en het gaat om één bestand dat in de cache zit.
- **`jdk-handtekening.py` weigert voortaan `cache:` op een setup-java-stap.** Een terugkerende
  `cache: maven` werkt gewoon en valt in geen run op, maar vult de 10 GB weer.

## Verificatie

- Lokaal, lege repository, `-pl libraries/fbs-kern -am compile` met de CI-settings:
  628 downloads van `google-maven-central`, 0 van `central`.
- Spiegel die alles mist (404): alle 628 vallen terug op `central`, build slaagt.
- Spiegel onbereikbaar (connection refused): idem, build slaagt.
- `test-jdk-handtekening.sh`: 26 asserties groen; twee mutaties van de cache-check (weg, alleen
  `maven`) worden allebei gevangen.
- Na de merge op main: de eerste push per scope bewaart een cache `maven-Linux-<scope>-<hash>`;
  PR's tonen daarna `Cache restored from key: maven-Linux-…` en geen 429 meer.
