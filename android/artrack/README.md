# ARTrack — záznam trajektorie (ARCore) + GPS + fotky pro fotogrammetrii

Jednoduchá Android aplikace: jedno tlačítko, které naráz nahrává

* **frames/** — plnorozlišné JPEG fotky v nastaveném intervalu (hlavní vstup do fotogrammetrie),
* **frames.csv** — ke každé fotce pozici a orientaci kamery + kalibraci + GPS,
* **pose.csv** — pózu kamery pro **každý trackovaný snímek** (celá trajektorie, AR/VIO),
* **gps.csv + track.gpx** — všechny GPS fixy během záznamu,
* **video.mp4** — volitelně záznam ARCore session (viz upozornění níže),
* **meta.json** — zařízení, kalibrace kamery, počty, časové báze.

Smysl: fotogrammetrický SW nemusí hledat pozice kamer naslepo — pro každý snímek
dostane externí orientaci (z AR trackingu, v metrech a se správným měřítkem)
a polohu v WGS84 (z GPS).

## Instalace

APK: [`apk/artrack-debug.apk`](apk/artrack-debug.apk) — podepsané debug klíčem,
takže v telefonu stačí povolit instalaci z neznámých zdrojů.

Požadavky:

* Android 10 (API 29) a vyšší,
* telefon [podporovaný ARCore](https://developers.google.com/ar/devices)
  + *Google Play Services for AR* (aplikace si o instalaci řekne sama),
* oprávnění kamera + přesná poloha.

## Použití

1. Spustit, chvíli pomalu zamíchat telefonem, dokud není `tracking: TRACKING`.
2. Počkat na GPS fix (řádek `GPS:` ukáže souřadnice a přesnost).
3. **REC** → záznam. Chodit **pomalu a plynule**, bez rychlých otočení,
   objekt snímat s velkým překryvem.
4. **STOP** → aplikace dopíše CSV a přesune celou složku do
   `Download/ARTrack/<datum_čas>/`, odkud ji vytáhneš přes USB nebo správce souborů.

Přepínače (vpravo nahoře, nastavují se před spuštěním záznamu):

| přepínač | význam |
|---|---|
| **Foto: 0,25 / 0,5 / 1 / 2 s / vypnuto** | interval ukládání plnorozlišných JPEG (výchozí 0,5 s) |
| **Video (VGA)** | zapne i záznam MP4 přes ARCore (výchozí zapnuto) |
| **Autofocus** | vypnutý (výchozí) = fixní zaostření a tedy konstantní ohnisko pro celou sérii; zapnutý ostří průběžně, ale `fx/fy` se mění snímek po snímku (proto je kalibrace u každého řádku CSV) |

Záznam se sám ukončí, když aplikaci přepneš do pozadí.

## ⚠️ Video je jen referenční, fotky jsou ostrá data

ARCore nahrává MP4 **ve VGA (640×480) při 5 Mbps** — to je dané knihovnou,
ne nastavením aplikace (viz dokumentace `Session.startRecording()`). Na
fotogrammetrii je to málo, takže aplikace vedle videa ukládá **plnorozlišné
JPEG** z CPU streamu kamery, typicky **1920×1080** (ARCore nedává víc; strop
je rozlišení GPU textury). Každá fotka má v `frames.csv` svou pózu, takže
z videa nic řezat nemusíš — ale můžeš, mapování indexů je popsané níž.

Pokud by bylo potřeba **plné rozlišení senzoru** (12 Mpx a výš), musel by se
záznam přepsat na ARCore *shared camera* + Camera2 JPEG capture. To je jiná
liga složitosti; tady vědomě není.

## Výstupní soubory

`frames.csv` — jeden řádek na jednu uloženou fotku:

| sloupec | význam |
|---|---|
| `image_name` | soubor ve `frames/` |
| `frame_index` | index odpovídajícího řádku v `pose.csv` |
| `t_rel_s` | čas od prvního snímku záznamu [s] |
| `timestamp_ns` | původní časová značka kamery [ns] |
| `tx, ty, tz` | pozice kamery ve světovém systému ARCore [m] |
| `qx, qy, qz, qw` | orientace kamery jako kvaternion (camera → world) |
| `tracking_state` | `TRACKING` / `PAUSED` / `STOPPED` — ber jen `TRACKING` |
| `fx, fy, cx, cy` | kalibrace **této fotky** [px] |
| `width, height` | rozměr fotky [px] |
| `lat, lon, alt_m` | poslední známý GPS fix (WGS84, výška nad elipsoidem) |
| `gps_acc_m`, `gps_age_ms` | přesnost fixu a jak byl starý |

`pose.csv` má stejné sloupce pro **každý** trackovaný snímek (celá trajektorie),
jen `fx,fy,cx,cy` patří video streamu, ne fotkám.

**Konvence:** póza je *camera-to-world*, osy kamery +X vpravo, +Y vzhůru,
−Z dopředu (OpenGL styl). Jde o `Camera.getPose()`, tedy pózu fyzické kamery,
kde „vpravo/vzhůru“ je vztažené k **readoutu obrazu**, ne k displeji — póza
tedy odpovídá uloženým fotkám bez ohledu na to, jak jsi telefon držel
(JPEG se ukládají neotočené, v orientaci senzoru, bez EXIF).
Počátek světa = místo startu session, +Y je svisle vzhůru (podle gravitace),
azimut je **libovolný** — ARCore není orientovaný na sever, georeferencuj z GPS.

`gps.csv` má u každého fixu `t_rel_s_approx` = čas záznamu v okamžiku příchodu
fixu (±1 snímek), takže GPS a snímky jdou spárovat bez řešení hodinových bází.

Složka záznamu dostane i vlastní `README.txt` s popisem sloupců — data jsou
samovysvětlující i po roce.

## Export do COLMAPu

```bash
python3 tools/artrack_export.py /cesta/k/Download/ARTrack/20261127_143512
python3 tools/artrack_export.py ... --step 2        # jen každá druhá fotka
python3 tools/artrack_export.py ... --from-video --step 10   # řezání z videa
```

Vznikne `export/`:

* `colmap/cameras.txt`, `colmap/images.txt`, `colmap/points3D.txt` — pózy
  převedené do konvence COLMAP (world-to-camera, +Z dopředu, +Y dolů), hotové
  k importu jako známé pozice kamer,
* `frames_geo.csv` — ke každému snímku WGS84 `lat/lon/alt` (chybějící
  interpoluje z `gps.csv`) a lokální AR souřadnice; použitelné jako camera
  priors v RealityCapture / Metashape.

Skript jede na čistém Pythonu 3; `ffmpeg` potřebuje jen režim `--from-video`.
Ruční řezání videa se stejným mapováním indexů:

```bash
ffmpeg -i video.mp4 -vf "select='not(mod(n\,10))'" -vsync 0 -q:v 2 frame_%06d.jpg
# n-tý vyříznutý obrázek odpovídá řádku pose.csv s frame_index = n*10
```

## Import do RealityScan (Epic Games)

Export vedle COLMAPu vyrobí i `export/realityscan/`:

| soubor | k čemu |
|---|---|
| `trajectory.csv` | pozice kamer `name,x,y,z` v projektovaném CRS (default UTM zóna podle GPS) |
| `trajectory_space.txt` | totéž oddělené mezerami, kdyby import nebral čárky |
| `exif_gps.args` | argfile pro `exiftool` — zapíše GPS do EXIFu samotných fotek |
| `xmp/*.xmp` | (jen s `--xmp`) XMP sidecary s plnou pózou — **experimentální** |
| `jak-importovat.txt` | postup krok za krokem včetně reportu fitu |

Přepínače:

```bash
python3 tools/artrack_export.py <session>                 # UTM zóna podle GPS
python3 tools/artrack_export.py <session> --epsg 32633     # konkrétní EPSG
python3 tools/artrack_export.py <session> --epsg local     # lokální metry, bez GPS
python3 tools/artrack_export.py <session> --heading 137     # azimut AR osy +X ručně
python3 tools/artrack_export.py <session> --xmp             # + XMP s rotacemi
```

Postup v RealityScanu (cesta A, doporučená): nastavit CRS projektu **před**
importem → načíst fotky z `frames/` → *WORKFLOW → Import Metadata → Trajectory*
(ve starších verzích *Flight log*) → jako popis sloupců zadat `name x y z` →
v nastavení alignmentu **zapnout camera priors** → Align Images. Když popis
sloupců nesedí, import neohlásí chybu, jen sloupce zahodí — po importu zkontroluj
u pár kamer, že mají prior pozici.

Rotace se do trajektorie záměrně nepíšou: přesnou konvenci omega/phi/kappa
v RealityScanu jsem neměl z čeho ověřit a špatná rotace je horší než žádná.
Orientace si alignment dopočítá sám, pozice (a tím měřítko) jsou to cenné.

### Jak se dělá transformace AR → mapa

AR trajektorie je v metrech, ale azimut má libovolný (ARCore nemá kompas).
Z GPS fixů se proto odhadne **jen otočení kolem svislé osy a posun**, měřítko
zůstává 1 — AR měřítko je o dva řády lepší než GPS. Export vypíše RMS fitu
a **nejistotu azimutu** (≈ RMS / délka trasy): na pár metrech je azimut
nejistý o desítky stupňů, na desítkách metrů jsou to jednotky stupňů.
Na pozice kamer to má malý vliv (ověřeno na syntetických datech: při 2 m šumu
GPS jsou pozice do 1 m od pravdy i při chybě azimutu 10°), ale pokud potřebuješ
model správně otočený k severu, zadej `--heading` nebo exportuj `--epsg local`
a otoč to v GISu. Převod WGS84 → UTM je ověřený proti `pyproj` (odchylka < 1 mm).

## Co aplikace *nedělá*

* Nepřepočítává AR trajektorii do S-JTSK ani ENU — v CSV jsou lokální metry
  vedle WGS84; transformaci (Helmert z GPS fixů) si udělej v GIS.
* Nepoužívá ARCore Geospatial API (globální póza z VPS), které by dalo řádově
  lepší georeferencování — vyžaduje API klíč a připojení.
* Neposkytuje model distorze objektivu; `fx,fy,cx,cy` jsou jen pinhole prior,
  zbytek nech dopočítat fotogrammetrickému SW.
* Neřeší expozici, ISO ani vyvážení bílé — bere, co dá ARCore.

## Přesnost, na kterou se dá počítat

ARCore VIO drží řádově **1–2 % ujdené vzdálenosti** při plynulém pohybu —
jednotky centimetrů na krátkou obchůzku, desítky centimetrů na delší trasu;
při rychlém pohybu, v šeru nebo nad málo texturovaným povrchem driftuje víc
(hlídej `tracking_state`). GPS z telefonu je ±3–10 m horizontálně a výrazně
hůř ve výšce — slouží k umístění modelu do mapy, ne k jeho měřítku. Měřítko
(to podstatné) drží AR trajektorie.

## Build ze zdrojů

```bash
cd android/artrack
echo "sdk.dir=/cesta/k/Android/sdk" > local.properties
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

AGP 8.13.2, Kotlin 2.1.21, compileSdk 36, ARCore 1.57.0, minSdk 29.
Kód je v `app/src/main/java/cz/tsk/artrack/` — bez frameworků, 9 souborů:

| soubor | role |
|---|---|
| `MainActivity.kt` | lifecycle, ARCore session, start/stop záznamu, UI |
| `SessionRecorder.kt` | zápis `frames.csv`, `pose.csv`, `gps.csv`, `track.gpx`, `meta.json` |
| `StillCapturer.kt` | JPEG komprese na worker threadu (zahozený snímek místo zadrhnutí) |
| `YuvUtil.kt` | YUV_420_888 → NV21 |
| `LocationTracker.kt` | fused location provider |
| `Exporter.kt` | přesun složky do `Download/` přes MediaStore |
| `BackgroundRenderer.kt`, `ShaderUtil.kt`, `DisplayRotationHelper.kt` | hledáček |
