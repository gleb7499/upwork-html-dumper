#!/usr/bin/env bash
# Сборка dist (fat jar + bat) без сети: компиляция через maven (offline),
# мёрж jsoup вручную через jar-tool (shade-плагин офлайн недоступен).
set -e
MVN="/c/Users/kseni/.m2/wrapper/dists/apache-maven-3.9.12/59fe215c0ad6947fea90184bf7add084544567b927287592651fda3782e0e798/bin/mvn"
export JAVA_HOME="/c/Users/kseni/.jdks/openjdk-25.0.1"
export PATH="$JAVA_HOME/bin:$PATH"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/upwork-parse-diff"

"$MVN" -q -o package -DskipTests || true

# shade офлайн может падать на метаданных — для fat jar он не нужен (мёржим вручную ниже)
[ -f target/upwork-parse-diff.jar ] || { echo "ERROR: compile failed"; exit 1; }

rm -rf target/fatjar && mkdir -p target/fatjar
cd target/fatjar
jar xf ../upwork-parse-diff.jar
jar xf ~/.m2/repository/org/jsoup/jsoup/1.10.2/jsoup-1.10.2.jar
mkdir -p "$ROOT/upwork-parse-dist"
jar cfe "$ROOT/upwork-parse-dist/upwork-parse-diff.jar" com.example.upworkdiff.Main .
cd ..
rm -rf fatjar

printf '@java -jar "%%~dp0upwork-parse-diff.jar" %%*\r\n' > "$ROOT/upwork-parse-dist/upwork-parse-diff.bat"
echo "OK: $ROOT/upwork-parse-dist/upwork-parse-diff.jar"
