#!/usr/bin/env bash
# Builds LunchRoulette.apk from ../index.html without Gradle or the full Android SDK.
# Needs JDK 17+, python3 and, in $TOOLS:
#   aapt2            — Android resource compiler (linux binary)
#   android-all.jar  — org.robolectric:android-all:14-robolectric-10818077 (Android 14 framework, from Maven Central)
#   dx.jar           — com.jakewharton.android.repackaged:dalvik-dx:16.0.1 (Maven Central)
#   apksig.jar       — com.android.tools.build:apksig:2.3.0 (Maven Central)
# Signing key: $KEYSTORE (PKCS12, alias "lunch", password $KEYPASS); created on first run if missing.
# Keep the same keystore for later builds, or phones will refuse to install the update over the old app.
set -euo pipefail
cd "$(dirname "$0")"
: "${TOOLS:?set TOOLS to the folder with aapt2, android-all.jar, dx.jar, apksig.jar}"
KEYSTORE=${KEYSTORE:-lunch.p12} KEYPASS=${KEYPASS:-lunchroulette}
VERSION_CODE=${VERSION_CODE:-2} VERSION_NAME=${VERSION_NAME:-1.1}
OUT=${OUT:-build/LunchRoulette.apk}
B=build; rm -rf $B; mkdir -p $B/assets/www $B/classes $B/signer

cp ../index.html $B/assets/www/
"$TOOLS/aapt2" compile --dir res -o $B/res.zip
"$TOOLS/aapt2" link -o $B/unsigned.apk -I "$TOOLS/android-all.jar" --manifest AndroidManifest.xml \
  --min-sdk-version 29 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A $B/assets $B/res.zip

javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 -cp "$TOOLS/android-all.jar" \
  -d $B/classes $(find src -name '*.java')
java -cp "$TOOLS/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=29 --output=$B/classes.dex $B/classes
python3 -c "import zipfile,sys;zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" $B/unsigned.apk $B/classes.dex
python3 tools/align.py $B/unsigned.apk $B/aligned.apk

[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$KEYPASS" -keypass "$KEYPASS" \
  -alias lunch -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Lunch Roulette"
javac -nowarn -cp "$TOOLS/apksig.jar" -d $B/signer tools/Sign.java
java --add-exports java.base/sun.security.x509=ALL-UNNAMED -cp "$TOOLS/apksig.jar:$B/signer" Sign "$KEYSTORE" "$KEYPASS" lunch $B/aligned.apk "$OUT"
python3 tools/zipcheck.py "$OUT"
echo "built $OUT"
