**Status:** Uitgevoerd

# Ontwerp — FSC-peer `logius` naar `demo/environment/logius/`

Vervolg op [`2026-07-30-demo-environment-fsc-peers-design.md`](2026-07-30-demo-environment-fsc-peers-design.md)
(doelstructuur voor beide peers) en op de uitgevoerde `magazijn-a`-migratie
([`2026-07-31-magazijn-a-peer-migratie-plan.md`](2026-07-31-magazijn-a-peer-migratie-plan.md), PR #160).
Dit document legt alleen de `logius`-specifieke beslissingen vast en de punten waarop we
van het overkoepelende ontwerp afwijken.

## Aanleiding

De consumer-kant van de FSC-federatie leeft nog in de losse repo `moza-fsc-testconsumer`
(peer-id `uitvraag-org`, ZAD-project `mpfuc-84g`). Die peer is bidirectioneel: hij neemt af
via een outway (roept `berichtenmagazijn` bij `magazijn-a` aan) en biedt aan via een inway
(nog zonder gepubliceerde dienst). Met `magazijn-a` verhuisd naar `demo/environment/` wordt
`moza-poc-fbs-berichtenbox` de canonieke bron voor beide peers; `logius` is de laatste stap
voordat `moza-fsc-testconsumer` gearchiveerd kan worden.

De bronrepo staat op OpenFSC `v1.43.7`. De testfederatie (`moza-fsc-testnet`) handhaaft de
FSC-versie als group rule en `magazijn-a` draait `v2.5.2`; een peer op `v1.43.7` valt daarmee
buiten de contracten. De versie-upgrade is dus onderdeel van de migratie, geen los traject.

## Identiteit en naamgeving

| Parameter | `moza-fsc-testconsumer` | `demo/environment/logius/` |
|-----------|-------------------------|-----------------------------|
| Peer-naam / mapnaam | `uitvraag-org` | `logius` |
| Peer-OIN (`subject.serialNumber`) | `00000000000000000020` | `00000000000000001000` |
| Group ID | `moza-fbs-test` | ongewijzigd |
| Directory-OIN | `00000000000000000010` | ongewijzigd (externe `moza-fsc-testnet`) |
| ZAD-project | `mpfuc-84g` (eigen project) | `mpfb-8wh` (co-located met de uitvraag-app) |
| ZAD-deployment | `test` | `fsc-logius` |
| Componenten | `uvrpg`, `uvrmgr`, `uvrctl`, `uvrout`, `uvrin`, `uvrtxlog` | `logius-fscpg`, `logius-fscmgr`, `logius-fscctl`, `logius-fscoutway`, `logius-fscinway`, `logius-fsctxlog` |
| API-key-secret | `ZAD_API_KEY_FSCUITVRAAG` | `ZAD_API_KEY_UITVRAAG` (bestaand) |

De OIN volgt het overkoepelende ontwerp (kort `1000`, aangevuld tot 20 posities, naast
`magazijn-a`'s `00000000000000100000`).

**Componentprefix `logius-fsc*`** in plaats van het `uitvraag-fsc*` uit het overkoepelende
ontwerp: de mapnaam, de peer-naam in de PKI-paden en de componentnaam blijven dan één en
hetzelfde woord. De app-component in hetzelfde project heet `uitvraag`; het onderscheid
peer-vs-app blijft daarmee juist zichtbaar.

**Eigen deployment `fsc-logius`**, net als `fsc-magazijna` in `mpfm-w3h`. PR-previews klonen
met `clone-from: test`; een meegekloonde peer zou zich met dezelfde federatie-OIN opnieuw bij
de gedeelde directory aanmelden en zonder de UI-only cert-attachments direct crashloopen. Wat
niet in `test` staat, kan niet meegekloond worden.

## Versie-upgrade v1.43.7 → v2.5.2

Naast de image-tags in de lokale compose-harness en `deploy/zad/upsert-peer.sh` verandert het
ghcr-pad van de migratie-wrappers mee: `moza-fsc-testnet/{manager,controller,txlog}-migrate`
→ `moza-fsc-testnet-{manager,controller,txlog}-migrate` (naamswijziging in `moza-fsc-testnet`,
zoals `magazijn-a` die al consumeert).

**De lokale build-context `deploy/zad/manager-migrate/` verhuist niet mee.** Het overkoepelende
ontwerp noemde die map nog, maar `moza-fsc-testnet` publiceert de drie wrapper-images inmiddels
zelf op ghcr en `magazijn-a` consumeert ze daar. Eén bron voor de wrapper betekent één
digest-pin om bij te houden in plaats van één per peer. De lokale compose-harness trekt de
wrappers voortaan ook van ghcr in plaats van een lokaal gebouwde `manager-migrate:`-tag.

## Postgres

Ongewijzigd overgenomen, alleen hernoemd naar `logius-fscpg`: één self-hosted
`postgres:17`-component, één database `fsc`, geïsoleerde golang-migrate-tellers. `manager` en
`txlog` isoleren via een eigen `search_path`-schema (aangemaakt door
`deploy/zad/postgres-init.sql`, UI-attachment op `/docker-entrypoint-initdb.d`); de
`controller` draait ZONDER `search_path` — mét `search_path` loopt migratie #1 dirty vast — en
houdt zijn teller in `public`. Wachtwoord via `ZAD_PG_PASSWORD`, nooit gecommit.

ZAD's managed Postgres blijft ongeschikt: geen init-scripts en geen `CREATE SCHEMA`-rechten op
eigen voorwaarden.

## Lokale harness

De harness verhuist 1-op-1 mee (eigen directory + peer + SNI-router + smokes), met één
wijziging: de host-poortbindingen verschuiven van `127.0.0.1:8080`/`:8090` (directory-UI en
controller) naar `:8081`/`:8091`. `magazijn-a` bindt die twee poorten al; met beide peers in
één repo moet een gecombineerde demo zonder poortconflict kunnen draaien.

## CI en repo-integratie

Geen nieuwe workflows: `zad-deploy-peer.yml`, `lint.yml`, `codeql.yml` en `scorecard.yml` uit
de bronrepo vervallen (de repo-brede CI dekt `demo/` al). `deploy.yml` krijgt één extra stap in
de bestaande `deploy-test-uitvraag`-job richting deployment `fsc-logius` — spiegel van
de `fsc-magazijna`-stap, alleen tag-updates. De eerste componentcreatie (env/ports) blijft een
eenmalige handmatige `upsert-peer.sh apply` plus UI-only cert-attachments.

`.gitignore` dekt `demo/environment/*/pki/{ca,out,internal,zad-upload}/` al glob-breed; geen
wijziging nodig.

## Koppeling met `berichtenuitvraag`

Geen codewijziging in de service. `magazijnen."00000000000000100000".url` en `.grantHash`
bestaan al; op ZAD wijst `MAGAZIJN_A_URL` naar de `logius-fscoutway` en komt
`MAGAZIJN_A_GRANT_HASH` uit het FSC-contract. Beide zijn projectspec-env buiten deze repo en
horen dus in het runbook, niet in een commit.

## Wat niet meeverhuist

- `deploy/zad/manager-migrate/` (build-context, zie hierboven).
- De vier workflows uit de bronrepo.
- Repo-boilerplate die deze repo al heeft: `LICENSE`, `SECURITY.md`, `SUPPORT.md`,
  `DISCLAIMER.md`, `.markdownlint.yaml`, `.yamllint.yaml`, `.gitignore`, `CLAUDE.md`.
- De bronrepo-plannen onder `docs/superpowers/` — de ontwerpachtergrond zit in `docs/design.md`,
  dat wél meeverhuist.

## Verificatie

Uitvoerbaar zonder Docker/cfssl: `deploy/zad/upsert-peer.sh plan` (jq-only, geen netwerk),
`shellcheck` op de scripts, en repo-brede greps op achtergebleven `uitvraag-org`/`uvr*`/
`mpfuc-84g`/`00000000000000000020`/`v1.43.7`-referenties.

Vereist Docker + cfssl (expliciet als openstaand markeren als de omgeving ze mist, niet
stilzwijgend overslaan): certificaatuitgifte (`pki/issue.sh`, `verify.sh`, `zad-bundle.sh`) en
de lokale smokes (`deploy/local/run-smokes.sh`).

## Openstaand na deze migratie

- Certificaten uitgeven met de group-CA van `moza-fsc-testnet` (`pki/ca/` kopiëren, **niet**
  `init-ca.sh` draaien — dat maakt een verse, vreemde CA).
- Deployment `fsc-logius` eenmalig leeg aanmaken in de ZAD-UI, dan `upsert-peer.sh apply` plus
  de cert-attachments mounten (UI-only).
- Contract/grant tussen `logius` en `magazijn-a` aanmaken en de grant-hash als
  `MAGAZIJN_A_GRANT_HASH` in de `mpfb-8wh`-projectspec zetten.
- `moza-fsc-testconsumer` archiveren zodra de migratie bevestigd werkt.
