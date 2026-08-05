# demo-profiel

Profielservice-stubs die **alleen lokaal** (docker compose demo) worden gebruikt en
**bewust niet** in het publieke ZAD-`externe-stubs`-image zitten.

Reden: deze mappings zijn per demo-persona gesleuteld op **elfproef-geldige** BSN's
(999993653, 123456782) en een KVK-nummer. Het publieke `externe-stubs`-image mag zulke
waarden niet bevatten (zie `../externe-stubs/README.md`, AVG art. 32). Door ze hier apart
te houden blijft dat image schoon; de compose-`profiel-service` mount deze map als extra
subdir naast de gedeelde `externe-stubs`-mappings.

Elke persona krijgt een `OntvangViaBerichtenbox`-voorkeur met de organisatie-OIN's in scope
waar die persona berichten van ontvangt (prioriteit 1, wint van de gedeelde catch-all):

| Persona | Sleutel | Ontvangt van (OIN) |
|---|---|---|
| J. Pietersen | BSN 999993653 | RVO `00000001003214345000` + Belastingdienst `00000001823288444000` |
| Bakkerij De Vroege Vogel | BSN 123456782 | RVO `00000001003214345000` |
| Garage Van Dijk B.V. | KVK 12345678 | Belastingdienst `00000001823288444000` |

Deze opt-ins sturen twee dingen: de aanlever-autorisatie in het magazijn én welke
magazijnen de uitvraag per persona bevraagt (`ProfielMagazijnResolver`).
