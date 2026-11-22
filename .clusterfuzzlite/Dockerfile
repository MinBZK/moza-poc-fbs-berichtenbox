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
FROM ghcr.io/minbzk/fbs-fuzz-base@sha256:37094433b190f09be6f9277fd836a1777bf76a69065692c4b5aab639b32a1e89
COPY . $SRC/moza-poc-fbs-berichtenbox
WORKDIR $SRC/moza-poc-fbs-berichtenbox
RUN chmod +x mvnw

# De Central-mirror ook hier en niet alleen in het basis-image: dat is op digest gepind, en een
# wijziging zou anders pas na de volgende pin-PR in de fuzz-build doorwerken. build.sh haalt via
# Maven op wat de warme repository mist, en dat kreeg zonder mirror een 429 van Central.
RUN install -D -m 0644 .github/actions/maven-cache/settings.xml /root/.m2/settings.xml
ENV MVNW_REPOURL=https://maven-central-eu.storage-download.googleapis.com/maven2
COPY .clusterfuzzlite/build.sh $SRC/
