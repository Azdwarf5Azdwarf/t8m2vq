# Nedräkningskompassen

En kompass som räknar ned i stället för att peka. Två stora tal — **Δ NORR** och
**Δ ÖST**, båda i meter — går mot noll när du närmar dig målet. En nål pekar dit.

```
        Δ NORR      +412 m
        Δ ÖST       −178 m

              ↑

        avstånd 449 m · GPS ±8 m
```

Varför två tal och inte ett avstånd: ett enda avstånd döljer att du går fel. Med två
ser du direkt om bara det ena talet minskar — då rör du dig längs fel axel.

## Egenskaper

- Rena **`android.location.LocationManager`** med `GPS_PROVIDER` — inget beroende på
  Google Play Services.
- Riktning från `TYPE_ROTATION_VECTOR`, korrigerad från magnetiskt till sant norr med
  `GeomagneticField.getDeclination()`.
- Skärmen slocknar aldrig medan appen är framme (`FLAG_KEEP_SCREEN_ON`).
- Vanliga Views och XML-layout, ingen Compose. En enda `MainActivity`.
- `minSdk 26`, `targetSdk 35`.

## Målkoordinater

Hårdkodade överst i `app/src/main/java/se/t8m2vq/nedrakning/MainActivity.kt`:

```kotlin
const val TARGET_LAT = 59.330
const val TARGET_LON = 18.058
```

Standard är Stockholm C. Byt de två raderna för att peka om appen.

## Bygga

Kräver Android SDK och en JDK 17+. På macOS med Android Studio installerat:

```sh
cat > local.properties <<'EOL'
sdk.dir=/Users/DIN_ANVÄNDARE/Library/Android/sdk
EOL

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

## Installera

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Appen ber om `ACCESS_FINE_LOCATION` vid start. Nekas den visas en förklarande rad i
stället för en krasch.

## Licens

MIT — se [LICENSE](LICENSE).
