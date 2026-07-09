**Status:** Concept

# Ontwerp — Magazijn-provider-peer aansluiten op de FSC-federatie (#780)

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
  + `dirui` (directory-ui) uit. Er is **geen** `ca-cfssl`/`ca-certportal`-component of
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
lokaal in `fsc/pki/ca/` plaatsen en **`init-ca.sh` overslaan**; daarna alleen `issue.sh` draaien.
De per-peer INTERNAL-CA blijft lokaal/self-signed. (`init-ca.sh` blijft de juiste keuze voor de
geïsoleerde lokale compose-proof.) Van de directory zelf zijn géén per-peer client-certs of
private keys nodig — trust loopt volledig via de gedeelde group-PKI.

## Scope

**In scope:** de provider-peer voor **magazijn-a** (echte OIN `00000001003214345000`).

**Buiten scope:** consumer/outway (#725/#726), magazijn-b (`00000001823288444000`, latere
uitbreiding), txlog-hardening/e2e-verantwoording (#728), en het echte data-pad dóór de inway
(#728). De bestaande `berichtenmagazijn`-app verandert niet (config-only ervoor).

## Bekende parameters

| Parameter | Waarde | Herkomst |
|-----------|--------|----------|
| Peer-OIN (= Peer ID = `magazijnId`) | `00000001003214345000` | `services/berichtenmagazijn/.../application.properties:138` |
| Peer-naam | `magazijn-a` | conventie repo B (geen `example-*`) |
| Group ID | `moza-fbs-test` | repo A directory-deploy |
| Directory-OIN | `00000000000000000010` | repo A directory-deploy |
| ZAD-project peer | `mpfoa-e01` (eigen project) | dit ontwerp (was co-located `mpfm-w3h`) |
| ZAD-project app | `magazijnen` / `mpfm-w3h` | repo B `deploy.yml` |
| App-component (inway-upstream) | `magazijna` (cross-project via ingress-URL) | repo B `deploy.yml` |
| Dienst-naam in de directory | `berichtenmagazijn` | dit ontwerp |
| FSC-images (pin) | `v1.43.7` (manager/inway/controller/directory-ui) | repo A |
| manager-wrapper-image | `ghcr.io/minbzk/moza-fsc-testnet/manager-migrate:<tag>` | repo A |

## Architectuur

Gespiegeld op repo A's `example-provider`. Per peer een eigen set FSC-componenten; de
magazijn-peer draait **in een eigen ZAD-project `mpfoa-e01`** (project-isolatie). De
`magazijna`-app draait apart in `mpfm-w3h`; de inway bereikt die **cross-project via de
ingress-URL** (https, :443), niet via intra-project-DNS.

### Componenten van de provider-peer

| Component | ZAD-ref | Rol |
|-----------|---------|-----|
| manager | `mgzmgr` | announce bij de directory + ServicePublicationGrant; `manager-migrate`-wrapper migreert de peer-DB bij boot |
| controller | `mgzctl` | dienst `berichtenmagazijn` aanmaken (Administration-API, `AUTHN_TYPE=none`) + beheer-UI + inway-registratie (Registration-API) |
| inway | `mgzinway` | ingress vóór de `magazijna`-app-component (intra-project DNS); registreert bij de controller |
| DB | ZAD managed Postgres (`postgresql-database`-service) | system-of-record manager + controller |

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
   componenten in `mpfoa-e01`, inway → `magazijna` (cross-project). Bewijst alle vier AC's op de echte directory.

## Acceptatiecriteria (uit #780) → dekking

| AC | Gedekt door |
|----|-------------|
| Magazijn-peer (echte OIN) draait naast de app: manager + inway + controller + DB (ZAD, project-isolatie) | Fase 2 (ZAD-upsert in `mpfoa-e01`) |
| Peer verkrijgt group-cert via het cert-portal van repo A | Cert-portal lokaal aangetoond + bewezen `issue.sh`+attachment-pad (zie bevinding) |
| Peer meldt zich aan bij de directory (announce) | `smoke-announce.sh` (lokaal + ZAD) |
| `berichtenmagazijn` gepubliceerd + vindbaar in de directory | `publish-service.sh` + discover-check (lokaal + ZAD) |

## Open punten (genoteerd, niet-blokkerend)

- **Peer-topologie op ZAD (clobber-veilig via project-isolatie):** de peer draait in een **eigen
  ZAD-project `mpfoa-e01`**, los van het app-project `mpfm-w3h` dat `deploy.yml` beheert. Er is dus
  geen app-deployment om te overschrijven. Binnen `mpfoa-e01` draait de peer in de deployment
  `test` = één aanmelding van de federatie-OIN (singleton). De inway bereikt `magazijna`
  **cross-project via de ingress-URL** (`https://magazijna-<app-deployment>-mpfm-w3h.<base-domain>`,
  https/:443). Eigen project betekent ook een **eigen ZAD-API-key** (`ZAD_API_KEY_FSCORGA`). De
  raw v2-API maakt geen nieuwe deployments; `test` is doorgaans het project-default en bestaat al
  (anders eenmalig leeg in de UI aanmaken). De `zad-deploy-peer.yml`-workflow deployt op elke
  PR-push naar `mpfoa-e01`/`test`.
- **Interne-mTLS SAN — OPGELOST (least-privilege).** Elk internal-cert draagt nu zijn **eigen
  concrete** ZAD-hostnaam (`mgzmgr-test-mpfoa-e01.<base-domain>` op de manager, `mgzctl-…` op de
  controller, `mgzinway-…` op de inway) — géén domein-brede wildcard, zodat de certs niet voor het
  hele gedeelde Rijks-hosting-domein geldig zijn. Bewezen met `verify.sh` + `openssl`. Verandert het
  project of de deployment-naam, dan moeten de bijbehorende hostnamen als SAN opnieuw uitgegeven en
  geüpload worden.
- **txlog is verplicht voor een niet-directory manager — toegevoegd.** OpenFSC v1.43.7 faalt hard
  op `tx-log-api-address is required when the manager does not function as the directory` als
  `TX_LOG_API_ADDRESS` leeg is. De eerdere aanname (txlog op ZAD leeglaten tot #728) klopt dus niet;
  er draait nu een vierde component `mgztxlog` (txlog-api-image + eigen managed Postgres, internal-
  PKI mTLS), en mgzmgr/mgzinway wijzen ernaar. txlog-*hardening* / het echte data-pad blijft #728.
- **Component-config liep één deploy achter — OPGELOST met een re-roll.** Onze apply deed éérst
  `:upsert-deployment` (rolt de pods uit) en pas dáárna `POST /components` (zet env/aliases). Elke
  run rolde dus met de config van de vórige run; een `TX_LOG_API_ADDRESS` die in de tweede deploy
  werd gezet bereikte de manager pas een deploy later (bleef `tx-log-api-address not set`). Fix: ná
  de component-POSTs nóg één `:upsert-deployment`, zodat de rollout met de verse config draait —
  géén component-verwijdering nodig, dus de cert-attachments (UI-only per component) blijven staan.
  Daarnaast: omdat de deployment vast is (`test`/`mpfoa-e01`) gebruiken we ZAD's `$DEPLOYMENT_NAME`-
  substitutie niet meer — bash lost alle inter-component-hostnamen concreet op in `env_vars`; alleen
  de managed-Postgres-DSN (`$DATABASE_*`) blijft in `aliases`.
- **Interne-mTLS poort/routering op ZAD — nog open, verifiëren bij de eerste apply.** De interne
  FSC-API's luisteren op `:9443`/`:9444`, maar een ZAD-component exposet via zijn `:443`-ingress
  precies één containerpoort (mgzctl→8080, mgzmgr/mgzinway→8443). Een call naar
  `mgzctl-test-mpfoa-e01.<base-domain>:443` raakt dus die éne ingress-poort, niet `:9443`. De
  registratiecalls (`CONTROLLER_REGISTRATION_API_ADDRESS`, `MANAGER_ADDRESS_INTERNAL`,
  `MANAGER_INTERNAL_UNAUTHENTICATED_ADDRESS`) moeten waarschijnlijk over **intra-deployment DNS +
  de echte interne poorten** lopen i.p.v. de ingress — bevestigen zodra de certs gemount zijn en
  de pods booten (de mgzinway/mgzctl-logs tonen of de registratie de juiste poort bereikt). Als
  het intra-deployment DNS wordt, verhuizen die SANs mee naar de intra-DNS-namen.
- **Echte magazijn-OIN in een publiek repo** — stond al in `application.properties`; akkoord,
  hier expliciet genoteerd.
- **Cert-portal op ZAD** — repo-A-vervolg; buiten #780.
- **txlog-hardening / e2e-data-pad** — #728.
