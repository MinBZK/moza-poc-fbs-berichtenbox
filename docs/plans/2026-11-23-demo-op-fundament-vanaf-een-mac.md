# De demo op Fundament vanaf een Mac

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#1639. Volgt op #469 (`demo-op-fundament.sh`) en #471 (de
sandbox op `b363604d`).

## Context

De eerste `charts/fundament-testcluster.sh demo` op een Mac (Apple silicon, Rancher Desktop,
22 november 2026) liep drie keer vast, telkens pas nadat er al iets veranderd was:

1. **Helm 4 kent `helm list --all` niet meer.** `demo()` vraagt de releases op met `--all`, zodat
   ook een release die halverwege bleef staan meetelt. Helm 4 weigert die vlag (`unknown flag`).
   De CI draait wel Helm 4.3.1, maar de tests vervangen `helm` door een stub die elke vlag slikt.
2. **macOS levert bash 3.2.** `demo-op-fundament.sh` en `proeftuin-image.sh` gebruiken `mapfile`,
   en `installeren()` expandeert een lege array onder `set -u`; dat vraagt bash 4.4. Het script
   stopte met `mapfile: command not found`, na de databases en de geheimen.
3. **De stub-images starten niet op een arm64-node.** `fbs-externe-stubs` en `fbs-demo-profiel`
   komen uit `docker build`, en dat maakt met de provenance-attestation een index met alleen
   linux/amd64. Containerd op de sandbox-node (arm64) weigert die: `no match for platform in
   manifest`. De jib-images zijn ook alleen amd64, maar als los manifest; die draaien onder emulatie.

## Ontwerpkeuzes

- **Expliciete statusfilters in plaats van `--all`.** `--deployed --failed --pending --superseded
  --uninstalled --uninstalling` betekent in Helm 3 en 4 "alles". Alleen `--all` weglaten zou op
  Helm 4 volstaan, maar Helm 3 toont zonder filter alleen deployed en failed. Getoetst met de echte
  binaries (3.22.1 en 4.3.1); de suite draait de echte `helm` met de filters tegen een lege
  kubeconfig, zodat een vlag die de geïnstalleerde versie niet kent als fout leest.
- **Een versiecontrole, geen bash-3.2-versie van de scripts.** De repo kent beide routes. Hier is
  de controle de eerlijke: zonder een bash 3.2 om tegen te testen zou een omzetting alleen op papier
  kloppen. In `fundament-testcluster.sh` zelf staat geen `mapfile`, en `op` installeerde op
  20 november op dezelfde Mac onder 3.2 al diensten. Daar krijgt dus alleen `demo` de eis, in `main`,
  vóór demo() de proef van `op` aanpast. `demo-op-fundament.sh` controleert zelf ook,
  vóór elke opdracht: het draait op het fieldlab ook los. De functie staat één keer, in
  `demo-op-fundament/lib.sh`; `fundament-testcluster.sh` laadt die lib nu bovenaan in plaats van in
  demo(). Beide libs delen geen namen, en `demo-op-fundament/lib.sh` zet niets readonly.
- **Een los manifest, geen multi-arch-build.** `docker build --provenance=false` maakt de stubs
  gelijk aan de jib-images: een los amd64-manifest dat op arm64 onder emulatie start. Multi-arch
  vraagt QEMU en buildx op de runner en een controle per platform, voor images van een paar MB
  mappings. Het contract-bootstrap-image krijgt dezelfde vlag, zodat de regel voor elk image geldt.
- **De vorm toetsen in het register.** `images-gepubliceerd.sh` controleerde alleen of een image
  bestaat. Het weigert nu ook een index zonder linux/arm64. Een aparte vlag om die controle aan te
  zetten zou per job te vergeten zijn, en dan zweeg hij. De melding noemt de vorm die er wél stond.
- **Geen statische eis op de build-vlag.** De kruiscontrole zou kunnen eisen dat elke pushende
  `docker build` `--provenance=false` draagt. Een nieuwe build zonder vlag valt nu pas op bij de
  eerste run met uitrol, wel vóór de merge.
- **De provenance-attestation vervalt.** Die was ongetekend. Is hij later nodig, dan kan hij als
  referrer naast het image, zonder de vorm van de tag te veranderen.
- **Een oudere tag blijft op arm64 haperen.** `demo-op-fundament.sh` toetst alleen of de images
  bestaan, niet hun vorm; de README van `fbs-demo` noemt het, met de opdracht om de vorm te zien.

## Verificatie

- `charts/fundament-testcluster/test-lib.sh`, `charts/demo-op-fundament/test-lib.sh`,
  `charts/demo-op-fundament/test-script.sh` en `.github/scripts/test-images-gepubliceerd.sh` groen;
  de tellingen in `ci-scripts.yml` zijn bijgewerkt.
- De vormcontrole draait vóór de merge op een echte publicatie, in twee stappen. Op de draft bouwt
  de keten alleen het contract-bootstrap-image, met dezelfde vlag: dat is het eerste bewijs dat
  `--provenance=false` op de runner een los manifest oplevert. De stubs bouwt hij pas na ready for
  review, want een wijziging aan `deploy.yml` telt op een draft niet als uitrol.
- Met de hand, 22 november: de demo draaide op de sandbox met de drie omwegen (zonder `--all`,
  bash uit Homebrew, de stubs zelf gebouwd voor arm64), en de ketencontrole slaagde.
