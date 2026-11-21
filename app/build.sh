#!/bin/bash
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
DX=/usr/lib/android-sdk/build-tools/debian/dx
BUILD="$HERE/build"
KEYSTORE="$HERE/jarvis.keystore"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes"

echo "[1/6] Resources..."
aapt package -f -M "$HERE/AndroidManifest.xml" -S "$HERE/res" \
  -A "$HERE/assets" -I "$ANDROID_JAR" -F "$BUILD/resources.apk"

echo "[2/6] Java..."
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -cp "$ANDROID_JAR" -d "$BUILD/classes" $(find "$HERE/src" -name '*.java')

echo "[3/6] DEX..."
"$DX" --dex --output="$BUILD/classes.dex" "$BUILD/classes"

echo "[4/6] Assemble..."
cp "$BUILD/resources.apk" "$BUILD/app-unsigned.apk"
(cd "$BUILD" && jar uf app-unsigned.apk classes.dex)

echo "[5/6] Align..."
zipalign -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "[6/6] Sign..."
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" \
    -storepass android -keypass android -alias jarvis \
    -dname "CN=JARVIS, OU=Local, O=OpenJarvis" -validity 10000 \
    -keyalg RSA -keysize 2048 >/dev/null 2>&1
fi

apksigner sign --ks "$KEYSTORE" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/Jarvis.apk" "$BUILD/app-aligned.apk"

apksigner verify "$BUILD/Jarvis.apk"
aapt dump badging "$BUILD/Jarvis.apk" | head -12
ls -lh "$BUILD/Jarvis.apk"
