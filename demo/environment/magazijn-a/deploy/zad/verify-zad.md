# Verificatie ná apply — magazijn-a-peer op ZAD

> Draaiboek: wat de operator ná een geslaagde `upsert-peer.sh apply` + cert-attachments (zie
> `cert-manifest.md`) nog controleert.

## Volgorde

1. `upsert-peer.sh apply` gedraaid → deployment + componenten bestaan in project `mpfm-w3h`,
   elk met zijn `ports`-array (magazijna-fscmgr `8443,9443,9444`; magazijna-fscctl `8080,9443,9444`; magazijna-fscinway/magazijna-fsctxlog
   `8443`) zodat de interne mTLS-poorten een cluster-Service (`test-<comp>:<poort>`) krijgen.
2. Cert-attachments gemount (zie `cert-manifest.md`) + "Publicatie op het web"
   (passthrough-TLS, modus 2) op magazijna-fscmgr/magazijna-fscinway ingesteld in de ZAD-UI.
3. Componenten herstart en boot-logs foutloos (zie `cert-manifest.md`, laatste sectie) — in het
   bijzonder GEEN `x509: certificate signed by unknown authority` meer op de controller: die
   bereikt de manager nu intern op `fsc-magazijna-magazijna-fscmgr:9443` (interne-PKI) i.p.v. de `:443`-group-ingress.

## (a) Announce — magazijn-OIN vindbaar in de directory

Verwacht gedrag (analoog aan `deploy/local/smoke-announce.sh`, maar tegen de
ZAD-directory-DB i.p.v. de lokale compose-postgres):

```bash
# Via de magazijna-fscmgr-mesh-host (:443), met de group-cert als client-cert:
curl -sS --cert <group-cert> --key <group-key> --cacert <group-root> \
  "https://<dirmgr-host-op-ZAD>/v1/peers" | jq '.[] | select(.id == "00000000000000100000")'
```

Verwacht: één entry met `id: "00000000000000100000"` en een `manager_address` die eindigt op
`:443` en het magazijna-fscmgr-hostpatroon (`magazijna-fscmgr-<deployment>-mpfm-w3h.<base-domain>`) bevat.

Alternatief (UI): log in op de directory-UI (repo A's `dirui`-component) en zoek de peer op OIN.

## (b) `berichtenmagazijn` publiceren op de ZAD-controller

Sinds de multi-poort-fix (2026-07-13) loopt dit weer via de **normale** interne flow: de controller
maakt de dienst + het servicePublication-contract aan en laat de manager het ondertekenen over de
interne-PKI op `fsc-magazijna-magazijna-fscmgr:9443` — geen mesh-omweg meer nodig.

- **UI (aanbevolen — werkt nu end-to-end)**: via de extern gepubliceerde magazijna-fscctl-beheer-UI
  (`LISTEN_ADDRESS_UI`, extern op `https://magazijna-fscctl-<deployment>-mpfm-w3h.<base-domain>:443`,
  `AUTHN_TYPE=none`) een dienst aanmaken met naam `berichtenmagazijn`, `endpoint_url` = de waarde
  uit `upsert-peer.sh`'s `MAGAZIJNA_UPSTREAM_URL` (de ingress-URL van de app cross-deployment, bv.
  `https://magazijna-test-mpfm-w3h.<base-domain>`) en `inway_address` = de geregistreerde magazijna-fscinway
  `SELF_ADDRESS`. De controller praat achter de schermen intern met de manager (`:9443`) die het
  contract ondertekent.
- **Script (alleen vanuit de cluster)**: `deploy/local/publish-service.sh` POST naar de magazijna-fscctl
  Administration-API (`:9444`) en de manager-internal (`:9443`) — beide zijn nu cluster-interne
  Services (`fsc-magazijna-magazijna-fscctl:9444`, `fsc-magazijna-magazijna-fscmgr:9443`) met de **internal-PKI**, dus dit kan alleen
  vanuit een pod/job binnen namespace `rig-prd-mpfm-w3h` draaien (niet vanaf een externe host; die
  poorten hebben geen ingress). Buiten de cluster is de UI-route de enige.

Verwacht: de contract-respons bevat `content_hash` (manager signt) en de directory (
`AUTO_SIGN_GRANTS=servicePublication,delegatedServicePublication` op de directory-manager, buiten
deze bundel) accepteert automatisch.

## (c) Discover — dienst vindbaar

Analoog aan `deploy/local/smoke-discover.sh`, tegen de ZAD-directory-DB of
via de directory-UI: `berichtenmagazijn` moet als dienst van OIN `00000000000000100000` in de
catalogus staan.

## (d) Outway — uitgaand verkeer van het magazijn

Sinds de notificatie-push heeft deze peer ook een outway (`magazijna-fscoutway`): het magazijn is
niet alleen aanbieder van `berichtenmagazijn` maar ook afnemer van `notificatieservice`, en dat
verkeer gaat uit door zijn eigen outway. Het component is nieuw op ZAD en moet daar nog aangemaakt
worden: opnemen in de projectspec, certificaten uit `pki/peers/magazijn-a/outway/` uitgeven en de
bijlagen koppelen zoals `cert-manifest.md` dat voor de andere componenten beschrijft. De outway is
egress-only en heeft geen ingress-route nodig.

Het component kan vooruitlopend worden uitgerold zonder gedrag te veranderen: zolang
`NOTIFICATIE_GRANT_HASH` op het magazijn leeg is, blijft de aflevering rechtstreeks lopen.

## Acceptatiecriteria — afvinklijst

- [ ] Peer (echte OIN) draait op ZAD: manager + controller + inway + txlog + DB (project-isolatie)
- [ ] Peer heeft een geldige group-cert (getekend onder fsc-testnet's group-CA)
- [ ] Peer meldt zich aan bij de directory (announce)
- [ ] `berichtenmagazijn` gepubliceerd + vindbaar in de directory
- [ ] `magazijna-fscoutway` draait en registreert zich bij `magazijna-fscctl` (zie (d))

Elk vinkje vereist een operator met ZAD-toegang, gegenereerde certs en een draaiende peer.
