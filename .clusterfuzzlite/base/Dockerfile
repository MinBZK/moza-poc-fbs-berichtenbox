# Basis-image voor de fuzz-controle: de OSS-Fuzz-JVM-builder met de JDK 21 en een warme
# Maven-repository er al in gebakken.
#
# Zonder dit image doet élke fuzz-run hetzelfde voorbereidende werk opnieuw: ~14s apt-get voor
# de JDK en ~100s dependency-downloads. Dat werk verandert alleen als de pom's veranderen, dus
# het hoort in een image dat bij een pom-wijziging opnieuw gebouwd wordt — niet in elke run.
#
# Alleen de pom's gaan mee, geen sources: de laag met de Maven-repository blijft dan geldig
# zolang de dependency-declaraties gelijk blijven.
FROM gcr.io/oss-fuzz-base/base-builder-jvm:v1@sha256:73e9f2aaddb0a9ca97b137c1e7d8f2142203be6a5767be48a56888491edaf310

# `-headless` + --no-install-recommends scheelt ~150 MB aan GUI-libs (libgtk-3, libwayland,
# mesa-vulkan-drivers, libatk-wrapper-java) die fuzzers niet nodig hebben.
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jdk-headless rsync \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

WORKDIR /warmup
COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml
COPY libraries/fbs-common/pom.xml libraries/fbs-common/pom.xml
COPY libraries/fbs-magazijnregister/pom.xml libraries/fbs-magazijnregister/pom.xml
COPY libraries/fbs-berichtensessiecache/pom.xml libraries/fbs-berichtensessiecache/pom.xml
COPY services/berichtenmagazijn/pom.xml services/berichtenmagazijn/pom.xml
COPY services/berichtenuitvraag/pom.xml services/berichtenuitvraag/pom.xml
COPY services/demo-console/pom.xml services/demo-console/pom.xml

# Vult ~/.m2 met de dependencies én de plugins van de fuzz-modules. `go-offline` faalt op
# artefacten die pas tijdens een echte build resolvebaar zijn (de reactor-modules zelf), dus
# `|| true`: een onvolledige warme repo maakt de fuzz-build trager, niet stuk — die haalt wat
# ontbreekt alsnog op. Hetzelfde geldt voor de skip-flags: statische analyse, coverage en de
# Quarkus-augmentatie leveren geen fuzzbare bytecode, dus hun plugins hoeven hier niet warm.
RUN chmod +x mvnw \
    && ./mvnw -B -ntp dependency:go-offline \
        -Ddetekt.skip=true -Djacoco.skip=true -Dquarkus.build.skip=true \
        || true

WORKDIR /src
RUN rm -rf /warmup
