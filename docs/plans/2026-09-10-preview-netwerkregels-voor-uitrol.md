# Preview-netwerkregels klaarzetten vóór de eerste uitrol

**Status:** Uitgevoerd

## Aanleiding

De eerste deploy van een nieuwe magazijnen-preview mislukt structureel. Operations Manager (OM)
meldt `mpfm-w3h-pr-<n> (pr-<n>): timed out after 300s waiting for sync`; een herdraai slaagt daarna
meestal wel. Waargenomen bij `pr-302` (twee keer), `pr-303`, `pr-307` en `pr-308`. `pr-303` draagt
nog steeds de netwerkregels van `test`, en die preview heeft dus nooit gewerkt.

Op `pr-308` (10 september) meldde de console in de logs elke twee seconden
`Redis connection health check: DOWN`, en elke verbinding naar de Toxiproxy's in de twee andere
projecten eindigde op `connection timed out`. OM zette de deployment op `Degraded`.

## Oorzaak

Twee eigenschappen van OM, geverifieerd in `RijksICTGilde/RIG-Cluster`:

1. **Een cross-domain-regel wordt opgelost op het moment dat OM de deployment rendert.** Noemt hij
   een peer-deployment die dan nog niet bestaat, dan slaat de resolver hem over
   (`cross_domain_access/resolve.py`: "deployment ... not found in project ..., skipped"). Niets
   rendert hem opnieuw zodra de peer er wél is.
2. **OM wacht na elke uitrol 300 s op een gezonde Argo-sync** (`project_manager.py`); een pod die
   niet ready wordt, laat de taak dus falen.

De workflow deed het in de verkeerde volgorde. De uitvraag en de externe stubs zetten om 08:37 hun
inbound-regels voor `pr-308`, terwijl `pr-308` in het magazijnen-project pas om 09:11 bestond. Die
policies zijn nooit gerenderd. Daarnaast erft een gekloonde preview de uitgaande regels van `test`,
met `test` als peer; die werden pas omgezet ná een geslaagde deploy, en die deploy kon niet slagen
omdat de console zijn Redis niet bereikte. Een refresh van `pr-308` in de twee andere projecten
(09:39) renderde de ontbrekende policies alsnog, waarna de console binnen een minuut gezond was.

De parallelle uitrol uit `2026-09-09-preview-uitrol-parallel.md` ging uit van "die eis geldt voor
het zetten van de regels, niet voor de deploy". Dat klopt niet: de gezondheid van de console hangt
aan die regels, en de inbound-kant werd al vóór het bestaan van de peer gezet.

## Overwogen

- **Redis uit de readiness van de console halen.** Laat de deploy slagen, maar verbergt precies de
  storing die dit plan oplost; #292 houdt de readiness van de console bewust op `/q/health/ready`.
- **Eerst een deployment zonder images, dan de images erbij.** Een deployment zonder componenten
  provisioneert bij zijn eerste verwerking geen database, en de `clone-from`-markering staat op
  `mode: once`; het klonen van de data kan daarna wegvallen. Niet gebruikt.
- **Een feature bij ZAD aanvragen.** Niet nodig: OM heeft `rollout=false` al (RC-46). Wel het melden
  waard: een regel wordt niet opnieuw opgelost wanneer zijn peer later verschijnt, en een uitgestelde
  wijziging blijft als "wacht op uitrol" staan tot een refresh van het héle project.
- **`zadctl --no-rollout` in CI.** De CLI kan het, maar CI gebruikt zad-actions en de API; de
  bestaande netwerkregel-stap is al een API-script. zad-actions zelf kent geen `rollout`-input.

## Wijziging

Een nieuwe matrix-job `preview-klaarzetten`, met dezelfde drie legs als de opruim-matrix in
`cleanup-preview.yml`. Per project:

1. bestaat `pr-<n>` nog niet, dan maakt `.github/scripts/preview-klaarzetten.sh` hem aan via
   `POST /api/v2/projects/{p}/:upsert-deployment?rollout=false`, met `cloneFrom: test` en de
   componentenlijst van de deploy. Het script eist dat de taak níet uitrolde;
2. `cross-domain-preview.sh zet` vult de regels in met `ZAD_ROLLOUT=false`, en slaat de patch over
   als elke regel deze deployment al als peer noemt.

Daarna rollen `deploy-preview-uitvraag` en `deploy-preview-externe-stubs` parallel uit, en pas
daarna `deploy-preview-magazijnen`: de console wordt pas gezond als de inbound-policies in de twee
andere projecten in het cluster staan. De netwerkregel-stappen in de drie deploy-jobs en in
`preview-afronding` verdwijnen; die job plaatst alleen nog de comment.

Verder:

- `meta` publiceert de componentenlijst per preview-project; de klaarzetting en de deploy lezen
  dezelfde lijst. OM neemt bij het aanmaken alleen de meegegeven componenten over.
- `cross-domain-preview.sh` leest de taakstatus met jq. De sed-regel pakte de laatste `status` in
  de tekst, en in een taak zonder uitrol is dat de `skipped` van de verwerking.
- `uitrol-poort` beoordeelt `preview-klaarzetten` zoals `preview-afronding`: success bij een
  verwachte preview, skipped op een push en bij `deploy=false`.

## Gevolgen

- **Doorlooptijd.** De magazijnen-deploy wacht weer op de twee andere, wat #307 terugwon. Daar
  tegenover vallen de inbound-patches met uitrol weg, die volgens de meting uit #307 samen ~145 s
  kostten, en een volgende push op een bestaande preview zet niets klaar.
- **"Wacht op uitrol" in OM.** Elke nieuwe preview laat per project twee uitgestelde taken achter die
  OM als niet uitgerold blijft tellen, ook nadat de deploy ze uitrolde (`get_deferred_rollouts`
  wist alleen bij `refresh_project`). `zadctl project pending` en de OM-UI tonen dat getal dus te
  hoog.
- **Bestaande previews.** Een preview die nog de regels van `test` draagt, zoals `pr-303`, krijgt
  bij zijn volgende push alsnog de juiste regels.

## Verificatie

- `bash .github/scripts/test-preview-klaarzetten.sh`, `test-cross-domain-preview.sh`,
  `test-uitrol-poort.sh`, `test-preview-comment.sh`
- `actionlint`, `shellcheck -x -S warning`
- De preview van deze PR is een nieuwe deployment: de eerste run hoort in één keer groen te zijn,
  met een console die antwoordt en storingsknoppen die werken.
