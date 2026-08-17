# FSC-peer `logius`

Peer van de uitvraag-organisatie (OIN `00000000000000001000`). Neemt af via een outway
(roept `berichtenmagazijn` bij `magazijn-a` aan) en biedt aan via een inway — die laatste
heeft nog geen gepubliceerde dienst. Migratie van de losse repo `moza-fsc-testconsumer`;
zie `docs/design.md` voor de ontwerpachtergrond en
`../../../docs/plans/2026-08-06-logius-peer-migratie-design.md` voor de migratiebeslissingen.

## Lokaal draaien

```bash
cd pki && ./init-ca.sh && ./issue.sh && ./gen-crl.sh && ./verify.sh && cd -
cp deploy/local/.env.example deploy/local/.env
printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> deploy/local/.env
docker compose -f deploy/local/docker-compose.yaml up -d
./deploy/local/run-smokes.sh   # verwacht: "ALLE SMOKES GROEN."
docker compose -f deploy/local/docker-compose.yaml down -v
```

De harness bindt `127.0.0.1:8081` (directory-UI) en `:8091` (controller-UI) — bewust
verschoven t.o.v. `magazijn-a` (`:8080`/`:8090`), zodat beide peer-harnessen tegelijk
kunnen draaien.

## ZAD

Draait co-located met de `uitvraag`-app in project `mpfb-8wh`, in de EIGEN deployment
`fsc-logius` (niet `test`: PR-previews klonen `test` en zouden de peer met dezelfde
federatie-OIN dupliceren). `.github/workflows/deploy.yml` beheert alleen de image-tags;
eerste creatie van de componenten + hun env/ports/certs is een eenmalige handmatige stap —
zie `deploy/zad/README.md`, `cert-manifest.md` en `verify-zad.md`.
