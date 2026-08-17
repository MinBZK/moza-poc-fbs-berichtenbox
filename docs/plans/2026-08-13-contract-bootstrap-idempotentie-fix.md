# Contract-bootstrap: idempotentie zonder provider-blinde-vlek

**Status:** Concept
**PR:** nieuwe branch bovenop `feature/782-fsc-contract-magazijn-uitvraag` (PR #200), dus een
vierde gestapelde PR (A #197 → B #200 → **deze fix op B** → C ZAD-transport, nog te doen).
**Aanleiding:** [reviewcomment op PR #200](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/pull/200#issuecomment-5280760966),
bevindingen 1 en 2 (Hoog).

## Context

PR #200 (`contracts/bootstrap.sh`) claimt state-loze idempotentie: "drie runs, één contract",
afgeleid uit de contracten zelf in plaats van een lokaal state-bestand. De review op die PR vond
twee Hoog-bevindingen die precies díe claim ondermijnen:

1. **`bestaande_contracten()` (regel 121) kijkt alleen naar de provider-manager.** Het contract
   telt als "bestaand" zodra het daar `CONTRACT_STATE_VALID` is. Maar de PR-beschrijving zelf
   noemt het scenario waarin de accept-handtekening wél bij de provider landt maar de push naar de
   consumer-manager stokt (best-effort, geen cron-retry) — dan blijft het contract bij de consumer
   `proposed` en kan de outway de grant niet resolven. Een retry ziet op de provider al VALID,
   meldt "BOOTSTRAP OK (bestaand contract)" en doet niets — silent false-positive success.
2. **De existence-check (stap 2) en de contract-POST (stap 3) zijn niet atomair (regel 143).** Twee
   gelijktijdige aanroepen — concurrente CI-jobs, of een retry na een run die vóór VALID stierf —
   passeren beide de `BESTAAND=""`-check en posten elk een nieuw contract. De bestaande
   `AANTAL -gt 1`-WAARSCHUWING signaleert dit achteraf, maar voorkomt of herstelt het niet.

Dit landt bewust **niet** in PR #200 zelf: de reviewer (deze sessie/gebruiker) is niet de auteur
van #200, en rechtstreeks pushen op die branch zou reviewer en auteur in een lock zetten (de
auteur moet dan alsnog zelf pushen/rebasen om verder te kunnen reviewen/mergen). Vandaar een losse,
gestapelde PR die de #200-auteur zelf kan reviewen en inmergen — zelfde patroon als de bestaande
A→B→C-stapeling.

Buiten scope van dit plan: het [generieke mechanisme in `moza-fsc-testnet` overnemen](https://github.com/MinBZK/MijnOverheidZakelijk/issues/952)
(issue #952). Deze fix repareert de kopie in `moza-poc-fbs-berichtenbox`; zodra #952 is opgelost
kan die kopie alsnog vervangen worden door de generieke variant.

## Gewenst resultaat

- Een bootstrap-run meldt "bestaand contract" alleen als het contract **op zowel provider als
  consumer** VALID/gesynct is. Is de provider VALID maar de consumer niet, dan forceert het script
  de her-distributie die er voor dát scenario al staat, in plaats van te stoppen.
- Duplicaten die door een racecondition ontstaan, worden bij de eerstvolgende run automatisch
  opgeruimd (self-healing) in plaats van alleen gemeld.

## Aanpak

### 1. Consumer-sync meewegen in de existence-check

`bestaande_contracten()` blijft de provider bevragen (dat is en blijft de plek waar het contract
canoniek ontstaat), maar het resultaat telt alleen als "bestaand" als de consumer-kant het ook als
VALID/gesynct ziet. Daarvoor hergebruikt bootstrap.sh de bestaande gedeelde helper
`fsc_contract_state()` (`lib/fsc-harness.sh:109`) tegen de consumer-manager, in plaats van zijn
eigen smallere `consumer_state()` (regel 219) — dat lost meteen ook bevinding 7 (Medium,
gedupliceerde/afwijkende matchlogica) op.

Is de provider VALID maar de consumer niet gesynct: dit is precies het scenario waarvoor het
script al een geforceerde her-distributie kent (zie PR #200-beschrijving, "Wat opviel"). De
existence-check triggert die her-distributie i.p.v. de run als "klaar" te beschouwen.

### 2. Race op de check-en-POST self-healing maken

Een lock toevoegen lost het probleem niet echt op: concurrente CI-jobs draaien op verschillende
runners, dus een lokaal lockbestand voorkomt niets, en een gedeeld lock (bv. via de manager-API)
zou weer een vorm van state introduceren die de hele opzet van deze PR juist vermeed.

In plaats daarvan wordt de `AANTAL -gt 1`-tak self-healing: bij meer dan één matchende VALID
contract voor dezelfde identiteit (service + provider + consumer-outway + thumbprint) revoke't het
script alle contracten op één na — de oudste, canonieke — via de bestaande revoke-call op de
provider-manager. Dat verandert de garantie van "nooit een duplicaat" (niet haalbaar zonder lock)
naar "een duplicaat overleeft nooit de volgende run" — consistent met hoe de rest van het script al
richting self-healing-i.p.v.-voorkomen buigt (zie de her-distributie in punt 1).

### 3. Gedeelde matcher voor "geldig contract"

Bevinding 6 (Medium): de contract-matchcriteria in `bestaande_contracten()` (checkt `has_revoked` +
outway-thumbprint, niet `signatures.accept`) en in `smoke-contract.sh` (checkt `signatures.accept`
voor beide peers, niet `has_revoked`/thumbprint) lopen al uiteen. Beide criteria zijn nodig voor
een correcte "is dit contract geldig en van toepassing"-check; deze fix voegt één gedeelde
jq-matcher toe aan `lib/fsc-harness.sh` die beide toetst, en laat bootstrap.sh én smoke-contract.sh
'm allebei aanroepen.

## Meteen meegenomen (kleine, losse Medium/Laag-fixes uit dezelfde review)

- **Bevinding 3, 4 (Medium):** de twee `jq`-aanroepen zonder `\|\|`-fallback
  (`smoke-contract.sh:78`, `bootstrap.sh:189`) krijgen alsnog de guard die de rest van beide
  bestanden al gebruikt — zelfde faalklasse als de `ok_tenzij`-bug uit reviewronde 4 van #200.
- **Bevinding 5 (Medium):** de shellcheck-glob in `.github/workflows/fsc-harness-overlays.yml:245`
  krijgt `demo/environment/federatie/contracts/*.sh` toegevoegd.
- **Bevinding 9, 10 (Laag):** `contract_zichtbaar()` en de idempotentie-her-run in
  `smoke-contract.sh` tonen voortaan de onderliggende fout/output bij falen, i.p.v. die weg te
  gooien.
- **Bevinding 8 (Laag):** de idempotentie-assert in `smoke-contract.sh` (regel 179) vergelijkt
  voortaan per magazijn de contract-hash vóór/na, niet alleen de geaggregeerde COUNT.

## Verificatie

- `smoke-contract.sh` blijft groen op de lokale federatie (zelfde vijf asserts als #200, plus de
  verscherpte idempotentie-assert uit punt 8).
- Nieuw scenario in `smoke-contract.sh` of een los testscript: provider VALID + consumer bewust
  `proposed` gehouden (her-distributie-push overslaan) → bootstrap.sh moet de her-distributie
  forceren, niet vroegtijdig "bestaand" melden.
- Nieuw scenario: twee bootstrap.sh-aanroepen kunstmatig na elkaar zonder existence-check ertussen
  (bv. door de check tijdelijk te stubben) → twee contracten ontstaan, een derde aanroep ruimt de
  jongste op en laat er één over.
- `shellcheck` op `contracts/bootstrap.sh` en `contracts/fbs-contracten.sh` slaagt (nu voor het
  eerst via CI, bevinding 5).
