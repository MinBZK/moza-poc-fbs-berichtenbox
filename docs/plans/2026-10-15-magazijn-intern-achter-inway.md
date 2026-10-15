# Federatieverkeer naar magazijn-a blijft binnen het cluster

**Status:** Concept

Issue: MinBZK/MijnOverheidZakelijk#983. Spiegelbeeld van de uitvraag-kant
(`demo/environment/logius/deploy/zad/cutover-interne-outway.md`).

## Context

De inway van magazijn-a (`magazijna-fscinway`, deployment `fsc-magazijna`) stuurt het verkeer door
naar de `endpoint_url` van de dienst `berichtenmagazijn`. Die staat op het publieke adres van de app
(`https://magazijna-test-mpfm-w3h.<base-domain>`), omdat deployments van hetzelfde project elkaar
zonder netwerkregel niet bereiken. De laatste hop gaat dus het cluster uit en weer in.

Vastgesteld in de projectspec van `mpfm-w3h` (`rig-cluster-projects`, 2026-10-15):

- Er is nog geen `cross-domain-access`-regel tussen `magazijna-fscinway` en `magazijna`. De
  rendered manifests van `test` hebben alleen de baseline-policy en die van de console.
- `publish-on-web` hangt aan de **component** `magazijna`, dus geldt voor `test` én elke preview.
- Previews: de preview-uitvraag roept de preview-`magazijna` rechtstreeks aan op diens publieke
  adres, buiten de federatie om. De uitvraag draait op ZAD met de TLS-eis, en de magazijn-URL kent
  geen onveilige klep; een interne `http://`-URL laat een preview-uitvraag niet starten.

## Knip

De previews mee de federatie in vraagt per preview een eigen dienst, contract en grant-hash
(een FSC-dienst heeft één `endpoint_url`), netwerkregels op de preview-loze peer-deployments, en
opruimen bij het sluiten van de PR. Dat is een eigen stuk CI-werk en wordt een vervolg-issue.

Dit plan doet daarom alleen `test`: de federatie-ingang bereikt de app cluster-intern. De
publicatie van `magazijna` blijft staan tot het vervolg-issue de previews heeft verhuisd; pas dan
kan het acceptatiecriterium "geen publicatie op het web meer" worden afgevinkt.

## Stappen

1. **Repo:** `upsert-peer.sh` neemt het cluster-interne Service-adres als standaard-upstream
   (`http://<deployment>-magazijna.rig-prd-<project>.svc.cluster.local:8090`). Documentatie die
   nog de ingress-route beschrijft (README, design, verify-zad) volgt.
2. **Repo:** draaiboek `demo/environment/magazijn-a/deploy/zad/cutover-interne-upstream.md`:
   netwerkregels, dienst opnieuw publiceren, verificatie aan het transactielogboek, terugrollen.
3. **ZAD (operator, key `mpfm-w3h`):** het draaiboek uitvoeren.
4. **Vervolg-issue MinBZK/MijnOverheidZakelijk#1328:** previews via FSC, daarna de publicatie van
   `magazijna` intrekken.

## Ontwerpkeuzes

- **Plain HTTP op de laatste hop.** De app luistert zelf niet met TLS; de ingress termineerde het.
  Dezelfde afweging als aan de uitvraag-kant, en dezelfde voorwaarde als de LDV-verbinding:
  transport-encryptie in het project hoort per project bevestigd te worden. TLS op de app zelf is
  een apart recept (eigen certificaat, anker bij de inway) en hoort niet in deze stap.
- **Volledig gespecificeerde projectregels**, niet het per-deployment-invullen van
  `cross-domain-preview.sh`: beide kanten noemen een vaste deployment (`fsc-magazijna`, `test`),
  en een preview hoort deze route juist níet te krijgen.
- **Het publieke adres blijft het terugrol-adres.** Zolang de publicatie staat, is terugrollen één
  wijziging van de `endpoint_url`; daarom komt het intrekken pas na de previews.

## Verificatie

- Gerenderde manifests: Egress-policy op `magazijna-fscinway` (`fsc-magazijna`) en Ingress-policy
  op `magazijna` (`test`), elkaars spiegelbeeld op poort 8090.
- Een ophaalronde met een verse BSN levert een nieuwe transactie in de txlog van logius én van
  magazijn-a, en de inway logt geen fouten richting het interne adres.
