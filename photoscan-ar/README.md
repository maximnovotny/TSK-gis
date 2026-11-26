# PhotoScan AR

Android aplikace pro pořizování fotografií pro fotogrammetrii — automatické
snímání dvou fotek za sekundu při chůzi okolo objektu, s AR navigací, která
ukazuje, odkud už snímky jsou a kam ještě jít.

Určeno pro Google Pixel 9 / Android 17, funguje offline, bez účtu, bez reklam,
bez předplatného a **bez oprávnění k poloze**.

## Rychlý start

1. Nainstalujte `app-release.apk` (viz [docs/INSTALACE.md](docs/INSTALACE.md)).
2. Povolte fotoaparát.
3. Nastavte si odstup od objektu, zamkněte AE/AF.
4. **START**, obejděte objekt, **STOP**.
5. *Projekty → Export ZIP* → archiv je v `Download/PhotoScanAR/`.
6. V RealityScan importujte podsložku `images/`
   (viz [docs/FOTOGRAFOVANI_REALITYSCAN.md](docs/FOTOGRAFOVANI_REALITYSCAN.md)).

## Dokumentace

| Dokument | O čem |
|---|---|
| [docs/INSTALACE.md](docs/INSTALACE.md) | instalace APK, build ze zdrojů, podpis |
| [docs/FOTOGRAFOVANI_REALITYSCAN.md](docs/FOTOGRAFOVANI_REALITYSCAN.md) | jak fotografovat, zpracování v RealityScan a COLMAP |
| [docs/FUNKCE.md](docs/FUNKCE.md) | přehled implementovaných funkcí a jak jsou řešené |
| [docs/OMEZENI.md](docs/OMEZENI.md) | **omezení a co nebylo ověřeno na zařízení** |

## Architektura

```
cz.photoscanar
├── camera/      Camera2: výběr kamery, still capture, zápisová fronta
├── ar/          ARCore session (shared camera), historie pozic
├── capture/     adaptivní řízení kadence, SMART trigger
├── analysis/    asynchronní analýza kvality, detekce terčů
├── apriltag/    vlastní detektor AprilTag 36h11 (bez OpenCV)
├── coverage/    voxelové pokrytí, směrová skořepina, odhad překryvu
├── imaging/     ostrost, expozice, obrazové párování (čistá JVM logika)
├── project/     rozvržení projektu, CSV/JSON zápis, ZIP
├── session/     orchestrace snímací session
├── ui/          HUD, minimapa, obrysy terčů, GL vykreslování
└── export/      ZIP do Download, kopie přes systémový dialog
```

Pravidlo, kterým se řídí celý návrh: **uložení fotografie nikdy nečeká na AR,
na analýzu kvality ani na detekci terčů.** Z `ImageReader` jde JPEG přímo do
omezené zápisové fronty obsluhované vlastními vlákny; cokoli pomalejšího je
zahoditelné a metadata, která dorazí později, se do záznamu dopíší.

Druhé pravidlo: **AR je pomůcka, nikoli závislost.** Selže-li ARCore kdykoli
(není nainstalované, nepodporované zařízení, shared camera nefunguje, tracking
se ztratí), aplikace se přepne na čistou Camera2 cestu a fotografuje dál.

## Testy

```bash
./gradlew :app:testDebugUnitTest          # 99 testů, JVM, bez telefonu
./gradlew :app:connectedDebugAndroidTest  # testy, které potřebují fotoaparát
```

Jednotkové testy pokrývají detektor AprilTag (proti 18 vykresleným scénám
včetně negativních), analýzu obrazu, odhad překryvu, oba modely pokrytí,
adaptivní řízení kadence, SMART trigger, párování snímku s AR pozicí a celý
export projektu včetně bajtové shody v ZIP.

**Funkce fotoaparátu a ARCore na fyzickém zařízení ověřené nejsou** — viz
[docs/OMEZENI.md](docs/OMEZENI.md).

## Licence a třetí strany

- Codebook AprilTag `tag36h11` (587 kódů, `app/src/main/assets/apriltag36h11.txt`)
  vychází z rodiny AprilTag (BSD-2-Clause, University of Michigan); hodnoty byly
  extrahovány ze slovníku `DICT_APRILTAG_36h11` v OpenCV ArUco.
- Google ARCore SDK (`com.google.ar:core`) — Apache 2.0.
- AndroidX, Material Components, kotlinx.coroutines — Apache 2.0.
