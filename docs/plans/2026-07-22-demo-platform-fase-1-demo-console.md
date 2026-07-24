**Status:** Uitgevoerd — volledig geverifieerd (incl. Docker-runtime, door de gebruiker)

> **Correcties tijdens uitvoering.**
> - **Geen `fbs-common` (taak 1).** Die library bevat JAX-RS-filters
>   (`LogboekContextDefaultFilter`) die de LDV-wrapper vereisen; Arc brak daarop bij boot.
>   Opgelost door `fbs-common` weg te laten en de elfproef-validatie te inlinen in
>   `generator/Identificatiecheck.kt`. Een wegwerp-console erft de productie-JAX-RS-stack niet.
> - **`@ConfigMapping` i.p.v. `@ConfigProperty Map` (taak 3).** Het plan-risico bleek reëel;
>   ik nam meteen de robuuste route (`aanlever/MagazijnenConfig.kt`), zoals fbs-magazijnregister.
> - **`quarkus-junit5` → `quarkus-junit`** (relocation-warning weggewerkt).
> - **Taak 3 en 4 in één commit** — ze delen `DemoResource`, splitsen zou een niet-bouwbare
>   commit geven.
>
> **Lokaal geverifieerd (zonder Docker):** de module bouwt, Quarkus-augmentatie wiret alle
> CDI-beans, `@ConfigMapping` en beide `@DataSource`-injecties correct, 11 generator-unittests
> groen, compose-YAML gevalideerd, geen nieuwe waarschuwingen.
>
> **Nog te doen op een machine mét Docker** (runtime-gedrag): jib-images bouwen incl.
> demo-console, `docker compose --profile demo up -d`, dan via het paneel op :8095
> legen → basisvulling → status → random. Verwacht `geslaagd == aangeboden`. Controleer
> specifiek dat de **KVK-persona** (`X-Ontvanger: KVK:12345678`) door de uitvraag-keten komt;
> zo niet, vervang die persona door een geldige BSN in `GeneratorProducer` én `basis.json`.
> Sluit af met `./mvnw clean verify -pl services/demo-console -am` (detekt-schoon).

# Demo-platform fase 1 — demo-console — implementatieplan

> **Voor agentic workers:** VERPLICHTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> (aanbevolen) of `superpowers:executing-plans` om dit plan taak voor taak uit te voeren.
> Stappen gebruiken checkbox-syntax (`- [ ]`) voor voortgang.

**Ontwerp:** `docs/plans/2026-07-21-demo-platform-design.md` (fase 1 — Demo-datamanagement)

**Doel:** een wegwerp-module `services/demo-console` die de magazijnen kan legen,
vullen met een curated basisdataset en aanvullen met random berichten, bediend via
een kaal HTML-paneel.

**Architectuur:** een aparte Quarkus-module zonder OpenAPI-spec. Legen gaat via directe
JDBC-`TRUNCATE` op beide magazijn-databases; vullen en random gaan via de echte
aanlever-API (`POST /api/v1/berichten`) zodat validatie, publicatie-outbox en
notificatieketen meedraaien. De module draagt bewust "vieze" kennis (DB-schema,
truncate) die niet in de productieservices thuishoort.

**Tech stack:** Quarkus 3.37.1, Kotlin 2.3.21, JVM 21, quarkus-rest-jackson,
quarkus-rest-client-jackson, quarkus-agroal + quarkus-jdbc-postgresql, MockK/JUnit 5.

## Global Constraints

- **Wegwerp-module, buiten de coveragegate.** De 90%-JaCoCo-gate is per-module; demo-console
  declareert de jacoco-plugin niet en heeft `quarkus-jacoco` niet als dependency. Geen
  coverage-eis.
- **detekt geldt wél.** De `detekt-maven-plugin` zit in de parent-`<build><plugins>` en draait
  op `verify` met `maxIssues: 0` zonder baseline. Alle Kotlin moet detekt-schoon zijn;
  onvermijdelijke uitzonderingen krijgen een inline `@Suppress("Rule")` met motivatie.
- **Kotlin-stijl uit `CLAUDE.md`:** lege regel vóór én ná elk multi-line blok tussen accolades
  en rond zelfstandige control-statements. NL domeinbegrippen, Engelse technische idiomen.
- **Autorisatie-invariant (hard):** de profiel-stub geeft alleen een actieve
  `OntvangViaBerichtenbox`-voorkeur wanneer de **afzender-OIN exact `00000001003214345000`**
  is. Alle aangeleverde demo-berichten gebruiken daarom die ene afzender-OIN. De herkenbare
  afzendernaam gaat in het `onderwerp`, niet in een eigen afzender-OIN. (Zie "Afzender-variatie
  uitgesteld".)
- **Ontvanger-BSN's die falen — vermijden:** `111222333` (403 geen voorkeur), `999996915`
  (403 geen profiel), `999991401` (500). Deze nooit als demo-ontvanger gebruiken.
- **Berichtgrootte:** `inhoud` ver onder 1 MiB (in de praktijk enkele KB). Geen bijlagen in
  fase 1 (bijlage-scenario hoort bij fase 3).
- **Altijd `clean` vóór `test`/`verify`.**
- **Nooit direct naar `main`.** We werken door op `feature/demo-platform`.

## Demo-personas (ontvangers)

Vastgesteld in overleg: een handvol vaste demo-personas. Elke persona is een geldig,
niet-in-de-403-lijst voorkomend identificatienummer:

| Persona | Type | Waarde | Toelichting |
|---|---|---|---|
| J. Pietersen (ZZP-klusbedrijf) | BSN | `999993653` | eenmanszaak/ZZP, geïdentificeerd via BSN |
| Bakkerij De Vroege Vogel | BSN | `123456782` | eenmanszaak via BSN |
| Garage Van Dijk B.V. | KVK | `12345678` | rechtspersoon via KVK-nummer |

De KVK-persona toetst tegelijk het getypeerde-ontvanger-pad door de keten. Mocht een
KVK-ontvanger in fase 2 niet volledig door de uitvraag/sessiecache stromen, dan valt die
persona terug op een BSN — dat blijkt uit de integratieverificatie van taak 4.

## Afzender-variatie uitgesteld

Het ontwerp noemt herkenbare afzenders (Belastingdienst, KVK, RVO, UWV). Een eigen
afzender-OIN per organisatie vraagt uitbreiding van de profiel-stub (extra OIN's in de
voorkeur-scope), want het magazijn weigert (403) elke afzender-OIN die niet in de scope
staat. Dat is speculatief werk zolang er geen UI is die per-afzender sorteert/filtert.
**Fase 1 houdt één afzender-OIN aan** en zet de herkenbare naam in het `onderwerp`
("Belastingdienst — Voorlopige aanslag 2026"). Blijkt in fase 2 dat de UI echte
afzender-OIN-variatie nodig heeft, dan is dat één kleine stub-taak.

## Bestandsoverzicht

| Bestand | Verantwoordelijkheid | Actie |
|---|---|---|
| `pom.xml` (root) | module `services/demo-console` registreren | Wijzigen |
| `services/demo-console/pom.xml` | module-POM, geen jacoco/codegen | Aanmaken |
| `.../demo-console/src/main/resources/application.properties` | poort 8095, magazijn-URL's, twee datasources | Aanmaken |
| `.../demo/generator/DemoBerichtGenerator.kt` | random aanlever-opdrachten (pure logica) | Aanmaken |
| `.../demo/generator/AanleverModel.kt` | DTO's: `AanleverVerzoek`, `OntvangerDto`, `AanleverOpdracht`, `Persona` | Aanmaken |
| `.../demo/aanlever/MagazijnAanleverClient.kt` | REST-client-interface `POST /api/v1/berichten` | Aanmaken |
| `.../demo/aanlever/AanleverService.kt` | opdrachten routeren naar het juiste magazijn | Aanmaken |
| `.../demo/dataset/Basisdataset.kt` | `dataset/basis.json` inlezen → opdrachten | Aanmaken |
| `.../demo-console/src/main/resources/dataset/basis.json` | curated basisdataset | Aanmaken |
| `.../demo/legen/MagazijnDatabase.kt` | JDBC-truncate + telling per datasource | Aanmaken |
| `.../demo/DemoResource.kt` | endpoints `/api/demo/*` | Aanmaken |
| `.../demo-console/src/main/resources/META-INF/resources/index.html` | kaal bedieningspaneel | Aanmaken |
| `compose.yaml` | demo-console-container in profiel `demo` | Wijzigen |
| `README.md` | demo-console in de demo-stack-sectie | Wijzigen |

Package-root: `nl.rijksoverheid.moz.fbs.democonsole`.

## Verificatie zonder container-runtime

De generator-logica (taak 2) is **pure JVM** en lokaal te testen met MockK/JUnit —
geen Docker. De module-scaffold (taak 1) is te controleren met `mvn package` inclusief
Quarkus-augmentatie. Alles wat een echte database of magazijn raakt (taak 3, 4, 5) vraagt
een draaiende stack en wordt op een machine mét Docker geverifieerd. Elke taak markeert
expliciet wat waar verifieerbaar is.

---

### Taak 1: Module-scaffold

**Files:**
- Wijzigen: `pom.xml` (root, `<modules>`-blok)
- Aanmaken: `services/demo-console/pom.xml`
- Aanmaken: `services/demo-console/src/main/resources/application.properties`
- Aanmaken: `services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/DemoConsoleApiVersionProvider.kt`

**Interfaces:**
- Produceert: een bouwbare, startbare lege module op poort 8095.

- [ ] **Stap 1: Registreer de module in de root-POM**

Voeg in `pom.xml` binnen `<modules>` toe, ná `services/berichtenuitvraag`:

```xml
        <module>services/demo-console</module>
```

- [ ] **Stap 2: Schrijf de module-POM**

`services/demo-console/pom.xml` — bewust minimaal: geen OpenAPI-codegen, geen jacoco.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>nl.rijksoverheid.moz</groupId>
        <artifactId>moza-poc-fbs-berichtenbox</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>demo-console</artifactId>
    <name>FBS Demo Console</name>
    <description>Wegwerp-module voor demonstratie: magazijnen legen, vullen en random berichten opvoeren.</description>

    <dependencies>
        <dependency>
            <groupId>nl.rijksoverheid.moz</groupId>
            <artifactId>fbs-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-client-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-kotlin</artifactId>
        </dependency>
        <!-- Kotlin-module zodat de managed ObjectMapper data classes (basis.json) deserialiseert. -->
        <dependency>
            <groupId>com.fasterxml.jackson.module</groupId>
            <artifactId>jackson-module-kotlin</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-agroal</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-health</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-container-image-jib</artifactId>
        </dependency>

        <!-- Alleen quarkus-junit5 voor eventuele @QuarkusTest; junit-jupiter en mockk-jvm
             komen als test-deps uit de parent. Geen Mockito — dit project gebruikt MockK. -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit5</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <sourceDirectory>src/main/kotlin</sourceDirectory>
        <testSourceDirectory>src/test/kotlin</testSourceDirectory>
        <plugins>
            <plugin>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                            <goal>generate-code</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Stap 3: Schrijf de application.properties**

`services/demo-console/src/main/resources/application.properties`:

```properties
quarkus.application.name=fbs-demo-console
quarkus.container-image.name=fbs-demo-console

# Vrije poort naast magazijn (8090/8091), uitvraag (8086) en stubs (8081-8084, 8089).
quarkus.http.port=8095
quarkus.http.host=0.0.0.0

# Wegwerp-console: draait uitsluitend lokaal/demo, altijd onder profiel dev.
quarkus.log.level=INFO
%dev.quarkus.log.category."nl.rijksoverheid.moz".level=DEBUG

# Magazijn-aanlever-URL's. Buiten containers de dev-poorten; in de demo-compose
# overschrijven MAGAZIJN_A_URL/MAGAZIJN_B_URL naar container-DNS.
demo.magazijnen."00000001003214345000".url=${MAGAZIJN_A_URL:http://localhost:8090}
demo.magazijnen."00000001823288444000".url=${MAGAZIJN_B_URL:http://localhost:8091}

# Twee datasources voor het legen (directe TRUNCATE). Lazy: geen DB-verbinding bij boot,
# zodat de module ook zonder draaiende Postgres start.
quarkus.datasource.magazijn-a-db.db-kind=postgresql
quarkus.datasource.magazijn-a-db.jdbc.url=${MAGAZIJN_A_DB_URL:jdbc:postgresql://localhost:5432/berichtenmagazijn}
quarkus.datasource.magazijn-a-db.username=${MAGAZIJN_A_DB_USER:berichtenmagazijn}
quarkus.datasource.magazijn-a-db.password=${MAGAZIJN_A_DB_PASSWORD:berichtenmagazijn}
quarkus.datasource.magazijn-a-db.health-exclude=true

quarkus.datasource.magazijn-b-db.db-kind=postgresql
quarkus.datasource.magazijn-b-db.jdbc.url=${MAGAZIJN_B_DB_URL:jdbc:postgresql://localhost:5433/berichtenmagazijn}
quarkus.datasource.magazijn-b-db.username=${MAGAZIJN_B_DB_USER:berichtenmagazijn}
quarkus.datasource.magazijn-b-db.password=${MAGAZIJN_B_DB_PASSWORD:berichtenmagazijn}
quarkus.datasource.magazijn-b-db.health-exclude=true
```

- [ ] **Stap 4: Schrijf een API-Version-provider (zodat de module een fbs-common-bean heeft en compileert)**

`services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/DemoConsoleApiVersionProvider.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole

/**
 * Marker-object zodat de module een eigen top-level type heeft. De demo-console kent geen
 * API-versionering (wegwerp), maar houdt de package-root bezet voor de overige klassen.
 */
object DemoConsole {

    const val NAAM = "fbs-demo-console"
}
```

- [ ] **Stap 5: Bouw de module (augmentatie valideert config)**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console -am`
Expected: `BUILD SUCCESS`, en `Building jar: .../demo-console-0.1.0-SNAPSHOT.jar`. Geen
nieuwe waarschuwingen buiten de al-geaccepteerde (jansi, guava, LogManager).

- [ ] **Stap 6: Commit**

```bash
git add pom.xml services/demo-console/pom.xml \
        services/demo-console/src/main/resources/application.properties \
        services/demo-console/src/main/kotlin
git commit -m "build(demo-console): lege wegwerp-module op poort 8095

Geen OpenAPI-codegen en geen jacoco (buiten de coveragegate). Twee lazy
datasources voor het legen en magazijn-aanlever-URL's met env-var-defaults."
```

---

### Taak 2: Random-generator (pure logica)

De kern van fase 1, en volledig lokaal testbaar. Deterministisch door een injecteerbare
`Random` en `Clock`.

**Files:**
- Aanmaken: `services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/generator/AanleverModel.kt`
- Aanmaken: `services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/generator/DemoBerichtGenerator.kt`
- Test: `services/demo-console/src/test/kotlin/nl/rijksoverheid/moz/fbs/democonsole/generator/DemoBerichtGeneratorTest.kt`

**Interfaces:**
- Consumeert: `Bsn`, `Kvk` uit `nl.rijksoverheid.moz.fbs.common.identificatie` (validatie in `init`).
- Produceert:
  - `data class OntvangerDto(val type: String, val waarde: String)`
  - `data class AanleverVerzoek(val afzender: String, val ontvanger: OntvangerDto, val onderwerp: String, val inhoud: String, val publicatietijdstip: String)`
  - `data class AanleverOpdracht(val magazijnOin: String, val verzoek: AanleverVerzoek)`
  - `data class Persona(val naam: String, val type: String, val waarde: String)`
  - `class DemoBerichtGenerator(val personas: List<Persona>, val afzenderOin: String, val magazijnOins: List<String>, val klok: Clock)` met
    `fun genereer(aantal: Int, random: Random): List<AanleverOpdracht>`

- [ ] **Stap 1: Schrijf het datamodel**

`AanleverModel.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.generator

/** Ontvanger zoals het aanlevercontract het verwacht: getypeerd identificatienummer. */
data class OntvangerDto(val type: String, val waarde: String)

/**
 * Body voor `POST /api/v1/berichten` op het magazijn. `afzender` is een kale OIN-string
 * (20 cijfers); alleen `ontvanger` is getypeerd. Velden matchen BerichtAanleverenRequest.
 */
data class AanleverVerzoek(
    val afzender: String,
    val ontvanger: OntvangerDto,
    val onderwerp: String,
    val inhoud: String,
    val publicatietijdstip: String,
)

/** Eén aanlever-opdracht: het verzoek plus het magazijn (OIN) waar het naartoe moet. */
data class AanleverOpdracht(val magazijnOin: String, val verzoek: AanleverVerzoek)

/** Vaste demo-ontvanger. `type` is BSN/KVK/RSIN; `waarde` het (geldige) nummer. */
data class Persona(val naam: String, val type: String, val waarde: String)
```

- [ ] **Stap 2: Schrijf de falende test**

`DemoBerichtGeneratorTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

class DemoBerichtGeneratorTest {

    private val afzenderOin = "00000001003214345000"
    private val magazijnen = listOf("00000001003214345000", "00000001823288444000")

    private val personas = listOf(
        Persona("J. Pietersen", "BSN", "999993653"),
        Persona("Bakkerij De Vroege Vogel", "BSN", "123456782"),
        Persona("Garage Van Dijk B.V.", "KVK", "12345678"),
    )

    private val klok = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC)

    private fun generator() = DemoBerichtGenerator(personas, afzenderOin, magazijnen, klok)

    @Test
    fun `genereert exact het gevraagde aantal opdrachten`() {
        val opdrachten = generator().genereer(aantal = 25, random = Random(1))

        assertEquals(25, opdrachten.size)
    }

    @Test
    fun `alle opdrachten gebruiken de geautoriseerde afzender-OIN`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(2))

        assertTrue(opdrachten.all { it.verzoek.afzender == afzenderOin })
    }

    @Test
    fun `elke ontvanger is een van de personas`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(3))
        val toegestaan = personas.map { it.type to it.waarde }.toSet()

        assertTrue(opdrachten.all { (it.verzoek.ontvanger.type to it.verzoek.ontvanger.waarde) in toegestaan })
    }

    @Test
    fun `elk magazijn is een van de geregistreerde OIN's`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(4))

        assertTrue(opdrachten.all { it.magazijnOin in magazijnen })
    }

    @Test
    fun `onderwerp en inhoud vallen binnen de contractgrenzen`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(5))

        assertTrue(opdrachten.all { it.verzoek.onderwerp.length in 1..255 })
        assertTrue(opdrachten.all { it.verzoek.inhoud.isNotEmpty() && it.verzoek.inhoud.length < 10_000 })
    }

    @Test
    fun `publicatietijdstip ligt in het verleden en is ISO-8601 met Z`() {
        val opdrachten = generator().genereer(aantal = 50, random = Random(6))
        val nu = Instant.parse("2026-07-01T12:00:00Z")

        assertTrue(opdrachten.all { it.verzoek.publicatietijdstip.endsWith("Z") })
        assertTrue(opdrachten.all { Instant.parse(it.verzoek.publicatietijdstip).isBefore(nu) })
    }

    @Test
    fun `zelfde seed geeft identieke uitvoer`() {
        val a = generator().genereer(aantal = 20, random = Random(42))
        val b = generator().genereer(aantal = 20, random = Random(42))

        assertEquals(a, b)
    }

    @Test
    fun `aantal nul geeft een lege lijst`() {
        val opdrachten = generator().genereer(aantal = 0, random = Random(7))

        assertTrue(opdrachten.isEmpty())
    }

    @Test
    fun `bij meerdere berichten worden meerdere personas geraakt`() {
        val ontvangers = generator().genereer(aantal = 40, random = Random(8))
            .map { it.verzoek.ontvanger.waarde }
            .toSet()

        assertTrue(ontvangers.size >= 2, "verwacht spreiding over personas, kreeg: $ontvangers")
    }
}
```

- [ ] **Stap 3: Draai de test — verwacht falen**

Run: `./mvnw -B clean test -pl services/demo-console -am`
Expected: FAIL — `DemoBerichtGenerator` bestaat nog niet (compilatiefout).

- [ ] **Stap 4: Schrijf de generator**

`DemoBerichtGenerator.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.generator

import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Kvk
import nl.rijksoverheid.moz.fbs.common.identificatie.Rsin
import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Genereert geldige aanlever-opdrachten voor de demo. Deterministisch: dezelfde `Random`
 * en `Clock` geven dezelfde uitvoer, zodat de logica testbaar is zonder infrastructuur.
 *
 * Alle berichten dragen dezelfde geautoriseerde afzender-OIN — de profiel-stub geeft
 * alleen voor die OIN een actieve voorkeur. De herkenbare afzendernaam zit in het onderwerp.
 */
class DemoBerichtGenerator(
    private val personas: List<Persona>,
    private val afzenderOin: String,
    private val magazijnOins: List<String>,
    private val klok: Clock,
) {

    init {
        require(personas.isNotEmpty()) { "minstens één persona vereist" }
        require(magazijnOins.isNotEmpty()) { "minstens één magazijn vereist" }

        // Fail-fast: elke persona moet een geldig identificatienummer zijn, anders
        // weigert het magazijn het straks met een 400 die pas tijdens de demo opvalt.
        personas.forEach { valideerPersona(it) }
    }

    fun genereer(aantal: Int, random: Random): List<AanleverOpdracht> =
        (0 until aantal).map { index ->
            val persona = personas[random.nextInt(personas.size)]
            val magazijn = magazijnOins[random.nextInt(magazijnOins.size)]
            val afzenderNaam = AFZENDER_NAMEN[random.nextInt(AFZENDER_NAMEN.size)]
            val dagenTerug = random.nextInt(1, 90).toLong()

            val verzoek = AanleverVerzoek(
                afzender = afzenderOin,
                ontvanger = OntvangerDto(persona.type, persona.waarde),
                onderwerp = "$afzenderNaam — ${ONDERWERPEN[random.nextInt(ONDERWERPEN.size)]} (#${index + 1})",
                inhoud = "Beste ${persona.naam},\n\nDit is een demo-bericht van $afzenderNaam. " +
                    "Er is geen actie vereist; dit dient uitsluitend ter demonstratie.\n\n" +
                    "Met vriendelijke groet,\n$afzenderNaam",
                publicatietijdstip = klok.instant().minus(dagenTerug, ChronoUnit.DAYS).toString(),
            )

            AanleverOpdracht(magazijn, verzoek)
        }

    private fun valideerPersona(persona: Persona) {
        when (persona.type) {
            "BSN" -> Bsn(persona.waarde)
            "KVK" -> Kvk(persona.waarde)
            "RSIN" -> Rsin(persona.waarde)
            else -> throw IllegalArgumentException("onbekend ontvanger-type: ${persona.type}")
        }
    }

    private companion object {

        val AFZENDER_NAMEN = listOf("Belastingdienst", "KVK", "RVO", "UWV")

        val ONDERWERPEN = listOf(
            "Voorlopige aanslag 2026",
            "Inschrijving bijgewerkt",
            "Subsidieaanvraag ontvangen",
            "Jaaropgave beschikbaar",
            "Herinnering aangifte",
        )
    }
}
```

- [ ] **Stap 5: Draai de test — verwacht slagen**

Run: `./mvnw -B clean test -pl services/demo-console -am`
Expected: `BUILD SUCCESS`, alle tests in `DemoBerichtGeneratorTest` groen.

- [ ] **Stap 6: Commit**

```bash
git add services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/generator \
        services/demo-console/src/test
git commit -m "feat(demo-console): deterministische random-generator

Genereert geldige aanlever-opdrachten (personas, geautoriseerde afzender-OIN,
gespreid publicatietijdstip). Pure logica, lokaal getest zonder infrastructuur."
```

---

### Taak 3: Aanlever-client, basisdataset en vul-endpoints

**Files:**
- Aanmaken: `.../democonsole/aanlever/MagazijnAanleverClient.kt`
- Aanmaken: `.../democonsole/aanlever/AanleverService.kt`
- Aanmaken: `.../democonsole/dataset/Basisdataset.kt`
- Aanmaken: `services/demo-console/src/main/resources/dataset/basis.json`
- Aanmaken: `.../democonsole/DemoResource.kt` (endpoints `basisvulling` en `random`; `legen` volgt in taak 4)

**Interfaces:**
- Consumeert: `AanleverOpdracht`, `AanleverVerzoek`, `Persona`, `DemoBerichtGenerator` (taak 2).
- Produceert:
  - `interface MagazijnAanleverClient { fun leverAan(verzoek: AanleverVerzoek): Response }`
  - `class AanleverService` met `fun leverAan(opdrachten: List<AanleverOpdracht>): Int` (aantal geslaagd, HTTP 201).
  - `class Basisdataset` met `fun laad(): List<AanleverOpdracht>`.

- [ ] **Stap 1: Schrijf de REST-client-interface**

`MagazijnAanleverClient.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverVerzoek

/**
 * Minimale client voor de magazijn-aanlever-API. De base-URI wordt per magazijn
 * programmatisch gezet (zie AanleverService), zodat de console met een variabel aantal
 * magazijnen overweg kan (fase 6) zonder per magazijn een config-key.
 */
@Path("/api/v1/berichten")
interface MagazijnAanleverClient {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun leverAan(verzoek: AanleverVerzoek): Response
}
```

- [ ] **Stap 2: Schrijf de aanlever-service**

`AanleverService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.util.logging.Logger

/**
 * Levert opdrachten aan bij het juiste magazijn. De magazijn-URL's komen uit config
 * (`demo.magazijnen."<OIN>".url`); per URL wordt één REST-client gebouwd en hergebruikt.
 */
@ApplicationScoped
class AanleverService(
    @param:ConfigProperty(name = "demo.magazijnen") private val magazijnUrls: Map<String, String>,
) {

    private val log = Logger.getLogger(AanleverService::class.java.name)

    private val clients: Map<String, MagazijnAanleverClient> =
        magazijnUrls.mapValues { (_, url) ->
            QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(url))
                .build(MagazijnAanleverClient::class.java)
        }

    fun leverAan(opdrachten: List<AanleverOpdracht>): Int {
        var geslaagd = 0

        opdrachten.forEach { opdracht ->
            val client = clients[opdracht.magazijnOin]

            if (client == null) {
                log.warning("geen magazijn-URL voor OIN ${opdracht.magazijnOin} — opdracht overgeslagen")
                return@forEach
            }

            client.leverAan(opdracht.verzoek).use { response ->
                if (response.status == 201) {
                    geslaagd++
                } else {
                    log.warning("aanleveren gaf HTTP ${response.status} voor ontvanger ${opdracht.verzoek.ontvanger.waarde}")
                }
            }
        }

        return geslaagd
    }
}
```

**Let op:** `@ConfigProperty` op een `Map<String, String>` leest de `demo.magazijnen."<OIN>".url`
-keys niet automatisch als map. Als dit bij het bouwen faalt, vervang de injectie door een
`@ConfigMapping`-interface (zoals `fbs-magazijnregister` doet) — zie taak 3, stap 6 (fallback).
Verifieer dit bij stap 5.

- [ ] **Stap 3: Schrijf de basisdataset-loader**

`Basisdataset.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.dataset

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht

/**
 * Leest de curated basisdataset van het classpath (`dataset/basis.json`). Op het classpath
 * (niet in een externe map) zodat de dataset in de container-image meereist zonder mount.
 */
@ApplicationScoped
class Basisdataset(private val mapper: ObjectMapper) {

    fun laad(): List<AanleverOpdracht> {
        val stroom = javaClass.classLoader.getResourceAsStream(PAD)
            ?: throw IllegalStateException("basisdataset niet gevonden op classpath: $PAD")

        return stroom.use { mapper.readValue(it) }
    }

    private companion object {

        const val PAD = "dataset/basis.json"
    }
}
```

- [ ] **Stap 4: Schrijf de basisdataset**

`services/demo-console/src/main/resources/dataset/basis.json` — een curated set. Hieronder
een representatief begin; vul aan tot ~40 opdrachten, gespreid over beide magazijnen en de
drie personas, met herkenbare afzendernamen in het onderwerp en gespreide
publicatietijdstippen. Elke `verzoek.afzender` is de geautoriseerde OIN.

```json
[
  {
    "magazijnOin": "00000001003214345000",
    "verzoek": {
      "afzender": "00000001003214345000",
      "ontvanger": { "type": "BSN", "waarde": "999993653" },
      "onderwerp": "Belastingdienst — Voorlopige aanslag 2026",
      "inhoud": "Beste J. Pietersen,\n\nUw voorlopige aanslag 2026 staat klaar.\n\nBelastingdienst",
      "publicatietijdstip": "2026-06-02T08:00:00Z"
    }
  },
  {
    "magazijnOin": "00000001823288444000",
    "verzoek": {
      "afzender": "00000001003214345000",
      "ontvanger": { "type": "KVK", "waarde": "12345678" },
      "onderwerp": "KVK — Inschrijving bijgewerkt",
      "inhoud": "Beste Garage Van Dijk B.V.,\n\nUw inschrijving is bijgewerkt.\n\nKVK",
      "publicatietijdstip": "2026-05-20T09:30:00Z"
    }
  },
  {
    "magazijnOin": "00000001003214345000",
    "verzoek": {
      "afzender": "00000001003214345000",
      "ontvanger": { "type": "BSN", "waarde": "123456782" },
      "onderwerp": "RVO — Subsidieaanvraag ontvangen",
      "inhoud": "Beste Bakkerij De Vroege Vogel,\n\nUw subsidieaanvraag is ontvangen.\n\nRVO",
      "publicatietijdstip": "2026-04-11T14:15:00Z"
    }
  }
]
```

- [ ] **Stap 5: Schrijf DemoResource met de vul-endpoints**

`DemoResource.kt` (de `legen`-methode volgt in taak 4):

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import kotlin.random.Random

@Path("/api/demo")
@Produces(MediaType.APPLICATION_JSON)
class DemoResource(
    private val basisdataset: Basisdataset,
    private val aanleverService: AanleverService,
    private val generator: DemoBerichtGenerator,
) {

    @POST
    @Path("/basisvulling")
    fun basisvulling(): Map<String, Int> {
        val opdrachten = basisdataset.laad()
        val geslaagd = aanleverService.leverAan(opdrachten)

        return mapOf("aangeboden" to opdrachten.size, "geslaagd" to geslaagd)
    }

    @POST
    @Path("/random")
    fun random(@QueryParam("aantal") @DefaultValue("10") aantal: Int): Map<String, Int> {
        val opdrachten = generator.genereer(aantal, Random.Default)
        val geslaagd = aanleverService.leverAan(opdrachten)

        return mapOf("aangeboden" to opdrachten.size, "geslaagd" to geslaagd)
    }
}
```

De `DemoBerichtGenerator` moet een CDI-bean zijn. Voeg een producer toe —
`services/demo-console/src/main/kotlin/nl/rijksoverheid/moz/fbs/democonsole/generator/GeneratorProducer.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.generator

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

class GeneratorProducer {

    @Produces
    @ApplicationScoped
    fun generator(): DemoBerichtGenerator =
        DemoBerichtGenerator(
            personas = listOf(
                Persona("J. Pietersen", "BSN", "999993653"),
                Persona("Bakkerij De Vroege Vogel", "BSN", "123456782"),
                Persona("Garage Van Dijk B.V.", "KVK", "12345678"),
            ),
            afzenderOin = "00000001003214345000",
            magazijnOins = listOf("00000001003214345000", "00000001823288444000"),
            klok = Clock.systemUTC(),
        )
}
```

- [ ] **Stap 6: Bouw en controleer de config-injectie**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console -am`
Expected: `BUILD SUCCESS`.

Faalt de augmentatie op de `Map<String,String>`-injectie in `AanleverService` (melding over
`demo.magazijnen`), vervang die injectie dan door een `@ConfigMapping`:

```kotlin
// nieuw bestand: aanlever/MagazijnenConfig.kt
package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "demo")
interface MagazijnenConfig {

    fun magazijnen(): Map<String, Magazijn>

    interface Magazijn {

        fun url(): String
    }
}
```

en injecteer `MagazijnenConfig` in plaats van de `Map`; bouw `magazijnUrls` uit
`config.magazijnen().mapValues { it.value.url() }`. Dit spiegelt `ConfigMagazijnregister`.

- [ ] **Stap 7: Integratieverificatie (vereist Docker — op een machine mét runtime)**

```bash
# stack draaien (fase 0): infra + services, plus demo-console lokaal in dev
docker compose up -d
./mvnw quarkus:dev -pl services/demo-console          # aparte terminal

curl -s -X POST localhost:8095/api/demo/basisvulling  # verwacht {"aangeboden":N,"geslaagd":N}
curl -s -X POST 'localhost:8095/api/demo/random?aantal=5'
# controleer dat de berichten via de uitvraag zichtbaar zijn voor persona 999993653:
curl -s -N --max-time 30 localhost:8086/api/v1/berichten/_ophalen -H 'X-Ontvanger: BSN:999993653' >/dev/null
curl -s localhost:8086/api/v1/berichten -H 'X-Ontvanger: BSN:999993653' | head -c 400
```

Expected: `geslaagd` == `aangeboden` (alle 201). Controleer specifiek de KVK-persona
(`X-Ontvanger: KVK:12345678`): komt die niet door de keten, noteer dat en vervang de
KVK-persona door een geldige BSN in `GeneratorProducer` én `basis.json`.

- [ ] **Stap 8: Commit**

```bash
git add services/demo-console/src/main/kotlin services/demo-console/src/main/resources/dataset
git commit -m "feat(demo-console): aanlever-client, basisdataset en vul-endpoints

POST /api/demo/basisvulling en /random leveren via de echte aanlever-API aan,
zodat validatie, publicatie-outbox en notificatieketen meedraaien."
```

---

### Taak 4: Legen via JDBC-truncate

**Files:**
- Aanmaken: `.../democonsole/legen/MagazijnDatabase.kt`
- Wijzigen: `.../democonsole/DemoResource.kt` (endpoints `legen` en `status`)

**Interfaces:**
- Consumeert: de twee named datasources uit taak 1.
- Produceert:
  - `class MagazijnDatabase` met `fun leegAlles(): Map<String, Int>` (per magazijn-label het aantal verwijderde berichten vóór truncate) en `fun aantallen(): Map<String, Int>`.

- [ ] **Stap 1: Schrijf de database-component**

`MagazijnDatabase.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.democonsole.legen

import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import javax.sql.DataSource as JavaxDataSource

/**
 * Directe DB-toegang op de magazijn-databases voor het legen. Bewust "vieze" kennis van
 * het magazijn-schema in de wegwerp-console i.p.v. een reset-endpoint in productiecode.
 * TRUNCATE ... RESTART IDENTITY CASCADE geeft een schone lei inclusief child-tabellen.
 */
@ApplicationScoped
class MagazijnDatabase(
    @param:DataSource("magazijn-a-db") private val magazijnA: AgroalDataSource,
    @param:DataSource("magazijn-b-db") private val magazijnB: AgroalDataSource,
) {

    private val bronnen: Map<String, JavaxDataSource> = mapOf(
        "magazijn-a" to magazijnA,
        "magazijn-b" to magazijnB,
    )

    fun leegAlles(): Map<String, Int> =
        bronnen.mapValues { (_, bron) ->
            val aantal = telBerichten(bron)

            bron.connection.use { verbinding ->
                verbinding.createStatement().use { stmt ->
                    stmt.execute(
                        "TRUNCATE berichten, bijlagen, bericht_status, publicatie_deliveries " +
                            "RESTART IDENTITY CASCADE",
                    )
                }
            }

            aantal
        }

    fun aantallen(): Map<String, Int> = bronnen.mapValues { (_, bron) -> telBerichten(bron) }

    private fun telBerichten(bron: JavaxDataSource): Int =
        bron.connection.use { verbinding ->
            verbinding.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM berichten").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
}
```

- [ ] **Stap 2: Voeg de endpoints toe aan DemoResource**

Voeg in `DemoResource.kt` de dependency en twee methodes toe. Wijzig de constructor:

```kotlin
class DemoResource(
    private val basisdataset: Basisdataset,
    private val aanleverService: AanleverService,
    private val generator: DemoBerichtGenerator,
    private val magazijnDatabase: MagazijnDatabase,
) {
```

en voeg toe (plus de import `nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase`
en `jakarta.ws.rs.GET`):

```kotlin
    @POST
    @Path("/legen")
    fun legen(): Map<String, Int> = magazijnDatabase.leegAlles()

    @GET
    @Path("/status")
    fun status(): Map<String, Int> = magazijnDatabase.aantallen()
```

- [ ] **Stap 3: Bouw**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console -am`
Expected: `BUILD SUCCESS`.

- [ ] **Stap 4: Integratieverificatie (Docker)**

```bash
curl -s -X POST localhost:8095/api/demo/basisvulling
curl -s localhost:8095/api/demo/status      # verwacht berichten > 0 per magazijn
curl -s -X POST localhost:8095/api/demo/legen
curl -s localhost:8095/api/demo/status      # verwacht {"magazijn-a":0,"magazijn-b":0}
```

Expected: na `legen` staat de telling per magazijn op 0.

- [ ] **Stap 5: Commit**

```bash
git add services/demo-console/src/main/kotlin
git commit -m "feat(demo-console): legen via JDBC-truncate + status-telling

TRUNCATE RESTART IDENTITY CASCADE op beide magazijn-databases geeft een schone
lei; GET /api/demo/status telt de berichten per magazijn."
```

---

### Taak 5: Kaal bedieningspaneel + compose-integratie

**Files:**
- Aanmaken: `services/demo-console/src/main/resources/META-INF/resources/index.html`
- Wijzigen: `compose.yaml` (demo-console-container in profiel `demo`)
- Wijzigen: `README.md`

**Interfaces:**
- Consumeert: de endpoints uit taak 3 en 4.

- [ ] **Stap 1: Schrijf het paneel**

`index.html` — bewust kaal: knoppen die de endpoints aanroepen en het antwoord tonen.
Legen zit expres bovenaan met een bevestiging, want vullen-zonder-legen stapelt data.

```html
<!doctype html>
<html lang="nl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>FBS Demo-console</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 40rem; margin: 2rem auto; padding: 0 1rem; }
    button { font-size: 1rem; padding: 0.5rem 1rem; margin: 0.25rem 0; cursor: pointer; }
    input { font-size: 1rem; padding: 0.4rem; width: 5rem; }
    fieldset { margin: 1rem 0; }
    pre { background: #f4f4f4; padding: 1rem; overflow-x: auto; white-space: pre-wrap; }
  </style>
</head>
<body>
  <h1>FBS Demo-console</h1>
  <p>Wegwerp-bediening voor de demo-stack. Leeg vóór je opnieuw vult.</p>

  <fieldset>
    <legend>Beheer</legend>
    <button onclick="post('/api/demo/legen', true)">Magazijnen legen</button>
    <button onclick="get('/api/demo/status')">Status (aantal berichten)</button>
  </fieldset>

  <fieldset>
    <legend>Vullen</legend>
    <button onclick="post('/api/demo/basisvulling')">Basisvulling laden</button>
    <br>
    <label>Random berichten: <input id="aantal" type="number" value="10" min="1" max="500"></label>
    <button onclick="post('/api/demo/random?aantal=' + document.getElementById('aantal').value)">Opvoeren</button>
  </fieldset>

  <h2>Resultaat</h2>
  <pre id="uit">—</pre>

  <script>
    const uit = document.getElementById('uit');

    async function post(pad, bevestig) {
      if (bevestig && !confirm('Zeker weten? Dit verwijdert alle berichten uit beide magazijnen.')) return;

      toon('Bezig…');
      try {
        const r = await fetch(pad, { method: 'POST' });
        toon(JSON.stringify(await r.json(), null, 2));
      } catch (e) {
        toon('Fout: ' + e);
      }
    }

    async function get(pad) {
      toon('Bezig…');
      try {
        const r = await fetch(pad);
        toon(JSON.stringify(await r.json(), null, 2));
      } catch (e) {
        toon('Fout: ' + e);
      }
    }

    function toon(tekst) { uit.textContent = tekst; }
  </script>
</body>
</html>
```

- [ ] **Stap 2: Voeg de demo-console-container toe aan compose**

In `compose.yaml`, binnen het `demo`-profielblok (ná `berichtenuitvraag`, vóór `volumes:`):

```yaml
  demo-console:
    image: fbs-demo/fbs-demo-console:demo
    profiles: [demo]
    ports:
      - "8095:8095"
    depends_on:
      berichtenmagazijn-a:
        condition: service_started
      berichtenmagazijn-b:
        condition: service_started
    environment:
      QUARKUS_PROFILE: dev
      QUARKUS_HTTP_HOST: 0.0.0.0
      MAGAZIJN_A_URL: http://berichtenmagazijn-a:8090
      MAGAZIJN_B_URL: http://berichtenmagazijn-b:8090
      MAGAZIJN_A_DB_URL: jdbc:postgresql://postgres-a:5432/berichtenmagazijn
      MAGAZIJN_B_DB_URL: jdbc:postgresql://postgres-b:5432/berichtenmagazijn
```

- [ ] **Stap 3: Neem demo-console op in de image-build en README**

Voeg in `README.md`, in de demo-stack-sectie, de derde module toe aan het jib-commando:

```bash
./mvnw clean package -DskipTests \
  -pl services/berichtenmagazijn,services/berichtenuitvraag,services/demo-console -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo \
  -Dquarkus.container-image.tag=demo
```

en voeg onder de start-instructies toe:

```markdown
De demo-console draait op <http://localhost:8095> — een kaal paneel om de magazijnen te
legen, de basisdataset te laden en random berichten op te voeren.
```

- [ ] **Stap 4: Volledige verificatie (Docker)**

```bash
# images inclusief demo-console bouwen (commando hierboven), dan:
docker compose --profile demo up -d
docker compose --profile demo ps          # demo-console 'running', poort 8095
```

Open <http://localhost:8095>, klik: **Magazijnen legen** → **Basisvulling laden** →
**Status**. Verwacht na basisvulling een positieve telling per magazijn; na legen weer 0.
Controleer dat een random-opvoer tijdens een geopende uitvraag-sessie nieuwe berichten
oplevert.

- [ ] **Stap 5: detekt-schoon + volledige suite (Docker)**

Run: `./mvnw -B clean verify -pl services/demo-console -am`
Expected: `BUILD SUCCESS`, detekt zonder bevindingen. Bij een detekt-bevinding: los op of
motiveer met inline `@Suppress`.

- [ ] **Stap 6: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF compose.yaml README.md
git commit -m "feat(demo-console): kaal bedieningspaneel + compose-integratie

Statisch HTML-paneel op poort 8095 (legen/vullen/random/status). demo-console
draait mee in het compose demo-profiel."
```

---

## Definition of done

- [ ] `services/demo-console` bouwt (`mvn package`) en valt buiten de coveragegate
- [ ] Generator-unittests groen (lokaal, zonder Docker)
- [ ] `POST /api/demo/basisvulling` levert de curated dataset aan (alle 201)
- [ ] `POST /api/demo/random?aantal=N` voert N geldige berichten op
- [ ] `POST /api/demo/legen` maakt beide magazijnen leeg; `GET /api/demo/status` bevestigt 0
- [ ] Het paneel op :8095 bedient alle vier de acties
- [ ] `docker compose --profile demo up -d` start demo-console mee
- [ ] `./mvnw clean verify -pl services/demo-console -am` groen (detekt-schoon)
- [ ] KVK-persona bevestigd door de keten, of teruggevallen op BSN

## Risico's

| Risico | Signaal | Mitigatie |
|---|---|---|
| `@ConfigProperty Map` leest de aanhalingsteken-keys niet | Augmentatie faalt op `demo.magazijnen` | Val terug op `@ConfigMapping` (taak 3, stap 6) |
| KVK-ontvanger stroomt niet door uitvraag/sessiecache | Basisvulling 201, maar KVK-persona ziet niets in de uitvraag | Vervang KVK-persona door geldige BSN (taak 3, stap 7) |
| Datasource verbindt bij boot en faalt zonder Postgres | demo-console start niet in dev zonder DB | `health-exclude=true` staat; Agroal is lazy — geen verbinding vóór eerste query |
| detekt faalt op `verify` | `maxIssues: 0` overschreden | Kotlin schoonhouden; onvermijdelijk → inline `@Suppress` met reden |
| Aanleveren geeft 403 | `geslaagd` < `aangeboden` | Afzender-OIN moet exact `00000001003214345000` zijn; ontvanger niet uit de 403-lijst |

## Niet in deze fase

Toxiproxy en storingsscenario's (fase 3), de Berichtenbox-UI (fase 2), echte
afzender-OIN-variatie (vraagt profiel-stub-uitbreiding; pas bij een concrete UI-aanleiding),
bijlagen (fase 3), en het variabele aantal stub-magazijnen (fase 6).
