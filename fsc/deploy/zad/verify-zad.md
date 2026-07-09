# Verificatie ná apply — magazijn-a-peer op ZAD

> **UITGESTELD — vereist ZAD-key / cluster / certs.** Dit draaiboek is niet uitgevoerd: er is
> geen `ZAD_API_KEY_MAGAZIJNEN` in deze omgeving, geen draaiende peer op ZAD, en de cert-bundle
> is niet gegenereerd. Claim nergens dat de peer draait of dat `apply` geslaagd is — dit document
> beschrijft wat een mens ná een geslaagde `upsert-peer.sh apply` + cert-attachments (zie
> `cert-manifest.md`) nog moet controleren.

## Volgorde

1. `upsert-peer.sh apply` gedraaid (UITGESTELD) → deployment + drie componenten bestaan in
   project `mpfoa-e01`.
2. Cert-attachments gemount (UITGESTELD, zie `cert-manifest.md`) + "Publicatie op het web"
   (passthrough-TLS, modus 2) op mgzmgr/mgzinway ingesteld in de ZAD-UI.
3. Componenten herstart en boot-logs foutloos (zie `cert-manifest.md`, laatste sectie).

## (a) Announce — magazijn-OIN vindbaar in de directory

**UITGESTELD.** Verwacht gedrag (analoog aan `fsc/deploy/local/smoke-announce.sh`, maar tegen de
ZAD-directory-DB i.p.v. de lokale compose-postgres):

```bash
# Via de mgzmgr-mesh-host (:443), met de group-cert als client-cert:
curl -sS --cert <group-cert> --key <group-key> --cacert <group-root> \
  "https://<dirmgr-host-op-ZAD>/v1/peers" | jq '.[] | select(.id == "00000001003214345000")'
```

Verwacht: één entry met `id: "00000001003214345000"` en een `manager_address` die eindigt op
`:443` en het mgzmgr-hostpatroon (`mgzmgr-<deployment>-mpfoa-e01.<base-domain>`) bevat.

Alternatief (UI): log in op de directory-UI (repo A's `dirui`-component) en zoek de peer op OIN.

## (b) `berichtenmagazijn` publiceren op de ZAD-controller

**UITGESTELD.** `:443`-variant van `fsc/deploy/local/publish-service.sh`: dezelfde
create-service + servicePublication-contract-flow, maar tegen de mgzctl Administration-API op
`:443` (mesh) i.p.v. de lokale toolbox-container op de internal-PKI. Twee routes:

- **Script**: kopieer `publish-service.sh` se logica, vervang `$CONTROLLER`/`$MANAGER` door de
  ZAD mesh-hosts (`https://mgzctl-<deployment>-mpfoa-e01.<base-domain>:443` resp. mgzmgr) en het
  cert/key/CA door de group-cert-attachments (niet de internal-cert — de mesh-call loopt over de
  group-PKI, zie `cert-manifest.md`).
- **UI**: via de mgzctl-beheer-UI (`LISTEN_ADDRESS_UI`, `AUTHN_TYPE=none`) een dienst aanmaken
  met naam `berichtenmagazijn`, `endpoint_url` = de waarde uit `upsert-peer.sh`'s
  `MAGAZIJNA_UPSTREAM_URL` (de ingress-URL van de app cross-deployment, bv.
  `https://magazijna-test-mpfm-w3h.<base-domain>`) en `inway_address` = de geregistreerde
  mgzinway `SELF_ADDRESS`.

Verwacht: de contract-respons bevat `content_hash` (manager signt) en de directory (
`AUTO_SIGN_GRANTS=servicePublication,delegatedServicePublication` op de directory-manager, buiten
deze bundel) accepteert automatisch.

## (c) Discover — dienst vindbaar

**UITGESTELD.** Analoog aan `fsc/deploy/local/smoke-discover.sh`, tegen de ZAD-directory-DB of
via de directory-UI: `berichtenmagazijn` moet als dienst van OIN `00000001003214345000` in de
catalogus staan.

## (d) Bestaande FBS-app-tests groen

```bash
./mvnw clean test -pl services/berichtenmagazijn -am
```

**UITGESTELD in de zin dat dit nog niet ná een echte ZAD-apply is herdraaid** — deze commando's
draaien onafhankelijk van de FSC-peer (de app-tests raken geen FSC-componenten). Neem ze hier op
zodat de operator na de ZAD-rollout expliciet nogmaals bevestigt dat de bestaande suite groen
blijft (geen regressie via gedeelde config/env).

## Acceptatiecriteria (#780) — afvinklijst

- [ ] Magazijn-peer (echte OIN) draait naast de app: manager + inway + controller + DB (ZAD,
      project-isolatie)
- [ ] Peer verkrijgt group-cert via het cert-portal van repo A
- [ ] Peer meldt zich aan bij de directory (announce)
- [ ] `berichtenmagazijn` gepubliceerd + vindbaar in de directory

Elk vinkje vereist een mens met ZAD-toegang, gegenereerde certs en een draaiende peer — geen van
de vier is in deze bundel afgevinkt.
