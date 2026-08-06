# FSC-peer `magazijn-a`

Provider-peer die de dienst `berichtenmagazijn` in de FSC-federatie publiceert
(OIN `00000000000000100000`). Migratie van de losse repo `moza-fsc-org-a`; zie
`docs/design.md` voor de volledige ontwerpachtergrond en
`../../../docs/plans/2026-07-30-demo-environment-fsc-peers-design.md` voor de
directorystructuur-beslissing.

## Lokaal draaien

```bash
cd pki && ./init-ca.sh && ./issue.sh && ./gen-crl.sh && ./verify.sh && cd -
cp deploy/local/.env.example deploy/local/.env
printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> deploy/local/.env
docker compose -f deploy/local/docker-compose.yaml up -d
./deploy/local/run-smokes.sh   # verwacht: "ALLE SMOKES GROEN."
docker compose -f deploy/local/docker-compose.yaml down -v
```

## ZAD

Draait co-located in project `mpfm-w3h`, in de eigen deployment `fsc-magazijna` (de
`magazijna`/`magazijnb`-app zelf draait in deployment `test`). `.github/workflows/deploy.yml`
beheert alleen de image-tags (bestaande `deploy-test-magazijnen`-job); eerste creatie van
de componenten + hun env/ports/certs is een eenmalige handmatige stap — zie
`deploy/zad/README.md`, `cert-manifest.md` en `verify-zad.md`.
