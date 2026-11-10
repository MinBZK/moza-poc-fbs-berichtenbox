# Teststart van Quarkus loopt niet meer vast op een download

**Status:** Uitgevoerd

## Context

Op een Mac hing `./mvnw clean verify` op `main` bij de tests van `demo-console`, direct na
`T E S T S`, zonder melding en zonder einde. De thread dump van de testfork toont een deadlock bij
het opbouwen van het applicatiemodel van Quarkus, nog vóór de eerste test:

1. De main-thread zit in `FacadeClassLoader.loadClass` en houdt daarmee de lock van die
   classloader vast. Hij wacht in `NonBlockingModelResolutionTaskRunner.waitForCompletion` op de
   worker threads (`ForkJoinPool.commonPool-worker-N`) die de dependencies oplossen.
2. Een worker thread moet `io.quarkus:quarkus-flyway-postgresql` downloaden: een conditionele
   dependency van `quarkus-jdbc-postgresql`, waarvan Quarkus de descriptor leest. Bij die eerste
   HTTPS-connection logt de JDK een security-event, en drukt `JBossLoggerFinder` (JBoss LogManager)
   zijn waarschuwing `LogManager accessed before …` af op stderr.
3. Surefire vangt die uitvoer op en initialiseert daarvoor `StackWalkerStrategy`, die
   `java.lang.StackWalker` laadt via de context-classloader van de thread: de `FacadeClassLoader`,
   waarvan de main-thread de lock vasthoudt.

Dat Maven het artefact eerder in de build al downloadt (`quarkus:generate-code`), helpt niet: de
lokale repository noteert per repository-id waar een artefact vandaan komt, en de teststart van
Quarkus gebruikt een ander id. De testfork downloadt het dus opnieuw. Zichtbaar in
`_remote.repositories` van het artefact: daar staat het onder twee id's (`central` en
`central-8fac3ebd…`).

Nagebootst op Linux met JDK 25: met het artefact weg uit `~/.m2` hangt
`./mvnw clean test -pl demo/demo-console -am` op dezelfde manier, met dezelfde threads. Met
`-Dquarkus.bootstrap.blocking-task-runner=true` erbij slaagt diezelfde run.

## Ontwerpkeuzes

- **`quarkus.bootstrap.blocking-task-runner=true` voor elke testfork.** De
  `ModelResolutionTaskRunnerFactory` van Quarkus leest deze property. Daarmee voert de main-thread
  de resolutietaken van Quarkus zelf uit, in plaats van ze aan de common `ForkJoinPool` te geven,
  en wacht hij niet meer op die threads terwijl hij de lock vasthoudt. Dat dekt elke aanleiding
  via die threads, niet alleen deze waarschuwing.
- **Restrisico.** Maven Resolver start zelf nog een thread pool wanneer één aanvraag meer dan één
  download heeft. Schrijft zo'n thread als eerste naar de console, dan kan dezelfde keten ontstaan.
  Dat is niet waargenomen; de algemene oplossing ligt bij Quarkus (geen lock vasthouden terwijl de
  `FacadeClassLoader` op andere threads wacht).
- **Niet de waarschuwing van JBoss LogManager wegnemen** (door
  `java.util.logging.manager=org.jboss.logmanager.LogManager` in de testfork te zetten). Dat haalt
  alleen deze aanleiding weg: elke andere eerste uitvoer van een worker thread tijdens een download
  kan weer vastlopen.
- **In de `pluginManagement` van de parent-pom, niet in `.mvn/maven.config`.** Daar staat de
  reden naast de instelling, en raakt hij alleen de testforks en niet de resolutie in de
  Maven-JVM zelf (`quarkus:dev`, `quarkus:build`), waar geen `FacadeClassLoader` en geen Surefire
  meedoen. Een module die Surefire zelf configureert, erft de property: Maven voegt
  `systemPropertyVariables` met verschillende namen samen.
- **Surefire krijgt een beheerde versie (3.6.0, die Maven 3.10 nu al gebruikt).** De deadlock
  loopt deels via Surefire: `StackWalkerStrategy` zit in 3.6.0 en nog niet in 3.5.4. De versie
  hoort dus niet af te hangen van wie de build draait.
- **De waarschuwing heet niet langer "cosmetisch"** in CLAUDE.md en de skill `warnings-triage`:
  hij is onschuldig zolang deze instelling staat.

## Stappen

1. `pom.xml`: property `maven-surefire-plugin.version` en een `pluginManagement`-entry voor
   `maven-surefire-plugin` met de property in `systemPropertyVariables`.
2. CLAUDE.md en `.claude/skills/warnings-triage/SKILL.md`: de toelichting bij de waarschuwing.

## Verificatie

- Vóór de wijziging (zelfde basis, `5d101a6d`): artefact weg uit `~/.m2`, dan hangt
  `./mvnw clean test -pl demo/demo-console -am`.
- Na de wijziging, zonder extra vlaggen en met het artefact weer weg: dezelfde run slaagt (650
  tests in `demo-console`). De testfork downloadt het artefact zelf, op het punt waar hij eerst
  bleef hangen.
- `./mvnw clean verify` over de hele reactor: groen, 11 modules en 3.833 tests. De Maven-
  waarschuwingen zijn compilerwaarschuwingen in ongewijzigde Kotlin-code, een 401 op de metadata
  van een GitHub Packages-repository en een verouderde Quarkus-config-property; geen ervan komt
  uit Surefire of uit deze wijziging.
