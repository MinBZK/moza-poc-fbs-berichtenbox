# Ophaalronde rekent met de klok van Redis

**Status:** Uitgevoerd

## Context

Een ophaalronde die haar lock kwijt is, vraagt het sessieregister of er nog een sessie loopt die
vóór de ronde begon (`heeftSessieGestartVoor`). Zo ja, dan duurde de ronde langer dan de lock-TTL
en meldt ze `OphalenMisluktNaBevraging`; zo nee, dan eindigde de laatste sessie en eindigt de
stream stil.

Het moment dat de ronde meegeeft, is `Ronde.gestart = Instant.now()`: de wandklok van de JVM. De
starttijd van een sessie volgt uit het plafond dat het `START`-script met `redis.call('TIME')` zet.
Loopt de Redis-klok voor op de JVM, dan begint een sessie die vlak vóór de ronde startte, volgens
die vergelijking pas ná de ronde. De ronde eindigt dan stil terwijl de gebruiker nog ingelogd is,
en de berichtenbox hoort niet dat hij opnieuw moet ophalen. Dat is de onveilige kant.

Gezien op 2026-11-13: `SessieOpruimenTest` ("een ronde die langer duurt dan de lock-TTL terwijl
de sessie loopt…") faalde op een Mac (Redis in de Rancher-VM), terwijl de sessie pas 5 s van de
10 s inactiviteit liep. De log meldde "lock kwijt doordat de laatste sessie eindigde".

Dezelfde ochtend zijn de tests van het register al op de Redis-klok gezet (`a88b05f1`, via #439).
`Ronde.gestart` is de laatste plek waar een JVM-tijd naar het register gaat; geen testfix kan
daarbij, want het moment komt uit de productiecode.

## Ontwerpkeuze

De ronde vraagt het register om "nu" op de Redis-klok, op het moment dat ze de controle doet, en
trekt daar de verstreken duur van de ronde vanaf. Die duur wordt monotoon gemeten
(`System.nanoTime()`), dus een klokverschil telt niet mee.

- De duur wordt gemeten vóór de vraag naar de Redis-tijd. Het berekende moment valt daardoor
  hooguit iets ná de echte start, en een sessie die vlak na de ronde begon, telt mee. Dat is de
  veilige kant: een melding die niet nodig was, geen melding die uitblijft.
- De extra Redis-aanroep valt alleen op het zeldzame pad (lock kwijt). Faalt hij, dan geldt wat nu
  al geldt als de controle faalt: "niet vast te stellen", dus melden.
- `heeftSessieGestartVoor(ontvanger, moment: Instant)` blijft gelijk; de KDoc legt vast dat
  `moment` een tijd op de Redis-klok is. De tests van vanochtend blijven daardoor ongewijzigd.

Verworpen:

- De Redis-tijd bij de start van de ronde ophalen: een extra aanroep bij elke ronde, en een nieuw
  foutpad vóór de lock waarvoor de 503-afhandeling moet worden uitgebreid.
- `heeftSessieGestartVoor` een relatieve duur laten aannemen: maakt misbruik onmogelijk, maar
  verandert de interface en de tests die vanochtend op de Redis-klok zijn gezet; de grenstest op
  dezelfde milliseconde wordt dan racy.

## Stappen

1. `SessieRegister.nu(): Uni<Instant>`: in `RedisSessieRegister` via `TIME`, in
   `MockSessieRegister` de JVM-klok, in de fake van `SessieVolgerTest` niet ondersteund.
2. Failing test in `BerichtensessiecacheServiceTest`: met een Redis-klok die jaren voorloopt,
   krijgt `heeftSessieGestartVoor` een moment op die klok.
3. `Ronde` meet haar duur monotoon; `lockVerlopenTerwijlSessieLoopt` rekent het moment uit `nu()`.
4. Bijvangst op deze branch: `quarkus.http.test-port=0` uit de test-config van de sessiecache. De
   library start geen HTTP-server, dus de sleutel geeft alleen een "Unrecognized configuration
   key"-waarschuwing.

## Verificatie

- `BerichtensessiecacheServiceTest` en `SessieOpruimenTest` groen, daarna de hele module
  (`./mvnw clean verify -pl libraries/fbs-berichtensessiecache -am`), inclusief de coverage-gate
  en detekt.
- Geen nieuwe waarschuwingen; de `quarkus.http.test-port`-waarschuwing is weg.
