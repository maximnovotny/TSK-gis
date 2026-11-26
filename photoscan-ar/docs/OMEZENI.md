# Omezení a neověřené funkce

Tohle je nejdůležitější dokument v projektu. Čtěte ho před prvním reálným
nasazením.

## Co nebylo ověřeno na fyzickém zařízení

Aplikace byla vyvinuta a sestavena v linuxovém kontejneru **bez přístupu
k telefonu**. Úspěšná kompilace ani 99 jednotkových testů neříkají nic o tom,
jak se chová fotoaparát. **Neověřeno na Pixelu 9 (ani na žádném jiném
telefonu) je zejména:**

| Oblast | Co konkrétně není ověřeno |
|---|---|
| **Dosažitelná frekvence** | Jestli Pixel 9 uloží 2 plnohodnotné JPEG snímky za sekundu. Teoretický strop se čte z driveru (`minFrameDuration + stallDuration`) a podle něj se vybírá velikost snímku, ale skutečné číslo může být nižší. HUD proto vždy ukazuje **měřenou** rychlost, ne deklarovanou. |
| **Kombinace ARCore Shared Camera + still capture** | Toto je největší technické riziko celého projektu. Oficiální ARCore ukázky shared camera **nedělají** still capture; postup, který je tu použitý (JPEG surface v capture session, ale ne v ARCore repeating requestu), je logicky správný a je to standardní řešení, ale **na zařízení vyzkoušený není**. Proto je implementovaný automatický fallback na čistou Camera2 cestu. |
| **Vykreslování náhledu** | Mapování textury (rotace podle senzoru + crop na výplň) v režimu bez AR, překryv obrysů terčů přes náhled, orientace obrazu na obrazovce. Chyba zde je kosmetická, ale je možná. |
| **Skutečné rozlišení uloženého JPEG na Pixelu 9** | Jaká největší velikost se nabídne (12,5 MPx binned vs. 50 MPx) a jestli je při ní kadence udržitelná. |
| **EXIF** | Že HAL Pixelu skutečně vloží Make/Model/FocalLength/ExposureTime/ISO. Aplikace EXIF prvního snímku přečte a zapíše do `metadata/session_log.txt`, takže to po první session uvidíte. |
| **Manuální strop expozice (anti-blur)** | Jestli Pixel 9 vystaví `MANUAL_SENSOR` a jestli ruční čas + ISO dají použitelný výsledek. |
| **Chování při přehřívání a při dlouhém snímání** | Logika reakce je implementovaná a testovaná jednotkově, ale skutečný termální profil telefonu při dlouhém snímání ověřený není. |
| **Obnova po přerušení aplikace** | Že přerušená session po sobě nechá konzistentní projekt. Zápis metadat je navržený a jednotkově otestovaný jako průběžný (test `theWriterLeavesValidFilesBehindEvenIfTheSessionIsInterrupted`), ale skutečné zabití procesu během snímání vyzkoušené není. |
| **Export přes MediaStore** | ZIP do `Download/PhotoScanAR/` a kopie přes systémový dialog. Logika ZIP je jednotkově otestovaná včetně bajtové shody, samotný MediaStore ne. |

Pro všechno z toho jsou připravené **instrumentované testy**, které stačí
spustit na připojeném telefonu:

```bash
./gradlew :app:connectedDebugAndroidTest
```

`CaptureRateInstrumentedTest` vypíše skutečně měřenou frekvenci, velikosti
souborů, EXIF a hloubku zápisové fronty; `ArCoreInstrumentedTest` ověří shared
camera; `ExportInstrumentedTest` ověří export. Rate test schválně **netvrdí**
2 fps — měří a selže jen na něčem zjevně rozbitém (nic se neuložilo, špatné
rozlišení, duplicitní časové značky).

## Co bylo ověřeno (99 jednotkových testů, JVM)

| Oblast | Testy |
|---|---|
| AprilTag 36h11 — codebook | 587 kódů, min. Hammingova vzdálenost 11 včetně rotací, dekódování všech kódů i rotací, oprava 2 bitů, zamítnutí 5 bitů |
| AprilTag 36h11 — detektor | 18 vykreslených scén: vzpřímené terče různých velikostí a ID, rotace 90/180/270°, perspektivní zkosení, tři terče v jednom snímku, rozmazání + šum, nerovnoměrné osvětlení, a **negativní scény** (šachovnice, černý čtverec bez kódu) bez falešných detekcí; subpixelové rohy, rychlost 5,6 ms/snímek na JVM |
| Analýza obrazu | variance Laplaciánu (ostrý vs. rozmazaný), Tenengrad, směrový podíl, histogram expozice, percentily, ZNCC a obnovení posunu, odhad překryvu |
| Odhad překryvu | nehybná kamera = 100 %, monotónní pokles s krokem, bližší objekt = větší vliv kroku, rotace, obchůzka 4 m / 1,5° v cílovém pásmu |
| Pokrytí | frustum test, „stojím na místě a fotím 10×“ **nezezelená**, tři různé pohledy zezelenají, voxely bez pohledu zůstanou červené, celé kolečko vyplní směrovou skořepinu, druhé kolečko v jiné výšce se počítá zvlášť, navigace ukazuje do mezery |
| Řízení rychlosti | gating intervalem, protitlak fronty, limit snímků „in flight“, zastavení při nedostatku místa, zpomalení při zahlcení a přehřátí, návrat na cílovou kadenci, snížení rozlišení jen se souhlasem uživatele, DETAIL nikdy nesníží rozlišení, výpočet měřené fps včetně reakce na stall |
| SMART režim | první snímek, nehybný pohled, základna, rotace, překryv, vyčkání při rychlé rotaci, fallback bez AR pozice, minimální interval |
| Export projektu | číslování projektů včetně mezer, názvy souborů, CSV hlavička/escapování/čísla bez závislosti na locale, shoda šířky řádku s hlavičkou, rozlišení platných a neplatných AR pozic, UTC časové značky z nepůlnoční časové zóny, validita JSON, přepsání `cameras.csv` s doplněnou kvalitou, ZIP round-trip s bajtovou shodou a `STORED` pro JPEG, přerušení exportu |
| Párování snímku s AR pozicí | nejbližší pozice v čase + hlášení odchylky, kruhový buffer, jen trackované pozice v trajektorii, decimace |
| Geometrie | kvaterniony, směr pohledu, symetrie úhlů, q vs. −q, zachování délky |

## Věcná omezení (nikoli chyby)

1. **AR pozice jsou přibližné.** Vizuálně-inerciální odometrie driftuje, je
   v lokálním systému session a **nejsou to geodetické souřadnice**. Do
   RealityScan je neimportujte jako známou vnější orientaci. V exportu je to
   označené v `project.json` (`poses_are_approximate: true`) i ve sloupcích
   (`ar_tx_approx`, …).
2. **Pokrytí je vodítko, ne důkaz.** Zelená znamená „tento voxel byl ve
   zorném poli nejméně tří různě umístěných snímků“, nikoli „rekonstrukce
   vyjde“.
3. **Překryv je indikátor, ne počet tie-pointů.** Úplné hledání korespondencí
   (SIFT/ORB) mezi každou dvojicí snímků se při dvou snímcích za sekundu na
   telefonu spočítat nedá. Použitý je geometrický model z AR pozic plus ZNCC
   nad miniaturou.
4. **Terče: žádné měřítko ani souřadnice.** Aplikace detekuje ID a obrazové
   souřadnice a uloží vzdálenosti, které zadáte. Nepočítá z nich pozici terče
   v prostoru ani neškáluje trajektorii.
5. **ARCore vyžaduje Google Play Services for AR.** Na Pixelu 9 předinstalované.
   Bez něj je AR vrstva vypnutá a fotografuje se dál.
6. **V režimu shared camera řídí repeating request ARCore.** Uzamčení AE/AF a
   anti-blur se proto aplikují na *still-capture* request (tedy na samotnou
   fotografii), ne na náhled.
7. **Optická stabilizace (OIS) zůstává zapnutá** — priorita je ostrost, jak bylo
   zadáno. OIS ale mírně mění hlavní bod mezi snímky. Elektronická stabilizace
   (EIS), která by deformovala geometrii, je vypnutá vždy.
8. **Fotografie se ukládají do privátní složky aplikace**
   (`Android/data/cz.photoscanar/files/projects/`), což je rychlé a nevyžaduje
   oprávnění k úložišti, ale přes USB tam Android nepustí. Proto export: ZIP do
   `Download/` nebo kopie přes systémový dialog. Přímé čtení z PC jde přes
   `adb pull`.
9. **Orientace obrazovky je zamčená na portrét.** EXIF orientace se přitom
   odvozuje z fyzického držení telefonu (z `OrientationEventListener`), takže
   snímek pořízený na šířku se zobrazí správně. Pixely se nikdy nerotují.
10. **targetSdk je 36, ne 37.** Viz `INSTALACE.md` — jde o stabilitu buildu,
    nikoli o kompatibilitu; na Androidu 17 aplikace běží.
11. **Detekce terčů běží nad náhledovým snímkem** (~640×480 z ARCore, nebo
    vlastní YUV stream bez AR), ne nad plnou fotografií. Pro AR zobrazení a
    identifikaci ID to stačí; pro přesné obrazové souřadnice v plném rozlišení
    by bylo potřeba detekovat nad uloženým JPEG, což je zatím neimplementované.
12. **R8/ProGuard je vypnutý** i v release buildu. Záměrně: terénní aplikace
    nemá padat na obfuskačním pravidle, které nikdo nevyzkoušel na telefonu.
    APK je kvůli tomu asi o 3–4 MB větší.

## Známé věci k dotažení

- Detekce AprilTag nad uloženou fotografií v plném rozlišení (pro přesné
  obrazové souřadnice do exportu).
- Podpora rotace UI do krajiny (dnes zamčeno na portrét).
- Thumbnaily v historii snímání (dnes jen textový seznam).
- Odhad vzdálenosti objektu z ARCore depth API (dnes z point cloudu, případně
  z ručně nastavené hodnoty).
