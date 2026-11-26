# Jak fotografovat pro RealityScan / COLMAP

Tohle je ta část, která o výsledku rozhoduje víc než jakákoli funkce aplikace.
Fotogrammetrie odpouští málo: rozmazané snímky, skoky v překryvu a měnící se
expozice zkazí rekonstrukci bez ohledu na to, kolik fotek pořídíte.

## Před snímáním

1. **Světlo.** Zataženo je lepší než ostré slunce. Tvrdé stíny se při změně
   pozice slunce „hýbou“ a software je vyhodnotí jako texturu objektu.
   Nefotografujte proti slunci.
2. **Vzdálenost.** Zvolte si odstup, při kterém objekt vyplňuje záběr, a držte
   ho po celou obchůzku přibližně stejný. V *Nastavení → Vzdálenost objektu*
   nastavte přibližnou hodnotu — podle ní se počítá odhad překryvu.
3. **Expozice a ostření.** Jednou se nastavte na reprezentativní část objektu,
   pak v *Nastavení* zapněte **Uzamknout expozici (AE)** a **Uzamknout ostření
   (AF)**. Konstantní expozice v rámci jedné obchůzky je pro napojení snímků
   výrazně lepší než „vždy správně“ exponovaný snímek.
4. **Anti-blur.** Ponechte zapnuté *Omezit expoziční čas*. Aplikace pak při
   zhoršeném světle raději zvedne ISO než nechá delší čas závěrky — šum se dá
   v rekonstrukci přežít, pohybová neostrost nikoli.
5. **Režim.** Pro běžnou práci **AUTO 2 FPS**. **SMART** použijte, když chodíte
   pomalu a nechcete stovky téměř stejných záběrů. **DETAIL** na výztuž, hrany
   betonu a konstrukční detaily — nejvyšší rozlišení a nižší kadence.

## Samotná obchůzka

- **START** a jděte. Telefon držte oběma rukama u těla, obrazovka svisle.
- **Plynule.** Nerotujte telefonem během snímání; otočte se celým tělem.
  Při rychlém pohybu aplikace vypíše *Zpomalte pohyb* — to je varování před
  rozmazáním, ne kosmetika.
- **Překryv 80–85 %.** Ukazatel vlevo ho průběžně zobrazuje. Zeleně je
  v pořádku, červeně fotografujete příliš řídce.
- **Celý kruh.** Projděte celé kolečko okolo objektu, ne jen přední stranu.
  Minimapa vlevo dole ukazuje, kudy jste šli a odkud jsou snímky.
- **Druhé kolečko v jiné výšce nebo náklonu.** Jedno kolečko samo o sobě
  skoro nikdy nepokryje horní plochy. Aplikace to počítá: ukazatel „Pokrytí“
  hodnotí jen ty výškové pásy, ze kterých jste skutečně fotografovali, a počet
  pásů najdete v `project.json`.
- **Žlutá/červená v AR.** Barevné body v AR vrstvě jsou voxely se třemi a více
  různými pohledy (zelená), jedním až dvěma (žlutá) a bez pohledu (červená).
  Šipka a text *Pokračujte vpravo/vlevo…* navádí k nejslabšímu místu.
- **STOP** ukončí projekt. Aplikace dopíše metadata a ukáže souhrn.

## Čeho se vyvarovat

| Nedělejte | Proč |
|---|---|
| Fotografování z jednoho místa dokola (panorama) | Bez posunu kamery nevznikne hloubka. Fotogrammetrie potřebuje základnu mezi snímky. |
| Zoom | Aplikace drží 1× hlavní kamery. Zoom mění optickou soustavu a kalibraci. |
| Míchání snímků z ultrawide/tele | Jiné objektivy = jiný kamerový model v jednom projektu. |
| Lesklé a průhledné plochy | Sklo, voda a čerstvě nalitý beton se nerekonstruují. |
| Měnící se scéna | Jedoucí auta, lidé, vlající plachty — software je vyhodnotí jako šum. |
| Mazání „neostrých“ snímků v telefonu | Dělejte to až na PC, po prohlédnutí. Aplikace nic nemaže sama. |

## Zpracování v RealityScan Desktop

1. Projekt dostanete z telefonu dvěma způsoby (*Projekty* v aplikaci):
   - **Export ZIP** → `Download/PhotoScanAR/<projekt>.zip`, vidět přes USB i ve
     Souborech.
   - **Kopírovat do složky…** → systémový dialog, lze zapsat i na USB disk nebo
     SD kartu; zkopíruje celou strukturu projektu.
2. Na PC rozbalte archiv.
3. RealityScan: **Workflow → Add Imagery → Folder** a vyberte podsložku
   `images/`.
4. Spusťte **Align Images** s běžným nastavením.
5. **AR pozice neimportujte** jako známé pozice kamer ani jako vlícovací body —
   proč, je níže.
6. Pokud jste použili terče: `detections.csv` obsahuje obrazové souřadnice
   detekcí a `metadata/tag_distances.csv` vzdálenosti, které jste naměřili.
   Měřítko si z nich postavte sami v RealityScan (*Define Distance*);
   aplikace žádné měřítko nedopočítává.

## Zpracování v COLMAP

```bash
colmap feature_extractor  --database_path db.db --image_path images \
                          --ImageReader.single_camera 1
colmap sequential_matcher --database_path db.db          # obchůzka = sekvence
colmap mapper             --database_path db.db --image_path images --output_path sparse
```

`--ImageReader.single_camera 1` je v pořádku: všechny snímky jednoho projektu
jsou ze stejné kamery při stejném rozlišení. Pokud jste zapnuli
*EXIF orientace vždy 0*, mají navíc všechny snímky identickou orientaci pixelů,
což COLMAPu svědčí.

## Co AR data jsou a co nejsou

Sloupce `ar_*` v `cameras.csv` a `trajectory.csv` pocházejí z vizuálně-inerciální
odometrie ARCore. Jsou:

- v **lokálním souřadném systému session** (počátek tam, kde snímání začalo,
  osa Y nahoru, metry),
- **přibližné** — v průběhu obchůzky driftují, zejména u málo texturovaných
  ploch, za horšího světla a po každém přerušení trackingu,
- **nejsou geodetické souřadnice**, **nejsou zaměřená vnější orientace** a
  **nenahrazují** vyrovnání, které si fotogrammetrický software spočítá sám.

Řádky s `ar_pose_valid = 0` nemají použitelnou pozici vůbec; sloupce s pozicí
jsou tam prázdné, ne nulové.

Použijte je na kontrolu, že jste obešli celý objekt, na vizualizaci, odkud
snímky jsou, nebo jako hrubou inicializaci. Nic víc.
