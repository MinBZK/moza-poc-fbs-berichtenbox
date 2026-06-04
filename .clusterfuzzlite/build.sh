#!/bin/bash -eu

# Modules met fuzz-targets. Elke nieuwe service met een Jazzer-fuzzer hoort hier
# bij te komen, anders wordt hij niet meegebouwd én niet gefuzzd in CI.
MODULES=(libraries/fbs-berichtensessiecache services/berichtenuitvraag)

# Comma-gescheiden lijst voor de Maven `-pl`-flag.
PL=$(IFS=,; echo "${MODULES[*]}")

# Bouw de modules plus upstream-dependencies (o.a. libraries/fbs-common) en
# installeer ze in de lokale Maven-repo, zodat de vervolgstap
# `dependency:copy-dependencies` ze kan resolven. Met `package` zou fbs-common
# wel in target/ belanden maar niet in ~/.m2 — dan faalt copy-dependencies met
# "could not find artifact nl.rijksoverheid.moz:fbs-common".
./mvnw install -DskipTests -pl "$PL" -am -B

mkdir -p "$OUT/lib" "$OUT/classes" "$OUT/test-classes"

# Per module: dependency-jars + gecompileerde app- en test-classes verzamelen.
# Mergen in dezelfde $OUT-mappen; de packages verschillen per module, dus geen
# class-collisies. Trailing `/.` kopieert de inhoud i.p.v. de map zelf.
for MODULE in "${MODULES[@]}"; do
    ./mvnw dependency:copy-dependencies -DoutputDirectory="$OUT/lib" -pl "$MODULE" -B
    cp -r "$MODULE/target/classes/." "$OUT/classes/"
    cp -r "$MODULE/target/test-classes/." "$OUT/test-classes/"
done

# Bundle the JDK 21 runtime so jazzer_driver can run Java 21 bytecode
mkdir -p "$OUT/open-jdk-21"
rsync -aL --exclude='*.zip' "$JAVA_HOME/" "$OUT/open-jdk-21/"

# Find fuzz targets across all modules and create driver scripts
for MODULE in "${MODULES[@]}"; do
    for fuzzer in $(grep -rl "fuzzerTestOneInput" "$MODULE/src/test/kotlin/" || true); do
        class_name=$(echo "$fuzzer" | sed "s|$MODULE/src/test/kotlin/||;s|\.kt$||;s|/|.|g")
        simple_name=$(basename -s .kt "$fuzzer")

        echo "Creating fuzzer wrapper: $simple_name -> $class_name"

        cat > "$OUT/$simple_name" << 'WRAPPER_EOF'
#!/bin/bash
# LLVMFuzzerTestOneInput for jvm
this_dir=$(dirname "$0")

if [[ "$@" =~ (^| )-runs=[0-9]+($| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi

# Build classpath from compiled classes and all dependency jars
CP="$this_dir/test-classes:$this_dir/classes"
for jar in "$this_dir"/lib/*.jar; do
  CP="$CP:$jar"
done

JAVA_HOME="$this_dir/open-jdk-21" \
LD_LIBRARY_PATH="$this_dir/open-jdk-21/lib/server":"$this_dir" \
"$this_dir/jazzer_driver" \
  --agent_path="$this_dir/jazzer_agent_deploy.jar" \
  --cp="$CP" \
  --target_class=TARGET_CLASS_PLACEHOLDER \
  --jvm_args="$mem_settings" \
  "$@"
WRAPPER_EOF

        sed -i "s|TARGET_CLASS_PLACEHOLDER|$class_name|" "$OUT/$simple_name"
        chmod +x "$OUT/$simple_name"
    done
done
