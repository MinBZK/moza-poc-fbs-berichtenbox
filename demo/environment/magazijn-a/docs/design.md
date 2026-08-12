**Status:** Concept

# Ontwerp — Magazijn-provider-peer aansluiten op de FSC-federatie (#780)

> Toespitsing van **Spec B** (`moza-fsc-testnet:docs/superpowers/specs/2026-06-29-fbs-peers-onboarding-design.md`)
> op het provider-deel. Verwant: [Spec A](https://github.com/MinBZK/moza-fsc-testnet)
> (generieke infra), epic [#737](https://github.com/MinBZK/MijnOverheidZakelijk/issues/737),
> issue [#780](https://github.com/MinBZK/MijnOverheidZakelijk/issues/780). Uitvoerings-
> en directorybesluiten staan in
> `docs/plans/2026-07-30-demo-environment-fsc-peers-design.md` en
> `docs/plans/2026-07-31-magazijn-a-peer-migratie-plan.md`.

## Aanleiding

`moza-fsc-testnet` (repo A) levert de generieke FSC-infra: de **directory** (group-anker), de
group-config/CA en een neutrale `example-provider`-peer als kopieer-template. De FBS-PoC moet
zich als **consument van die infra** aansluiten. Deze eerste stap (#780) zet de **magazijn-kant
als aanbiedende organisatie** neer: een provider-peer (manager + inway + controller + DB) die
naast de magazijn-app draait en `berichtenmagazijn` als dienst in de directory publiceert. De
peer leeft in dit repo onder `demo/environment/magazijn-a/`.

De uitvraag-consumer-peer (#725) en het omleiden van `berichtenuitvraag` via de outway (#726)
vallen **buiten** dit ontwerp; die volgen apart onder epic #737.

## Bevinding cert-portal (onderzocht vóór dit ontwerp)

De AC "Peer verkrijgt group-cert via het cert-portal van repo A" veronderstelt een draaiend
cert-portal. Onderzoek in repo A wijst uit:

- De ZAD-directory-deploy (`deploy/zad/upsert-directory.sh`) rolt **alleen** `dirmgr` (manager)
  - `dirui` (directory-ui) uit. Er is **geen** `ca-cfssl`/`ca-certportal`-component of
  -workflow op ZAD.
- In Spec A staat het portal-pad expliciet als *lokaal aangetoond*, en "ca-cfssl op ZAD" als
  **open punt**.
- Het bewezen cert-pad waarmee de directory zélf op ZAD draait is: certs lokaal genereren
  (`pki/issue.sh`, cfssl) → `pki/zad-bundle.sh` → als **ZAD-attachment** mounten (UI-stap).

**Gevolg voor dit ontwerp:** we volgen datzelfde bewezen pad (lokaal `issue.sh` + ZAD-attachment)
en tonen de portal-flow **lokaal** aan (ca-cfssl + ca-certportal, cert voor de magazijn-OIN). Zo
is de AL functioneel gedekt zonder te blokkeren op een portal-op-ZAD; dat portal-op-ZAD is een
repo-A-vervolg, geen onderdeel van #780.

**Group-CA-herkomst (besloten):** voor de aansluiting op de échte fsc-testnet-directory moet
magazijn-a's group-leaf ketenen naar **fsc-testnet's** group-root — niet naar een lokaal verse CA.
Gekozen route: het group-CA-materiaal van repo A (`ca/root.pem` + `ca/intermediate.pem` + keys)
lokaal in `pki/ca/` plaatsen en **`init-ca.sh` overslaan**; daarna alleen `issue.sh` draaien.
De per-peer INTERNAL-CA blijft lokaal/self-signed. (`init-ca.sh` blijft de juiste keuze voor de
geïsoleerde lokale compose-proof.) Van de directory zelf zijn géén per-peer client-certs of
private keys nodig — trust loopt volledig via de gedeelde group-PKI.

## Scope

**In scope:** de provider-peer voor **magazijn-a** (echte OIN `00000000000000100000`).

**Buiten scope:** consumer/outway (#725/#726), magazijn-b (`00000001823288444000`, latere
uitbreiding), txlog-hardening/e2e-verantwoording (#728), en het echte data-pad dóór de inway
(#728). De bestaande `berichtenmagazijn`-app verandert niet (config-only ervoor).

## Bekende parameters

| Parameter | Waarde | Herkomst |
|-----------|--------|----------|
| Peer-OIN (= Peer ID = `magazijnId`) | `00000000000000100000` | `services/berichtenmagazijn/.../application.properties` (`MAGAZIJN_OIN`) |
| Peer-naam | `magazijn-a` | conventie repo B (geen `example-*`) |
| Group ID | `moza-fbs-test` | repo A directory-deploy |
| Directory-OIN | `00000000000000000010` | repo A directory-deploy |
| ZAD-project (peer én app) | `magazijnen` / `mpfm-w3h` | co-locatie, zie Architectuur |
| ZAD-deployment peer | `fsc-magazijna` | deployment-isolatie, zie Architectuur |
| ZAD-deployment app (inway-upstream) | `test` (previews: `pr-<n>`) | repo B `deploy.yml` |
| App-component (inway-upstream) | `magazijna` (cross-deployment via ingress-URL) | repo B `deploy.yml` |
| Dienst-naam in de directory | `berichtenmagazijn` | dit ontwerp |
| FSC-images (pin) | `v2.5.2` (manager/inway/controller/directory-ui) | repo A |
| migrate-wrapper-images | `ghcr.io/minbzk/moza-fsc-testnet-{manager,controller,txlog}-migrate:<tag>` | repo A |

## Architectuur

Gespiegeld op repo A's `example-provider`: per peer een eigen set FSC-componenten. De peer
draait **co-located in het bestaande ZAD-project `mpfm-w3h`** — hetzelfde project als de
`magazijna`/`magazijnb`-componenten die `deploy.yml` beheert — maar in een **eigen
deployment `fsc-magazijna`**, niet in `test`.

**Waarom deployment-isolatie:** `deploy-preview-magazijnen` deployt PR-previews met
`clone-from: test`. Of `clone-from` alleen de componenten uit de eigen `components:`-lijst
kloont of álle componenten van de bron-deployment, is bij RijksICTGilde/zad-actions niet
bevestigd. Bij het laatste zou elke preview vijf extra pods krijgen: clones zonder de UI-only
cert-attachments (die kloont de v2-API niet mee), dus direct crashloopend — en worst-case zou
een gekloonde peer-manager zich met dezelfde federatie-OIN opnieuw aanmelden bij de gedeelde
directory, een tweede manager die dezelfde peer-identiteit claimt. Wat niet in `test` staat,
kan niet uit `test` gekloond worden; de peer blijft zo een singleton. `fsc-magazijna` is zelf
nooit een kloon-bron.

De app-component `magazijna` blijft in `test`. De inway bereikt haar **cross-deployment via de
ingress-URL** (`https://magazijna-test-mpfm-w3h.<base-domain>`, https/:443; override via
`ZAD_MAGAZIJNA_DEPLOYMENT`). Gedeeld project betekent gedeelde API-key: `ZAD_API_KEY_MAGAZIJNEN`.
De raw v2-API maakt geen nieuwe deployments aan, dus `fsc-magazijna` wordt eenmalig leeg in de
UI aangemaakt (zonder clone-from).

De componentnamen dragen het prefix `magazijna-fsc*` om dubbelzinnigheid met de app-componenten
in het gedeelde project te voorkomen. Doorlopende image-tag-updates lopen via de
`deploy-test-magazijnen`-job in `.github/workflows/deploy.yml` (aparte stap tegen
`fsc-magazijna`); `deploy/zad/upsert-peer.sh` is het lokale plan/validate/apply-hulpmiddel voor
de eenmalige componentcreatie en voor debugging.

### Componenten van de provider-peer

| Component | ZAD-ref | Rol |
|-----------|---------|-----|
| manager | `magazijna-fscmgr` | announce bij de directory + ServicePublicationGrant; `manager-migrate`-wrapper migreert de peer-DB bij boot |
| controller | `magazijna-fscctl` | dienst `berichtenmagazijn` aanmaken (Administration-API, `AUTHN_TYPE=none`) + beheer-UI + inway-registratie (Registration-API); `controller-migrate`-wrapper migreert bij boot |
| inway | `magazijna-fscinway` | ingress vóór de `magazijna`-app-component; registreert bij de controller |
| txlog | `magazijna-fsctxlog` | transactielog-API (internal-PKI mTLS); manager en inway wijzen ernaar |
| DB | `magazijna-fscpg` (self-hosted Postgres, één DB, geïsoleerde migratie-tellers) | system-of-record manager + controller + txlog |

De txlog is **niet optioneel**: OpenFSC faalt hard op `tx-log-api-address is required when the
manager does not function as the directory` zodra `TX_LOG_API_ADDRESS` leeg is. txlog-*hardening*
en het echte data-pad blijven #728.

**DB — self-hosted Postgres i.p.v. ZAD-managed.** ZAD's managed Postgres laat ons de init/schema's
niet inrichten, en met één gedeelde DB botsen de golang-migrate `schema_migrations`-tellers van
manager/controller/txlog (de controller-migratie zag de manager-versie, sloeg over, en
`controller.services` ontbrak — `42P01`). Daarom een eigen postgres-component `magazijna-fscpg`:
één database met **geïsoleerde migratie-tellers per component**, waarbij de componenten zich niet
gelijk gedragen. **Manager en txlog** isoleren hun teller via een eigen `search_path`-schema
(`manager`/`txlog`, aangemaakt door `deploy/zad/postgres-init.sql`, UI-attachment op
`/docker-entrypoint-initdb.d`). De **controller is de uitzondering**: mét een vooraf aangemaakt
`controller`-schema + `search_path=controller` loopt migratie #1 dirty vast; die draait daarom
**zonder** search_path (`ZAD_CTL_SCHEMA=""`), maakt z'n eigen `controller`-schema aan
(schema-gekwalificeerde DDL) en houdt z'n teller in `public`. De DSN staat concreet in `env_vars`
(geen ZAD `$DATABASE_*`-substitutie); het wachtwoord komt uit `ZAD_PG_PASSWORD` (niet gecommit).
Manager, controller en txlog migreren bij boot via hun eigen wrapper-image
(`{manager,controller,txlog}-migrate`, `migrate up && serve`). Aandachtspunt: zonder persistent
volume is `magazijna-fscpg` ephemeral — prima voor test, een blijvende peer vraagt een PVC.

### Certificaat-topologie (per endpoint, uit repo A)

Elk endpoint (`manager`/`controller`/`inway`/`txlog`) krijgt twee ketens:

- **GROUP** (extern, door de group-intermediate getekend) → `TLS_GROUP_CERT/KEY` (+ hergebruikt
  voor `TLS_GROUP_TOKEN_*` en `TLS_GROUP_CONTRACT_*`). De OIN staat 1:1 in `serialnumber` van de
  `csr.json`.
- **INTERNAL** (per-peer self-signed CA) → `TLS_CERT/KEY` (+ `TLS_INTERNAL_UNAUTHENTICATED_*`),
  voor de mTLS tussen manager ↔ controller ↔ inway.

**SAN's zijn least-privilege.** Elk internal-cert draagt zijn **eigen concrete** hostnamen: de
publieke ZAD-hostnaam (`magazijna-fscmgr-fsc-magazijna-mpfm-w3h.<base-domain>` op de manager,
`magazijna-fscctl-…`, `magazijna-fscinway-…`, `magazijna-fsctxlog-…`) **plus** de cluster-interne
Service-DNS (`fsc-magazijna-magazijna-fscmgr` +
`fsc-magazijna-magazijna-fscmgr.rig-prd-mpfm-w3h.svc.cluster.local`), waarnaar het interne
mTLS-verkeer verbindt. Géén domein-brede wildcard, zodat de certs niet voor het hele gedeelde
Rijks-hosting-domein geldig zijn; te controleren met `verify.sh` + `openssl`. Verandert het
project of de deployment-naam, dan moeten de bijbehorende publieke én Service-DNS-hostnamen als
SAN opnieuw uitgegeven en geüpload worden.

### Interne mTLS — poorten en routering

Een ZAD-component exposet **alle** poorten uit `ports.inbound` als Service-poort
(`AddComponentRequest.ports`, array; `ports[0]` is de ingress). Elke poort krijgt een Service
`<deployment>-<component>`, intern bereikbaar als `fsc-magazijna-magazijna-fscmgr:9443` enz. De
interne edges (controller→manager:9443, manager/inway→controller:9443, inway→manager:9444,
→txlog:8443) wijzen daarom naar die cluster-Service-DNS en niet naar `:443`;
`upsert-peer.sh` zendt per component de `ports`-array (`magazijna-fscmgr` `8443,9443,9444`;
`magazijna-fscctl` `8080,9443,9444`). De externe mesh (`magazijna-fscmgr`/`magazijna-fscinway`
op `:443`, SNI-passthrough) loopt los daarvan.

### Onboarding-flow (wat de smokes bewijzen)

```text
magazijn-a                                   centrale kern (directory)
  controller ──CreateService(admin-API)───►  (eigen DB)
  manager ───announce────────────────────►  directory-manager (peers.peers, :443)
  manager ───ServicePublicationGrant─────►  directory-manager ──auto-sign──► directory (services)
  inway ────GetService(registration-API)─►  controller
```

1. **Cert** — group-cert voor de magazijn-OIN (lokaal `issue.sh`; portal lokaal aangetoond).
2. **Deploy** — peer-componenten naast `magazijna` (lokaal compose → ZAD-upsert).
3. **Announce** — manager verschijnt in `peers.peers` met `manager_address` op `:443`.
4. **Publiceren** — controller `CreateService(berichtenmagazijn, <inway-address>, <upstream>)`
   → manager dient ServicePublicationGrant in → directory auto-signt
   (`AUTO_SIGN_GRANTS=servicePublication`) → dienst vindbaar.

## Levering — twee fasen

1. **Lokale compose-proof** (mirror example-provider, echte OIN + dienst `berichtenmagazijn`):
   `smoke-announce.sh` + een discover-check groen. Bewijst AC-3 en AC-4 lokaal.
2. **ZAD** — `upsert-peer.sh` (er is nog géén peer-ZAD-deploy in repo A) + cert-attachments,
   componenten in deployment `fsc-magazijna` van project `mpfm-w3h`, inway → `magazijna`
   (cross-deployment). Bewijst alle vier AC's op de echte directory.

## Acceptatiecriteria (uit #780) → dekking

| AC | Gedekt door |
|----|-------------|
| Magazijn-peer (echte OIN) draait naast de app: manager + inway + controller + DB (ZAD, deployment-isolatie) | Fase 2 (ZAD-upsert in `fsc-magazijna`) |
| Peer verkrijgt group-cert via het cert-portal van repo A | Cert-portal lokaal aangetoond + bewezen `issue.sh`+attachment-pad (zie bevinding) |
| Peer meldt zich aan bij de directory (announce) | `smoke-announce.sh` (lokaal + ZAD) |
| `berichtenmagazijn` gepubliceerd + vindbaar in de directory | `publish-service.sh` + discover-check (lokaal + ZAD) |

## Aandachtspunten bij beheer

- **Component-env is UI-beheerd.** `POST /components` werkt de env van een BESTAANDE component
  niet bij: een `TX_LOG_API_ADDRESS` die ná de eerste creatie werd gezet (via re-POST én via een
  extra `:upsert-deployment`) bereikte de manager nooit — verse pods bleven `tx-log-api-address
  not set` geven. De env is bevroren op wat er bij de éérste creatie in zat. Dit spiegelt het
  app-model (zad-actions): de deploy-API beheert images/refs, runtime-env komt uit
  `clone-from`/de UI. Componenten **niet verwijderen** om env te wijzigen — dan raak je de
  cert-attachments kwijt (UI-only per component).
- **Wildcard-SAN in de directory-stub-CSR.** `pki/peers/directory/directory/csr.json` draagt een
  domein-brede wildcard-SAN (`*.rig.prd1.gn2.quattro.rijksapps.nl`), overgenomen uit de
  directory-stub van de lokale harness. `pki/issue.sh` itereert onvoorwaardelijk over ALLE
  `peers/*/*/csr.json`, dus bij een echte ZAD-run (fsc-testnet's échte `ca/root.pem` +
  `ca/intermediate.pem` in `pki/ca/`, `init-ca.sh` overslaan, `issue.sh` draaien) tekent de échte
  group-CA óók die wildcard-cert. Vóór een echte ZAD-run adresseren: `peers/directory/` uitsluiten
  van die `issue.sh`-aanroep, of de SAN versmallen.
- **Echte magazijn-OIN in een publiek repo** — staat al in `application.properties`; akkoord,
  hier expliciet genoteerd.
- **Cert-portal op ZAD** — repo-A-vervolg; buiten #780.
- **txlog-hardening / e2e-data-pad** — #728.
