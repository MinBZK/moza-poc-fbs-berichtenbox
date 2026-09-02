# Reviewbevindingen op de ZAD-runbooks verwerken

**Status:** Uitgevoerd

## Context

De review van `demo/environment/zad-demo` op `feature/storingsknoppen-op-zad` (PR #250) leverde 30
bevindingen op: 12 hoog, 12 medium, 6 laag. De vier bestanden — `README.md`,
`magazijn-simulator.md`, `verify-zad.md` en `proeftuin-component.sh` — zijn geen proza maar
handwerk dat iemand zonder voorkennis in volgorde uitvoert. Elke onjuistheid erin wordt een
handeling.

Drie oorzaken lopen door bijna alle bevindingen heen, en die zijn leidend voor de aanpak:

1. **Dezelfde waarheid staat op drie plekken en loopt uiteen.** De alias-mutabiliteit, de status van
   de simulator, de volgorde-eis rond de merge en de rationale achter de proeftuin-aliassen staan
   elk twee tot vier keer opgeschreven, telkens net anders. Eén kopie werd bijgewerkt, de andere
   niet.
2. **Verificatiestappen die groen zijn zonder iets te toetsen.** Een `curl` zonder statuscontrole,
   een `grep` op een logtekst die niet bestaat, een herstart-toets waarvan de premisse onwaar is, en
   nergens een tak voor `replicas: 0` — de faalwijze die de ZAD-kennis in `CLAUDE.md` als de
   misleidendste aanwijst.
3. **Schrijvende handelingen zonder vangnet.** De vier OM-`PATCH`-calls hebben geen `-f` en geen
   taak-afwachting, terwijl `.github/scripts/cross-domain-preview.sh` in dezelfde repo precies laat
   zien hoe het wel moet.

## Aanpak

Per bestand, met de bron-van-waarheid-keuze expliciet:

| Onderwerp | Waar de waarheid komt te staan | Waar een verwijzing komt |
|---|---|---|
| Alias-mutabiliteit | `README.md` §"Waarom dit handwerk is" | `magazijn-simulator.md`, `proeftuin-component.sh` |
| Status van de simulator | `magazijn-simulator.md` statusregel | `README.md` (zonder eigen bewering) |
| Volgorde-eis vóór de merge | `proeftuin-component.sh` header | `README.md` §7 |
| Rationale proeftuin-aliassen | `README.md` §7 | `proeftuin-component.sh` (kort) |
| Destructief verwijderen | `README.md` §"Waarom dit handwerk is" | de andere drie |

## Twee punten die lokaal niet te verifiëren zijn

Beide krijgen een fix die de veilige kant kiest, met de aanname expliciet in de tekst:

- **Precedentie tussen `zadctl alias` en `zadctl env`.** `README.md` stelt zelf vast dat het
  user-secret ná het platform-secret komt en bij gelijke sleutels wint. Daaruit volgt dat de lege
  `TOXIPROXY_*_URL` uit stap 3 de alias uit stap 6 overschrijft. Stap 6 krijgt daarom een
  `env unset` vóór de aliassen — die is onschadelijk als de precedentie andersom blijkt, en
  noodzakelijk als hij klopt.
- **Het Quarkus-profiel van de uitvraag op ZAD.** Of `RedisVerbindingValidator` daar `rediss://`
  afdwingt is van buiten niet vast te stellen. De belofte "houdt het schema dat er staat" is
  onafhankelijk daarvan onjuist — het commando schrijft `redis://` — dus die zin wordt gecorrigeerd
  en de TLS-eis wordt als te controleren voorwaarde benoemd, niet als vaststaand feit.

## Verificatie

- `.github/scripts/test-wijzigingsfilter.sh` en de shellcheck-container over het gewijzigde script.
- `proeftuin-component.sh plan` kan niet zonder ZAD-toegang gedraaid worden; de plan-modus is
  daarom met `bash -n` en een gemockte `zadctl` op de PATH doorlopen, zodat elk pad minstens één
  keer geraakt is.
- Handmatig nalopen dat elk bestand nog precies één keer beweert wat de tabel hierboven toewijst.
