# FSC-demo-omgeving

Provider- en consumer-peers voor de FSC-federatie, co-located met de services die ze
begeleiden. **Op ZAD** draait de gedeelde directory/group-CA (group-anker) in de externe,
aparte repo `moza-fsc-testnet` — die verhuist bewust niet mee (org-onafhankelijke kern,
door meerdere consumer-repo's tegelijk gebruikt).

| Peer | Rol | OIN | ZAD-project / deployment |
|------|-----|-----|--------------|
| [`magazijn-a`](magazijn-a/) | provider (biedt `berichtenmagazijn` aan) | `00000000000000100000` | `mpfm-w3h`, deployment `fsc-magazijna` (co-located met de `magazijna`/`magazijnb`-app) |
| [`logius`](logius/) | afnemer (outway naar `magazijn-a`) + aanbieder (inway, nog zonder dienst) | `00000000000000001000` | `mpfb-8wh`, deployment `fsc-logius` (co-located met de `uitvraag`-app) |

Elke peer-map bevat dezelfde indeling: `pki/` (certificaat-scripts), `deploy/local/`
(lokale docker-compose-harness), `deploy/zad/` (ZAD-rollout-runbooks + plan/validate-
script) en `docs/` (ontwerpachtergrond).

## Lokaal: één peer of de hele federatie

Elke peer-harness draait standalone een complete mini-federatie mét eigen directory en eigen
group-CA — genoeg om díé peer te beproeven, maar twee ervan kunnen niet tegelijk draaien en
zouden elkaar ook niet vertrouwen.

Voor alles wat zich *tussen* peers afspeelt — een contract, een data-pad, service-discovery —
zet [`federatie/`](federatie/) de peers naast elkaar in één netns, met één directory, één
group-CA en één SNI-router. Dat is de lokale tegenhanger van de ZAD-opstelling hierboven.

`lib/fsc-harness.sh` bevat de helpers die de scripts van alle peers delen.
