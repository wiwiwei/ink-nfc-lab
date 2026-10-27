#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_path="${JAVA_HOME:?Set JAVA_HOME}"
android_jar="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}/platforms/android-36/android.jar"
mkdir -p build/thn31-tests
find src tests/thn31 -name '*.java' > build/thn31-test-sources.txt
echo tests/ApduLinkTest.java >> build/thn31-test-sources.txt
"$java_path/bin/javac" --release 8 -encoding UTF-8 -cp "$android_jar" -d build/thn31-tests \
    @build/thn31-test-sources.txt
classpath="build/thn31-tests:$android_jar"
# java.exe on Git Bash uses Windows classpath separators.
case "$(uname -s)" in MINGW*|MSYS*) classpath="build/thn31-tests;$(cygpath -w "$android_jar")";; esac
for suite in NciDataSessionTest NciDataCaptureTest SelectionGateTest ScreenSessionLockTest Thn31ProfileTest; do
    "$java_path/bin/java" -cp "$classpath" "dev.inknfclab.thn31.$suite"
done
"$java_path/bin/java" -cp "$classpath" dev.inknfclab.ApduLinkTest
node tests/thn31_session_test.js
