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
ZAD draait het `profiel`-component het demo-image, dat dezelfde lagen al bevat.

## Drie lagen, van breed naar smal

WireMock leest recursief en breekt gelijke voorrang op leesvolgorde; het lagere getal wint. Van
zwakst naar sterkst:

| Laag | Voorrang | Waar |
|---|---|---|
| Gedeelde catch-all en foutgevallen | 100 en 10 | `externe-stubs/mappings/` |
| Handgeschreven persona's | 5 | `demo-profiel/mappings/`, deze map |
| De vier ondernemers, met volledige fan-out | 1 | `demo-profiel/generated/`, gegenereerd |

De onderste laag komt uit `demo/genereer-magazijnen.py` en draagt naast de twee echte magazijnen ook
de gesimuleerde: 3, 15, 45 en 100 organisaties. Lokaal bind-mount compose die map rechtstreeks; in
het image kopieert de stap `Build + push externe-stubs` in `deploy.yml` ze naar `generated/` vóór de
build, en controleert daarna op het draaiende image dat Landelijk Concern werkelijk 100 organisaties
teruggeeft. Zonder die laag werkt de demo nog steeds — dan kent elke persona alleen de twee echte
magazijnen, en dat is precies wat de nacontrole moet afvangen.

De gebruikte BSN's zijn **elfproef-geldig maar verzonnen**: ze komen uit de conventionele
999-testreeks, die nooit aan een echt persoon wordt uitgegeven. Ze horen dus tot geen enkele
betrokkene en zijn geen persoonsgegevens — vandaar dat ze in deze publieke repo mogen staan.
Wie hier een persona bijzet, houdt die reeks aan; een elfproef-geldig nummer buiten 999 kan wél
van een bestaand persoon zijn.

Elke persona krijgt een `OntvangViaBerichtenbox`-voorkeur met de organisatie-OIN's in scope
waar die persona berichten van ontvangt (voorrang 5, wint van de gedeelde catch-all):

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
