# Aanpak en keuzes van de PoC

Een Federatief Berichtenstelsel werkt anders dan een centraal beheerde
berichtenbox. Het doel van de PoC is om een omgeving op te zetten waarmee we
verschillende experimenten kunnen uitvoeren. Die zetten we enerzijds vanuit
gebruikersperspectief op, en anderzijds vanuit de techniek. We beginnen met een
eerste versie die fungeert als interactieve praatplaat. Daarna maken we
aanpassingen om verschillende situaties te doorlopen, al dan niet naast elkaar.

Dit document beschrijft **hoe** we deze Proof of Concept (PoC) aanvliegen en
**welke keuzes** we aan het begin hebben gemaakt — de context die het C4-model
(zie [`docs/architecture/`](architecture/)) zelf niet geeft. Het C4-model toont
*wat* er is; deze pagina geeft het *waarom* en de herkomst van de keuzes.

## 1. Hoe we dit project aanvliegen

- **Vertrekpunt: inventarisatie van de BBO.** We zijn begonnen met een
  inventarisatie van hoe de Berichtenbox voor Burgers en Ondernemers (BBO) wordt ontwikkeld,
  met de vraag hoe we die *federatief* en *geschikt voor ondernemers* kunnen
  maken. Die blik — een federatief stelsel waarin ook zakelijke ontvangers passen
  — bepaalt de richting van de doel-architectuur en daarmee de keuzes hieronder.
  Daarnaast hebben we met architecten van Logius afgestemd of de plannen stroken
  met de visie van het Federatief Berichtenstelsel (FBS).

- **PoC binnen MOZa, met de doel-architectuur als kompas.** Het C4-model
  beschrijft de doel-architectuur van het FBS. De PoC werkt toe naar dat
  **volledige** doel — het is geen bewust afgebakende deelverzameling. Wat nu nog
  niet gerealiseerd is, houden we expliciet bij in
  [PoC-afwijkingen](architecture/workspace-docs/03-poc-afwijkingen.md).

- **Aansluitvoorwaarden van Logius als uitgangspunt.** We volgen de
  aansluitvoorwaarden van Logius rondom beheer en de relevante Logius-stelsels en -standaarden als
  randvoorwaarde voor de architectuur. De aansluitvoorwaarden voor beheer moeten nog **centraal in
  de MOZa-documentatie** worden belegd.

- **Spec-driven / OpenAPI-first.** De OpenAPI-spec per service is de bron van
  waarheid. Server-interfaces worden uit de spec gegenereerd (`jaxrs-spec`,
  `interfaceOnly=true`); de implementatie kan niet ongemerkt van het contract
  afwijken, want compilatie faalt dan. Uitgaande clients en foutresponses worden
  tegen dezelfde spec gevalideerd.

- **AI onder menselijke regie, naleving van standaarden geborgd met overheidsskills.**
  De code is grotendeels gegenereerd met een AI-assistant onder menselijke
  eindverantwoordelijkheid; de
  [overheidsskills](https://github.com/developer-overheid-nl/skills-marketplace)
  helpen het resultaat aantoonbaar in lijn te brengen met de Nederlandse
  overheidsstandaarden. De volledige verantwoording staat in
  [`ai-verantwoording.md`](ai-verantwoording.md).

- **C4 als levend doelmodel.** De architectuur is vastgelegd in een C4-model met
  Structurizr ([`docs/architecture/workspace.dsl`](architecture/workspace.dsl)) en
  groeit mee met de inzichten uit de PoC.

## 2. Standaarden die de keuzes sturen

Een groot deel van de architectuur volgt rechtstreeks uit door Logius beheerde en
door het Forum Standaardisatie aanbevolen standaarden. Hieronder kort: per
standaard *welke keuze die stuurde* en *waarom die hier van toepassing is*, met
een bronlink.

| Standaard | Keuze die eruit volgt | Waarom van toepassing |
|---|---|---|
| [API Design Rules (ADR)](https://publicatie.centrumvoorstandaarden.nl/api/adr/) | OpenAPI-first, `/api/v1`-prefix, camelCase JSON, `application/problem+json` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)), HAL `_links`, `API-Version`-header; gevalideerd met de Spectral-linter | Verplichte standaard voor REST-API's bij de Nederlandse overheid |
| [OIN-Stelsel](https://www.logius.nl/diensten/oin) | Deelnemende organisaties worden geïdentificeerd via hun OIN; het `magazijnId` dat door het systeem stroomt *is* de afzender-OIN (publiek, geen PII) | Landelijke identificatie van overheidsorganisaties; basis voor het FSC PeerID |
| [Digikoppeling](https://www.logius.nl/diensten/digikoppeling) (REST-API, Identificatie & Authenticatie) | Magazijn-koppelvlakken als Digikoppeling REST-API; OIN uit het `subject.serialNumber` van het PKIoverheid-certificaat | Verplichte koppelvlakstandaard voor gegevensuitwisseling tussen overheden |
| [FSC (Federated Service Connectivity)](https://fsc-standaard.nl/) | mTLS-transportlaag met ondertekende contracten als doel-architectuur; in de PoC nog plain HTTP (zie PoC-afwijkingen) | Opvolger van NLX; verplicht transport bij Digikoppeling REST-API |
| OAuth 2.0 / OpenID Connect — NL GOV-profiel | Token-uitgifte door de Interactielaag en JWT-bearer-tokenvalidatie in de Berichten Uitvraag Service (doel-architectuur) | Standaard voor authenticatie/autorisatie bij de overheid |
| [NL GOV CloudEvents-profiel](https://vng-realisatie.github.io/NL-GOV-profile-for-CloudEvents/) | Notificatie-forwarding via CloudEvents (structured mode, `source` als OIN-URN) | Standaardprofiel voor notificaties tussen overheidsorganisaties |
| [Logboek Dataverwerkingen (LDV)](https://logius-standaarden.github.io/logboek-dataverwerkingen/) (NEN 7513) | Verwerkingenlogging via OpenTelemetry/OTLP voor elk component dat persoonsgegevens verwerkt | AVG/GDPR-transparantie; vastgelegd in de LDV-standaard |
| [NeRDS](https://github.com/MinBZK/nerds) / [BIO](https://www.digitaleoverheid.nl/overzicht-van-alle-onderwerpen/cybersecurity/kaders-voor-cybersecurity/baseline-informatiebeveiliging-overheid/) / AVG | Functionele package-structuur, security-headers en -scans, geen persoonsgegevens in de PoC | Richtlijnen voor verantwoorde overheidssoftware |

> De canonieke bronnen en versies horen — net als de aansluitvoorwaarden —
> centraal in de MOZa-documentatie te worden vastgelegd. De links hierboven zijn
> de ingang; de exacte profielversies staan als `properties` in
> [`workspace.dsl`](architecture/workspace.dsl).

## 3. Startkeuzes (techniek)

- **Quarkus 3 op Java 21.** Lichtgewicht, cloud-native JVM-framework met sterke
  OpenAPI- en testondersteuning. Dit is de standaard voor Logius.

- **Kotlin — achteraf niet de juiste keuze.** We zijn met Kotlin begonnen, omdat
  dit een evolutie is van Java, en verder op de JVM draait en compleet compatible
  is met Java. Echter is Kotlin nu niet opgenomen in de aansluitvoorwaarden van
  Logius. Mocht (een deel) van deze PoC overgenomen worden door Logius zal hier nog
  een vertaalslag gedaan moeten worden. We verwachten dat dit vrij eenvoudig te doen
  is, omdat Java en Kotlin tegelijk gebruikt kunnen worden, en een vertaling onder
  de huidige Kotlin-tests gedaan kan worden. Gezien de PoC-status staat beheer nog
  niet op de roadmap, dus we hebben deze migratie niet ingepland.

- **Maven-monorepo.** Parent-POM met modules voor de services en gedeelde
  libraries; één build, gedeelde conventies.

- **Logisch ≠ fysiek.** De C4-containers zijn een *logische* decompositie van
  verantwoordelijkheden, niet de fysieke deploybare eenheden. In de PoC draaien
  meerdere logische containers in één Quarkus-proces. Zie
  [Deployment-units](architecture/workspace-docs/02-deployment-units.md).

- **PostgreSQL en Redis.** De doel-architectuur liet de opslagtechniek vrij; de
  PoC kiest PostgreSQL (berichtenmagazijn, met een database-outbox voor publicatie)
  en Redis (sessiecache). Onderbouwing per onderdeel staat in de
  [plannen](plans/).

## 4. Eén openstaande uitdaging: authenticatie & autorisatie over de keten

De PoC wil de volledige doel-architectuur realiseren — niet slechts een
deelverzameling. Eén samenhangend deel stellen we voorlopig uit: **authenticatie
en autorisatie over de hele MOZa-keten**. Daarvoor is nog geen oplossingsrichting;
zolang die er niet is, parkeren we dat deel en werken we door aan de onderdelen en
onderzoeksvragen waar we wél controle over hebben.

De nog-niet-gerealiseerde onderdelen hangen alle aan dit ene vraagstuk:
organisatievertrouwen en -transport via FSC (mTLS, ondertekende contracten),
token-uitgifte en -validatie (OIDC NL GOV), pseudonimisering (BSNk) en
keten-autorisatie (AuthZEN PEP/PDP). De actuele stand staat in
[PoC-afwijkingen](architecture/workspace-docs/03-poc-afwijkingen.md).

Gebruik in pilot of productie vereist daarnaast aanvullende toetsing (o.a. BIO en
DPIA); zie de [AI-verantwoording](ai-verantwoording.md).

## Verwijzingen

- [C4-model (Structurizr)](architecture/workspace.dsl) — doel-architectuur
- [Deployment-units: logisch vs. fysiek](architecture/workspace-docs/02-deployment-units.md)
- [PoC-afwijkingen t.o.v. de doel-architectuur](architecture/workspace-docs/03-poc-afwijkingen.md)
- [Verantwoording inzet van generatieve AI](ai-verantwoording.md)
- [Projectoverzicht (README)](../README.md)
- [Issue #717 — Architectuurkeuzes vastleggen](https://github.com/MinBZK/MijnOverheidZakelijk/issues/717)
