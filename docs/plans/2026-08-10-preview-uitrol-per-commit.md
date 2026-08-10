# Preview rolt uit per commit (unieke image-tag)

**Status:** Uitgevoerd
**Issue:** #171

## Context

Een PR-preview op ZAD bevroor bij de eerste build. Elke volgende commit werd wel gebouwd en
gepusht en de `deploy-preview-*`-check werd groen, maar er rolde niets uit: de pods bleven op
de image-laag van de eerste build draaien.

Oorzaak: `deploy.yml` gaf op een PR altijd dezelfde image-tag mee (`pr-<n>`). Die tag komt via
`zad-actions/deploy` in Operations Manager en belandt in het gerenderde k8s-manifest in
`rig-cluster-application-test`. Bij een tweede commit is dat manifest byte-identiek — dezelfde
`image:`-regel stond er al. Argo CD synct op verschil in het manifest, ziet er geen, en doet
niets. `imagePullPolicy: Always` verandert dat niet: dat werkt alleen bij een herstart van de
pod, en die komt niet vanzelf. Op `main` speelde het niet, want `main-<sha7>` wijzigt per commit.

Gemeten bewijs (PR #168): `odcn-production/mpfm-w3h/pr-168/magazijna-deployment.yaml` was sinds
2026-08-07 09:56 onveranderd, terwijl de deploy van 2026-08-10 07:40 groen stond. Die deploy
raakte in de PR-map alleen `decrypt-sops.yaml`, `kustomization.yaml` en de twee
`*-user-secret.sops.yaml` — geen enkel `*-deployment.yaml`.

Waarom dit erger is dan een vertraging: een groene deploy-check zegt alleen dat het image
bouwde en dat OM de aanroep accepteerde. Wie op de preview verifieert kijkt vanaf de tweede
commit naar oude code, zonder signaal dat dat zo is.

## Afweging

### 1. Deployen op digest (`image: …@sha256:…`)

De digest wijzigt zodra de inhoud wijzigt, dus het manifest ook. De tag `pr-<n>` kan blijven
bestaan, waarmee de bestaande cleanup ongewijzigd werkt.

Onderzocht in `RijksICTGilde/zad-actions@13434cd` (v4): de `deploy`-action valideert alleen de
component-*naam* (`^[a-zA-Z0-9._-]+$`) en geeft het image-veld ongewijzigd door aan
`zad-cli@v0.8.0`. Die valideert in `api/models.py` eveneens alleen `Component.name`; de
OM-OpenAPI (`api/upstream-openapi.json`) legt geen patroon op `image`/`newImageUrl` op. Een
digest-referentie wordt dus waarschijnlijk geaccepteerd — maar dat is een afwezigheid van
validatie, geen bewijs dat OM's manifest-rendering en de pull-through-mirror er goed mee
omgaan. Dat pad is niet zonder een echte deploy vast te stellen.

Zwaarder weegt de opruimkant. Jib bouwt reproduceerbaar: identieke sources geven een identieke
digest. Voor de tag betekent dat weinig, maar het maakt de digest ongeschikt als *label* van
een PR: een ghcr-versie draagt geen PR-nummer, en na het verplaatsen van de tag `pr-<n>` is de
vorige versie ongetagd. Ongetagde versies zijn niet meer aan een PR toe te wijzen en dus niet
gericht op te ruimen.

Dat is geen theorie: `fbs-berichtenmagazijn` telde bij het schrijven van dit plan ~237
ghcr-versies waarvan ~157 ongetagd — precies de tussenbuilds die het verplaatsen van `pr-<n>`
achterliet. De huidige cleanup ruimt per package één exacte tag op en laat die berg staan.

### 2. Unieke tag per commit (`pr-<n>-<sha7>`) — gekozen

Elke build krijgt een eigen tag, dus elk manifest wijzigt, dus Argo rolt uit. Geen aannames
over wat OM met een referentievorm doet: de vorm blijft `repo:tag`, precies zoals nu op `main`
(waar de per-commit-tag al jaren werkt — dat is meteen het bewijs dat dit pad werkt).

De cleanup moet dan wél alle tags van de PR opruimen in plaats van één. Dat kan, en het is
strikt beter dan vandaag: elke build houdt een tag, dus elke build blijft toewijsbaar aan zijn
PR en gaat bij het sluiten mee. Het ongetagde-versies-lek stopt daarmee voor nieuwe PR's.

Kosten: bij twee builds van identieke sources rolt Argo een identiek image opnieuw uit (jib
levert dan dezelfde digest, dus ghcr hangt beide tags aan dezelfde versie). Dat is één
overbodige herstart in een randgeval, tegenover een preview die anders stil verkeerd staat.

### 3. Iets aan ZAD-zijde dat een rollout forceert

Bestaat niet. `:refresh` reconcilet zonder revisiewijziging, en een deployment herscheppen
(`DELETE` + `:upsert-deployment`) draait op projecten met de `postgresql-database`-service een
`database_cleanup` — dat vernietigt data en is geen routine-oplossing.

### Conclusie

Richting 2. Richting 1 lost het bevriezen even goed op, maar laat het opruimprobleem staan en
vraagt bovendien een niet-verifieerbare aanname over de OM-keten. Richting 2 heeft één nadeel
(een zeldzame overbodige rollout) en één extra stuk workflow-code, en lost het ghcr-lek meteen
mee op.

## Wijzigingen

`.github/workflows/deploy.yml`

- `meta`: PR-tag wordt `pr-<n>-<sha7>` op basis van `github.event.pull_request.head.sha` — de
  sha die in de PR-tijdlijn staat, niet de efemere merge-commit uit `GITHUB_SHA`. De
  interpolaties gaan via `env:` (defense-in-depth injectie), zoals de rest van de workflow.
  De `main`-tak blijft `main-<sha7>`.
- `cleanup-preview-*`: `delete-container: 'false'`; de `containers`-lijst en de daarvoor
  benodigde `packages: write` + `needs: meta` zijn weg.
- Nieuw: `cleanup-preview-images` ruimt bij PR-sluiten alle ghcr-versies op waarvan minstens
  één tag begint met `pr-<n>-`. Het afsluitende koppelteken is essentieel: zonder dat zou het
  sluiten van PR 16 de images van PR 168 meenemen. De job faalt zichtbaar (rood) als een
  verwijdering mislukt, zodat een groeiende ghcr-berg niet ongemerkt ontstaat.

Bewust géén package-brede fallback bij "cannot delete the last tagged version" (die de
`zad-actions`-cleanup wél heeft): dat zou een preview-cleanup het hele package laten
verwijderen, main-images incluis. De `main-<sha7>`-tags zorgen ervoor dat die situatie zich
niet voordoet.

`CLAUDE.md`: de tag-conventie in "ZAD deploy & GitOps (debug)" bijgewerkt, plus de reden dat
image-tags uniek per commit moeten zijn.

## Randvoorwaarden getoetst

- **Alle drie projecten.** De tag komt uit één `meta`-job; de jib-services en de
  docker-gebouwde externe-stubs gebruiken dezelfde output. Geen van beide bouwpaden kent de
  tagvorm.
- **Geen weesversies.** Elke gepushte PR-versie houdt een unieke tag en valt onder de prefix
  die de cleanup opruimt.
- **Actions SHA-gepind.** Er komt geen nieuwe action bij; `cleanup-preview-images` draait op
  `gh`/`jq` van de runner.
- **`pin-consistency.yml`.** Bewaakt alleen `redis/redis-stack-server` en
  `clickhouse/clickhouse-server`; die pins zijn niet geraakt.

## Verificatie

Een groene CI-run is hier geen bewijs — dat was juist het probleem. Aangetoond op de PR van
deze wijziging zelf:

1. Eerste commit → preview uitgerold.
2. Tweede, triviale commit gepusht.
3. In `RijksICTGilde/rig-cluster-application-test` heeft
   `odcn-production/<project-id>/pr-<n>/<component>-deployment.yaml` ná die tweede push een
   nieuwe commit met een gewijzigde `image:`-regel.
4. Het draaiende component vertoont het gedrag van de tweede commit.

Zie de verificatiesectie in de PR-beschrijving voor de uitkomst.

## Openstaand

De ~157 bestaande ongetagde ghcr-versies van `fbs-berichtenmagazijn` (en de overeenkomstige
van de andere twee packages) blijven staan: ze zijn niet aan een PR toe te wijzen. Ze
opruimen vraagt een eigen afweging (een ongetagde versie kan ook een index-/attestatie-kind
zijn) en valt buiten deze wijziging.
