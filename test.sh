#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
java_path="${JAVA_HOME:?Set JAVA_HOME to your JDK directory}"
mkdir -p build/tests
sdk_path="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
android_jar="$sdk_path/platforms/android-36/android.jar"
classic_sha="$(shasum -a 256 src/dev/inknfclab/ImageQuantizer.java | awk '{print $1}')"
[[ "$classic_sha" == "cf25d2de9e38dea8a7a02089d676d5db27cefed3a07b190213e2ebf42b7ca6ee" ]] || { echo "Classic 0.4.0 source changed"; exit 1; }
"$java_path/bin/javac" --release 8 -encoding UTF-8 -cp "$android_jar" -d build/tests src/dev/inknfclab/Protocol.java src/dev/inknfclab/NciTrace.java src/dev/inknfclab/ImageCodec.java src/dev/inknfclab/ImageQuantizer.java src/dev/inknfclab/ExperimentalQuantizer.java src/dev/inknfclab/AlgorithmCatalog.java src/dev/inknfclab/ResearchQuantizer.java src/dev/inknfclab/DeviceSupport.java src/dev/inknfclab/NfcVendor.java src/dev/inknfclab/ImageTransfer.java src/dev/inknfclab/RfConfig.java tests/ProtocolTest.java tests/NciTraceTest.java tests/ImageCodecTest.java tests/ImageQuantizerTest.java tests/ClassicAlgorithmTest.java tests/ExperimentalQuantizerTest.java tests/AlgorithmCatalogTest.java tests/DeviceSupportTest.java tests/NfcVendorTest.java tests/ImageTransferTest.java tests/RfConfigTest.java
"$java_path/bin/java" -cp build/tests dev.inknfclab.ProtocolTest
"$java_path/bin/java" -cp build/tests dev.inknfclab.RfConfigTest
"$java_path/bin/java" -cp build/tests dev.inknfclab.ImageQuantizerTest
"$java_path/bin/java" -cp build/tests dev.inknfclab.ClassicAlgorithmTest
"$java_path/bin/java" -cp build/tests dev.inknfclab.ExperimentalQuantizerTest
"$java_path/bin/java" -cp "build/tests:$android_jar" dev.inknfclab.AlgorithmCatalogTest
"$java_path/bin/java" -cp build/tests dev.inknfclab.DeviceSupportTest
"$java_path/bin/java" -cp build/tests dev.inknfclab.NfcVendorTest
"$java_path/bin/java" -cp build/tests dev.inknfclab.NciTraceTest
"$java_path/bin/java" -cp "build/tests:$android_jar" dev.inknfclab.ImageCodecTest
"$java_path/bin/java" -cp "build/tests:$android_jar" dev.inknfclab.ImageTransferTest
node tests/nfc_compat_test.js
node tests/mifare_compat_test.js
bash tests/profile_collector_test.sh
"$java_path/bin/javac" --release 8 -encoding UTF-8 -cp build/tests -d build/tests src/dev/inknfclab/PollingConfigSession.java tests/PollingConfigSessionTest.java
"$java_path/bin/java" -cp build/tests dev.inknfclab.PollingConfigSessionTest
bash tests/thn31_test.sh
