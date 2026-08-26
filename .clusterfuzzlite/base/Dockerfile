# Basis-image voor de fuzz-controle: de OSS-Fuzz-JVM-builder met de JDK 21 en een warme
# Maven-repository er al in gebakken.
#
# Zonder dit image doet élke fuzz-run hetzelfde voorbereidende werk opnieuw: ~14s apt-get voor
# de JDK en ~100s dependency-downloads. Dat werk verandert alleen als de pom's veranderen, dus
# het hoort in een image dat bij een pom-wijziging opnieuw gebouwd wordt — niet in elke run.
#
# Alleen de build-declaraties gaan mee (pom's, `.mvn`, `mvnw`), geen sources: de laag met de
# Maven-repository blijft dan geldig zolang de dependency-declaraties gelijk blijven.
FROM gcr.io/oss-fuzz-base/base-builder-jvm:v1@sha256:73e9f2aaddb0a9ca97b137c1e7d8f2142203be6a5767be48a56888491edaf310

# `-headless` + --no-install-recommends scheelt ~150 MB aan GUI-libs (libgtk-3, libwayland,
# mesa-vulkan-drivers, libatk-wrapper-java) die fuzzers niet nodig hebben.
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jdk-headless rsync \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# Alleen de pom's: dit image warmt de Maven-repository, het bouwt geen fuzz-doelen. Élke module uit
# de reactor hoort erbij, ook demo/demo-console — zonder zijn pom kan Maven de reactor niet lezen en
# struikelt `go-offline`. Meekopiëren is dus geen fuzzen: welke modules daadwerkelijk gefuzzd worden
# staat in de MODULES-lijst van ../build.sh, en demo-code staat daar niet in (het heeft ook geen
# Jazzer-doelen). Een PR die alleen demo/ raakt slaat de fuzz-ronde helemaal over.
WORKDIR /warmup
COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml
COPY libraries/fbs-common/pom.xml libraries/fbs-common/pom.xml
COPY libraries/fbs-magazijnregister/pom.xml libraries/fbs-magazijnregister/pom.xml
COPY libraries/fbs-berichtensessiecache/pom.xml libraries/fbs-berichtensessiecache/pom.xml
COPY services/berichtenmagazijn/pom.xml services/berichtenmagazijn/pom.xml
COPY services/berichtenuitvraag/pom.xml services/berichtenuitvraag/pom.xml
COPY demo/demo-console/pom.xml demo/demo-console/pom.xml

RUN chmod +x mvnw

# De COPY-lijst hierboven is handwerk, de pom-hash die dit image tagt is dat niet. Een nieuwe
# module die niet meegekopieerd wordt, laat `go-offline` struikelen op iets heel anders dan de
# reactor-artefacten waarvoor de tolerantie hieronder bedoeld is — en zou dan ongemerkt een
# leeg image opleveren. Met sed in plaats van `grep -oP`: geen PCRE-afhankelijkheid.
RUN modules=$(sed -n 's|.*<module>\(.*\)</module>.*|\1|p' pom.xml); \
    [ -n "$modules" ] || { echo "geen <module>-regels in pom.xml — deze controle bewaakt niets meer"; exit 1; }; \
    for m in $modules; do \
        [ -f "$m/pom.xml" ] || { echo "module $m ontbreekt in de COPY-lijst van dit Dockerfile"; exit 1; }; \
    done

# Vult ~/.m2 met de dependencies én de plugins van de reactor. De skip-flags: statische analyse,
# coverage en de Quarkus-augmentatie leveren geen fuzzbare bytecode, dus hun plugins hoeven hier
# niet warm.

# `|| true`: de reactor-modules zelf zijn hier nog niet resolvebaar (er zijn geen sources), en
# dat is de enige fout die hier verwacht wordt. Andere fouten mogen niet in stilte een half
# gevulde repository opleveren — de postconditie eronder bewaakt dat.
RUN ./mvnw -B -ntp dependency:go-offline \
        -Ddetekt.skip=true -Djacoco.skip=true -Dquarkus.build.skip=true \
        || true
RUN jars=$(find /root/.m2/repository -name '*.jar' | wc -l); \
    echo "warme repository: $jars jars"; \
    [ "$jars" -ge 500 ] || { echo "warme repository vrijwel leeg — dit image levert geen tijdwinst op"; exit 1; }

WORKDIR /src
RUN rm -rf /warmup
