#!/bin/bash -eu

MODULE=services/berichtensessiecache

# Build the module and its dependencies
./mvnw package -DskipTests -pl $MODULE -am -B

# Copy all dependencies to $OUT/lib
mkdir -p $OUT/lib
./mvnw dependency:copy-dependencies -DoutputDirectory=$OUT/lib -pl $MODULE -B

# Copy compiled application and test classes
cp -r $MODULE/target/classes $OUT/classes
cp -r $MODULE/target/test-classes $OUT/test-classes

# Bundle the JDK 21 runtime so jazzer_driver can run Java 21 bytecode
mkdir -p "$OUT/open-jdk-21"
rsync -aL --exclude='*.zip' "$JAVA_HOME/" "$OUT/open-jdk-21/"

# Find fuzz targets in $MODULE and create driver scripts
for fuzzer in $(grep -rl "fuzzerTestOneInput" $MODULE/src/test/kotlin/ || true); do
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
