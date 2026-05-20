# Ontwerp: AI-verantwoording voor de PoC

**Status:** Concept
**Issue:** [MinBZK/MijnOverheidZakelijk#431](https://github.com/MinBZK/MijnOverheidZakelijk/issues/431) — Verantwoording AI-gebruik toevoegen
**Datum:** 2026-05-20

## Context

Deze repository is een Proof of Concept (PoC) voor de Berichtenbox binnen het
Federatief Berichtenstelsel (FBS). De code is **grotendeels gegenereerd met
generatieve AI (Claude Code, Anthropic)** en **volledig menselijk gereviewed**.

Issue #431 vraagt om een disclaimer/verantwoording over het gebruik van AI,
naar voorbeeld van de
[AI skills-marketplace van developer.overheid.nl](https://github.com/developer-overheid-nl/skills-marketplace).
Dat voorbeeld kent een korte `DISCLAIMER.md` plus een uitgebreide
`docs/verantwoording.md` die het experiment toetst tegen hoofdstuk 4 van de
*Overheidsbrede handreiking verantwoorde inzet van generatieve AI*.

**Verschil met het voorbeeld:** de skills-marketplace genereert *skills* en
wegwerp-testcode; deze PoC genereert *applicatiesoftware*. De verantwoording
wordt daarop toegesneden.

## Vastgestelde feiten (input gebruiker)

- **AI-assistant:** uitsluitend Claude Code (Anthropic). Eén leverancier →
  vendor lock-in als expliciet aandachtspunt.
- **Codestatus:** PoC; code grotendeels met AI gegenereerd én volledig menselijk
  gereviewed. Pilot/productie valt **buiten de huidige scope** en zou
  aanvullende toetsing vereisen (BIO, DPIA).
- **Persoonsgegevens:** geen; uitsluitend fictieve/testdata. AVG/DPIA als
  aandachtspunt voor toekomstig gebruik.

## Te leveren wijzigingen

### 1. `DISCLAIMER.md` (repo-root) — kort

Beknopt, in de stijl van het skills-marketplace-voorbeeld. Kopjes:

- **Inleiding** — experimentele PoC; software grotendeels gegenereerd met
  generatieve AI (Claude Code) en volledig menselijk gereviewed.
- **Geen officiële dienst** — dit is geen productiesysteem en geen officiële
  Berichtenbox; geen rechten aan te ontlenen.
- **AI-output is herkenbaar** — met AI gegenereerde bijdragen zijn gemarkeerd
  (commit-trailer `Co-Authored-By`), en menselijk gereviewed vóór merge.
- **Gebruik van generatieve AI** — verwijzing naar het
  [Overheidsbreed standpunt voor de inzet van generatieve AI](https://open.overheid.nl/documenten/bc03ce31-0cf1-4946-9c94-e934a62ebe73/file).
- **Geen garantie** — aangeboden zonder garantie van volledigheid, juistheid of
  actualiteit; gebruik op eigen risico.
- **Volledige verantwoording** — link naar `docs/ai-verantwoording.md`.

### 2. `docs/ai-verantwoording.md` — volledig

Toetsing tegen hoofdstuk 4 van de
[Overheidsbrede handreiking verantwoorde inzet van generatieve AI](https://open.overheid.nl/documenten/9c273b71-cebb-4e11-b06f-fa20f7b4b90e/file).
Structuur:

**Beschrijving van de PoC en de rol van AI**
- Wat de PoC is (Berichtensessiecache, Berichtenmagazijn, FBS-context).
- Rol van AI: codegeneratie en ondersteuning bij review/refactor met Claude Code.
- **Menselijke review:** alle AI-bijdragen worden volledig door ontwikkelaars
  gereviewed vóór merge (PR-workflow, CODEOWNERS, CI met o.a. CodeQL/Scorecard).
- Geen persoonsgegevens; uitsluitend fictieve/testdata.
- Scope-grens: pilot/productie buiten huidige scope → vereist aanvullende
  toetsing (BIO, DPIA, beveiligingseisen).

**Verantwoording per stappenplan (handreiking, hoofdstuk 4)**

1. **Doel en toepassingsgebied** — onderzoeken of een Berichtenbox-PoC
   verantwoord met generatieve AI te bouwen is volgens overheidsstandaarden
   (NL API Design Rules, FBS, Logius-standaarden).
2. **Mensen en vaardigheden** — ontwikkelaars met domein- en
   standaardenkennis; AI als gereedschap, mens verantwoordelijk.
3. **AI-governance** — opdracht BZK; AI-output gemarkeerd (`Co-Authored-By`);
   transparantie via openbare GitHub-repo (EUPL-1.2); volledige PR-review.
4. **Risicoanalyse:**
   - a. **EU AI-verordening** — wij bouwen geen AI-systeem maar gebruiken
     Claude Code als gereedschap; verplichtingen liggen primair bij de aanbieder
     (Anthropic, ondertekenaar GPAI Code of Practice).
   - b. **AVG/DPIA** — geen persoonsgegevens; alleen fictieve/testdata.
     Toekomstig gebruik vereist eigen AVG-beoordeling.
   - c. **BIO/security** — PoC, niet in productie; pilot/productie vereist
     toetsing aan beveiligingseisen. Bestaande CI: CodeQL, Scorecard,
     dependency-/secret-aandacht.
   - d. **Datadeling met AI-aanbieder** — geen vertrouwelijke data; opt-out
     voor modeltraining; licenties via overheidsorganisatie.
   - e. **Schijnzekerheid** — AI is hulpmiddel, geen compliance-garantie;
     officiële standaarden zijn leidend; team blijft verantwoordelijk.
   - f. **Codekwaliteit** — geborgd via volledige menselijke review,
     teststrategie (≥90% coverage, JaCoCo), spec-driven OpenAPI-first,
     en CI-controles.
   - g. **Auteursrecht** — brondocumentatie/standaarden gecontroleerd op
     gebruik als input; bronnen vermeld.
   - h. **Uitlegbaarheid** — code en architectuur (C4/Structurizr) openbaar en
     leesbaar; beslissingen vastgelegd in `docs/plans/`.
   - i. **AI-geletterdheid** — kennisdeling binnen het team; verantwoording
     openbaar.
5. **Inkopen/bouwen:**
   - a. **Vendor lock-in** — momenteel alleen Claude Code; aandachtspunt voor
     vervolg, code blijft leverancier-onafhankelijk (Quarkus/Kotlin, geen
     AI-runtime-afhankelijkheid).
   - b. **Keuze AI-assistant** — Claude Code (Anthropic), GPAI-ondertekenaar.

### 3. `README.md` — sectie "AI-verantwoording"

Korte sectie nabij Licentie/Governance met links naar `DISCLAIMER.md` en
`docs/ai-verantwoording.md`.

## Buiten scope

- Geen wijzigingen aan applicatiecode of `CLAUDE.md`.
- Geen pilot-/productietoetsing (BIO, DPIA) — alleen benoemd als vervolg.

## Verificatie

- Markdown rendert correct; alle interne links (`DISCLAIMER.md`,
  `docs/ai-verantwoording.md`) en externe links resolven.
- Inhoud consistent met vastgestelde feiten (één assistant, geen
  persoonsgegevens, PoC-scope).
- `markdownlint` (indien actief in CI) slaagt.
