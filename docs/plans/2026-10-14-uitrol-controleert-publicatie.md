# De uitrol controleert of de eigen images gepubliceerd zijn — implementatieplan

**Status:** Uitgevoerd

**Doel:** [MinBZK/MijnOverheidZakelijk#1051](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1051).
Een build-job in `deploy.yml` die `BUILD SUCCESS` meldt zonder dat het image op de verwachte naam en
tag in het register staat, faalt voortaan zelf, met de naam van het ontbrekende image in de melding.

## Context

Vier build-jobs publiceren eigen images: `build` (matrix van drie services, jib),
`build-externe-stubs` (twee images, docker), `build-contract-bootstrap` (docker) en
`build-demo-images` (drie demo-modules in één jib-aanroep). Geen van de vier toetst daarna of het
register het image ook heeft. De namen die de uitrol verwacht staan op drie plekken: de `eigen`-aanroepen
in de stap `componenten` van `meta`, de inline componentenlijsten van `deploy-test-*`, en impliciet in
de builds zelf (artifactId/`quarkus.container-image.name`, of `docker build -t`).

## Ontwerp

1. **Eén lijst met eigen images per build-job**, als workflow-env `EIGEN_IMAGES` (JSON, sleutel = de
   build-job of matrix-waarde, waarde = de image-namen die die job publiceert). Een env-waarde en
   geen regel in een run-blok: zo is hij met een YAML-parser te lezen voor de kruiscontrole, en met
   `fromJSON(env.EIGEN_IMAGES)[…]` in elke build-job te gebruiken.
2. **Een controle als laatste stap van elke build-job**, `.github/scripts/images-gepubliceerd.sh
   <registry/owner> <tag> <naam>…`: per image `docker buildx imagetools inspect`, het register zelf
   en niet de exitcode van de build. Faalt op élk ontbrekend image (niet alleen het eerste) met een
   `::error` per naam, en faalt ook op een lege lijst ("meet niets"). Een mislukte opvraging wordt
   twee keer herhaald; dat kost alleen tijd als er iets ontbreekt.
   - Een stap in de build-job en geen aparte job: de needs- en `if`-keten van de uitrol-jobs en van
     `uitrol-poort` blijven ongewijzigd, want een falende build sloeg de uitrol al over en de poort
     beoordeelt die uitkomst al.
   - De jib-jobs loggen in die stap eerst bij het register in (jib gebruikt zijn eigen credentials,
     niet die van docker).
3. **Kruiscontrole** in `test-images-gepubliceerd.sh` (naast de fixture-tests van het script):
   - elke eigen image-naam die `deploy.yml` in een componentenlijst noemt — `eigen <naam>` in `meta` en
     `…/${{ needs.meta.outputs.owner }}/<naam>:` in de inline lijsten — staat in `EIGEN_IMAGES`;
   - elke sleutel van `EIGEN_IMAGES` wordt door precies één controlestap geraakt (de matrix van `build`
     uitgeklapt), en elke controlestap leest een bestaande sleutel.

   - een controlestap kan niet stil uitgeschakeld worden: geen `continue-on-error`, geen andere `if`
     dan `success()`, niets na de aanroep dat de exitcode wegneemt (`||`, `;`, `&`), en de stap staat
     na de laatste push van zijn job;
   - de opruiming van een preview (`cleanup-preview.yml`) kent dezelfde namen als `EIGEN_IMAGES`.

   Zo kan de uitrol geen naam verwachten die niemand controleert, en kan een build-job geen naam
   controleren die de uitrol niet verwacht.

## Buiten scope

De inline componentenlijsten van `deploy-test-*` omzetten naar de outputs van `meta` (zoals
`deploy-test-stelselregister` al doet): wenselijk, maar een eigen wijziging; de kruiscontrole dekt de
namen daar al.

## Verificatie

- `bash .github/scripts/test-images-gepubliceerd.sh` groen; het verwachte aantal asserties staat in
  `ci-scripts.yml`.
- shellcheck en actionlint schoon.
- Op de PR zelf: de build-jobs draaien de controle en blijven groen.
