# Cert-attachments op ZAD — magazijn-a-peer

> **UITGESTELD — vereist ZAD-key / cluster / certs.** Niets in dit document is uitgevoerd: er
> zijn geen certs gegenereerd (`fsc/pki/issue.sh` vereist `cfssl`, niet beschikbaar in deze
> omgeving), er is niet ingelogd op de ZAD-UI en er is niets gemount. Dit is een draaiboek voor
> de mens, ná `fsc/pki/issue.sh` (zie `fsc/pki/README.md`) en vóór `upsert-peer.sh apply`.

## Waarom UI-only

De ZAD v2 Operations Manager API dekt deployment + componenten (image, env_vars, aliases,
services) maar **geen bijlagen** — net als repo A's directory-deploy
(`deploy/zad/upsert-directory.sh`, zie de header-comment daar). Cert-mounts op
`/etc/fsc/...`-paden gaan dus via de ZAD-UI, per component, als losse attachment-bestanden
(geen `combined.pem` nodig — zie `fsc/pki/zad-bundle.sh`, modus 2/passthrough).

## Volgorde

0. **Group-CA plaatsen (NIET `init-ca.sh`).** Voor de échte directory moet de group-leaf ketenen
   naar fsc-testnet's group-root. Zet fsc-testnet's `ca/root.pem` + `ca/intermediate.pem` (+ keys)
   in `fsc/pki/ca/` — draai `init-ca.sh` **niet** (dat maakt een verse, vreemde CA). De
   INTERNAL-CA blijft wél lokaal/self-signed (die maakt `issue.sh` per-peer aan).
1. `fsc/pki/issue.sh` (UITGESTELD, vereist `cfssl`) — genereert `fsc/pki/out/magazijn-a/*` (group,
   getekend door fsc-testnet's intermediate) en `fsc/pki/internal/magazijn-a/*` (internal).
2. `fsc/pki/zad-bundle.sh magazijn-a` (UITGESTELD, hierboven van afhankelijk) — verzamelt de
   upload-klare set in `fsc/pki/zad-upload/magazijn-a/` met een eigen `MANIFEST.md`
   (bestand → pod-pad → `TLS_*`-env-var, zie dat script voor de exacte `env_for()`-mapping).
3. Per component (`mgzmgr`, `mgzctl`, `mgzinway`) in de ZAD-UI: bijlage toevoegen op het
   `/etc/fsc/...`-pad uit de tabellen hieronder, met de bestandsinhoud uit stap 2's
   upload-set. De paden zijn identiek aan de `TLS_*`-waarden die `upsert-peer.sh` al als
   `env_vars`/`aliases` naar de component stuurt — de attachment moet dus exact op dat pad
   gemount worden, anders faalt de container-boot met een ontbrekend-bestand-fout.

## mgzmgr (manager)

| Bijlage-pad (`/etc/fsc/...`) | Bronbestand (`fsc/pki/...`) | Env-var op mgzmgr |
|-------------------------------|-------------------------------------------|--------------------|
| `ca/root.pem` | `ca/root.pem` | `TLS_GROUP_ROOT_CERT` |
| `out/magazijn-a/manager/cert.pem` | `out/magazijn-a/manager/cert.pem` | `TLS_GROUP_CERT`, `TLS_GROUP_TOKEN_CERT`, `TLS_GROUP_CONTRACT_CERT` |
| `out/magazijn-a/manager/key.pem` | `out/magazijn-a/manager/key.pem` | `TLS_GROUP_KEY`, `TLS_GROUP_TOKEN_KEY`, `TLS_GROUP_CONTRACT_KEY` |
| `internal/magazijn-a/ca/root.pem` | `internal/magazijn-a/ca/root.pem` | `TLS_ROOT_CERT`, `TLS_INTERNAL_UNAUTHENTICATED_ROOT_CERT` |
| `internal/magazijn-a/manager/cert.pem` | `internal/magazijn-a/manager/cert.pem` | `TLS_CERT`, `TLS_INTERNAL_UNAUTHENTICATED_CERT` |
| `internal/magazijn-a/manager/key.pem` | `internal/magazijn-a/manager/key.pem` | `TLS_KEY`, `TLS_INTERNAL_UNAUTHENTICATED_KEY` |

## mgzctl (controller)

| Bijlage-pad (`/etc/fsc/...`) | Bronbestand (`fsc/pki/...`) | Env-var op mgzctl |
|-------------------------------|-------------------------------------------|--------------------|
| `internal/magazijn-a/ca/root.pem` | `internal/magazijn-a/ca/root.pem` | `TLS_ROOT_CERT` |
| `internal/magazijn-a/controller/cert.pem` | `internal/magazijn-a/controller/cert.pem` | `TLS_CERT` |
| `internal/magazijn-a/controller/key.pem` | `internal/magazijn-a/controller/key.pem` | `TLS_KEY` |

De controller heeft geen group-cert nodig (hij spreekt geen mesh-verkeer met andere peers, alleen
de eigen manager op de internal-PKI) — vandaar geen `out/magazijn-a/controller/*`-rij.

## mgzinway (inway)

| Bijlage-pad (`/etc/fsc/...`) | Bronbestand (`fsc/pki/...`) | Env-var op mgzinway |
|-------------------------------|-------------------------------------------|--------------------|
| `ca/root.pem` | `ca/root.pem` | `TLS_GROUP_ROOT_CERT` |
| `out/magazijn-a/inway/cert.pem` | `out/magazijn-a/inway/cert.pem` | `TLS_GROUP_CERT` |
| `out/magazijn-a/inway/key.pem` | `out/magazijn-a/inway/key.pem` | `TLS_GROUP_KEY` |
| `internal/magazijn-a/ca/root.pem` | `internal/magazijn-a/ca/root.pem` | `TLS_ROOT_CERT` |
| `internal/magazijn-a/inway/cert.pem` | `internal/magazijn-a/inway/cert.pem` | `TLS_CERT` |
| `internal/magazijn-a/inway/key.pem` | `internal/magazijn-a/inway/key.pem` | `TLS_KEY` |

## Na het mounten

Herstart (of laat ZAD herstarten na attachment-wijziging) elk component en controleer de boot-log
op een TLS-laadfout — een fout pad of een verwisselde group/internal-cert faalt hard bij startup
("no such file", of een handshake-fout tegen de verkeerde CA). Ga daarna verder met
`verify-zad.md`.
