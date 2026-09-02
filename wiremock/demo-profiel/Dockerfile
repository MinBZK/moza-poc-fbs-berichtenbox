# Profielservice-stub mét de voorkeuren van de demo-persona's. Layert op dezelfde gedeelde
# mappings als het externe-stubs-image; alleen de persona-bestanden komen erbij.
#
# Waarom een eigen image en niet één regel extra in externe-stubs: dat image is publiek en breed
# uitgerold, en zijn eigen regel is dat mappings alléén identificatienummers mogen bevatten die de
# elfproef falen. De persona's kunnen daar niet aan voldoen — het magazijn en de uitvraag valideren
# met de elfproef, dus een demo-persona móet een geldig nummer hebben. De gebruikte BSN's komen
# daarom uit de 999-testreeks, die nooit aan een echt persoon wordt uitgegeven, en dat compromis
# blijft opgesloten in dít image in plaats van de regel van het gedeelde image te verruimen.
#
# Bouwen vanuit `wiremock/` als context, niet vanuit deze map: de gedeelde mappings liggen ernaast.
FROM wiremock/wiremock:3.13.2@sha256:fd27e46090916e85326229e5102ee169ae729059f3374549615bd20a35fee1f2
COPY externe-stubs/mappings /home/wiremock/mappings
# Als subdir, want WireMock leest recursief en de persona-mappings winnen op priority, niet op
# bestandsvolgorde. Zo blijft zichtbaar wat gedeeld is en wat bij de demo hoort.
COPY demo-profiel/mappings /home/wiremock/mappings/demo-profiel
# De vier ondernemers met hun volledige fan-out (3, 15, 45 en 100 organisaties), gegenereerd door
# demo/genereer-magazijnen.py en door de CI-stap hierheen gekopieerd — het script schrijft in
# demo/generated/, en dat ligt buiten deze build-context. Voorrang 1 wint van de handgeschreven
# persona's hierboven (5), die alleen de twee echte magazijnen dragen. Zonder deze laag draait de
# demo nog steeds, met een fan-out van twee; de map is daarom leeg-maar-aanwezig in git.
COPY demo-profiel/generated /home/wiremock/mappings/demo-profiel-generated
