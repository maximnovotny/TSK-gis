# Přehled implementovaných funkcí

## 1. Automatické fotografování

| Požadavek | Stav | Jak |
|---|---|---|
| Snímek každých 500 ms, cíl 2 fotky/s | implementováno | `capture/RateController` + pacer v `session/CaptureController` |
| Vysoké rozlišení originálů | implementováno | největší JPEG velikost, kterou driver označí za udržitelnou při cílové kadenci; v DETAIL vždy maximum (`camera/CaptureEngine.chooseInitialJpegSize`) |
| Hlavní kamera Pixelu, 1× | implementováno | `CameraInfo.selectMainRearCamera` vybere zadní logickou multi-kameru s největším JPEG; `CONTROL_ZOOM_RATIO = 1.0` |
| JPEG ve vysoké kvalitě | implementováno | `JPEG_QUALITY` nastavitelné 80–100, výchozí 97 |
| Zachování EXIF a parametrů kamery | implementováno | JPEG z HAL se ukládá bez jakékoli úpravy; parametry se navíc čtou z `TotalCaptureResult` do `cameras.csv`; EXIF prvního snímku se zkontroluje a zapíše do logu |
| Žádné výřezy z videa | implementováno | výhradně `CameraDevice.TEMPLATE_STILL_CAPTURE` přes `ImageReader` ve formátu JPEG |
| Minimalizace pohybového rozmazání | implementováno | EIS vypnutá, `CONTROL_AE_TARGET_FPS_RANGE` 30–60 (tlačí AE ke krátkým časům), volitelný manuální strop expozice 1/125 s s dorovnáním ISO |
| Autofokus a autoexpozice | implementováno | `CONTROL_AF_MODE_CONTINUOUS_PICTURE`, `CONTROL_AE_MODE_ON` |
| Uzamčení AE a AF | implementováno | přepínače v nastavení → `CONTROL_AE_LOCK`, `CONTROL_AF_MODE_OFF` |
| Jedno tlačítko START / STOP | implementováno | velké tlačítko dole uprostřed |
| Automatické přizpůsobení frekvence nebo rozlišení | implementováno | `RateController` zpomaluje podle zaplnění fronty, počtu snímků „in flight“ a teploty; při povolení uživatelem místo toho sníží rozlišení |
| Zobrazení skutečně dosažené frekvence | implementováno | HUD „Rychlost“ ukazuje `měřeno/cíl`; měří se z časů skutečných zápisů na disk |

## 2. AR navigace

- ARCore session s `Session.Feature.SHARED_CAMERA`, náhled jde do ARCore
  textury, still-capture přes vlastní `ImageReader` (`ar/ArEngine`,
  `camera/CaptureEngine`).
- **JPEG surface není registrovaná jako ARCore app surface** — ARCore ji tedy
  nezařadí do svého repeating requestu a nestřílí fotky na 30 fps; spouštíme
  je přesně my.
- Záznam 3D pozice a orientace: `trajectory.csv`, 10 Hz, včetně lineární
  a úhlové rychlosti.
- Trajektorie pohybu, značky míst pořízení a směrové paprsky v AR
  (`ui/gl/OverlayDraw`), plus minimapa z ptačí perspektivy.
- Historie snímání: klepnutí na minimapu → seznam posledních snímků s ostrostí,
  příznaky kvality a informací, zda měl snímek platnou AR pozici.
- Varování při ztrátě trackingu, včetně rozlišení důvodu (málo světla, málo
  textury, příliš rychlý pohyb).
- Upozornění při rychlém pohybu z **gyroskopu**, nezávisle na ARCore —
  funguje i s AR vypnutým.
- **Fallback:** selže-li vytvoření shared session, konfigurace sessionu nebo
  `resume()`, aplikace se automaticky přepne na čistou Camera2 cestu a
  pokračuje ve fotografování. Dvojí selhání na daném zařízení se zapamatuje a
  příště se shared camera nezkouší.

## 3. Kontrola pokrytí

Aplikace důsledně odlišuje dva různé údaje:

| Model | Co to je | V `project.json` |
|---|---|---|
| **Geometrické voxely** | mřížka 25 cm naplněná feature pointy z ARCore; u každého voxelu se počítají **různé** směry pohledu (dva snímky z téhož místa se počítají jako jeden) | `coverage.geometric_voxels`, `geometrically_verified: true` |
| **Směrová skořepina** | odhad z trajektorie telefonu, bez jakékoli znalosti objektu | `coverage.direction_shell_estimate`, `geometrically_verified: false` |

Barvy: zelená ≥ 3 různé pohledy, žlutá 1–2, červená 0. Doporučený směr dalšího
snímání se počítá k nejslabší buňce ve **výškových pásech, ze kterých už se
fotografovalo** — jinak by navigace ukazovala do míst, kam se nelze postavit.

Ani jeden údaj netvrdí, že rekonstrukce vyjde; v `project.json` i v README
projektu je to napsané výslovně.

## 4. Kontrola kvality

Asynchronně, na jednom vlákně s nízkou prioritou, nad zmenšeným dekódem
uloženého JPEG (pevně ~1200 px, aby byla čísla mezi snímky srovnatelná):

- **Ostrost:** variance Laplaciánu + Tenengrad (Sobelova gradientní energie)
  jako kontrola — plochá netexturovaná stěna se tak neoznačí jako rozmazaná.
- **Pohybové rozmazání:** směrový podíl gradientů; výrazná anizotropie spolu
  s nízkou ostrostí značí smear, ne rozostření. Plus úhlová rychlost
  z gyroskopu v momentě expozice.
- **Expozice:** histogram, průměrná jasnost, podíl vyhořelých a utopených
  pixelů, kontrast.
- **Překryv:** kombinace geometrického modelu z AR pozic (posun stopy při
  předpokládané vzdálenosti objektu + rotace + změna měřítka) a obrazového
  párování (ZNCC nad miniaturou 32×24 s prohledáním posunu). Cíl 80–85 %.
- **Téměř stejný záběr:** vysoký překryv + minimální rozdíl miniatur.

Snímek může být označen `BLURRY`, `DARK`, `BRIGHT`, `LOW_CONTRAST`,
`DUPLICATE`, `LOW_OVERLAP`, `FAST_MOTION`. **Nikdy se nic nemaže** — příznaky
jdou do `cameras.csv` a `metadata/quality.csv`, originál zůstává. Když analýza
nestíhá, snímek se označí jako neanalyzovaný; snímání se nezpomalí.

## 5. AprilTag 36h11

Vlastní detektor v Kotlinu (`apriltag/AprilTagDetector`), bez OpenCV a bez
nativní knihovny:

dlaždicový adaptivní threshold → komponenty tmavých pixelů → konvexní obálka →
čtyřúhelník ze čtyř extrémů → doladění hran metodou nejmenších čtverců
(subpixelové rohy) → homografie → vzorkování 6×6 datových buněk s referencí
černé z rámečku a bílé z klidové zóny → dekódování přes všechny 4 rotace.

- Codebook: **587 oficiálních kódů tag36h11** v `assets/apriltag36h11.txt`,
  minimální Hammingova vzdálenost 11 včetně rotací (otestováno).
- Oprava až 2 chybných bitů, jinak detekce zamítnuta.
- Běží na vlákně s nejnižší prioritou a drží jen **nejnovější** snímek —
  nestíhá-li, zahazuje mezisnímky, takže nikdy nezdrží fotografování.
- Rozpoznané terče se kreslí jako obrys s číslem přes živý náhled.
- Zadání známých vzdáleností mezi terči → `metadata/tag_distances.csv`.
- Export detekcí s obrazovými souřadnicemi → `detections.csv`.
- Aplikace **nepočítá** z terčů geodetické souřadnice ani neškáluje podle nich
  AR trajektorii.

## 6. Režimy snímání

| Režim | Chování |
|---|---|
| **AUTO 2 FPS** (výchozí) | pevná kadence 500 ms |
| **SMART** | spouští podle geometrie: základna ≥ 12 cm, rotace ≥ 5°, nebo odhadovaný překryv pod cílovým pásmem; při rychlé rotaci vyčká, nejpozději po 2,5 s sepne; bez AR pozice se chová jako pevná kadence |
| **DETAIL** | vždy maximální rozlišení, kadence 900 ms, nikdy nesnižuje rozlišení |

## 7. Uživatelské rozhraní

Tmavé, jednoručně ovladatelné: velké START/STOP dole uprostřed, volby režimu
nad ním, vedlejší tlačítka v dolních rozích. HUD: počet snímků, dosažená/cílová
rychlost, stav AR, volné místo, ostrost, překryv, pokrytí, terče, rozlišení,
hloubka zápisové fronty, teplota. Minimapa, textová navigace, přechodná
varování.

Tlačítko „oko“ **úplně skryje AR vrstvu** (včetně minimapy a obrysů terčů) a
nechá čistý náhled fotoaparátu. **AR grafika se nikdy nekreslí do uložených
fotografií** — vykresluje se v GL náhledu, který se neukládá; do souboru jde
výhradně JPEG z fotoaparátu.

## 8. Export

```
PhotoScanAR_Project_001/
├── images/                 IMG_00001.jpg … originály bez úprav
├── metadata/
│   ├── tag_distances.csv   vzdálenosti zadané obsluhou
│   ├── quality.csv         průběžné výsledky analýzy kvality
│   ├── session_log.txt     log session včetně EXIF kontroly a AR událostí
│   └── README_RealityScan.txt
├── project.json            zařízení, kamera, session, pokrytí, kvalita
├── cameras.csv             řádek na snímek
├── trajectory.csv          celý proud pozic, ne jen okamžiky snímků
└── detections.csv          detekce AprilTag
```

- Metadata se zapisují **průběžně**, ne na konci: přerušená session po sobě
  nechá platné CSV. Na konci se `cameras.csv` jednou přepíše s doplněnými
  výsledky asynchronní analýzy.
- Společné hodiny: `capture_timestamp_ns` (senzor) a `timestamp_ns`
  (trajektorie) jsou ve stejné časové základně, pokud kamera hlásí
  `REALTIME` zdroj časových značek — a `project.json` zaznamená, pokud ne.
- Platné i neplatné AR pozice se rozlišují sloupcem `ar_pose_valid`.
- ZIP export do `Download/PhotoScanAR/` (viditelné z PC přes USB), JPEGy se
  ukládají **nekomprimované** (`STORED`) — deflate u JPEG nic neušetří a stál
  by minuty CPU.
- Kopie celé struktury do libovolné složky přes systémový dialog (i USB/SD).

## 9. Technické

Kotlin, Camera2 + ARCore, žádný cloud, žádná reklama, žádné předplatné, žádné
oprávnění k poloze. Analýza obrazu je napsaná v Kotlinu bez OpenCV (menší APK,
žádná nativní závislost). Kontrola volného místa (snímání se zastaví při
< 400 MB), reakce na přehřívání přes `PowerManager` thermal status, omezená
zápisová fronta s protitlakem místo neomezeného rostoucího bufferu.

**Uložení snímku nikdy nečeká na AR ani na analýzu.** Z `ImageReader` jde
JPEG přímo do zápisové fronty; metadata, která dorazí později, se do záznamu
dopíší.
