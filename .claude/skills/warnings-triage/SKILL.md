---
name: warnings-triage
description: Vergelijk de waarschuwingen uit een build- of testrun met de bewust geaccepteerde lijst, zodat nieuw en onverklaard zichtbaar wordt
---

# Warnings triëren

"Build groen" is alleen een betrouwbaar kwaliteitssignaal als er geen onverklaarde nieuwe
waarschuwingen bij komen. In een Maven-run van deze omvang verdrinkt een nieuwe melding tussen de
bekende; deze skill haalt hem eruit.

Nieuwe, onverklaarde waarschuwingen blokkeren een PR tot ze getrieerd zijn.

## 1. Vang de output

```bash
./mvnw clean verify -pl <module> -am 2>&1 | tee /tmp/build.log
```

`clean` hoort erbij: we wisselen vaak van branch op een gedeelde bind mount, en een achtergebleven
`target/` laat Surefire stale `.class`-bestanden draaien — dat geeft `NoSuchMethodError`-achtige
fouten in ongewijzigde code.

## 2. Trek de waarschuwingen eruit

```bash
grep -nE '\[WARNING\]|WARN |warning:|deprecat' /tmp/build.log | sort -u
```

## 3. Streep de bewust geaccepteerde weg

Deze drie komen uit de Maven-wrapper en transitieve libraries — niet uit onze code of config, en
dus buiten eigen beheer:

| Melding | Herkomst |
|---------|----------|
| `java.lang.System::load has been called ... (restricted method)` | jansi, via de Maven-wrapper-CLI. Niet beïnvloedbaar zonder de wrapper te patchen |
| `sun.misc.Unsafe::objectFieldOffset is deprecated` | guava, transitief via de Maven-wrapper. Verdwijnt zodra de upstream-libs migreren |
| `LogManager accessed before the "java.util.logging.manager" system property was set` | Initialisatie-volgorde van de JBoss LogManager in de test-bootstrap; cosmetisch, geen effect op test- of runtime-gedrag |

En één die geen build-warning is maar wel elke start haalt:

| Melding | Status |
|---------|--------|
| `HV000271` — dubbel `@Valid` op een container, uit de openapi-generator | Bewust uitgesteld. De fix vergt een gevendorde template plus een guard-test; we wachten op upstream. Niet opnieuw voorstellen |

## 4. Triëer wat overblijft

Per resterende waarschuwing één van drie uitkomsten:

- **Oplossen.** Komt hij uit onze code of config, dan is dat de default.
- **Bewust accepteren, met reden.** Alleen als hij aantoonbaar buiten eigen beheer valt. Zet de
  melding, de herkomst en de reden erbij en meld dat hij aan de lijst hierboven toegevoegd moet
  worden — anders is de volgende triage weer even duur.
- **Doorverwijzen.** Hoort hij bij een ander team of een upstream-project, noem dan waar hij ligt.

Twee bijzondere gevallen: een waarschuwing over TLS, credentials of een uitgezette
veiligheidsklep (`FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT`, `FBS_OUTWAY_UNSAFE_ALLOW_UNVERIFIED_TLS`) is
geen ruis maar een signaal dat bewust gelogd wordt voor alert-routing — die accepteer je niet stil.
En detekt faalt op `maxIssues: 0` zonder baseline: een detekt-bevinding is geen waarschuwing om te
triëren maar iets om op te lossen, of te onderdrukken met een inline `@Suppress("Rule")` plus
motivatie-comment.

## 5. Rapporteer

Geef drie lijstjes: nieuw en opgelost, nieuw en bewust geaccepteerd (met reden), nog open. Waren er
geen nieuwe waarschuwingen, zeg dat dan expliciet — dat is de uitkomst waar de PR op wacht.
