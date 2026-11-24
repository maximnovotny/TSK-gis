# GS Foto Prep

Offline Android aplikace pro přípravu fotek a videa na **Gaussian splatting** (3DGS, Postshot, Nerfstudio, COLMAP…):
AI zvětšení (Real-ESRGAN), doostření, odšumění a jednotné úpravy tónů. Vše běží **na GPU telefonu**,
bez internetu a bez jakýchkoli knihoven třetích stran.

## Co umí

| Krok | Funkce |
|---|---|
| Vstup | Výběr více fotek, celé složky, nebo videa. Funguje i „Sdílet“ z galerie. |
| Video → snímky | N snímků/s; v každém úseku vybere **nejostřejší** z K kandidátů (méně rozmazání pohybem). |
| Výběr ostrých | Skóre ostrosti (rozptyl Laplaciánu) a vyřazení X % nejrozmazanějších snímků. |
| Zvětšení | 1× / 2× / 4×. AI **Real-ESRGAN realesr-general-x4v3** (GLES 3.1 compute shadery, po dlaždicích s překryvem) nebo klasické bikubické. Limit delší strany 2048–8192 px. |
| Vylepšení | Odšumění (bilaterální filtr), doostření jasové složky (unsharp mask s prahem), expozice, kontrast, stíny, světla, sytost, **auto úrovně společné pro celou sadu**. |
| Výstup | JPEG 95/100 % nebo PNG do `Obrázky/GSPrep/<datum_čas>/`. Kopíruje EXIF (ohnisko, 35mm ekvivalent, GPS, čas), orientaci zapíše do pixelů. |
| Náhled | Výřez před/po s aktuálním nastavením, ještě před spuštěním celé dávky. |

### Proč je to „pro GS“
* **Konzistence:** všechny snímky dostanou úplně stejné úpravy. Automatické úpravy po jednotlivých snímcích by
  změnily barvy mezi pohledy a v modelu by vznikly plovoucí artefakty a skvrny.
* **Ostrost > rozlišení:** vyřazení rozmazaných snímků a výběr nejostřejšího snímku z videa pomáhá víc než doostření.
* **EXIF:** COLMAP z ohniskové vzdálenosti odhaduje kalibraci. Hodnoty v mm nezávisí na rozlišení, takže po
  zvětšení zůstávají správné. GPS se hodí pro georeferenci.
* **Měřítko:** původní 3DGS trénuje na max. ~1600 px. AI zvětšení má smysl hlavně pro malé zdroje
  (video 720p/1080p) nebo při tréninku v plném rozlišení.

## Jak z projektu dostat APK

Projekt je běžný Android/Gradle projekt bez závislostí (jen Android SDK).

**A) GitHub Actions (bez instalace čehokoli)**
1. Vytvořte na GitHubu nový repozitář a nahrajte do něj obsah této složky (včetně `.github/`).
2. Záložka **Actions**, workflow **Build APK** se spustí sám (nebo ho spusťte ručně tlačítkem *Run workflow*).
3. Po ~3–5 minutách stáhněte artefakt **GSFotoPrep-apk** (zip s `app-release.apk`).

**B) Android Studio**
*File → Open* (tuto složku) → *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

**C) Příkazová řádka** (nainstalované Android SDK, JDK 17)
```
./gradlew assembleRelease
# výsledek: app/build/outputs/apk/release/app-release.apk
```
APK je podepsané ladicím klíčem, takže jde rovnou nainstalovat. Při instalaci povolte instalaci z tohoto zdroje.

## Požadavky
Android 10+ a GPU s OpenGL ES 3.1. To splňuje prakticky každý telefon z posledních ~8 let.

## Výkon (orientačně)
AI model potřebuje ~1,2 milionu násobení na každý vstupní pixel. Na moderním telefonu (Snapdragon 8 Gen 1–3,
Dimensity 9000+) počítejte řádově s **10–40 s na snímek 1080p** při 2×. Klasické zvětšení a doostření trvá zlomky
sekundy. Během zpracování nechte aplikaci otevřenou; obrazovka zůstane rozsvícená.

## Technické poznámky
* `app/src/main/java/cz/gsprep/core/` je jádro nezávislé na Androidu: `GpuEngine` (pipeline), `Shaders` (GLSL ES 3.10),
  `Model` (načtení vah) a `ImageStats` (ostrost, histogramy).
* Shadery i Java pipeline byly ověřené na desktopu (Mesa llvmpipe, GLES 3.2) proti referenční implementaci sítě
  v numpy (ta se shoduje s ncnn na 3·10⁻⁶). Odchylka GPU výstupu je max. ~3/255, včetně švů mezi dlaždicemi.
* `tools/convert_model.py` převádí ncnn váhy (`realesr-general-x4v3`) do formátu `.gsm` v `assets/`.

## Licence modelu
Real-ESRGAN `realesr-general-x4v3` – © Xintao Wang a kol., licence BSD-3-Clause
(https://github.com/xinntao/Real-ESRGAN). Váhy převzaty ve formátu ncnn z projektu Upscayl (`realesrgan-x4fast`).
