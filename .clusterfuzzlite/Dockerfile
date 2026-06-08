# JDK 21 komt uit het officiële Eclipse Temurin-image i.p.v. een apt-install. De OSS-Fuzz
# base-builder-jvm bundelt alleen JDK 17; voorheen haalden we openjdk-21 via apt (~126 MB van
# archive.ubuntu.com, traag/instabiel — eerdere runs faalden op connection-errors). De
# Adoptium-binaries zijn tegen een oude glibc (CentOS 7, 2.17) gebouwd en draaien daarom op de
# Ubuntu 20.04-base. Gepind op de multi-arch manifest-digest, net als de OSS-Fuzz base.
FROM eclipse-temurin:21-jdk-jammy@sha256:801b7e1a9c4befaf82bf9a2a58025ef43a7694bbc84779187ad0524d84742772 AS jdk21

FROM gcr.io/oss-fuzz-base/base-builder-jvm:v1@sha256:73e9f2aaddb0a9ca97b137c1e7d8f2142203be6a5767be48a56888491edaf310
COPY --from=jdk21 /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=$JAVA_HOME/bin:$PATH

# rsync blijft nodig voor build.sh (bundelt de JDK-runtime naar $OUT); klein pakket, los van de
# JDK-download — de zware/instabiele openjdk-apt is hiermee weg.
RUN apt-get update \
    && apt-get install -y --no-install-recommends rsync \
    && rm -rf /var/lib/apt/lists/*

COPY . $SRC/moza-poc-fbs-berichtenbox
WORKDIR $SRC/moza-poc-fbs-berichtenbox
RUN chmod +x mvnw
COPY .clusterfuzzlite/build.sh $SRC/
