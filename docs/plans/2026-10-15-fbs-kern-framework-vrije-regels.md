# fbs-kern: één plek voor de framework-vrije regels — implementatieplan

**Status:** Uitgevoerd

**Doel:** de controle op identificatienummers en de bijlage-regels (`inline`/`attachment`, de
bestandsnaam in `Content-Disposition`) bestaan nog maar één keer, en het stelsel en de demo
gebruiken allebei die ene versie
([MinBZK/MijnOverheidZakelijk#1054](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1054)).

## Context

`fbs-common` is Jandex-geïndexeerd. Elke consumer erft daardoor elke bean en provider erin: het
LDV-filter, vier exception mappers, vijf opstart-validators. De demo-modules nemen daarom bewust geen
`fbs-common` en dragen eigen kopieën:

| Regel | Gezaghebbend | Kopie |
|---|---|---|
| Identificatienummers (elfproef, vorm, niet-geheel-nullen) | `fbs-common/identificatie/Identificatienummer.kt` | `demo-personas/Identificatiecheck.kt`, `magazijn-simulator/opslag/Identificatie.kt` |
| Domein-validatiefout | `fbs-common/exception/DomainValidationException.kt` (+ `requireValid`) | `magazijn-simulator/opslag/DomeinFout.kt` (+ `vereis`) |
| Bijlage-weergave en bestandsnaam | `fbs-common/bijlage/BijlageContentDisposition.kt` | `magazijn-simulator/berichten/BijlageContentDisposition.kt` (+ testkopie) |

Uit de comments op het issue: de nuttige snijlijn is niet "welk onderwerp" maar "welke laag". De
framework-vrije waarden en regels horen in een module die élke consumer veilig kan nemen; de
bedrading (filters, mappers, observers, validators) blijft bij `fbs-common` of de dienst.

## Ontwerpkeuzes

1. **Eén module `libraries/fbs-kern`, geen module per onderwerp.** Gekozen bij het oppakken van het
   issue; een volgend gedeeld onderwerp landt hier zonder nieuwe module-discussie.
2. **Geen Jandex-plugin, geen CDI, geen `@Provider`, geen `provided`-dependency die een bean nodig
   heeft.** Er valt dus niets ongewenst mee te erven. De enige framework-dependency is
   `jakarta.ws.rs-api` (`provided`) voor het type `MediaType` in de signatuur van de bijlage-regels:
   een API-type, geen bean. Alle consumers draaien een JAX-RS-runtime.
3. **Packages `nl.rijksoverheid.moz.fbs.kern.{identificatie,bijlage,exception}`.** Volgt de
   conventie `fbs.<module-naam>.*`. Een verhuizing van package, dus de imports in alle consumers
   gaan mee (~140 bestanden, mechanisch). Geen typealiassen in `fbs-common`: die houden een tweede
   naam voor hetzelfde ding in leven.
4. **`DomainValidationException` + `requireValid` gaan mee naar `kern.exception`.** De
   identificatienummers gooien hem; de mapper (`DomainValidationExceptionMapper`) blijft in
   `fbs-common`. De simulator vervangt zijn eigen `DomeinFout`/`vereis` door deze twee: dezelfde
   rationale, dezelfde 400 — en zo vangt één mapper (`DomeinFoutMapper`, nu op
   `DomainValidationException`) beide soorten invarianten.
5. **De melding bij een onbekend type echoot de aangeboden waarde niet meer.** `fbs-common` zette
   `parts[0]` in de melding; de simulator deed dat bewust niet, omdat een omgedraaide
   `123456782:BSN` dan een BSN in een foutmelding (en log) zet. Het strengere gedrag wint. Welke
   nummers aangenomen of geweigerd worden verandert niet.
6. **Simulator: `afzender: Oin`, `ontvanger: Identificatienummer`.** De runtime-check "afzender moet
   een OIN zijn" wordt een type. `toString` van BSN/RSIN wordt daarmee het hash-suffix van het
   stelsel in plaats van `***` — beide maskeren.
7. **Personadienst: `type`/`waarde` blijven strings** (het endpoint publiceert ze zo). De validatie
   delegeert aan `Identificatienummer.of(...)`; het eigen deel is alleen nog welke types een
   persona mag hebben (BSN, RSIN, KVK), vóór de enum-lookup, zodat een verkeerd om getypte waarde
   niet in de melding belandt.
8. **De fuzzer `IdentificatienummerFromHeaderFuzzer` verhuist mee** naar `fbs-kern`; de naam blijft
   globaal uniek. `fbs-common` heeft daarna geen fuzzer meer en verliest `jazzer-api`.

## Stappen

1. Module `libraries/fbs-kern` met pom (kotlin, jakarta.ws.rs-api provided, junit +
   resteasy-core test voor `MediaType.valueOf`, jazzer-api test, eigen jacoco 90%-gate). Toevoegen
   aan de reactor vóór `fbs-common`.
2. `git mv` van `identificatie/`, `bijlage/`, `DomainValidationException.kt` en hun tests + de fuzzer;
   package-declaraties en imports omzetten in alle modules. KDoc-links naar mappers in `fbs-common`
   worden tekst.
3. `fbs-common` hangt aan `fbs-kern` (compile); `jazzer-api` eruit.
4. Simulator: dependency op `fbs-kern`; `Identificatie`, `DomeinFout`/`vereis` en de
   `BijlageContentDisposition`-kopie + test weg; `Bericht`, repository, resources en tests om naar
   de kern-typen.
5. Personadienst: dependency op `fbs-kern`; `Identificatiecheck` delegeert.
6. Ketenbestanden: `.clusterfuzzlite/build.sh` (MODULES), `.clusterfuzzlite/base/Dockerfile`
   (COPY-pom), `.github/workflows/detekt.yml` (SARIF-upload), `.claude/hooks/geraakte-module-test.sh`
   (kandidaat + snel), JaCoCo-excludes in de drie services (`kern/**` naast `common/**`), de
   pom-comments in de demo-modules.
7. Documentatie: `CLAUDE.md` (modulelijst, build-commando, belangrijke bestanden), `docs/ontwikkelen.md`,
   `demo/README.md`, `README.md` waar ze de kopieën of de modules noemen.

Bewust buiten scope: de exception-mapper-kopieën in de simulator (bedrading, hoort per dienst) en
het same-origin insluiten van bijlagen in de simulator (#298, een beslissing over gedrag, niet over
duplicatie).

## Verificatie

- `./mvnw clean test -pl libraries/fbs-kern,libraries/fbs-common,libraries/fbs-magazijnregister -am`
- `./mvnw clean test -pl demo/demo-personas,demo/magazijn-simulator -am` (simulator: Docker)
- `./mvnw clean test -pl services/berichtenmagazijn,services/berichtenuitvraag,services/stelselregister,libraries/fbs-berichtensessiecache -am`
- `./mvnw detekt:check`
- `.github/scripts/demo-grens.sh`, `.github/scripts/fuzz-basis-modules.sh`, `test-*.sh` van geraakte scripts
- Warnings vergelijken met de geaccepteerde lijst (`/warnings-triage`), met name Quarkus-meldingen
  over klassen uit een niet-geïndexeerde jar.
