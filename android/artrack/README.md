# ARTrack — záznam trajektorie (ARCore) + GPS k videu pro fotogrammetrii

Jednoduchá Android aplikace: jedno tlačítko, které současně nahrává

* **video.mp4** — surový stream z kamery (nahrává přímo ARCore),
* **pose.csv** — pozici a orientaci kamery pro **každý snímek videa** (VIO/AR tracking),
* **gps.csv + track.gpx** — všechny GPS fixy během záznamu,
* **meta.json** — zařízení, kalibraci kamery (fx, fy, cx, cy), počty, časové báze.

Snímky si pak z videa vyřízneš ručně (nebo skriptem v `tools/`) a ke každému máš
polohu kamery → fotogrammetrii lze inicializovat z known poses místo slepého SfM.

## Instalace

APK: `android/artrack/apk/artrack-debug.apk` (podepsané debug klíčem — stačí
povolit „instalace z neznámých zdrojů“).

Požadavky:

* Android 10 (API 29) a vyšší,
* telefon [podporovaný ARCore](https://developers.google.com/ar/devices) +
  nainstalované *Google Play Services for AR* (aplikace si o instalaci řekne sama),
* oprávnění kamera + poloha (přesná).

## Použití

1. Spustit aplikaci, chvíli pomalu zamíchat telefonem — stav `tracking: TRACKING`.
2. Počkat na GPS fix (řádek `GPS:` ukáže souřadnice a přesnost).
3. **REC** → nahrávání. Chodit **pomalu**, plynule, bez rychlých otočení,
   objekt snímat s velkým překryvem.
4. **STOP** → aplikace dopíše CSV a přesune celou složku do
   `Download/ARTrack/<datum_čas>/`, odkud ji vytáhneš přes USB nebo správce souborů.

Přepínač **Autofocus** (vlevo nahoře je stav, vpravo nahoře přepínač):

* **vypnutý** (doporučeno) = fixní zaostření → konstantní ohnisková vzdálenost,
  tedy jedna kalibrace pro celé video; vhodné pro fasády a scény od ~2 m,
* **zapnutý** = ostří průběžně, ale `fx/fy` se mění snímek po snímku
  (v `pose.csv` je proto kalibrace u každého řádku).

Záznam se automaticky ukončí, když aplikaci přepneš do pozadí.

## Výstupní soubory

`pose.csv` — jeden řádek na jeden snímek videa:

| sloupec | význam |
|---|---|
| `frame_index` | index snímku ve videu (0-based) |
| `t_rel_s` | čas snímku od začátku videa [s] — lze použít místo indexu |
| `timestamp_ns` | původní časová značka kamery [ns] |
| `tx, ty, tz` | pozice kamery ve světovém systému ARCore [m] |
| `qx, qy, qz, qw` | orientace kamery jako kvaternion (camera → world) |
| `tracking_state` | `TRACKING` / `PAUSED` / `STOPPED` — pro fotogrammetrii ber jen `TRACKING` |
| `failure_reason` | důvod, proč tracking nejede |
| `fx, fy, cx, cy` | kalibrace daného snímku videa [px] |
| `lat, lon, alt_m` | poslední známý GPS fix v době snímku (WGS84, výška nad elipsoidem) |
| `gps_acc_m`, `gps_age_ms` | přesnost fixu a jak byl v ten moment starý |

**Konvence:** pozice je *camera-to-world*, osy kamery +X vpravo, +Y vzhůru,
−Z dopředu (OpenGL styl). Jde o `Camera.getPose()`, tedy pose fyzické kamery
nezávislý na rotaci displeje — odpovídá přesně tomu, co je ve videu.
Počátek světa = místo startu session, +Y je svisle vzhůru (podle gravitace),
azimut je **libovolný** (ARCore není orientovaný na severní směr —
georeferencování dělej z GPS).

`gps.csv` má u každého fixu `t_rel_s_approx`, což je čas videa v okamžiku
příchodu fixu (±1 snímek), takže GPS a video jde spárovat bez řešení hodinových bází.

Složka záznamu obsahuje i `README.txt` s popisem sloupců, takže data jsou
samovysvětlující i po roce.

## Řezání snímků a export poloh

```bash
# každý 10. snímek + COLMAP pózy + lat/lon ke každému snímku
python3 tools/artrack_export.py /cesta/k/Download/ARTrack/20261127_143512 --step 10
```

Vznikne `export/`:

* `images/frame_XXXXXX.jpg` — vyříznuté snímky (jen ty s `TRACKING`),
* `colmap/cameras.txt`, `colmap/images.txt`, `colmap/points3D.txt` —
  pózy převedené do konvence COLMAP (world-to-camera, +Z dopředu, +Y dolů);
  lze rovnou použít jako `--import_path` pro triangulaci bez hledání pozic,
* `frames_geo.csv` — ke každému snímku WGS84 `lat/lon/alt` (interpolované z GPS)
  a lokální AR souřadnice; import do RealityCapture / Metashape jako camera priors.

Potřebuje jen `ffmpeg` na PATH a standardní Python 3.

Ruční varianta (přesně stejné mapování indexů):

```bash
ffmpeg -i video.mp4 -vf "select='not(mod(n\,10))'" -vsync 0 -q:v 2 frame_%06d.jpg
# n-tý vyříznutý obrázek odpovídá řádku pose.csv s frame_index = n*10
```

## Co aplikace *nedělá*

* Nepřepočítává AR trajektorii do S-JTSK ani do ENU — v `pose.csv` jsou lokální
  metry a vedle nich GPS; transformaci (Helmert z GPS fixů) si dělej v GIS.
* Nepoužívá ARCore Geospatial API (globální pose z VPS) — to by dalo řádově
  lepší georeferencování, ale vyžaduje API klíč a připojení.
* Neodstraňuje distorzi objektivu a neposkytuje její model; v `meta.json` je
  pinhole kalibrace jako prior, zbytek nech dopočítat fotogrammetrickému SW.
* Neřeší expozici/ISO ani bitrate — video je to, co dá ARCore (typicky 1920×1080@30).

## Přesnost, na kterou se dá počítat

ARCore VIO drží řádově **1–2 % ujdené vzdálenosti** při plynulém pohybu, tedy
několik centimetrů na krátkou obchůzku a desítky centimetrů na delší trasu;
při rychlém pohybu, v šeru nebo nad málo texturovaným povrchem driftuje víc
(hlídej `tracking_state`). GPS z telefonu je ±3–10 m horizontálně a výrazně
hůř ve výšce — slouží k umístění modelu do mapy, ne k jeho měřítku.
Měřítko (to podstatné) drží AR trajektorie.

## Build ze zdrojů

```bash
cd android/artrack
echo "sdk.dir=/cesta/k/Android/sdk" > local.properties
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

Android Gradle Plugin 8.13.2, Kotlin 2.1.21, compileSdk 36, ARCore 1.57.0.
Celý kód je ve `app/src/main/java/cz/tsk/artrack/` (6 souborů, bez frameworků):

| soubor | role |
|---|---|
| `MainActivity.kt` | lifecycle, ARCore session, start/stop záznamu, UI |
| `SessionRecorder.kt` | zápis `pose.csv`, `gps.csv`, `track.gpx`, `meta.json` |
| `LocationTracker.kt` | fused location provider |
| `Exporter.kt` | přesun složky do `Download/` přes MediaStore |
| `BackgroundRenderer.kt`, `ShaderUtil.kt`, `DisplayRotationHelper.kt` | hledáček |
