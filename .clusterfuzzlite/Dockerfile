# Basis-image met de JDK 21 en een warme Maven-repository al erin gebakken; gebouwd door
# .github/workflows/fuzz-base-image.yml uit .clusterfuzzlite/base/Dockerfile. Zonder dat image
# deed elke fuzz-run ~14s apt-get en ~100s dependency-downloads opnieuw.
#
# Gepind op digest, niet op tag: de tag beweegt mee met de pom's, en een fuzz-build hoort niet
# stilzwijgend van basis te wisselen. Wijzigen de pom's, dan bouwt die workflow een nieuw image
# en print hij de digest die hier hoort.
FROM ghcr.io/minbzk/fbs-fuzz-base@sha256:e2c4764dc4e7061cf256cfeb276126046a35f088870256a882e5fdb430530809
COPY . $SRC/moza-poc-fbs-berichtenbox
WORKDIR $SRC/moza-poc-fbs-berichtenbox
RUN chmod +x mvnw
COPY .clusterfuzzlite/build.sh $SRC/
