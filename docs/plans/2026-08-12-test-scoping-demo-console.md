# CI: test.yml scopen op demo-console-only wijzigingen

**Status:** Concept

## Aanleiding

Review op PR #150 (ericwout-overheid, `.github/workflows/test.yml:1`): een wijziging die
uitsluitend `demo-console` raakt, bouwt via `./mvnw -B test` toch de volledige reactor —
inclusief `berichtenmagazijn`, `berichtenuitvraag` en alle libraries, met hun Testcontainers-
integratietests. Voor een demo-only wijziging is dat pure doorlooptijd zonder signaal.

## Waarom dit veilig kan

`services/demo-console/pom.xml` heeft bewust **geen** afhankelijkheid op `fbs-common` of een
andere reactor-module (zie het commentaar op regel 19-21: de LDV-wrapper hoort niet bij een
wegwerp-console). `demo-console` is dus het enige blad in de reactor-graaf zonder inkomende of
uitgaande module-afhankelijkheden. Dat maakt scoping voor déze ene module aantoonbaar veilig,
zonder een algemene afhankelijkheid-bewuste CI-herstructurering voor alle zes modules (grotere
newoperatie, groter risico, niet wat de review vraagt).

## Voorstel

Breid de bestaande `changes`-job in `test.yml` uit met een tweede output naast `run`:

- `run` (bestaand): `false` als de PR uitsluitend `docs/` of `*.md` raakt → hele `test`-job
  overgeslagen (ongewijzigd gedrag).
- `demo-only` (nieuw): `true` als, bovenop docs, **alle** overige gewijzigde bestanden binnen
  `services/demo-console/**` of `demo/**` vallen (de Python-generator en `smoke.sh`, die alleen
  de demo-stack aansturen). Elk ander pad — `services/berichtenmagazijn/**`,
  `services/berichtenuitvraag/**`, `libraries/**`, root-`pom.xml`, `.mvn/**`, `compose.yaml`,
  `.github/workflows/**` — forceert `demo-only=false`.

In de `test`-job:
```bash
if needs.changes.outputs.demo-only == 'true':
  ./mvnw -B test -pl services/demo-console
else:
  ./mvnw -B test          # huidig gedrag, ongewijzigd
```

**Fail-safe zoals de rest van de `changes`-job:** onbekend/onduidelijk pad → `demo-only=false`
→ volledige build. Nooit omgekeerd stil onder-testen.

## Wat NIET verandert

- `detekt.yml` blijft ongewijzigd: statische analyse zonder Testcontainers is al snel; de
  review-klacht ("images bouwen en alle tests draaien") gaat over de trage `test`-job, niet
  detekt. Losse follow-up indien gewenst.
- `berichtenmagazijn`/`berichtenuitvraag`/libraries-wijzigingen blijven altijd de volledige
  suite draaien — geen wijziging aan hun coverage-gate of testgedrag.
- `compose`-job (config-validatie) blijft ongemoeid — is al lichtgewicht.

## Randgeval: JaCoCo-coverage-comment

`madrapps/jacoco-report` glob't `services/*/target/site/jacoco/jacoco.xml` +
`libraries/*/target/site/jacoco/jacoco.xml`. Bij een `demo-only`-run bestaat alleen (als
demo-console al JaCoCo had, wat nu niet zo is — geen `jacoco-maven-plugin` in
`services/demo-console/pom.xml`) geen enkel rapport. De comment-stap draait alleen bij
`github.event_name == 'pull_request'` en faalt niet op een lege glob (Ant-style patterns die
niets matchen leveren gewoon nul entries); te verifiëren bij implementatie met een demo-only
testrun.

## Verificatie

- Lokale simulatie: `./mvnw -B test -pl services/demo-console` los draaien, vergelijken met de
  huidige volledige run — zelfde testresultaten voor die module.
- PR met alleen `services/demo-console/**`-wijziging: bevestig dat de `test`-job zichtbaar
  korter duurt en dat de coverage-comment niet faalt/crasht.
- PR die zowel `demo-console` als bv. `berichtenuitvraag` raakt: bevestig `demo-only=false` en
  volledige build.
