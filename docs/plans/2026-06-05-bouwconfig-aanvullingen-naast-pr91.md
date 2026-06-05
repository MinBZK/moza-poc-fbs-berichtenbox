# Bouwconfig-aanvullingen naast PR #91: module-versies, root-deps, dev-mode-fix

**Status:** Uitgevoerd

## Context

De module-POM's declareren bouwconfiguratie lokaal die centraal hoort. PR #91
("Gedeelde bouwconfiguratie centraliseren om configuratiedrift te voorkomen") pakt
het versie-beheer van gedeelde third-party (test-)dependencies en de jacoco-/jandex-
plugin-versies al op via `<dependencyManagement>` en properties in de root-POM.

Dit plan dekt het **complementaire deel** dat #91 níét doet, plus twee losse
bevindingen uit dezelfde sessie. De aanvankelijk bredere opzet (volledige
dependency-centralisatie) is bewust versmald om niet met #91 te overlappen.

## Wijzigingen

1. **Interne module-versies centraal.** `fbs-common` en `fbs-berichtensessiecache`
   staan in de root-`<dependencyManagement>` op `${project.version}`; consumers
   declareren alleen nog groupId/artifactId.
2. **Root-`<dependencies>`** voor wat élke module nodig heeft: `kotlin-stdlib`
   (compile) en `junit-jupiter` (test). Dependencies die in 3 van de 4 modules
   voorkomen verhuizen bewust níét: fbs-common is een pure-JVM-library en mag geen
   Quarkus-extensies of ongebruikte test-frameworks erven.
3. **`openapi-generator-maven-plugin` naar `<pluginManagement>`** (versie stond al
   als property, maar dubbel gerefereerd in beide service-POM's).
4. **`quarkus:dev -pl services/<service> -am` bleef hangen op de sessiecache-library.**
   `quarkus:dev` start op elke reactor-module waarvan de quarkus-maven-plugin een
   `build`-goal heeft (de `enforceBuildGoal`-check). Het `build`-goal is uit de
   library-POM verwijderd: quarkus-junit bouwt de test-app zelf tijdens de testrun,
   en zonder dat goal slaat dev mode de module over als support-library.
5. **`quarkus-junit5` → `quarkus-junit`** in sessiecache en uitvraag: het artefact is
   sinds Quarkus 3.31 relocated; de oude naam gaf een build-warning. (Magazijn was al om.)
6. **Drie Kotlin-compilerwarnings opgelost:** twee overbodige `!!` op al-smart-gecaste
   receivers (`RedisBerichtenCacheIntegrationTest`, `ProfielServiceFoutExceptionMapperTest`)
   en een ontbrekende `@param:`-use-site-target op `@RestClient` in
   `ProfielMagazijnResolver` (toekomstige Kotlin past annotaties zonder target ook op
   het field toe).

## Verificatie

1. `./mvnw -q dependency:list` vóór en ná: identieke resolutie (alleen declaratieplek
   verschoven, geen versie-verschuivingen).
2. `./mvnw clean verify` over de hele reactor (Docker vereist): alle tests groen,
   coverage-gates gehaald, en de drie Kotlin-warnings plus de relocation-warning
   niet meer in de output.
3. `./mvnw quarkus:dev -pl libraries/fbs-berichtensessiecache` keert direct terug met
   "Skipping quarkus:dev as this is assumed to be a support library".
