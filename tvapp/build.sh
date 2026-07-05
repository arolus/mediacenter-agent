#!/usr/bin/env bash
# Сборка MediaCenter TV APK без Gradle — напрямую aapt2/javac/d8/zipalign/apksigner.
# Запуск на маке разработчика:  bash agent/tvapp/build.sh
# Результат: agent/tv/app/mediacenter-tv.apk (+ version.json) — коммитится в репо,
# на ноды приезжает обычным git-обновлением агента, ставится кнопкой на TV-странице.
#
# Keystore — СЕКРЕТ, живёт ВНЕ git: <корень проекта>/keys/tvapp.jks (генерится при
# первом запуске). Потерять нельзя: обновление поверх требует той же подписи.
set -euo pipefail

# --- версия приложения: бампать при каждом изменении tvapp/ ---
VERSION_CODE=7
VERSION_NAME="1.6"

DIR="$(cd "$(dirname "$0")" && pwd)"          # agent/tvapp
OUT_APK="$DIR/../tv/app/mediacenter-tv.apk"   # артефакт в репо агента
KEYS_DIR="$DIR/../../keys"                    # корень проекта (вне git)
KEYSTORE="$KEYS_DIR/tvapp.jks"
KEYPASS_FILE="$KEYS_DIR/tvapp.pass"

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.1"
PLATFORM="$SDK/platforms/android-35/android.jar"
[ -f "$PLATFORM" ] || { echo "нет android.jar: $PLATFORM"; exit 1; }
[ -x "$BT/aapt2" ] || { echo "нет build-tools 35.0.1 в $SDK"; exit 1; }

# --- keystore: генерим один раз, пароль в файле рядом ---
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$KEYS_DIR"
  PASS="$(openssl rand -hex 16)"
  printf '%s' "$PASS" > "$KEYPASS_FILE"
  keytool -genkeypair -keystore "$KEYSTORE" -alias tvapp -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=MediaCenter TV, O=MediaCenter" >/dev/null
  echo "✓ создан keystore: $KEYSTORE (пароль в tvapp.pass — НЕ терять и НЕ коммитить)"
fi
PASS="$(cat "$KEYPASS_FILE")"

BUILD="$DIR/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$(dirname "$OUT_APK")"

echo "→ aapt2: ресурсы + манифест"
"$BT/aapt2" compile --dir "$DIR/res" -o "$BUILD/res.zip"
"$BT/aapt2" link -o "$BUILD/unsigned.apk" -I "$PLATFORM" \
  --manifest "$DIR/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 30 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$BUILD/res.zip"

echo "→ javac + d8"
javac -classpath "$PLATFORM" --release 8 -d "$BUILD/classes" \
  $(find "$DIR/src" -name '*.java') 2>&1 | grep -v "^warning:\|deprecat\|^Note:\|^1 warning" || true
[ -f "$BUILD/classes/com/mediacenter/tv/MainActivity.class" ] || { echo "✗ javac не собрал классы"; exit 1; }
"$BT/d8" --release --lib "$PLATFORM" --min-api 24 \
  --output "$BUILD" $(find "$BUILD/classes" -name '*.class')

echo "→ упаковка + подпись"
(cd "$BUILD" && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias tvapp \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$OUT_APK" "$BUILD/aligned.apk"
rm -f "$OUT_APK.idsig"

# Сайдкар с версией: агент сравнивает с установленной (pm) и предлагает обновление
printf '{"versionCode":%s,"versionName":"%s"}\n' "$VERSION_CODE" "$VERSION_NAME" \
  > "$(dirname "$OUT_APK")/version.json"

"$BT/apksigner" verify "$OUT_APK"
echo "✓ $(ls -la "$OUT_APK" | awk '{print $5}') байт → $OUT_APK (v$VERSION_NAME, code $VERSION_CODE)"
