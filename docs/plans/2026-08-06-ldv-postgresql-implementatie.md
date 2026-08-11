# PostgreSQL als LDV-backend — implementatieplan

> **Voor agentische uitvoerders:** VEREISTE SUB-SKILL: gebruik superpowers:subagent-driven-development (aanbevolen) of superpowers:executing-plans om dit plan taak voor taak uit te voeren. Stappen gebruiken checkbox-syntax (`- [ ]`) voor voortgang.

**Status:** Uitgevoerd

**Doel:** Het Logboek Dataverwerkingen schrijft naar PostgreSQL in plaats van ClickHouse, met de gedragswijzigingen die de sprong van wrapper 1.2.1 naar 2.0.0 meebrengt.

**Aanpak:** Eerst de afhankelijkheid en de configuratie omzetten zodat de build op 2.0.0 draait (taak 1). Daarna per gedragswijziging één taak met eigen testcyclus: TLS-guard (2), fail-closed op het aanleverpad (3), herordening van het publicatiepad (4). Tot slot een integratietest tegen een echte PostgreSQL (5), de CI/deploy-opruiming (6) en de documentatie (7).

**Stack:** Maven, Quarkus 3.x, Kotlin/JVM 21, JUnit 5 + MockK + REST-assured, PostgreSQL 18, `nl.mijnoverheidzakelijk.ldv:logboekdataverwerking-wrapper`.

**Ontwerp:** `docs/plans/2026-08-06-ldv-postgresql-backend.md` — lees dat eerst; dit plan voert het uit.

## Globale randvoorwaarden

- Wrapper-versie: `1.0.0` (Maven Central). Tijdens de uitvoering nog `2.0.0-SNAPSHOT`; de
  klassen in de release bleken byte-identiek en de dependencies gelijk, dus de bump was een
  versiewissel zonder codegevolgen. Waar hieronder "2.0.0" staat, gaat het om die versie.
- Tabelnaam in beide services: `logboek_dataverwerkingen`.
- Default backend: `postgresql`; `LDV_DBMS=clickhouse` blijft een werkend pad.
- `logboekdataverwerking.span-processor=simple` en `write-failure-policy=fail-closed`.
- Draai altijd `clean` vóór `test`/`verify` — een achtergebleven `target/` van een andere
  branch-state geeft misleidende fouten.
- Elke build-/testronde nalopen op nieuwe waarschuwingen; onverklaarde nieuwe
  waarschuwingen triëren vóór de commit.
- Commit-berichten in het Nederlands, Conventional Commits, met de
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`-regel.
- Comments leggen het *waarom* vast, niet het *wat*. Geen verwijzingen naar dit plan,
  naar CLAUDE.md of naar review-labels in code of testnamen.
- Kotlin-stijl: lege regel vóór en ná elk multi-line blok en elk zelfstandig
  control-statement.

## Bestandsoverzicht

| Bestand | Verantwoordelijkheid | Taak |
|---|---|---|
| `pom.xml` | Wrapper-versie | 1 |
| `services/berichtenuitvraag/pom.xml` | PostgreSQL-JDBC-driver declareren | 1 |
| `services/berichtenmagazijn/src/main/resources/application.properties` | LDV-config magazijn | 1 |
| `services/berichtenuitvraag/src/main/resources/application.properties` | LDV-config uitvraag | 1 |
| `compose.yaml` | Lokale infra: clickhouse eruit, `postgres-uitvraag` erin | 1 |
| `libraries/fbs-common/.../OutboundTlsValidator.kt` | TLS-check op uitgaande endpoints, nu ook JDBC | 2 |
| `libraries/fbs-common/.../LdvEndpointValidator.kt` | Kiest de check op basis van `dbms` | 2 |
| `libraries/fbs-common/.../LogboekContextDefaultFilter.kt` | KDoc herzien (rationale klopt niet meer) | 2 |
| `services/berichtenmagazijn/.../aanlever/AanleverResource.kt` | Fail-closed op het handmatige span-pad | 3 |
| `services/berichtenmagazijn/.../publicatie/PublicatieClaimVerwerker.kt` | Logregel vóór de levering | 4 |
| `services/berichtenmagazijn/src/test/.../LdvPostgresIntegrationTest.kt` | Echte insert in PostgreSQL | 5 |
| `.github/workflows/deploy.yml`, `pin-consistency.yml` | ClickHouse-componenten en -pin weg | 6 |
| `README.md`, `docs/operator-handleiding.md`, `CLAUDE.md` | Documentatie | 7 |

---

## Taak 1: Wrapper 2.0.0, driver en configuratie

**Bestanden:**
- Wijzig: `pom.xml:40`
- Wijzig: `services/berichtenuitvraag/pom.xml` (dependencies-blok)
- Wijzig: `services/berichtenmagazijn/src/main/resources/application.properties:107-135`
- Wijzig: `services/berichtenuitvraag/src/main/resources/application.properties:259-287`
- Wijzig: `compose.yaml:68-75` en `compose.yaml:113-116`

**Interfaces:**
- Levert: de configkeys `logboekdataverwerking.dbms`, `logboekdataverwerking.postgresql.url`,
  `.username`, `.password`, `.table` in beide services. Taak 2 leest `dbms` en `postgresql.url`.
- Levert: `logboekdataverwerking.clickhouse.endpoint` heeft vanaf nu een lege default,
  zodat injectie niet klapt wanneer de ClickHouse-backend niet gekozen is.

- [ ] **Stap 1: Bump de wrapper-versie**

In `pom.xml`:

```xml
<logboekdataverwerking-wrapper.version>1.0.0</logboekdataverwerking-wrapper.version>
```

- [ ] **Stap 2: Declareer de PostgreSQL-driver in berichtenuitvraag**

In `services/berichtenuitvraag/pom.xml`, direct ná de `logboekdataverwerking-wrapper`-dependency:

```xml
        <!-- LDV opent zijn eigen JDBC-verbinding naar het logboek; de wrapper markeert
             beide drivers als optional zodat een consumer alleen die van zijn gekozen
             backend meeneemt. Bewust de kale driver en niet quarkus-jdbc-postgresql:
             die extensie verwacht een geconfigureerde datasource, en deze service heeft
             er geen. -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
```

Geen `<version>` — de Quarkus-BOM beheert deze. `berichtenmagazijn` heeft de driver al
via `quarkus-jdbc-postgresql` en krijgt geen extra dependency.

- [ ] **Stap 3: Zet de LDV-config om in berichtenmagazijn**

Vervang in `services/berichtenmagazijn/src/main/resources/application.properties` het
hele LDV-blok (vanaf de comment "Logboek Dataverwerkingen (AVG Art. 30)" tot en met de
`%test.logboekdataverwerking.clickhouse.password`-regel) door:

```properties
# Logboek Dataverwerkingen (AVG Art. 30) — geen fallback-defaults voor credentials
# en endpoint, zodat de service faalt te starten als env-vars ontbreken; anders draait
# LDV stilletjes op bekende defaults of (erger) een onversleuteld endpoint.
logboekdataverwerking.enabled=true
# Zet (true) de TLS-eis op het LDV-endpoint uit. Default false = veilig.
# BEWUST ONVEILIG — rationale + voorwaarden in de KDoc van OutboundTlsValidator.requireHttps.
fbs.ldv.unsafe-allow-plaintext-endpoint=false
logboekdataverwerking.service-name=berichtenmagazijn

# PostgreSQL is de default-backend; LDV_DBMS=clickhouse schakelt terug.
logboekdataverwerking.dbms=${LDV_DBMS:postgresql}
# Synchroon exporteren, zodat de applicatie ziet of de logregel is opgeslagen en een
# verwerking zonder logregel niet als uitgevoerd telt (LDV-acknowledgement-eis).
logboekdataverwerking.span-processor=simple
logboekdataverwerking.write-failure-policy=fail-closed

logboekdataverwerking.postgresql.url=${LDV_POSTGRES_URL}
logboekdataverwerking.postgresql.username=${LDV_POSTGRES_USERNAME}
logboekdataverwerking.postgresql.password=${LDV_POSTGRES_PASSWORD}
logboekdataverwerking.postgresql.table=logboek_dataverwerkingen

# Lege default: de wrapper valideert alleen de properties van de gekozen backend, maar
# de TLS-guard injecteert deze key onvoorwaardelijk. Zonder default zou een deployment
# op PostgreSQL klappen op een ClickHouse-env-var die niets meer doet.
logboekdataverwerking.clickhouse.endpoint=${LDV_CLICKHOUSE_ENDPOINT:}
logboekdataverwerking.clickhouse.username=${CLICKHOUSE_USERNAME:}
logboekdataverwerking.clickhouse.password=${CLICKHOUSE_PASSWORD:}
logboekdataverwerking.clickhouse.database=ldv_logging
logboekdataverwerking.clickhouse.table=logboek_dataverwerkingen

# Voor dev/test: veilige defaults zodat lokaal draaien zonder env-vars werkt. In %prod
# blijven de basisproperties zonder default; operators MOETEN een TLS-verbinding zetten
# conform BIO 13.2.1 (persoonsgegevens versleuteld over netwerk).
%dev.logboekdataverwerking.postgresql.url=${LDV_POSTGRES_URL:jdbc:postgresql://localhost:5432/berichtenmagazijn}
%dev.logboekdataverwerking.postgresql.username=${LDV_POSTGRES_USERNAME:berichtenmagazijn}
%dev.logboekdataverwerking.postgresql.password=${LDV_POSTGRES_PASSWORD:berichtenmagazijn}
%dev.logboekdataverwerking.clickhouse.endpoint=${LDV_CLICKHOUSE_ENDPOINT:http://localhost:8123}
%dev.logboekdataverwerking.clickhouse.username=ldv
%dev.logboekdataverwerking.clickhouse.password=ldv
%test.logboekdataverwerking.postgresql.url=jdbc:postgresql://localhost:5432/berichtenmagazijn
%test.logboekdataverwerking.postgresql.username=berichtenmagazijn
%test.logboekdataverwerking.postgresql.password=berichtenmagazijn
%test.logboekdataverwerking.clickhouse.endpoint=http://localhost:8123
%test.logboekdataverwerking.clickhouse.username=ldv
%test.logboekdataverwerking.clickhouse.password=ldv

# Quarkus bean discovery voor logboekdataverwerking-wrapper
quarkus.index-dependency.ldv.group-id=nl.mijnoverheidzakelijk.ldv
quarkus.index-dependency.ldv.artifact-id=logboekdataverwerking-wrapper
```

De `%test`-waarden worden niet echt gebruikt (`logboekdataverwerking.enabled=false` in
`src/test/resources/application.properties`), maar ze houden de config resolvable.

- [ ] **Stap 4: Zet de LDV-config om in berichtenuitvraag**

Zelfde blok in `services/berichtenuitvraag/src/main/resources/application.properties`,
met drie verschillen: `logboekdataverwerking.service-name=berichtenuitvraag`, en de
`%dev`/`%test`-defaults wijzen naar de eigen container:

```properties
%dev.logboekdataverwerking.postgresql.url=${LDV_POSTGRES_URL:jdbc:postgresql://localhost:5434/ldv_logging}
%dev.logboekdataverwerking.postgresql.username=${LDV_POSTGRES_USERNAME:ldv}
%dev.logboekdataverwerking.postgresql.password=${LDV_POSTGRES_PASSWORD:ldv}
%test.logboekdataverwerking.postgresql.url=jdbc:postgresql://localhost:5434/ldv_logging
%test.logboekdataverwerking.postgresql.username=ldv
%test.logboekdataverwerking.postgresql.password=ldv
```

Laat de bestaande `quarkus.index-dependency.sessiecache.*`-regels staan.

- [ ] **Stap 5: Vervang ClickHouse door postgres-uitvraag in compose.yaml**

Verwijder de `clickhouse`-service (`compose.yaml:68-75`) en zet er in de plaats:

```yaml
  # LDV-logboek van de uitvraag. Elke organisatie voert een eigen verwerkingenlogboek;
  # de magazijnen schrijven daarom in hun eigen postgres-a/-b, de uitvraag hier.
  postgres-uitvraag:
    image: postgres:18
    ports:
      - "5434:5432"
    environment:
      POSTGRES_USER: ldv
      POSTGRES_PASSWORD: ldv
      POSTGRES_DB: ldv_logging
    volumes:
      - postgres-data-uitvraag:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ldv -d ldv_logging"]
      interval: 5s
      timeout: 3s
      retries: 5
```

En vul het `volumes:`-blok onderaan aan:

```yaml
volumes:
  postgres-data-a:
  postgres-data-b:
  postgres-data-uitvraag:
```

- [ ] **Stap 6: Build en draai de volledige suites**

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am
./mvnw clean test -pl services/berichtenuitvraag -am
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am
```

Verwacht: groen. De bestaande tests mocken `ProcessingHandler`, dus de nieuwe
`propagatingException`-parameter (met default `null`) verandert de matching niet.

Faalt een MockK-stub met "no answer found", dan matcht een tweeargs-stub niet meer op de
drieargs-methode; breid die stub uit naar `addLogboekContextToSpan(any(), any(), any())`.

- [ ] **Stap 7: Rook­proef lokaal**

```bash
docker compose up -d
docker compose ps
```

Verwacht: `postgres-uitvraag` healthy op poort 5434, geen `clickhouse`-container meer.

- [ ] **Stap 8: Commit**

```bash
git add pom.xml services/berichtenuitvraag/pom.xml \
  services/berichtenmagazijn/src/main/resources/application.properties \
  services/berichtenuitvraag/src/main/resources/application.properties compose.yaml
git commit -m "feat(ldv): PostgreSQL als default LDV-backend op wrapper 2.0.0"
```

---

## Taak 2: TLS-guard dbms-bewust maken

**Bestanden:**
- Wijzig: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/OutboundTlsValidator.kt`
- Wijzig: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/LdvEndpointValidator.kt`
- Wijzig: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/LogboekContextDefaultFilter.kt`
- Test: `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/OutboundTlsValidatorTest.kt`
- Test: `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/LdvEndpointValidatorTest.kt`

**Interfaces:**
- Gebruikt: de configkeys uit taak 1.
- Levert: `OutboundTlsValidator.requireJdbcTls(profile: String, url: String, configKey: String, unsafeAllowPlaintext: Boolean = false)` — gooit `IllegalArgumentException`.
- Levert: `LdvEndpointValidator.validate(profile: String, dbms: String, endpoint: String, unsafeAllowPlaintext: Boolean = false)` — de bestaande tweeargs-vorm vervalt.

- [ ] **Stap 1: Schrijf de falende tests voor requireJdbcTls**

Voeg toe aan `OutboundTlsValidatorTest.kt`:

```kotlin
    @ParameterizedTest
    @ValueSource(
        strings = [
            "jdbc:postgresql://db:5432/ldv?ssl=true",
            "jdbc:postgresql://db:5432/ldv?sslmode=require",
            "jdbc:postgresql://db:5432/ldv?sslmode=verify-ca",
            "jdbc:postgresql://db:5432/ldv?sslmode=verify-full",
            "jdbc:postgresql://db:5432/ldv?user=x&sslmode=require&y=1",
        ],
    )
    fun `prod accepteert een JDBC-URL die daadwerkelijk versleutelt`(url: String) {
        assertDoesNotThrow { OutboundTlsValidator.requireJdbcTls("prod", url, "ldv.url") }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "jdbc:postgresql://db:5432/ldv",
            "jdbc:postgresql://db:5432/ldv?sslmode=disable",
            "jdbc:postgresql://db:5432/ldv?sslmode=allow",
            "jdbc:postgresql://db:5432/ldv?sslmode=prefer",
            "jdbc:postgresql://db:5432/ldv?ssl=false",
            "",
        ],
    )
    fun `prod weigert een JDBC-URL zonder gegarandeerde versleuteling`(url: String) {
        val ex = assertThrows<IllegalArgumentException> {
            OutboundTlsValidator.requireJdbcTls("prod", url, "ldv.url")
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "foutmelding moet naar BIO 13.2.1 verwijzen")
        assertTrue(ex.message!!.contains("ldv.url"), "foutmelding moet de configkey noemen")
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test laten een plaintext JDBC-URL toe`(profiel: String) {
        assertDoesNotThrow {
            OutboundTlsValidator.requireJdbcTls(profiel, "jdbc:postgresql://localhost:5432/ldv", "ldv.url")
        }
    }

    @Test
    fun `de onveilige override laat plaintext JDBC toe in prod`() {
        assertDoesNotThrow {
            OutboundTlsValidator.requireJdbcTls(
                "prod",
                "jdbc:postgresql://db:5432/ldv",
                "ldv.url",
                unsafeAllowPlaintext = true,
            )
        }
    }
```

Imports die erbij moeten: `org.junit.jupiter.params.ParameterizedTest` en
`org.junit.jupiter.params.provider.ValueSource`.

`sslmode=prefer` staat bewust in de weiger-lijst: die valt stil terug op plaintext als de
server geen TLS aanbiedt en geeft dus geen garantie.

- [ ] **Stap 2: Draai de tests en zie ze falen**

```bash
./mvnw clean test -pl libraries/fbs-common -Dtest=OutboundTlsValidatorTest
```

Verwacht: compilatiefout — `requireJdbcTls` bestaat niet.

- [ ] **Stap 3: Implementeer requireJdbcTls**

Voeg toe aan `OutboundTlsValidator`:

```kotlin
    /** `sslmode`-waarden die versleuteling garanderen; `prefer` valt stil terug op plaintext. */
    private val SSLMODES_MET_GARANTIE = setOf("require", "verify-ca", "verify-full")

    /**
     * Verifieert dat [url] een JDBC-verbinding opzet die daadwerkelijk versleutelt.
     * De JDBC-vorm heeft geen scheme om op te controleren, dus de check kijkt naar
     * `ssl=true` of een `sslmode` uit [SSLMODES_MET_GARANTIE].
     *
     * Verder identiek aan [requireHttps]: zelfde profielvrijstelling, zelfde
     * bewust-onveilige override met dezelfde alert-token-waarschuwing.
     *
     * @throws IllegalArgumentException als het profiel TLS vereist, de URL geen
     *   versleuteling garandeert, en de onveilige override niet expliciet aan staat.
     */
    fun requireJdbcTls(
        profile: String,
        url: String,
        configKey: String,
        unsafeAllowPlaintext: Boolean = false,
    ) {
        if (profile in PROFIELEN_ZONDER_TLS_EIS) return

        val parameters = url.substringAfter('?', "").split('&')
        val isVersleuteld = parameters.any { parameter ->
            val (sleutel, waarde) = parameter.substringBefore('=') to parameter.substringAfter('=', "")

            when (sleutel.lowercase()) {
                "ssl" -> waarde.equals("true", ignoreCase = true)
                "sslmode" -> waarde.lowercase() in SSLMODES_MET_GARANTIE
                else -> false
            }
        }

        if (unsafeAllowPlaintext && !isVersleuteld) {
            log.warning(
                "$TLS_DISABLED_ALERT_TOKEN: TLS-eis BEWUST uitgeschakeld voor $configKey in profiel " +
                    "'$profile' — persoonsgegevens (o.a. BSN) gaan PLAINTEXT over het netwerk. Alleen " +
                    "toegestaan bij mesh-mTLS of zonder echte persoonsgegevens.",
            )
        }

        require(isVersleuteld || unsafeAllowPlaintext) {
            "$configKey MOET een versleutelde verbinding opzetten in profiel '$profile' — " +
                "gebruik ssl=true of sslmode=require/verify-ca/verify-full " +
                "(BIO 13.2.1: persoonsgegevens versleuteld over netwerk)."
        }
    }
```

De URL zelf staat bewust niet in de foutmelding: een JDBC-URL kan een wachtwoord als
query-parameter bevatten.

- [ ] **Stap 4: Draai de tests en zie ze slagen**

```bash
./mvnw clean test -pl libraries/fbs-common -Dtest=OutboundTlsValidatorTest
```

Verwacht: PASS.

- [ ] **Stap 5: Schrijf de falende tests voor de dbms-keuze**

Vervang de inhoud van `LdvEndpointValidatorTest.kt` door tests op de nieuwe signatuur:

```kotlin
package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LdvEndpointValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test laten een plaintext ClickHouse-endpoint toe`(profiel: String) {
        assertDoesNotThrow {
            LdvEndpointValidator.validate(profiel, "clickhouse", "http://localhost:8123")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `dev en test laten een plaintext JDBC-URL toe`(profiel: String) {
        assertDoesNotThrow {
            LdvEndpointValidator.validate(profiel, "postgresql", "jdbc:postgresql://localhost:5432/ldv")
        }
    }

    @Test
    fun `prod met plaintext ClickHouse-endpoint faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", "clickhouse", "http://insecure:8123")
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "foutmelding moet naar BIO 13.2.1 verwijzen")
        assertTrue(ex.message!!.contains("https://"), "foutmelding moet https:// noemen")
    }

    @Test
    fun `prod met https ClickHouse-endpoint slaagt`() {
        assertDoesNotThrow {
            LdvEndpointValidator.validate("prod", "clickhouse", "https://clickhouse.intern:8443")
        }
    }

    @Test
    fun `prod met plaintext JDBC-URL faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", "postgresql", "jdbc:postgresql://db:5432/ldv")
        }
        assertTrue(ex.message!!.contains("BIO 13.2.1"), "foutmelding moet naar BIO 13.2.1 verwijzen")
        assertTrue(ex.message!!.contains("sslmode"), "foutmelding moet de bruikbare sslmode-waarden noemen")
    }

    @Test
    fun `prod met versleutelde JDBC-URL slaagt`() {
        assertDoesNotThrow {
            LdvEndpointValidator.validate("prod", "postgresql", "jdbc:postgresql://db:5432/ldv?sslmode=verify-full")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["staging", "acceptatie"])
    fun `ook staging en acceptatie vallen onder de TLS-eis`(profiel: String) {
        assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate(profiel, "postgresql", "jdbc:postgresql://db:5432/ldv")
        }
    }

    @Test
    fun `lege waarde faalt buiten dev en test, ongeacht de backend`() {
        assertThrows<IllegalArgumentException> { LdvEndpointValidator.validate("prod", "clickhouse", "") }
        assertThrows<IllegalArgumentException> { LdvEndpointValidator.validate("prod", "postgresql", "") }
    }

    @Test
    fun `de onveilige override vloeit door naar beide backends`() {
        // Dit is de hele reden dat de parameter bestaat: op ZAD is het logboek intern
        // zonder TLS bereikbaar.
        assertDoesNotThrow {
            LdvEndpointValidator.validate("prod", "clickhouse", "http://ch:8123", unsafeAllowPlaintext = true)
        }
        assertDoesNotThrow {
            LdvEndpointValidator.validate("prod", "postgresql", "jdbc:postgresql://db:5432/ldv", unsafeAllowPlaintext = true)
        }
    }

    @Test
    fun `override staat default uit, dus prod met plaintext blijft fail-fast`() {
        assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", "postgresql", "jdbc:postgresql://db:5432/ldv", unsafeAllowPlaintext = false)
        }
    }

    @Test
    fun `een onbekende dbms-waarde faalt fail-fast`() {
        val ex = assertThrows<IllegalArgumentException> {
            LdvEndpointValidator.validate("prod", "mysql", "jdbc:mysql://db:3306/ldv")
        }
        assertTrue(ex.message!!.contains("mysql"), "foutmelding moet de onbekende waarde tonen")
    }
}
```

- [ ] **Stap 6: Draai de tests en zie ze falen**

```bash
./mvnw clean test -pl libraries/fbs-common -Dtest=LdvEndpointValidatorTest
```

Verwacht: compilatiefout — `validate` neemt nog geen `dbms`.

- [ ] **Stap 7: Implementeer de dbms-keuze**

Vervang `LdvEndpointValidator.kt` door:

```kotlin
package nl.rijksoverheid.moz.fbs.common

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Borgt dat de verbinding naar het LDV (Logboek Dataverwerkingen) in productie-achtige
 * profielen versleuteld is. Persoonsgegevens (zoals dataSubjectId met BSN) mogen niet
 * onversleuteld over het netwerk — BIO 13.2.1 / AVG art. 32. In `dev` en `test` mag
 * plaintext voor lokale containers.
 *
 * De vorm van de check hangt af van de gekozen backend: een ClickHouse-endpoint is een
 * URL met een scheme, een PostgreSQL-verbinding een JDBC-URL waarin de versleuteling
 * uit de parameters blijkt. Beide keys worden geïnjecteerd met een lege default, zodat
 * de ongebruikte backend geen env-var hoeft te hebben.
 */
@ApplicationScoped
class LdvEndpointValidator(
    @param:ConfigProperty(name = DBMS_KEY, defaultValue = "clickhouse") private val dbms: String,
    @param:ConfigProperty(name = CLICKHOUSE_KEY, defaultValue = "") private val clickhouseEndpoint: String,
    @param:ConfigProperty(name = POSTGRESQL_KEY, defaultValue = "") private val postgresqlUrl: String,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
    // BEWUST ONVEILIG: zet de TLS-eis op het LDV-endpoint uit; rationale + voorwaarden in
    // de KDoc van OutboundTlsValidator.requireHttps. Default false (fail-closed).
    @param:ConfigProperty(name = UNSAFE_PLAINTEXT_KEY, defaultValue = "false")
    private val unsafeAllowPlaintext: Boolean,
) {

    fun onStartup(@Observes event: StartupEvent) {
        val endpoint = if (dbms.lowercase() == "clickhouse") clickhouseEndpoint else postgresqlUrl

        validate(profile, dbms, endpoint, unsafeAllowPlaintext)
    }

    companion object {
        const val DBMS_KEY = "logboekdataverwerking.dbms"
        const val CLICKHOUSE_KEY = "logboekdataverwerking.clickhouse.endpoint"
        const val POSTGRESQL_KEY = "logboekdataverwerking.postgresql.url"
        const val UNSAFE_PLAINTEXT_KEY = "fbs.ldv.unsafe-allow-plaintext-endpoint"

        fun validate(
            profile: String,
            dbms: String,
            endpoint: String,
            unsafeAllowPlaintext: Boolean = false,
        ) {
            when (dbms.lowercase()) {
                "clickhouse" ->
                    OutboundTlsValidator.requireHttps(profile, endpoint, CLICKHOUSE_KEY, unsafeAllowPlaintext)

                "postgresql", "postgres" ->
                    OutboundTlsValidator.requireJdbcTls(profile, endpoint, POSTGRESQL_KEY, unsafeAllowPlaintext)

                else -> throw IllegalArgumentException(
                    "$DBMS_KEY heeft een onbekende waarde '$dbms'; geldig zijn 'clickhouse' en 'postgresql'",
                )
            }
        }
    }
}
```

De aliassen `postgres`/`postgresql` volgen de wrapper, die beide accepteert.

- [ ] **Stap 8: Draai de tests en zie ze slagen**

```bash
./mvnw clean test -pl libraries/fbs-common
```

Verwacht: PASS, inclusief de al bestaande tests in de module.

- [ ] **Stap 9: Herzie de KDoc van LogboekContextDefaultFilter**

De klasse-KDoc noemt nu een `IllegalArgumentException` die de wrapper niet meer gooit.
Vervang de eerste alinea door:

```kotlin
/**
 * Zet safe defaults op LogboekContext vóór resource-code de echte `dataSubjectId` zet.
 * Zonder deze defaults levert een request dat vóór de resource sneuvelt — Bean Validation
 * wijst het af, of de service doet zelf span-management zoals `AanleverResource` — een
 * logregel op met lege betrokkene-velden, die de wrapper als onvolledige context
 * wegschrijft met een waarschuwing.
 *
 * Vroege [LDV_CONTEXT_DEFAULT_PRIORITY] zodat latere filters op een gevulde context rekenen.
 */
```

- [ ] **Stap 10: Draai de volledige module en commit**

```bash
./mvnw clean verify -pl libraries/fbs-common
git add libraries/fbs-common
git commit -m "feat(ldv): TLS-guard controleert de backend die daadwerkelijk gebruikt wordt"
```

---

## Taak 3: Fail-closed op het aanleverpad

**Bestanden:**
- Wijzig: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/aanlever/AanleverResource.kt:53-154`
- Wijzig: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/aanlever/AanleverResourceLdvSwallowTest.kt`
- Verwijder: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/aanlever/AanleverResourcePendingFailureLogTest.kt`

**Interfaces:**
- Gebruikt: `ProcessingHandler.enforceWriteAcknowledgement(throwOnFailure: Boolean = true)` en
  `nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder.clear()` uit wrapper 2.0.0.
- Gebruikt: `nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException` (een `RuntimeException`).

- [ ] **Stap 1: Schrijf de falende tests**

Vervang in `AanleverResourceLdvSwallowTest.kt` de test
`addLogboekContextToSpan-fout breekt response NIET` (regel 106) en
`IllegalStateException uit ProcessingHandler propageert WEL als 500 en span eindigt`
(regel 137) door:

```kotlin
    @Test
    fun `LDV-schrijffout laat het aanleveren falen in plaats van 201 te geven`() {
        // Fail-closed: een verwerking die niet in het logboek kwam, mag niet als
        // geslaagd worden gerapporteerd (LDV-acknowledgement-eis).
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        every {
            processingHandler.enforceWriteAcknowledgement(true)
        } throws LogboekWriteException("Logregel kon niet in het Logboek worden opgeslagen")

        assertThrows<LogboekWriteException> { resource.leverBerichtAan(geldigeRequest()) }

        verify { span.end() }
    }

    @Test
    fun `op het foutpad mag de acknowledgement de domeinfout niet maskeren`() {
        // Er propageert al een functionele fout; die moet de gebruiker bereiken, niet
        // een LDV-fout die er overheen komt.
        every { opslagService.slaBerichtOp(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("opslag stuk")
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(false) }

        val ex = assertThrows<IllegalStateException> { resource.leverBerichtAan(geldigeRequest()) }

        assertEquals("opslag stuk", ex.message)
        verify { processingHandler.enforceWriteAcknowledgement(false) }
        verify { span.end() }
    }

    @Test
    fun `de propagerende fout gaat mee naar de LDV-context`() {
        // Zonder dit overschrijft een optimistische OK uit de context de ERROR-status
        // en missen per-betrokkene child-logregels hun exception-attributen.
        val domeinfout = IllegalStateException("opslag stuk")
        every { opslagService.slaBerichtOp(any(), any(), any(), any(), any(), any(), any()) } throws domeinfout
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        assertThrows<IllegalStateException> { resource.leverBerichtAan(geldigeRequest()) }

        verify { processingHandler.addLogboekContextToSpan(span, any<LogboekContext>(), domeinfout) }
    }
```

Pas in de klasse-KDoc punt 1 aan: de swallow bestaat niet meer, de resource dwingt nu de
acknowledgement af. Punt 2 (`dataSubjectType`-parity) blijft ongewijzigd.

Werk in dezelfde klasse álle overige `justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }`-stubs
bij naar de drieargs-vorm `(any(), any<LogboekContext>(), any())`, en voeg aan elke test
die het succespad doorloopt `justRun { processingHandler.enforceWriteAcknowledgement(any()) }` toe.

Nieuwe imports: `nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException`.

Bestaat er in de klasse nog geen helper `geldigeRequest()`, gebruik dan de manier waarop
de bestaande tests hun `BerichtAanleverenRequest` bouwen.

- [ ] **Stap 2: Verwijder de test van de verdwenen swallow**

`AanleverResourcePendingFailureLogTest.kt` test uitsluitend de logregel die in de
`catch (IllegalArgumentException)`-tak stond. Die tak verdwijnt, dus het bestand ook:

```bash
git rm services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/aanlever/AanleverResourcePendingFailureLogTest.kt
```

- [ ] **Stap 3: Draai de tests en zie ze falen**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am -Dtest=AanleverResourceLdvSwallowTest
```

Verwacht: FAIL — `enforceWriteAcknowledgement` wordt nooit aangeroepen, dus de
`LogboekWriteException` komt niet naar buiten.

- [ ] **Stap 4: Implementeer fail-closed in de resource**

In `AanleverResource.leverBerichtAan`, direct vóór `val span = processingHandler.startSpan(...)`:

```kotlin
        // De recorder is thread-gebonden en deze resource doet zijn eigen span-beheer:
        // zonder legen kan een schrijffout van een eerder request op deze pooled thread
        // dit request laten falen.
        LogboekWriteFailureRecorder.clear()
```

Vervang `koppelLdvContextEnEindigSpan` (regel 118-154) door:

```kotlin
    private fun koppelLdvContextEnEindigSpan(span: Span, pendingFailure: Throwable?) {
        // foreign_operation.processor-attribuut equivalent aan LogboekInterceptor
        // — alleen koppelen als upstream een traceparent stuurde.
        val traceparent = httpHeaders.getHeaderString("traceparent")

        if (traceparent != null) {
            val processor = httpHeaders.getHeaderString("traceparent-processor")
            span.setAttribute(
                "dpl.core.foreign_operation.processor",
                FoutBeschrijving.saneer(processor),
            )
        }

        try {
            processingHandler.addLogboekContextToSpan(span, logboekContext, pendingFailure)
        } finally {
            span.end()
        }

        // Fail-closed: een aanlevering die niet in het logboek kwam, telt niet als
        // uitgevoerd. Propageert er al een functionele fout, dan mag een schrijffout die
        // niet maskeren — die fout moet de aanleverende partij bereiken.
        processingHandler.enforceWriteAcknowledgement(throwOnFailure = pendingFailure == null)
    }
```

De `log`-property van de klasse wordt hiermee ongebruikt; verwijder het veld en de
`org.jboss.logging.Logger`-import. Voeg toe:
`import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder`.

Werk ook de KDoc van de klasse bij: de alinea over "Geen `@Logboek`-annotatie" blijft,
maar noem erbij dat deze resource daardoor zelf de recorder leegt en de acknowledgement
afdwingt — werk dat de interceptor anders doet.

- [ ] **Stap 5: Draai de tests en zie ze slagen**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am -Dtest=AanleverResourceLdvSwallowTest
```

Verwacht: PASS.

- [ ] **Stap 6: Draai de volledige suite**

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am
```

Verwacht: groen. Faalt een andere aanlever-test op een niet-gestubde
`enforceWriteAcknowledgement`, voeg daar dezelfde `justRun`-stub toe.

- [ ] **Stap 7: Commit**

```bash
git add services/berichtenmagazijn
git commit -m "feat(ldv): aanleveren faalt als de logregel niet is opgeslagen"
```

---

## Taak 4: Publicatiepad — logregel vóór de levering

**Bestanden:**
- Wijzig: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/PublicatieClaimVerwerker.kt:76-257`
- Wijzig: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/PublicatieClaimVerwerkerEdgeCaseTest.kt`
- Wijzig: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/PublicatieClaimVerwerkerMissingBerichtTest.kt`
- Wijzig: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/publicatie/PublicatieClaimVerwerkerCooldownTest.kt`

**Interfaces:**
- Gebruikt: dezelfde wrapper-API als taak 3.
- Wijzigt intern: `verwerkGeslaagd` en `verwerkMislukt` verliezen hun `ldvContext`- en
  `span`-parameters; `verwerkOntbrekendBericht` beheert voortaan zijn eigen span.

- [ ] **Stap 1: Schrijf de falende test voor de volgorde**

Voeg toe aan `PublicatieClaimVerwerkerEdgeCaseTest.kt`:

```kotlin
    @Test
    fun `de logregel is bevestigd voordat er geleverd wordt`() {
        // Bevestigen na de levering zou betekenen dat een rollback op een LDV-fout een
        // al verstuurd CloudEvent opnieuw laat versturen.
        stubClaimMetBericht()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(any(), any()) } returns DownstreamResultaat.Geslaagd

        verwerker.verwerkEenClaim()

        verifyOrder {
            processingHandler.addLogboekContextToSpan(span, any<LogboekContext>(), any())
            span.end()
            processingHandler.enforceWriteAcknowledgement(true)
            downstreamClient.lever(any(), any())
        }
    }

    @Test
    fun `een LDV-schrijffout verhindert de levering`() {
        stubClaimMetBericht()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        every {
            processingHandler.enforceWriteAcknowledgement(any())
        } throws LogboekWriteException("Logregel kon niet in het Logboek worden opgeslagen")

        assertThrows<LogboekWriteException> { verwerker.verwerkEenClaim() }

        verify(exactly = 0) { downstreamClient.lever(any(), any()) }
        verify(exactly = 0) { claimer.markeerGeslaagd(any(), any()) }
    }

    @Test
    fun `elke fout uit addLogboekContextToSpan propageert en de span eindigt alsnog`() {
        // Er is geen swallow meer: een fout hier betekent dat het logboek niet gevuld is,
        // en dan mag er niet geleverd worden.
        stubClaimMetBericht()
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any())
        } throws IllegalStateException("ldv stuk")

        assertThrows<IllegalStateException> { verwerker.verwerkEenClaim() }

        verify { span.end() }
        verify(exactly = 0) { downstreamClient.lever(any(), any()) }
    }
```

`stubClaimMetBericht()` is een helper die je in de klasse toevoegt en die doet wat de
bestaande tests in hun `@BeforeEach`/testbody al doen: `claimer.claimNuVerwerkbaar`
retourneert één claim, `berichten.findByBerichtId` retourneert een bericht,
`processingHandler.startSpan` retourneert `span`, en `config.downstreams()` bevat het doel
van de claim. Hergebruik de bestaande opbouw uit deze testklasse; dupliceer geen tweede
variant.

Verwijder de bestaande test `LDV-config-fout wordt geslikt en status-write blijft staan`
(regel 118) — dat gedrag bestaat niet meer — en vervang `niet-IAE uit ProcessingHandler
propageert WEL en span end() draait alsnog` (regel 197) door de derde test hierboven.

Nieuwe imports: `io.mockk.verifyOrder`,
`nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException`.

- [ ] **Stap 2: Draai de tests en zie ze falen**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am -Dtest=PublicatieClaimVerwerkerEdgeCaseTest
```

Verwacht: FAIL op de volgorde — er wordt nu geleverd vóór de span eindigt.

- [ ] **Stap 3: Herorden verwerkClaim**

Vervang `verwerkClaim` (regel 76-128) door:

```kotlin
    /**
     * De logregel gaat eruit vóór de levering. Faalt het schrijven, dan gooit
     * [ProcessingHandler.enforceWriteAcknowledgement], rolt deze `REQUIRES_NEW`-transactie
     * en blijft de claim openstaan — er is dan niets geleverd, dus de retry is veilig.
     * Andersom zou een rollback ná een geslaagde levering een dubbele levering opleveren.
     *
     * De logregel legt daarmee de voorgenomen verstrekking vast, niet de uitkomst: de
     * LDV-schrijfactie loopt over een eigen JDBC-verbinding met een eigen commit, dus een
     * rollback haalt hem niet meer weg. De uitkomst van de levering blijft in de
     * claim-status en het applicatielog.
     */
    private fun verwerkClaim(claim: PublicatieClaim) {
        val bericht = berichten.findByBerichtId(claim.berichtId)

        if (bericht == null) {
            verwerkOntbrekendBericht(claim)
            return
        }

        val downstreamConfig = config.downstreams()[claim.doel.key]

        legVerstrekkingVast(claim, bericht, downstreamConfig)

        val nu = clock.instant()
        val event = cloudEventBuilder.bouw(bericht, claim.doel, nu)

        when (val resultaat = downstreamClient.lever(claim.doel, event)) {
            is DownstreamResultaat.Geslaagd -> verwerkGeslaagd(claim, nu)
            is DownstreamResultaat.Mislukt -> verwerkMislukt(claim, resultaat, nu, downstreamConfig)
        }
    }

    /**
     * Schrijft de logregel voor deze verstrekking en wacht op bevestiging. Status blijft
     * `UNSET`: het logboek registreert dat de gegevens verstrekt gaan worden, niet of de
     * downstream ze aannam.
     */
    private fun legVerstrekkingVast(
        claim: PublicatieClaim,
        bericht: Bericht,
        downstreamConfig: PublicatieConfig.Downstream?,
    ) {
        // De recorder is thread-gebonden; deze bean doet zijn eigen span-beheer op de
        // scheduler-thread, dus een fout van een eerdere claim moet er eerst af.
        LogboekWriteFailureRecorder.clear()

        val span = processingHandler.startSpan("publicatie-${claim.doel}", Context.current())
        val ldvContext = LogboekContext().apply {
            processingActivityId = config.verwerkingsregisterPubliceren()
        }

        zetLdvEnSpanAttributen(claim, bericht, downstreamConfig, ldvContext, span)

        try {
            processingHandler.addLogboekContextToSpan(span, ldvContext)
        } finally {
            span.end()
        }

        processingHandler.enforceWriteAcknowledgement()
    }
```

- [ ] **Stap 4: Geef verwerkOntbrekendBericht een eigen span**

Vervang `verwerkOntbrekendBericht` (regel 138-152) door:

```kotlin
    /**
     * Bericht weg tussen plan en verwerking. Een hard-delete neemt via CASCADE op
     * bericht_db_id ook de delivery-rij mee, dus die orphan-claim is onbereikbaar. Het
     * live pad hierheen is soft-delete: findByBerichtId filtert verwijderdOp IS NULL,
     * terwijl de delivery-rij blijft bestaan. dataSubject = berichtId (ontvanger
     * ontbreekt) zodat het LDV-record auditbaar blijft zonder lege subject-velden.
     *
     * Hier wordt niets verstrekt, dus de logregel gaat ná de statusmutatie de deur uit;
     * fail-closed kan hier zonder risico op een dubbele levering.
     */
    private fun verwerkOntbrekendBericht(claim: PublicatieClaim) {
        LogboekWriteFailureRecorder.clear()

        val span = processingHandler.startSpan("publicatie-${claim.doel}", Context.current())
        val ldvContext = LogboekContext().apply {
            processingActivityId = config.verwerkingsregisterPubliceren()
            dataSubjectId = claim.berichtId.toString()
            dataSubjectType = "BERICHT_ID_ONLY"
            status = StatusCode.ERROR
        }

        log.warnf(
            "Bericht ontbreekt voor claim claimId=%d berichtId=%s; markeer MISLUKT",
            claim.claimId, claim.berichtId,
        )
        claimer.markeerMislukt(claim.claimId, "Bericht niet gevonden", volgendePoging = null)
        span.setStatus(StatusCode.ERROR, "Bericht niet gevonden")

        try {
            processingHandler.addLogboekContextToSpan(span, ldvContext)
        } finally {
            span.end()
        }

        processingHandler.enforceWriteAcknowledgement()
    }
```

- [ ] **Stap 5: Strip de span-parameters uit de uitkomst-handlers**

`verwerkGeslaagd` en `verwerkMislukt` draaien nu ná `span.end()`; hun `span.setStatus`- en
`ldvContext.status`-regels zouden op een beëindigde span landen. Verwijder in beide
methodes de parameters `ldvContext: LogboekContext` en `span: Span` en alle regels die
daarop schrijven. De `log`-regels en de claim-statusmutaties blijven ongewijzigd.

In `verwerkGeslaagd` blijft de `catch (ex: IllegalStateException)`-tak bestaan, zonder de
twee status-regels:

```kotlin
        } catch (ex: IllegalStateException) {
            // 2xx ontvangen maar status niet bijgewerkt → gegarandeerd
            // duplicate-send volgende ronde; ops moet dit kunnen correleren.
            log.errorf(
                ex,
                "Duplicate-send venster: HTTP 2xx ontvangen maar markeerGeslaagd faalde; berichtId=%s doel=%s",
                claim.berichtId, claim.doel,
            )
            throw ex
        }
```

Is `io.opentelemetry.api.trace.Span` daarna nergens meer als parametertype in gebruik,
verwijder dan die import. `StatusCode` blijft nodig voor `verwerkOntbrekendBericht`. Voeg
toe: `import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder`.

- [ ] **Stap 6: Draai de tests en zie ze slagen**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am -Dtest='PublicatieClaimVerwerker*'
```

Verwacht: PASS. `PublicatieClaimVerwerkerMissingBerichtTest` en
`PublicatieClaimVerwerkerCooldownTest` hebben een drieargs-stub en een
`justRun { processingHandler.enforceWriteAcknowledgement(any()) }` nodig; hun assertions
over `markeerMislukt` en de warn-cooldown blijven ongewijzigd.

- [ ] **Stap 7: Draai de volledige suite**

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am
```

Verwacht: groen, inclusief de E2E-tests van de Publicatie Stream. Faalt daar een test
omdat `ProcessingHandler` een echte bean is met LDV uit, controleer dan of
`enforceWriteAcknowledgement` zonder geregistreerde fout stil terugkeert — dat hoort zo.

- [ ] **Stap 8: Commit**

```bash
git add services/berichtenmagazijn
git commit -m "feat(ldv): logregel van een publicatie gaat vooraf aan de levering"
```

---

## Taak 5: Integratietest tegen een echte PostgreSQL

**Bestanden:**
- Maak: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/ldv/LdvPostgresIntegrationTest.kt`

**Interfaces:**
- Gebruikt: de configkeys uit taak 1 en het aanleverpad uit taak 3.
- De test schrijft naar de Dev-Services-database die de module al gebruikt; er komt geen
  extra container bij.

- [ ] **Stap 1: Schrijf de test**

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Borgt dat de LDV-spans daadwerkelijk in PostgreSQL landen: schema-aanmaak, de insert
 * zelf en de jsonb-kolommen. De overige tests draaien met LDV uit, dus zonder deze test
 * wordt onze backend-configuratie pas in de demo of op ZAD voor het eerst uitgevoerd.
 */
@QuarkusTest
@TestProfile(LdvPostgresIntegrationTest.LdvAanProfile::class)
class LdvPostgresIntegrationTest {

    @Inject
    lateinit var dataSource: DataSource

    /**
     * LDV krijgt dezelfde database als de service. De waarden komen uit
     * `quarkus.datasource.*`, die Dev Services pas bij het opstarten invult; de
     * expressie wordt bij uitlezen geëxpandeerd, dus dit volgt de container vanzelf.
     */
    class LdvAanProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "logboekdataverwerking.enabled" to "true",
            "logboekdataverwerking.dbms" to "postgresql",
            "logboekdataverwerking.span-processor" to "simple",
            "logboekdataverwerking.write-failure-policy" to "fail-closed",
            "logboekdataverwerking.postgresql.url" to "\${quarkus.datasource.jdbc.url}",
            "logboekdataverwerking.postgresql.username" to "\${quarkus.datasource.username}",
            "logboekdataverwerking.postgresql.password" to "\${quarkus.datasource.password}",
            "logboekdataverwerking.postgresql.table" to "logboek_dataverwerkingen",
        )
    }

    @Test
    fun `een aanlevering schrijft een logregel in PostgreSQL`() {
        val ontvanger = "999993653"

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": { "type": "BSN", "waarde": "$ontvanger" },
                  "onderwerp": "LDV-integratietest",
                  "inhoud": "Inhoud",
                  "publicatietijdstip": "2026-08-06T10:00:00Z"
                }
                """.trimIndent(),
            )
            .post("/api/v1/berichten")
            .then()
            .statusCode(201)

        dataSource.connection.use { connectie ->
            connectie.prepareStatement(
                """
                SELECT name, status, attributes->>'dpl.core.processing_activity_id' AS activiteit,
                       attributes->>'dpl.core.data_subject_id_type' AS subject_type,
                       trace_id, span_id
                  FROM logboek_dataverwerkingen
                 WHERE name = 'aanleveren-bericht'
                """.trimIndent(),
            ).executeQuery().use { rijen ->
                assertTrue(rijen.next(), "er moet een logregel voor aanleveren-bericht zijn")
                assertEquals("BSN", rijen.getString("subject_type"))
                assertTrue(
                    rijen.getString("activiteit").startsWith("http"),
                    "processing_activity_id moet een absolute URI zijn",
                )
                assertTrue(rijen.getString("trace_id").isNotBlank(), "trace_id moet gevuld zijn")
                assertTrue(rijen.getString("span_id").isNotBlank(), "span_id moet gevuld zijn")
            }
        }
    }
}
```

Het BSN `999993653` is elfproef-geldig en wordt in andere tests al gebruikt. Neem de
publicatietijdstip- en afzenderwaarden over uit een bestaande aanlevertest als het
request-schema afwijkt.

De query controleert bewust niet op `data_subject_id`: dat veld bevat het BSN, en een
assertie erop zou de waarde in de testoutput zetten.

- [ ] **Stap 2: Draai de test**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am -Dtest=LdvPostgresIntegrationTest
```

Verwacht: PASS.

Faalt hij met een verbindingsfout op `localhost:5432` (de `%test`-default uit taak 1),
dan expandeert `${quarkus.datasource.jdbc.url}` niet in `getConfigOverrides`. Gebruik dan
in plaats daarvan een `QuarkusTestResourceLifecycleManager` die zelf een
`PostgreSQLContainer` start en de drie `logboekdataverwerking.postgresql.*`-waarden uit
die container teruggeeft; `org.testcontainers:postgresql` moet dan als test-dependency in
`services/berichtenmagazijn/pom.xml`.

- [ ] **Stap 3: Draai de volledige suite en controleer de coverage-gate**

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am
```

Verwacht: groen, JaCoCo ≥ 90% lijn-coverage.

- [ ] **Stap 4: Commit**

```bash
git add services/berichtenmagazijn/src/test
git commit -m "test(ldv): borg dat logregels echt in PostgreSQL landen"
```

---

## Taak 6: ClickHouse uit CI en deploy

**Bestanden:**
- Wijzig: `.github/workflows/deploy.yml`
- Wijzig: `.github/workflows/pin-consistency.yml`

- [ ] **Stap 1: Haal de ClickHouse-component uit de deploy**

In `.github/workflows/deploy.yml`:

1. Verwijder de env-regel `CLICKHOUSE_IMAGE: clickhouse/clickhouse-server:26.7` (regel 79)
   en de comment-alinea erboven die het geheugen-limit van ClickHouse toelicht.
2. Verwijder de vier `{"name": "clickhouse", "image": "${{ env.CLICKHOUSE_IMAGE }}"},`-regels
   uit de component-payloads (regels 352, 397, 499, 537).
3. Werk de kopregels 6-15 bij: `uitvraag (mpfb-8wh)` heeft nog `redis + uitvraag`,
   `magazijnen (mpfm-w3h)` nog `magazijna + magazijnb`, en de alinea over ClickHouse
   vervangen door: het LDV-logboek draait op de PostgreSQL die het platform per component
   levert; de interne verbinding is plaintext, waarvoor
   `FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT` aan blijft staan.
4. Werk de comment op regel 323 bij: alleen `redis` is nog een component zonder 2xx op het
   health-pad.

- [ ] **Stap 2: Haal de ClickHouse-pin uit de guard**

In `.github/workflows/pin-consistency.yml`, in de `for`-regel:

```bash
          for img in redis/redis-stack-server; do
```

Dit moet in dezelfde commit als stap 1: de guard faalt hard bij nul gevonden pins, dus
één van beide alleen laat CI rood.

- [ ] **Stap 3: Controleer dat er geen ClickHouse-referentie meer in CI staat**

```bash
grep -rn -i clickhouse .github/ compose.yaml
```

Verwacht: geen output.

- [ ] **Stap 4: Commit**

```bash
git add .github/workflows/deploy.yml .github/workflows/pin-consistency.yml
git commit -m "chore(ci): ClickHouse-component en -pin vervallen met de LDV-overstap"
```

---

## Taak 7: Documentatie

**Bestanden:**
- Wijzig: `README.md:28,33`
- Wijzig: `docs/operator-handleiding.md:21-22`
- Wijzig: `CLAUDE.md:139,238,246-247`

- [ ] **Stap 1: README**

Vervang op regel 28 en 33 "ClickHouse" door "PostgreSQL", zodat er staat:
`- Docker (voor lokale services: Redis, WireMock, PostgreSQL)` en
`# Start lokale services (Redis, WireMock magazijnen, PostgreSQL)`.

- [ ] **Stap 2: Operator-handleiding**

Vervang de twee env-var-rijen (regel 21-22) door:

```markdown
| `LDV_DBMS` | env var | Backend voor het logboek: `postgresql` (default) of `clickhouse` | Onbekende waarde = startup-fout |
| `LDV_POSTGRES_URL` | env var | JDBC-URL van het logboek; buiten dev/test verplicht `ssl=true` of `sslmode=require`/`verify-ca`/`verify-full` conform BIO 13.2.1 | Geen default in `%prod` — env var ontbreekt = startup-fout |
| `LDV_POSTGRES_USERNAME`, `LDV_POSTGRES_PASSWORD` | env var | LDV-credentials; geen prod-defaults | Idem |
| `LDV_CLICKHOUSE_ENDPOINT`, `CLICKHOUSE_USERNAME`, `CLICKHOUSE_PASSWORD` | env var | Alleen nodig bij `LDV_DBMS=clickhouse`; endpoint is dan `https://`-only | Ontbreekt = startup-fout zodra die backend gekozen is |
```

- [ ] **Stap 3: CLAUDE.md**

1. Regel 139: `docker compose up -d` start `Redis, WireMock, PostgreSQL`.
2. Regel 238: de omschrijving van `compose.yaml` idem.
3. Regels 246-247: vervang de twee `CLICKHOUSE_*`-rijen in de omgevingsvariabelentabel door:

```markdown
| `LDV_DBMS`             | `postgresql` | Backend voor het Logboek Dataverwerkingen (`postgresql` of `clickhouse`) |
| `LDV_POSTGRES_URL`     | per profiel | JDBC-URL van het logboek; buiten dev/test TLS-verplicht |
| `LDV_POSTGRES_USERNAME`, `LDV_POSTGRES_PASSWORD` | per profiel | LDV-credentials |
```

4. Werk in de sectie "Technische stack" de regel over persistentie bij: naast de
   berichtenmagazijn-database schrijft het logboek nu ook naar PostgreSQL.

- [ ] **Stap 4: Commit**

```bash
git add README.md docs/operator-handleiding.md CLAUDE.md
git commit -m "docs: PostgreSQL is de LDV-backend"
```

---

## Afronding

- [ ] **Stap 1: Draai alles nog één keer schoon**

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am
./mvnw clean test -pl services/berichtenuitvraag -am
./mvnw clean test -pl libraries/fbs-magazijnregister -am
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am
./mvnw detekt:check
```

- [ ] **Stap 2: Handmatige rookproef**

```bash
docker compose up -d
./mvnw quarkus:dev -pl services/berichtenmagazijn
```

Lever één bericht aan via de Bruno-collectie en controleer:

```bash
docker compose exec postgres-a psql -U berichtenmagazijn -d berichtenmagazijn \
  -c "SELECT name, status FROM logboek_dataverwerkingen;"
```

Verwacht: rijen voor `aanleveren-bericht` en `publicatie-…`.

Herhaal voor de uitvraag tegen `postgres-uitvraag`:

```bash
docker compose exec postgres-uitvraag psql -U ldv -d ldv_logging \
  -c "SELECT name, status FROM logboek_dataverwerkingen;"
```

- [ ] **Stap 3: Sync met main en open de PR**

```bash
git fetch origin main
git rebase origin/main
./mvnw clean verify -pl services/berichtenmagazijn -am
git push -u origin feature/736-ldv-postgresql
gh pr create --title "PostgreSQL als LDV-backend" --body-file pr-body.md
```

`main` heeft branch-protection met `strict=true`; sync dus vóórdat CI afrondt, anders
wacht je twee keer op de deploy-preview. Voeg geen reviewer toe.

Schrijf `pr-body.md` buiten de repo (bijvoorbeeld in een scratch-map) zodat het bestand
niet in de commit belandt. De beschrijving moet de handmatige stappen in Operations
Manager bevatten, want de deploy slaagt pas als die gedaan zijn:

1. Koppel de service `postgresql-database` aan component `uitvraag` in `mpfb-8wh`.
2. Vervang op `uitvraag`, `magazijna` en `magazijnb` de alias `LDV_CLICKHOUSE_ENDPOINT`
   door `LDV_POSTGRES_URL: jdbc:postgresql://$DATABASE_SERVER_HOST:5432/$DATABASE_DB`,
   `LDV_POSTGRES_USERNAME: $DATABASE_SERVER_USER` en
   `LDV_POSTGRES_PASSWORD: $DATABASE_PASSWORD`.
3. Verwijder de `clickhouse`-componenten uit `mpfb-8wh` en `mpfm-w3h`.
4. Laat `FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT=true` staan.

- [ ] **Stap 4: Volg CI**

```bash
gh pr checks <PR#>
```

Bij falen: `gh run view <id> --log-failed`.

## Nog te doen na deze branch

`compose.podman-hostnet.yaml` en `demo/podman-up.sh` bestaan niet op `main`; ze komen mee
met PR #163 en hebben dezelfde wijziging nodig (clickhouse eruit, `postgres-uitvraag`
erin, `LDV_CLICKHOUSE_ENDPOINT` → `LDV_POSTGRES_*` op de drie app-containers). Doe dat in
#163 zelf of in een rebase daarna. Hetzelfde geldt voor `docs/demo-runbook.md`, dat
ClickHouse in zijn infra-opsomming en poorttabel noemt.
