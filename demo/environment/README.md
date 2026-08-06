# FSC-demo-omgeving

Provider- en consumer-peers voor de FSC-federatie, co-located met de services die ze
begeleiden. De gedeelde directory/group-CA (group-anker) draait in de externe, aparte
repo `moza-fsc-testnet` — die verhuist bewust niet mee (org-onafhankelijke kern, door
meerdere consumer-repo's tegelijk gebruikt).

| Peer | Rol | OIN | ZAD-project |
|------|-----|-----|--------------|
| [`magazijn-a`](magazijn-a/) | provider (biedt `berichtenmagazijn` aan) | `00000000000000100000` | `mpfm-w3h` (co-located met de `magazijna`/`magazijnb`-app) |

Elke peer-map bevat dezelfde indeling: `pki/` (certificaat-scripts), `deploy/local/`
(lokale docker-compose-harness), `deploy/zad/` (ZAD-rollout-runbooks + plan/validate-
script) en `docs/` (ontwerpachtergrond).
