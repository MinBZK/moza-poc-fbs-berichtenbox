# Verificatie ná apply — logius-peer op ZAD

> Draaiboek: wat de operator ná een geslaagde `upsert-peer.sh apply` + cert-attachments (zie
> `cert-manifest.md`) nog controleert.

## Volgorde

1. `upsert-peer.sh apply` gedraaid → deployment `fsc-logius` + componenten bestaan in het gedeelde
   ZAD-project `mpfb-8wh`, elk met zijn `ports`-array (logius-fscmgr `8443,9443,9444`; logius-fscctl
   `8080,9443,9444`; logius-fscoutway/logius-fsctxlog `8443`; logius-fscinway `8443`) zodat de
   interne mTLS-poorten een cluster-Service (`fsc-logius-logius-<comp>:<poort>`) krijgen.
2. Cert-attachments gemount (zie `cert-manifest.md`) + "Publicatie op het web"
   (passthrough-TLS, modus 2) op logius-fscmgr **en** logius-fscinway ingesteld in de ZAD-UI. De
   outway logius-fscoutway blijft functioneel egress-only richting de mesh, maar heeft sinds
   2026-08-13 óók een eigen "Publicatie op het web" (`tls: standard`, geen passthrough — dit is
   niet de mesh-SNI-route, maar de lokale serve-poort `8443` waarop de `berichtenuitvraag`-app
   'm aanroept). Reden: de per-deployment tenant-baseline-NetworkPolicy in ZAD isoleert de
   `test`- en `fsc-logius`-deployment van elkaar (elke deployment mag alleen naar zichzelf +
   platform-namespaces, ongeacht dat ze in hetzelfde project/dezelfde namespace zitten); de
   ClusterIP-service `fsc-logius-logius-fscoutway:8443` is daardoor vanuit `test` onbereikbaar,
   terwijl verkeer via de ingress-controller wél is toegestaan. Nog te verifiëren: of `tls:
   standard` hier volstaat, of dat de serve-poort — net als manager/inway — passthrough (eigen
   cert) nodig heeft. Herzie deze publicatie zodra ZAD een manier biedt om gericht een
   NetworkPolicy-uitzondering tussen deployments binnen hetzelfde project te configureren; dan
   kan de outway weer zuiver intern (zonder publieke ingress) bereikt worden.
3. Componenten herstart en boot-logs foutloos (zie `cert-manifest.md`, laatste sectie) — in het
   bijzonder GEEN `x509: certificate signed by unknown authority` meer op de controller: die
   bereikt de manager nu intern op `fsc-logius-logius-fscmgr:9443` (interne-PKI) i.p.v. de `:443`-group-ingress.

## (a0) logius-fscinway (inway) — draait en registreert zich

- **Pod draait** — geen restart-loop op de logius-fscinway-component in de ZAD-UI.
- **Geen cert-ketenfout in de boot-log** — met name geen `certificate signed by unknown authority`
  (verkeerde group/internal-cert verwisseld, zie `cert-manifest.md`) en geen handshake-fout tegen
  `fsc-logius-logius-fscctl:9443` of `fsc-logius-logius-fscmgr:9444`.
- **Registratie bij logius-fscctl zichtbaar** — de controller-UI (of de Registration-API) toont de inway
  als geregistreerde inway voor deze peer.

## (a) Announce — consumer-OIN vindbaar in de directory

Verwacht gedrag (analoog aan `deploy/local/smoke-announce.sh`, maar tegen de
ZAD-directory-DB i.p.v. de lokale compose-postgres):

```bash
# Via de logius-fscmgr-mesh-host (:443), met de group-cert als client-cert:
curl -sS --cert <group-cert> --key <group-key> --cacert <group-root> \
  "https://<dirmgr-host-op-ZAD>/v1/peers" | jq '.[] | select(.id == "00000000000000001000")'
```

Verwacht: één entry met `id: "00000000000000001000"` en een `manager_address` die eindigt op
`:443` en het logius-fscmgr-hostpatroon (`logius-fscmgr-<deployment>-<project>.<base-domain>`) bevat.

Alternatief (UI): log in op de directory-UI (repo A's `dirui`-component) en zoek de peer op OIN.

## (b) Discover + data-pad — vervolg op ZAD

Buiten scope van deze levering: `berichtenmagazijn` discoveren in de catalogus en het echte
data-pad (`logius-fscoutway → inway → berichtenmagazijn`) bewijs je op ZAD tegen de échte directory + de
draaiende magazijn-a-peer (`demo/environment/magazijn-a/`). Dat vereist een geaccepteerd
afnemer-contract (ServiceConnectionGrant) — nog niet onderdeel van dit ontwerp.

`berichtenuitvraag`'s `MAGAZIJN_A_URL` wijst dan naar `https://fsc-logius-logius-fscoutway:8443`
(cluster-interne Service-DNS, zie `LISTEN_ADDRESS`/poort in `upsert-peer.sh`): peer en app delen
het project `mpfb-8wh` en dus de namespace, en de ingress-URL-variant vervalt omdat de outway
bewust niet op het web gepubliceerd is (zie stap 2 hierboven).

### Inbound data-pad — profiel-service (lokaal bewezen, ZAD-apply is handmatig vervolgwerk)

Lokaal bewezen in `deploy/local/` (`publish-service.sh` + `smoke-discover.sh` +
`consume-service.sh`, zie `docs/plans/2026-08-12-logius-profiel-service-fsc-publicatie.md`):
`logius` publiceert de dienst `profiel-service` op zijn eigen inway en heeft een geldig,
zelfreferentieel afnemer-contract (consumer-OIN = provider-OIN, want `berichtenuitvraag`'s
eigen outway IS de logius-outway). Op ZAD moet dit nog worden herhaald tegen de échte
infrastructuur:

1. `ZAD_LOGIUS_UPSTREAM_URL` in `upsert-peer.sh` (cross-deployment ingress-URL, https/:443,
   naar analogie van magazijn-a's `ZAD_MAGAZIJNA_UPSTREAM_URL`) — wijst naar de echte
   MOZA Profiel Service, niet naar een stub.
2. `CreateService` via de `logius-fscctl` Administration-API (`SERVICE_NAME=profiel-service`,
   `endpoint_url=<ZAD_LOGIUS_UPSTREAM_URL>`, `inway_address=SELF_ADDRESS` van `logius-fscinway`,
   `https://logius-fscinway-fsc-logius-mpfb-8wh.<base-domain>:443`).
3. Het zelfreferentiële `serviceConnection`-contract opnieuw opzetten tegen de ZAD-manager
   (zelfde POST+PUT-stroom als `consume-service.sh`, met de ZAD-groep-cert-thumbprint van
   `logius-fscoutway`).
4. `PROFIEL_SERVICE_URL=https://fsc-logius-logius-fscoutway:8443` en
   `PROFIEL_SERVICE_GRANT_HASH=<content_hash uit stap 3>` als env-vars op de gedeployde
   `berichtenuitvraag`-app zetten (project `mpfb-8wh`).
5. Een smoke voor het pad `berichtenuitvraag → logius-fscoutway → logius-fscinway → upstream`.

## Acceptatiecriteria — afvinklijst

- [ ] Peer (echte OIN) draait op ZAD: manager + controller + outway + inway + txlog + DB
      (deployment-isolatie binnen het gedeelde project `mpfb-8wh`)
- [ ] Peer heeft een geldige group-cert (getekend onder fsc-testnet's group-CA)
- [ ] Peer meldt zich aan bij de directory (announce)
- [ ] `logius-fscinway` draait en registreert zich bij `logius-fscctl` (zie hierboven, punt a0)
- [ ] `profiel-service` lokaal gepubliceerd + vindbaar + zelfreferentieel contract geldig
      (zie `deploy/local/run-smokes.sh`)
- [ ] Discover + contract + inbound data-pad OP ZAD: vervolgwerk (zie hierboven), niet in deze afvinklijst

Elk vinkje vereist een operator met ZAD-toegang, gegenereerde certs en een draaiende peer.
