**Status:** Concept

# Ontwerp — Magazijn-provider-peer aansluiten op de FSC-federatie (#780)

> **LET OP — dit ontwerp is bijgewerkt.** De onderstaande hoofdtekst beschrijft het
> *oorspronkelijke* ontwerp: een eigen ZAD-project (`mpfoa-e2w`), project-isolatie, en de
> `zad-deploy-peer.yml`-workflow. Dat model is sinds de migratie naar
> `demo/environment/magazijn-a/` **niet meer actueel** — zie de sectie "Addendum
> 2026-07-31" onderaan dit document voor de huidige situatie (co-locatie in `mpfm-w3h`,
> `magazijna-fsc*`-componentnamen, `deploy-test-magazijnen` i.p.v. een losse workflow).
> Lees de hoofdtekst als historische context, niet als de huidige stand van zaken.

> Toespitsing van **Spec B** (`moza-fsc-testnet:docs/superpowers/specs/2026-06-29-fbs-peers-onboarding-design.md`)
> op het provider-deel, verhuisd naar repo B (`moza-poc-fbs-berichtenbox`). Verwant:
> [Spec A](https://github.com/MinBZK/moza-fsc-testnet) (generieke infra), epic
> [#737](https://github.com/MinBZK/MijnOverheidZakelijk/issues/737), issue
> [#780](https://github.com/MinBZK/MijnOverheidZakelijk/issues/780).

## Aanleiding

`moza-fsc-testnet` (repo A) levert de generieke FSC-infra: de **directory** (group-anker), de
group-config/CA en een neutrale `example-provider`-peer als kopieer-template. De FBS-PoC moet
zich als **consument van die infra** aansluiten. Deze eerste stap (#780) zet de **magazijn-kant
als aanbiedende organisatie** neer: een provider-peer (manager + inway + controller + DB) die
co-located bij de magazijn-app draait en `berichtenmagazijn` als dienst in de directory
publiceert.

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
| Peer-OIN (= Peer ID = `magazijnId`) | `00000000000000100000` | `services/berichtenmagazijn/.../application.properties:138` |
| Peer-naam | `magazijn-a` | conventie repo B (geen `example-*`) |
| Group ID | `moza-fbs-test` | repo A directory-deploy |
| Directory-OIN | `00000000000000000010` | repo A directory-deploy |
| ZAD-project peer | `mpfm-w3h` (eigen project) | dit ontwerp (was co-located `mpfm-w3h`) |
| ZAD-project app | `magazijnen` / `mpfm-w3h` | repo B `deploy.yml` |
| App-component (inway-upstream) | `magazijna` (cross-project via ingress-URL) | repo B `deploy.yml` |
| Dienst-naam in de directory | `berichtenmagazijn` | dit ontwerp |
| FSC-images (pin) | `v1.43.7` (manager/inway/controller/directory-ui) | repo A |
| migrate-wrapper-images | `ghcr.io/minbzk/moza-fsc-testnet/{manager,controller,txlog}-migrate:<tag>` | repo A |

## Architectuur

Gespiegeld op repo A's `example-provider`. Per peer een eigen set FSC-componenten; de
magazijn-peer draait **in een eigen ZAD-project `mpfm-w3h`** (project-isolatie). De
`magazijna`-app draait apart in `mpfm-w3h`; de inway bereikt die **cross-project via de
ingress-URL** (https, :443), niet via intra-project-DNS.

### Componenten van de provider-peer

| Component | ZAD-ref | Rol |
|-----------|---------|-----|
| manager | `magazijna-fscmgr` | announce bij de directory + ServicePublicationGrant; `manager-migrate`-wrapper migreert de peer-DB bij boot |
| controller | `magazijna-fscctl` | dienst `berichtenmagazijn` aanmaken (Administration-API, `AUTHN_TYPE=none`) + beheer-UI + inway-registratie (Registration-API); `controller-migrate`-wrapper migreert bij boot |
| inway | `magazijna-fscinway` | ingress vóór de `magazijna`-app-component (intra-project DNS); registreert bij de controller |
| DB | `magazijna-fscpg` (self-hosted Postgres, één DB, geïsoleerde migratie-tellers) | system-of-record manager + controller + txlog |

`txlog` draait lokaal mee (mirror van example-provider) maar wordt **niet** gehard voor #780;
volledige tx-logging is #728. Op ZAD blijft `TX_LOG_API_ADDRESS` in eerste ronde leeg/minimaal.

### Certificaat-topologie (per endpoint, uit repo A)

Elk endpoint (`manager`/`controller`/`inway`/`txlog`) krijgt twee ketens:

- **GROUP** (extern, door de group-intermediate getekend) → `TLS_GROUP_CERT/KEY` (+ hergebruikt
  voor `TLS_GROUP_TOKEN_*` en `TLS_GROUP_CONTRACT_*`). De OIN staat 1:1 in `serialnumber` van de
  `csr.json`.
- **INTERNAL** (per-peer self-signed CA) → `TLS_CERT/KEY` (+ `TLS_INTERNAL_UNAUTHENTICATED_*`),
  voor de mTLS tussen manager ↔ controller ↔ inway.

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
2. **ZAD** — nieuw `upsert-peer.sh` (er is nog géén peer-ZAD-deploy in repo A) + cert-attachments,
   componenten in `mpfm-w3h`, inway → `magazijna` (cross-project). Bewijst alle vier AC's op de echte directory.

## Acceptatiecriteria (uit #780) → dekking

| AC | Gedekt door |
|----|-------------|
| Magazijn-peer (echte OIN) draait naast de app: manager + inway + controller + DB (ZAD, project-isolatie) | Fase 2 (ZAD-upsert in `mpfm-w3h`) |
| Peer verkrijgt group-cert via het cert-portal van repo A | Cert-portal lokaal aangetoond + bewezen `issue.sh`+attachment-pad (zie bevinding) |
| Peer meldt zich aan bij de directory (announce) | `smoke-announce.sh` (lokaal + ZAD) |
| `berichtenmagazijn` gepubliceerd + vindbaar in de directory | `publish-service.sh` + discover-check (lokaal + ZAD) |

## Open punten (genoteerd, niet-blokkerend)

- **Peer-topologie op ZAD (clobber-veilig via project-isolatie):** de peer draait in een **eigen
  ZAD-project `mpfm-w3h`**, los van het app-project `mpfm-w3h` dat `deploy.yml` beheert. Er is dus
  geen app-deployment om te overschrijven. Binnen `mpfm-w3h` draait de peer in de deployment
  `test` = één aanmelding van de federatie-OIN (singleton). De inway bereikt `magazijna`
  **cross-project via de ingress-URL** (`https://magazijna-<app-deployment>-mpfm-w3h.<base-domain>`,
  https/:443). Eigen project betekent ook een **eigen ZAD-API-key** (`ZAD_API_KEY_FSCORGA`). De
  raw v2-API maakt geen nieuwe deployments; `test` is doorgaans het project-default en bestaat al
  (anders eenmalig leeg in de UI aanmaken). De `zad-deploy-peer.yml`-workflow deployt op elke
  PR-push naar `mpfm-w3h`/`test`.
- **Interne-mTLS SAN — OPGELOST (least-privilege).** Elk internal-cert draagt nu zijn **eigen
  concrete** hostnamen: de publieke ZAD-hostnaam (`magazijna-fscmgr-test-mpfm-w3h.<base-domain>` op de
  manager, `magazijna-fscctl-…`, `magazijna-fscinway-…`, `magazijna-fsctxlog-…`) **plus** — sinds de multi-poort-fix — de
  cluster-interne Service-DNS (`test-magazijna-fscmgr` + `test-magazijna-fscmgr.rig-prd-mpfm-w3h.svc.cluster.local`),
  waarnaar het interne mTLS-verkeer sinds 2026-07-13 verbindt. Géén domein-brede wildcard (alle
  SAN's zijn concrete namen), zodat de certs niet voor het hele gedeelde Rijks-hosting-domein geldig
  zijn. Bewezen met `verify.sh` + `openssl`. Verandert het project of de deployment-naam, dan moeten
  de bijbehorende publieke én Service-DNS-hostnamen als SAN opnieuw uitgegeven en geüpload worden.
  **Uitzondering:** deze "géén wildcard"-claim geldt niet voor
  `pki/peers/directory/directory/csr.json` — die draagt wél een domein-brede wildcard-SAN
  (`*.rig.prd1.gn2.quattro.rijksapps.nl`), ongewijzigd overgenomen uit de lokale-harness
  directory-stub van de bronrepo. `pki/issue.sh` itereert onvoorwaardelijk over ALLE
  `peers/*/*/csr.json`, dus bij een echte ZAD-run (het gedocumenteerde pad: fsc-testnet's
  échte `ca/root.pem` + `ca/intermediate.pem` in `pki/ca/` zetten, `init-ca.sh` overslaan,
  `issue.sh` draaien) tekent de échte group-CA óók deze wildcard-cert. Dat is een risico dat
  vóór een echte ZAD-run moet worden geadresseerd (bv. `peers/directory/` uitsluiten van die
  `issue.sh`-aanroep, of de SAN versmallen).
- **txlog is verplicht voor een niet-directory manager — toegevoegd.** OpenFSC v1.43.7 faalt hard
  op `tx-log-api-address is required when the manager does not function as the directory` als
  `TX_LOG_API_ADDRESS` leeg is. De eerdere aanname (txlog op ZAD leeglaten tot #728) klopt dus niet;
  er draait nu een component `magazijna-fsctxlog` (txlog-api-image, internal-PKI mTLS), en magazijna-fscmgr/magazijna-fscinway
  wijzen ernaar. txlog-*hardening* / het echte data-pad blijft #728.
- **DB: self-hosted Postgres i.p.v. ZAD-managed (2026-07-15).** ZAD's managed Postgres laat ons de
  init/schema's niet inrichten, en toen manager/controller/txlog één gedeelde DB kregen, botsten hun
  golang-migrate `schema_migrations`-tellers: de controller-migratie zag de manager-versie, sloeg over,
  en `controller.services` ontbrak (`42P01`). Oplossing: een eigen postgres-component `magazijna-fscpg` die we
  volledig beheren — één database met **geïsoleerde migratie-tellers per component**, waarbij de
  componenten zich niet gelijk gedragen (2026-07-16): **manager en txlog** isoleren hun teller via een
  eigen `search_path`-schema (`manager`/`txlog`, aangemaakt door `deploy/zad/postgres-init.sql`,
  UI-attachment op `/docker-entrypoint-initdb.d`). De **controller is de uitzondering**: mét een vooraf
  aangemaakt `controller`-schema + `search_path=controller` liep migratie #1 dirty vast — die draait
  daarom **zonder** search_path (`ZAD_CTL_SCHEMA=""`), maakt z'n eigen `controller`-schema aan
  (schema-gekwalificeerde DDL) en houdt z'n teller in `public` (los van manager/txlog). Concrete DSN in
  `env_vars` (geen ZAD `$DATABASE_*` meer); wachtwoord via `ZAD_PG_PASSWORD` (niet gecommit). Manager en
  manager/controller/txlog migreren bij boot via hun eigen wrapper-image
  (`{manager,controller,txlog}-migrate`, `migrate up && serve`). Aandachtspunt:
  zonder persistent volume is `magazijna-fscpg` ephemeral (prima voor test; PVC voor een blijvende peer).
- **`POST /components` werkt de env van een BESTAANDE component niet bij — env is UI-beheerd.**
  Bewezen: een `TX_LOG_API_ADDRESS` die na de eerste creatie werd gezet (via re-POST én via een
  extra re-roll-`:upsert-deployment`) bereikte de manager nooit — verse pods bleven
  `tx-log-api-address not set` geven. De component-env is bevroren op wat er bij de éérste creatie
  in zat. Dit spiegelt het app-model (zad-actions): de deploy-API beheert images/refs, runtime-env
  komt uit `clone-from`/de UI. Gevolg voor deze peer: de component-env wordt **in de UI** gezet/
  bijgewerkt (concrete waarden, geen `$DEPLOYMENT_NAME`-substitutie nodig want de deployment is vast
  `test`/`mpfm-w3h`); de Postgres-DSN is sinds de self-hosted `magazijna-fscpg` óók concreet (geen `$DATABASE_*`
  meer). De workflow
  blijft betrouwbaar voor images/refs. Componenten NIET verwijderen om env te wijzigen — dan raak je
  de cert-attachments (UI-only per component) kwijt.
- **Interne-mTLS poort/routering op ZAD — OPGELOST (2026-07-13), zie
  zad-fsc-mesh-blocker.md (bron-repo moza-fsc-org-a/docs/, niet meeverhuisd).** Was van 2026-07-10 t/m 2026-07-13
  geblokkeerd: een ZAD-component publiceerde **precies één** inbound-poort, dus de interne FSC-API's
  op `:9443`/`:9444` hadden geen ClusterIP-Service en waren cluster-intern onbereikbaar (`x509:
  certificate signed by unknown authority` omdat het interne verkeer noodgedwongen over de
  `:443`-group-ingress liep). Het RIG/ZAD-team heeft de **multi-poort-fix** uitgerold: een component
  exposet nu **alle** poorten uit `ports.inbound` als Service-poort (`AddComponentRequest.ports`,
  array; `ports[0]` blijft de ingress). Elke poort krijgt een Service `<deployment>-<component>`,
  intern bereikbaar als `test-magazijna-fscmgr:9443` enz. Onze deploy is daarop aangepast: de interne edges
  (controller→manager:9443, manager/inway→controller:9443, inway→manager:9444, →txlog:8443) wijzen
  nu naar die cluster-Service-DNS i.p.v. `:443`, de internal-certs dragen `test-<comp>` (+ svc-FQDN)
  als SAN, en `upsert-peer.sh` zendt per component de `ports`-array (magazijna-fscmgr `8443,9443,9444`; magazijna-fscctl
  `8080,9443,9444`). De externe mesh (magazijna-fscmgr/magazijna-fscinway op `:443`, SNI-passthrough) blijft ongewijzigd.
  Hiermee is **AC-4 (publiceren) gedeblokkeerd**: de extern gepubliceerde controller-UI kan nu
  intern de manager op `:9443` bereiken en een servicePublication-contract laten ondertekenen.
- **Echte magazijn-OIN in een publiek repo** — stond al in `application.properties`; akkoord,
  hier expliciet genoteerd.
- **Cert-portal op ZAD** — repo-A-vervolg; buiten #780.
- **txlog-hardening / e2e-data-pad** — #728.

## Addendum 2026-07-31 — migratie naar demo/environment + co-locatie

Dit ontwerp beschreef oorspronkelijk een **eigen** ZAD-project (`mpfoa-e2w`) voor de
peer, in de losse repo `moza-fsc-org-a`. Bij de migratie naar
`demo/environment/magazijn-a/` in `moza-poc-fbs-berichtenbox` is dat gewijzigd:

- De peer draait sindsdien **co-located** in `mpfm-w3h` (het bestaande ZAD-project van
  de `magazijna`/`magazijnb`-app), niet meer in een eigen project.
- De ZAD-componentnamen kregen het prefix `magazijna-fsc*` (was `mgz*`) om
  dubbelzinnigheid met andere componenten in het gedeelde project te voorkomen.
- De doorlopende image-tag-updates lopen via de bestaande `deploy-test-magazijnen`-job
  in `.github/workflows/deploy.yml`; de losse `zad-deploy-peer.yml`-workflow is
  vervallen. `upsert-peer.sh` blijft bestaan als lokaal plan/validate/apply-hulpmiddel
  voor de eenmalige componentcreatie en voor debugging.
- Het OIN is gewijzigd naar `00000000000000100000` (was `00000001003214345000`,
  hergebruikt de niet-FSC-specifieke `MAGAZIJN_A_GRANT_HASH`-env-varnaam blijft gelijk).

Zie `docs/plans/2026-07-30-demo-environment-fsc-peers-design.md` (directorystructuur)
en `docs/plans/2026-07-31-magazijn-a-peer-migratie-plan.md` (uitvoering) in
`moza-poc-fbs-berichtenbox` voor de volledige besluitvorming.
