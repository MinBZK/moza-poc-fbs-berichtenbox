**Status:** Concept

# Ontwerp — FSC-peers integreren in `demo/environment/`

## Aanleiding

De FSC-infra voor deze PoC leeft nu verspreid over drie losse repo's, volgens een eerder
vastgelegde project-isolatie-aanpak (elke peer = eigen repo/ZAD-project):

- **`moza-fsc-testnet`** — de gedeelde kern: directory (group-anker), group-CA,
  `example-provider`/`example-consumer`-templates.
- **`moza-fsc-org-a`** — provider-peer die `berichtenmagazijn` als dienst aanbiedt
  (huidige peer-id `magazijn-a`, OIN `00000001003214345000`).
- **`moza-fsc-testconsumer`** — consumer-peer die de uitvraagkant deployt (huidige
  peer-id `uitvraag-org`, test-OIN `00000000000000000020`).

We verhuizen de org-a- en uitvraag-peer naar `demo/environment/` in déze repo, zodat
`moza-poc-fbs-berichtenbox` de canonieke bron wordt voor beide peers. `moza-fsc-org-a` en
`moza-fsc-testconsumer` worden na de migratie gearchiveerd. `moza-fsc-testnet` (de gedeelde
directory/group-CA) blijft bewust **apart** — die kern is org-onafhankelijk en kan door
meerdere consumer-repo's tegelijk gebruikt worden.

## Peer-namen

De twee peers krijgen de mapnamen `magazijn-a` en `logius`:

- `magazijn-a` — provider-peer (huidige OIN `00000001003214345000`), spiegelt de bestaande
  peer-id die al gebruikt wordt in `application.properties` (`magazijnen."<OIN>".naam=Magazijn A`),
  Bruno-env-vars en PKI-paden.
- `logius` — consumer-peer die de uitvraag deployt (vervangt de huidige naam `uitvraag-org`).

De gewenste OIN's zijn aangevuld met voorloopnullen tot het standaard 20-cijferige formaat dat
elders in deze repo gebruikt wordt (bv. `00000001003214345000`):

| Peer | OIN (kort) | OIN (20 posities) |
|------|-----------|--------------------|
| `magazijn-a` | `100000` | `00000000000000100000` |
| `logius` | `1000` | `00000000000000001000` |

## ZAD-project-colocatie

Beide peers draaien **niet** in een eigen ZAD-project (zoals `moza-fsc-org-a` nu doet met
`mpfoa-e2w`), maar in het bestaande ZAD-project van de app die ze begeleiden:

- **`magazijn-a`** → project `mpfm-w3h` (magazijnen — waar `magazijna`/`magazijnb`/clickhouse
  al draaien).
- **`logius`** → project `mpfb-8wh` (berichtenuitvraag — waar de `uitvraag`-app al draait).

`deploy.yml` doet per project al één `:upsert-deployment`-call met de volledige
componentenlijst (zie de bestaande `{"name": "magazijna", "image": ...}`-entries). De
FSC-peer-componenten (manager/inway of manager/outway, controller, txlog, postgres) worden
**toegevoegd aan die bestaande component-body**, niet via een losse workflow/upsert-call —
twee onafhankelijke upserts tegen dezelfde deployment zouden elkaars componenten kunnen
overschrijven. Er komen dus **geen** `demo-zad-deploy-*.yml`-workflows; de rol van
`upsert-peer.sh` (nu de CI-apply-stap in de losse repo's) verschuift naar een lokaal
plan/validate-hulpmiddel — de daadwerkelijke apply loopt via `deploy.yml`.

**Componentnamen krijgen een app-prefix**, omdat het project straks componenten van meerdere
apps/peers samen bevat (bv. `magazijnb` zonder FSC-peer ernaast in `mpfm-w3h`) en generieke
namen als `mgzmgr`/`inway` dan dubbelzinnig zouden zijn:

| Peer | Componenten |
|------|-------------|
| `magazijn-a` | `magazijna-fscmgr`, `magazijna-fscinway`, `magazijna-fscctl`, `magazijna-fsctxlog`, `magazijna-fscpg` |
| `logius` | `uitvraag-fscmgr`, `uitvraag-fscoutway`, `uitvraag-fscctl`, `uitvraag-fsctxlog`, `uitvraag-fscpg` |

> **Aandachtspunt voor de implementatiefase:** met peer en app in hetzelfde project/dezelfde
> deployment kan de inway/outway de app mogelijk in-cluster bereiken in plaats van via de
> publieke cross-project ingress-URL (`ZAD_MAGAZIJNA_UPSTREAM_URL` e.d. in het huidige
> org-a-ontwerp) — te verifiëren bij uitwerking.

## Directorystructuur

```
demo/
└── environment/
    ├── README.md                     # topologie: externe directory (moza-fsc-testnet) ↔
    │                                  # magazijn-a ↔ logius, en hoe berichtenmagazijn/
    │                                  # berichtenuitvraag hierop aansluiten
    ├── magazijn-a/                   # provider-peer (was moza-fsc-org-a)
    │   ├── README.md
    │   ├── pki/
    │   │   ├── README.md
    │   │   ├── init-ca.sh, issue.sh, verify.sh, gen-csr.sh, gen-crl.sh,
    │   │   │   combine-pem.sh, fix-permissions.sh, zad-bundle.sh
    │   │   ├── ca.json, config.json, intermediate.json, internal-ca.json
    │   │   ├── certportal-proof.md
    │   │   └── peers/**/csr.json     # CSR-templates (getrackt; gegenereerde certs niet)
    │   ├── deploy/
    │   │   ├── local/                # docker-compose harness: directory + peer + router
    │   │   │   ├── README.md, docker-compose.yaml, haproxy.cfg, postgres-init.sql,
    │   │   │   │   .env.example
    │   │   │   └── run-smokes.sh, smoke-announce.sh, publish-service.sh,
    │   │   │       smoke-discover.sh
    │   │   └── zad/                  # ZAD-runbooks + lokaal plan/validate-hulpmiddel
    │   │       ├── README.md, upsert-peer.sh, cert-manifest.md, verify-zad.md,
    │   │       │   postgres-init.sql
    │   └── docs/
    │       └── design.md             # bestaand #780-ontwerp, 1-op-1 meeverhuisd
    └── logius/                       # consumer-peer (was moza-fsc-testconsumer)
        ├── README.md
        ├── pki/                      # zelfde subset als magazijn-a
        ├── deploy/
        │   ├── local/
        │   └── zad/
        │       └── manager-migrate/  # eigen build-context (Dockerfile + entrypoint.sh)
        └── docs/
            └── design.md
```

**Waarom deze knip:** elke peer krijgt dezelfde interne indeling — dat spiegelt wat er nu al
in de losse repo's staat, dus de migratie is vooral "verplaatsen", geen herontwerp. De
bijna-identieke compose-/PKI-scripts tussen `magazijn-a` en `logius` worden bewust **niet**
gededupliceerd naar een gedeelde `_lib/`-laag: dat is ook nu al zo tussen de twee losse repo's,
en met precies twee peers is een gedeelde abstractie prematuur (rule of three). Wel deelt elke
peer zijn group-CA-materiaal (root/intermediate) met de externe `moza-fsc-testnet`-directory —
dat blijft zo, alleen de her-issue-stap (`pki/issue.sh`) verhuist mee.

**Aanvulling (2026-08-13):** met een derde peer op komst is de rule-of-three-drempel bereikt —
`docs/plans/2026-08-13-demo-environment-gedeelde-fsc-harness-lib.md` voert alsnog een gedeelde
`demo/environment/lib/fsc-harness.sh` in voor de `deploy/local/`-scripts (niet de `pki/`-scripts,
die blijven vooralsnog gedupliceerd).

## CI / GitHub Actions

GitHub Actions-workflows moeten aan de repo-root staan (`.github/workflows/`) — ze kunnen niet
genest onder `demo/`. Elke org-repo heeft nu vier workflows (`zad-deploy-peer.yml`, `lint.yml`,
`codeql.yml`, `scorecard.yml`). Door de ZAD-project-colocatie (zie hierboven) vervalt
`zad-deploy-peer.yml` volledig — die logica gaat op in de bestaande `deploy.yml`. De overige
drie consolideren in de bestaande repo-brede CI van `moza-poc-fbs-berichtenbox` (CodeQL,
Scorecard, architectuur-validatie draaien al repo-breed en nemen `demo/` vanzelf mee). Er komen
dus **geen nieuwe workflow-bestanden** bij; alleen `deploy.yml` wijzigt (extra componenten in de
bestaande `mpfm-w3h`- en `mpfb-8wh`-upsert-calls).

## `.gitignore`-aanvullingen

Privésleutel-materiaal wordt nooit gecommit (zoals nu ook al het geval is in de losse repo's).
`deploy/local/.env` valt al onder de bestaande generieke `*.local`/`.env`-regels; vier nieuwe
regels dekken de per-peer gegenereerde PKI-output:

```gitignore
# FSC-peer certificaten (privésleutels — NOOIT committen)
demo/environment/*/pki/ca/
demo/environment/*/pki/out/
demo/environment/*/pki/internal/
demo/environment/*/pki/zad-upload/
```

## Wat niet meeverhuist

- De lege `organisatie-a-fsc-peer/`-map uit `moza-fsc-org-a` (ongebruikt artefact).
- `lint.yml`/`codeql.yml`/`scorecard.yml` per peer (zie CI-sectie hierboven).
- `zad-deploy-peer.yml` per peer — logica gaat op in `deploy.yml` (zie ZAD-project-colocatie).
- `moza-fsc-testnet` (blijft een aparte, gedeelde repo).

## Vervolgstappen (buiten dit ontwerp)

Dit document beschrijft alleen de doelstructuur. De daadwerkelijke migratie (git-historie
meenemen of niet, secrets/`ZAD_API_KEY_*`-namen aanpassen richting de bestaande
`ZAD_API_KEY_UITVRAAG`/`ZAD_API_KEY_MAGAZIJNEN`, OIN-waardes definitief vaststellen, de
`deploy.yml`-componentenlijsten uitbreiden, in-cluster vs. ingress-URL voor de
peer-naar-app-upstream, archiveren van `moza-fsc-org-a`/`moza-fsc-testconsumer`, README's
bijwerken met de nieuwe paden) wordt uitgewerkt in een implementatieplan via de
`writing-plans`-skill.
