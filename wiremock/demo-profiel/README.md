# demo-profiel

Profielservice-stubs die **alleen lokaal** (docker compose demo) worden gebruikt en
**bewust niet** in het publieke ZAD-`externe-stubs`-image zitten.

Reden: deze mappings zijn per demo-persona gesleuteld op concrete identificatienummers, en het
gedeelde `externe-stubs`-image moet generiek blijven — het wordt door meerdere omgevingen
gebruikt en hoort geen demo-specifieke persona's te bevatten. Door ze hier apart te houden
blijft dat image schoon; de compose-`profiel-service` mount deze map als extra subdir naast de
gedeelde `externe-stubs`-mappings.

De gebruikte BSN's zijn **elfproef-geldig maar verzonnen**: ze komen uit de conventionele
999-testreeks, die nooit aan een echt persoon wordt uitgegeven. Ze horen dus tot geen enkele
betrokkene en zijn geen persoonsgegevens — vandaar dat ze in deze publieke repo mogen staan.
Wie hier een persona bijzet, houdt die reeks aan; een elfproef-geldig nummer buiten 999 kan wél
van een bestaand persoon zijn.

Elke persona krijgt een `OntvangViaBerichtenbox`-voorkeur met de organisatie-OIN's in scope
waar die persona berichten van ontvangt (prioriteit 1, wint van de gedeelde catch-all):

| Persona | Sleutel | Ontvangt van (OIN) |
|---|---|---|
| J. Pietersen | BSN 999993653 | RVO `00000000000000100000` + Belastingdienst `00000001823288444000` |
| Bakkerij De Vroege Vogel | BSN 999996666 | RVO `00000000000000100000` |
| Garage Van Dijk B.V. | KVK 90000014 | Belastingdienst `00000001823288444000` |

Deze opt-ins sturen twee dingen: de aanlever-autorisatie in het magazijn én welke
magazijnen de uitvraag per persona bevraagt (`ProfielMagazijnResolver`).
