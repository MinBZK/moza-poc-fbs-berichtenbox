**Status:** Uitgevoerd

# C4-model: apart berichtenmagazijn voor eigen-hostende organisaties (#530)

## Context

Het C4-model (`docs/architecture/workspace.dsl`) kende één berichtenmagazijn-systeem
(`decentraalMagazijn`, "Berichtenmagazijn (per deelnemende organisatie)"). Zowel
Organisatie A (host zelf) als Organisatie B (neemt af bij BBO) leverden berichten aan
diezelfde Aanlever API. Dat is verwarrend: het stelsel ondersteunt nadrukkelijk dat
organisaties hun eigen magazijn hosten, maar het model toonde maar één instantie. Ook
klopte de verbinding van een eigen-gehost magazijn naar de centrale Aanmeld Service niet:
die liep impliciet via de container van het BBO-magazijn.

Issue: https://github.com/MinBZK/MijnOverheidZakelijk/issues/530

## Ontwerpkeuze

Twee verschijningsvormen van het magazijn met identieke koppelvlakken:

1. **BBO-gehoste referentie-implementatie** — de bestaande, volledig uitgemodelleerde
   `softwareSystem` (hernoemd `decentraalMagazijn` → `bboMagazijn`, label "Berichtenmagazijn
   (BBO-gehost)"). Organisatie B neemt deze af. Var-naam `decentraalMagazijn` was misleidend:
   een BBO-gehoste instantie is juist niet decentraal.
2. **Eigen-gehost magazijn** — nieuw `softwareSystem` `eigenMagazijn` ("Berichtenmagazijn
   (eigen gehost)") als **black box**: geen interne containers, want het wordt door de
   organisatie zelf gehost en valt buiten het centrale stelsel. Tag `Extern Systeem` (grijs,
   dashed). Organisatie A levert hieraan.

Alternatief "volledig dupliceren van alle containers" is verworpen: veel DSL-duplicatie en
drukke diagrammen, zonder informatiewinst — de federatie-koppelvlakken zijn wat telt, niet
een tweede kopie van de interne componenten.

## Wijzigingen

- `orgA -> magazijnAanleverApi` vervangen door `orgA -> eigenMagazijn` (A levert aan z'n
  eigen magazijn, niet aan de BBO-aanlever-API). `orgB -> magazijnAanleverApi` blijft.
- Het eigen magazijn krijgt als black box **exact dezelfde grensrelaties** als het BBO-magazijn
  — identieke beschrijving en techniek, enkel op systeem- i.p.v. containerniveau. Het enige
  verschil is de aanleverende organisatie (orgA i.p.v. orgB). De volledige set:
  - `orgA -> eigenMagazijn` ("Levert berichten aan").
  - `uitvraagOphaalService -> eigenMagazijn` ("Haalt berichtenlijst, incl. berichtinhoud en
    attributen, of bijlagen op").
  - `uitvraagBeheerService -> eigenMagazijn` ("Beheert berichtstatus").
  - `sessiecacheMagazijnClient -> eigenMagazijn` ("Haalt berichten op").
  - `eigenMagazijn -> aanmeldService` ("Meldt nieuw bericht aan") — de kern van het issue.
  - `eigenMagazijn -> notificatieService` ("Stuurt bericht-events door", Async).
  - `eigenMagazijn -> profielService` ("Controleert of de ontvanger toestemming gegeven heeft").
- Het eigen magazijn is een **extern systeem** (tag `Extern Systeem` → grijs, dashed): het wordt
  door de organisatie zelf gehost en valt buiten het centrale stelsel.
- Views: var-rename in `systemContext`/`container`-views; `SystemLandscape` krijgt
  `exclude "eigenMagazijn -> berichtenUitvraagSysteem"` (spiegelt de bestaande exclude voor
  het BBO-magazijn — verwijdert de dubbele, geaggregeerde lijn maar houdt één verbinding
  zichtbaar).

## Verificatie

- Alle relatie-identifiers resolven naar bestaande model-elementen (grep-controle).
- DSL-render via `structurizr-site-generatr` gebeurt in CI (`architecture.yml`): de PR-preview
  genereert de statische site; faalt de generatie, dan faalt de check. Lokale docker-render
  was in deze omgeving niet beschikbaar.
