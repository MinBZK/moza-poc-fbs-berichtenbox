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
| ZAD-project (co-located) | `magazijnen` / `mpfm-w3h` | repo B `deploy.yml` |
| App-component (inway-upstream) | `magazijna` | repo B `deploy.yml` |
| Dienst-naam in de directory | `berichtenmagazijn` | dit ontwerp |
| FSC-images (pin) | `v1.43.7` (manager/inway/controller/directory-ui) | repo A |
| manager-wrapper-image | `ghcr.io/minbzk/moza-fsc-testnet/manager-migrate:<tag>` | repo A |

## Architectuur

Gespiegeld op repo A's `example-provider`. Per peer een eigen set FSC-componenten; de
magazijn-peer draait **in hetzelfde ZAD-project als de magazijn-app** (mpfm-w3h) zodat de inway
de app via intra-project-DNS bereikt.

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
   componenten in mpfm-w3h, inway → `magazijna`. Bewijst alle vier AC's op de echte directory.

## Acceptatiecriteria (uit #780) → dekking

| AC | Gedekt door |
|----|-------------|
| Magazijn-peer (echte OIN) draait naast de app: manager + inway + controller + DB (ZAD, project-isolatie) | Fase 2 (ZAD-upsert in mpfm-w3h) |
| Peer verkrijgt group-cert via het cert-portal van repo A | Cert-portal lokaal aangetoond + bewezen `issue.sh`+attachment-pad (zie bevinding) |
| Peer meldt zich aan bij de directory (announce) | `smoke-announce.sh` (lokaal + ZAD) |
| `berichtenmagazijn` gepubliceerd + vindbaar in de directory | `publish-service.sh` + discover-check (lokaal + ZAD) |

## Open punten (genoteerd, niet-blokkerend)

- **Intra-project-DNS-vorm** van de `magazijna`-upstream in mpfm-w3h (bare servicenaam vs.
  koppelteken-vorm) — verifiëren bij de ZAD-upsert (Fase 3).
- **Interne-mTLS-adressen op ZAD (SAN + poort) — vóór de eerste `apply` oplossen.** De
  manager↔controller↔inway-registratiecalls (`CONTROLLER_REGISTRATION_API_ADDRESS`,
  `MANAGER_ADDRESS_INTERNAL`, `MANAGER_INTERNAL_UNAUTHENTICATED_ADDRESS`) lopen over de
  INTERNAL-PKI en verifiëren de hostname. In `upsert-peer.sh` wijzen ze naar de ZAD-ingress-
  hostnamen (`mgz{ctl,mgr}-$DEPLOYMENT_NAME-mpfm-w3h.<base-domain>:443`), maar (a) die naam zit
  níét in de magazijn-a internal-cert-SANs (alleen `*.magazijn-a.fsc-test.local`), en (b) de
  interne API's luisteren op `:9443`/`:9444`, terwijl alleen de externe/data-poorten (`:8443`)
  passthrough krijgen. Zonder oplossing falen de registratie-handshakes bij boot (TLS-hostname-
  mismatch en/of verkeerde poort) → de inway registreert niet en er publiceert geen dienst.
  Repo A's directory-deploy (alleen dirmgr+dirui) oefent dit pad niet, dus het is onbewezen.
  Oplossingsrichting: internal-cert-SANs uitbreiden met de ZAD-hostnamen (of een intra-project-
  DNS-alias op `.fsc-test.local` gebruiken) én de interne poorten correct routeren/exposen.
- **Echte magazijn-OIN in een publiek repo** — stond al in `application.properties`; akkoord,
  hier expliciet genoteerd.
- **Cert-portal op ZAD** — repo-A-vervolg; buiten #780.
- **txlog-hardening / e2e-data-pad** — #728.
