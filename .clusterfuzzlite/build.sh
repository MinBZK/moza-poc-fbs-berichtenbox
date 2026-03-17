#!/bin/bash -eu

MODULE=services/berichtensessiecache

# Build the module and its dependencies
./mvnw package -DskipTests -pl $MODULE -am -B

# Copy dependencies to $OUT/lib
./mvnw dependency:copy-dependencies -DoutputDirectory=$OUT/lib -pl $MODULE -B

# Copy compiled classes
rsync -a $MODULE/target/classes/ $OUT/classes/
rsync -a $MODULE/target/test-classes/ $OUT/classes/

# Copy module's own JAR to lib
cp $MODULE/target/*.jar $OUT/lib/ 2>/dev/null || true

# Find all fuzz targets and create driver scripts
for fuzzer_source in $(grep -rl "fuzzerTestOneInput" $MODULE/src/test/kotlin/); do
    # Strip source prefix to get class path
    class_path=${fuzzer_source#"$MODULE/src/test/kotlin/"}
    # Convert path to fully qualified class name
    class_name=$(echo "$class_path" | sed 's|/|.|g' | sed 's|\.kt$||')
    # Extract simple class name for the script name
    simple_name=$(basename "$class_path" .kt)

    # Create the fuzzer driver script
    cat > $OUT/$simple_name <<EOF
#!/bin/bash
# LLVMFuzzerTestOneInput for $class_name
this_dir=\$(dirname "\$0")
CLASSPATH=\$this_dir/classes:\$(echo \$this_dir/lib/*.jar | tr ' ' ':')
exec java -cp \$CLASSPATH \
    com.code_intelligence.jazzer.Jazzer \
    --target_class=$class_name \
    "\$@"
EOF
    chmod +x $OUT/$simple_name
done
