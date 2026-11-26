# Instalace PhotoScan AR

## Co potřebujete

- Google Pixel 9 (nebo jiný Android telefon s Androidem 10 a novějším, minSdk 29)
- Soubor `app-release.apk`
- Volné místo v úložišti: snímání na 2 fotky/s spotřebuje přibližně **0,5 GB za minutu**

## Postup

1. Zkopírujte `app-release.apk` do telefonu (USB, Disk, e-mail — jakkoli).
2. V telefonu otevřete **Soubory** a klepněte na APK.
3. Android se zeptá na povolení instalace z tohoto zdroje — potvrďte
   (*Nastavení → Aplikace → Speciální přístup → Instalovat neznámé aplikace*).
4. Po instalaci spusťte **PhotoScan AR** a povolte přístup k **fotoaparátu**.
   Jiné oprávnění aplikace nežádá — zejména **nežádá o polohu**.

### Instalace přes adb (z PC)

```bash
adb install -r app-release.apk
```

## Dvě varianty APK

| Soubor | Package | K čemu |
|---|---|---|
| `app-release.apk` | `cz.photoscanar` | Běžné použití. Podepsané, bez ladicích informací. |
| `app-debug.apk` | `cz.photoscanar.debug` | Ladění, jiný package → může být nainstalované **vedle** release verze. |

Podpis release APK:

```
CN=PhotoScan AR, OU=Photogrammetry, O=PhotoScan AR, L=Praha, C=CZ
RSA 4096, APK Signature Scheme v3 (minSdk 29 je vyšší než API 28, kde v3 začíná,
takže v1/v2 nejsou potřeba)
```

Klíč je v projektu jako `photoscan-release.jks` (heslo i alias jsou v
`keystore.properties`). Je to **self-signed klíč vytvořený pro tento projekt**,
nikoli produkční klíč — pro vlastní distribuci si vygenerujte svůj
(`keytool -genkeypair -keystore muj.jks -alias mujalias -keyalg RSA -keysize 4096 -validity 10950`).

## ARCore (Google Play Services for AR)

AR navigace potřebuje systémovou komponentu **Google Play Services for AR**.
Na Pixelu 9 je předinstalovaná. Pokud chybí, AR funkce se samy vypnou a
**fotografování funguje dál** — aplikace je napsaná tak, že AR nikdy nesmí
zablokovat snímání.

Aplikace nepotřebuje přihlášení, účet ani internet. Jediná část, která by
internet mohla chtít, je případná aktualizace Play Services for AR z Play Store;
samotné snímání, analýza, detekce terčů i export jsou plně offline.

## Build ze zdrojových kódů

```bash
# Android SDK (platform 36 + build-tools 36.0.0 + platform-tools)
echo "sdk.dir=/cesta/k/Android/Sdk" > local.properties

./gradlew :app:assembleRelease        # podepsané APK (potřebuje keystore.properties)
./gradlew :app:assembleDebug          # ladicí APK
./gradlew :app:testDebugUnitTest      # 99 jednotkových testů (JVM, bez telefonu)
./gradlew :app:connectedDebugAndroidTest   # testy na připojeném telefonu
```

Použité verze: Gradle 8.14.3, Android Gradle Plugin 8.13.2, Kotlin 2.2.21,
compileSdk/targetSdk 36, JDK 17.

### Proč targetSdk 36 a ne 37

Android 17 (API 37) je v SDK dostupný jen s Android Gradle Pluginem 9.x, který
přináší řadu nekompatibilních změn v build skriptech. Zvolil jsem stabilní
kombinaci AGP 8.13.2 + compileSdk 36. **Aplikace se na Androidu 17 normálně
nainstaluje a běží** — targetSdk 36 jen znamená, že se nepřihlašuje k novým
změnám chování API 37. Posun na 37 je později otázka změny dvou čísel v
`app/build.gradle.kts` a upgradu AGP.
