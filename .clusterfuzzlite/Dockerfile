# Basis-image met de JDK 21 en een warme Maven-repository al erin gebakken; gebouwd door
# .github/workflows/fuzz-base-image.yml uit .clusterfuzzlite/base/Dockerfile. Zonder dat image
# deed elke fuzz-run ~14s apt-get en ~100s dependency-downloads opnieuw.
#
# Gepind op digest, niet op tag: de tag beweegt mee met de pom's, en een fuzz-build hoort niet
# stilzwijgend van basis te wisselen. Wijzigen de pom's, dan bouwt die workflow bij de merge naar
# main een nieuw image en biedt hij de bijgewerkte FROM-regel zelf als pull request aan.
#
# Een verouderde pin is expres geen blokkade: de build werkt dan gewoon, Maven haalt op wat er in
# de warme repository ontbreekt. Alleen de voorbereiding is trager tot de pin-PR gemerged is.
FROM ghcr.io/minbzk/fbs-fuzz-base@sha256:84753b211a45cfd382cee9e76c2720a11e80b36d0231f70a2f587661c1e7a688
COPY . $SRC/moza-poc-fbs-berichtenbox
WORKDIR $SRC/moza-poc-fbs-berichtenbox
RUN chmod +x mvnw
COPY .clusterfuzzlite/build.sh $SRC/
