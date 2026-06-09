# Deployment-units: logisch vs. fysiek

Dit model beschrijft de **doel-architectuur**. De C4-containers zijn een *logische*
decompositie: ze drukken verantwoordelijkheden en koppelvlakken uit, niet de fysieke
deploybare eenheden. In de huidige implementatie (PoC) zijn meerdere logische containers
samengevoegd tot één deploybaar proces.

## Fysieke deploybare eenheden

De implementatie kent **twee** deploybare Quarkus-services plus **drie** in-process
gedeelde libraries die binnen die services meedraaien (geen eigen proces, geen netwerk-hop):

| Deploybare eenheid | Type | Bevat (logische containers / modules) |
|---|---|---|
| `services/berichtenmagazijn` | Quarkus-service | Berichtenmagazijn Aanlever API, Ophaal- en Beheer API, Bericht Validatie Service, Autorisatie Service, Publicatie Stream, Retentie Service, Dataopslag (PostgreSQL via Dev Services/compose) |
| `services/berichtenuitvraag` | Quarkus-service | Berichten Uitvraag Service, Aanmeld Service, en — in-process — Berichtensessiecache en Magazijnregister |
| `libraries/fbs-berichtensessiecache` | in-process library | Berichtensessiecache-container (Sessiecache-facade); draait binnen berichtenuitvraag |
| `libraries/fbs-magazijnregister` | in-process library | Magazijnregister-container; draait binnen berichtenuitvraag |
| `libraries/fbs-common` | in-process library | Gedeelde JAX-RS filters, exception mappers, identificatienummers, Profiel-client |

## Waarom logisch ≠ fysiek

- **Berichtensessiecache** is in het model een container met een eigen `Sessiecache`-facade,
  maar is bewust **geen losse service**: het is een Kotlin-`internal` library die in-process in
  berichtenuitvraag draait. De facade is het enige publieke koppelvlak; de interne werking
  (Redis-cache, magazijn-aggregatie) is compile-afgedwongen onzichtbaar. Relaties die in het
  model als CDI lopen (`CDI (in-process facade)`) zijn dus in-process method-calls, geen REST.
- **Bericht Validatie Service** en **Autorisatie Service** zijn logisch losse containers met een
  intern REST-koppelvlak (`REST API (intern, mTLS)`), maar in de PoC zijn het in-process
  CDI-beans binnen berichtenmagazijn. Zie [PoC-afwijkingen](03-poc-afwijkingen.md).

De logische container-grenzen blijven in het model staan omdat ze de **doel**-decompositie
beschrijven (en de koppelvlakken waarlangs later opgesplitst kan worden). De fysieke
groepering hierboven beschrijft wat vandaag daadwerkelijk als één proces deployt.
