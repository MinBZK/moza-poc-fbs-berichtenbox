# demo-profiel

Profielservice-stubs met de voorkeuren van de demo-persona's. Ze zitten **niet** in het gedeelde
`externe-stubs`-image, maar wel in het eigen `fbs-demo-profiel`-image ernaast (`Dockerfile` in deze
map), dat op dezelfde gedeelde mappings layert.

Reden voor die scheiding: `externe-stubs` is publiek en breed uitgerold, en zijn eigen regel is dat
mappings alléén identificatienummers mogen bevatten die de elfproef fálen. De persona's kunnen daar
niet aan voldoen — het magazijn en de uitvraag valideren met de elfproef, dus een demo-persona móet
een geldig nummer hebben. Dat compromis blijft opgesloten in het demo-image in plaats van de regel
van het gedeelde image te verruimen.

Lokaal mount de compose-`profiel-service` deze map als extra subdir naast de gedeelde mappings; op
ZAD draait het `profiel`-component het demo-image, dat dezelfde twee lagen al bevat.

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
magazijnen de uitvraag per persona bevraagt (`ProfielMagazijnResolver`). Ze moeten daarom sporen met
`demo.personas.*.magazijnen` in de demo-console; `DemoDatasetConsistentieTest` faalt zodra ze uit
elkaar lopen.

Zonder deze mappings valt élke ontvanger terug op de gedeelde catch-all, die voor iedereen hetzelfde
ene magazijn teruggeeft — dan toont de demo geen federatie meer.
