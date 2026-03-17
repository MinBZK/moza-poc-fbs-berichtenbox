FROM gcr.io/oss-fuzz-base/base-builder-jvm:v1@sha256:73e9f2aaddb0a9ca97b137c1e7d8f2142203be6a5767be48a56888491edaf310
RUN apt-get update && apt-get install -y openjdk-21-jdk rsync
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH
COPY . $SRC/moza-poc-fbs-berichtenbox
WORKDIR $SRC/moza-poc-fbs-berichtenbox
RUN chmod +x mvnw
COPY .clusterfuzzlite/build.sh $SRC/
