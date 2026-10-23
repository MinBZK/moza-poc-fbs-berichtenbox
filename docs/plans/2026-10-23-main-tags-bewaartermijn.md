# Bewaartermijn voor `main-<sha>`-images

**Status:** Uitgevoerd

Geen eigen issue: een vervolg op MinBZK/MijnOverheidZakelijk#1017 (de periodieke opsporing van
preview-resten), en een deelvraag van het releasebeheer in MinBZK/MijnOverheidZakelijk#310.

## Context

Elke push naar main bouwt alle eigen images en tagt ze `main-<sha7>`. Niets ruimde die op. Op
2026-10-23 stonden er per package tot 215, de oudste van 2026-07-01, met een aanwas van ongeveer
zeventig per maand.

In gebruik is alleen de nieuwste: `test` en de `fsc-*`-peers rollen bij elke push de nieuwe tag uit.
Na een mislukte uitrol kan een component op een eerdere versie blijven staan.

De groei kost ook iets. De `ongetagd`-stap in `preview-resten.yml` vraagt van elke getagde versie het
mediatype op, zodra er iets op te ruimen valt. Die stap wordt dus met elke merge trager, en zou op
den duur zijn time-out van 30 minuten halen.

## Ontwerpkeuzes

- **Waar.** Een stap `main-tags` in `preview-resten.yml`, naast `ongetagd`. Die workflow heeft het
  ritme, de meldroute (één issue) en de nameting al. Er komt geen aparte workflow met een eigen set
  aannames bij.
- **Volgorde.** `main-tags` draait vóór `ongetagd`. Elke versie die hier weggaat, scheelt daar een
  aanvraag. `ongetagd` draait ook als `main-tags` omviel.
- **Selectie.** Een versie gaat alleen weg als hij aan drie voorwaarden voldoet:
  - Hij draagt uitsluitend tags van de vorm `main-[0-9a-f]{7}`. Een versie met daarnaast een
    andere tag blijft staan, net als een tag die alleen op een main-tag lijkt.
  - Hij is minstens 14 dagen oud.
  - Hij hoort niet bij de 10 nieuwste van die versies in het package.
- **Waarom die twee grenzen.**
  - Het aantal beschermt wat `test` draait, ook na weken zonder merge.
  - De termijn houdt recente versies bij de hand om naar terug te gaan.
  - Wat ouder is, is uit de commit opnieuw te bouwen.
- **Plafond.** Hoogstens 150 verwijderingen per run, over alle packages samen, naast de 100 van
  `ongetagd`. De achterstand bij invoering was 955 versies. Die is in ongeveer zeven werkdagen weg,
  binnen de API-limiet van `GITHUB_TOKEN`.
- **Melden.**
  - Een geweigerde verwijdering wordt gemeld als `main-tags-mislukt`.
  - Een package dat niet te lezen is, wordt gemeld als `onbepaald`.
  - Een achterstand die op het plafond wacht, wordt niet gemeld. Die wordt elke run kleiner, en
    een melding erover zou bij invoering wekenlang openstaan zonder dat er iets te doen is.
- **Nameten.** Verwijderen en nameten op de versielijst gebeurt in één gedeelde functie
  `verwijder_versies`, voor `ongetagd` en `main-tags` allebei.

## Verificatie

- `test-preview-resten.sh` dekt deze gevallen:
  - selectie (gedeelde tag, ongetagd, op main lijkende tags, twee pagina's)
  - de grens van 14 dagen op de seconde
  - de 10 nieuwste na een lange pauze
  - plafond en uitgesteld
  - nameting en een onleesbare lijst
  - de meldregels
  - de volgorde in de workflow

  Tien mutaties zijn alle tien gevangen.
- Een droge run tegen ghcr (zonder `VERWIJDER`) telde de verwijderbare versies per package:

  | Package | Te verwijderen |
  | --- | --- |
  | `fbs-berichtenuitvraag` | 181 |
  | `fbs-berichtenmagazijn` | 181 |
  | `fbs-externe-stubs` | 181 |
  | `fbs-fsc-contract-bootstrap` | 109 |
  | `fbs-demo-console` | 84 |
  | `fbs-demo-profiel` | 79 |
  | `fbs-demo-personas` | 71 |
  | `fbs-magazijn-simulator` | 69 |
  | `fbs-stelselregister` | 0 |
