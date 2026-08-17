# Basis-image met de JDK 21 en een warme Maven-repository al erin gebakken; gebouwd door
# .github/workflows/fuzz-base-image.yml uit .clusterfuzzlite/base/Dockerfile. Zonder dat image
# deed elke fuzz-run ~14s apt-get en ~100s dependency-downloads opnieuw.
#
# Gepind op digest, niet op tag: de tag beweegt mee met de pom's, en een fuzz-build hoort niet
# stilzwijgend van basis te wisselen. Wijzigen de pom's, dan bouwt die workflow bij de merge naar
# main een nieuw image en print hij de digest die hier hoort; `fuzz-basis-pin` in
# pin-consistency.yml waarschuwt tot die pin is bijgewerkt.
#
# Een verouderde pin is expres geen blokkade: de build werkt dan gewoon, Maven haalt op wat er in
# de warme repository ontbreekt. Alleen de voorbereiding is trager tot iemand de pin bijwerkt.
FROM ghcr.io/minbzk/fbs-fuzz-base@sha256:283ebfd78ce10ac2d9e023d37f6f9eb60fbe7a72a23018d844a4ddbb9530ac95
COPY . $SRC/moza-poc-fbs-berichtenbox
WORKDIR $SRC/moza-poc-fbs-berichtenbox
RUN chmod +x mvnw
COPY .clusterfuzzlite/build.sh $SRC/
