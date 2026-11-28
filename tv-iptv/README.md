# IPTV Hrač — aplikace pro Samsung TV (Tizen)

Přehrávač IPTV z M3U playlistu pro sideload do televize. Celé ovládání je
dálkovým ovladačem, nastavení se zadává přímo na televizi a ukládá se v ní.

Postavené pro konkrétní kus:

| | |
| --- | --- |
| Model | UE50RU7022KXXH (RU7000, 2019) |
| Platforma | Tizen 5.0, `required_version="5.0"` v `config.xml` |
| Firmware | T-MSLDEUC-1510.0 |
| DUID | 7XCC27C7T4QTC — tohle číslo ukáže Certificate Manager při vytváření certifikátu |
| MAC | 3868A4F3BF0D |

## Co umí

- M3U / M3U8 playlist z internetu (`tvg-id`, `tvg-logo`, `tvg-chno`, `group-title`,
  `#EXTGRP`, `#EXTVLCOPT` s User-Agentem a Refererem, přípona `|User-Agent=…`)
- Přehrávání přes nativní `webapis.avplay` — HLS, MPEG-TS, DASH, progresivní MP4
- Seznam kanálů se skupinami, oblíbenými, hledáním a detailem pořadu
- EPG z XMLTV (nekomprimovaný `.xml`), teď/příště v liště i v detailu
- Přepínání čísly kanálů, CH+/CH−, poslední kanál po spuštění
- Automatické obnovení streamu při výpadku (5 pokusů s prodlužující se pauzou)
- Poměr stran, délka informační lišty, řazení kanálů — vše v nastavení v aplikaci

Co **neumí**: kanály chráněné DRM (Widevine/PlayReady) — ty Tizen web aplikaci
nepustí, aplikace je pozná podle `#KODIPROP` a rovnou to napíše. Dál nepodporuje
EPG zabalené v `.gz` (televize ho nerozbalí) a nahrávání.

## Ovládání

| Tlačítko | V přehrávači | V seznamu kanálů |
| --- | --- | --- |
| OK | otevře seznam kanálů | pustí kanál |
| ▲ ▼ | kanál nahoru/dolů | pohyb v seznamu |
| ◀ ▶ | informační lišta / seznam | přepnutí mezi skupinami a kanály |
| CH+ / CH− | kanál nahoru/dolů | stránkování |
| 0–9 | přepnutí na číslo kanálu | totéž |
| INFO | informační lišta s programem | program k označenému kanálu |
| červená | nastavení | nastavení |
| zelená | hledání | hledání |
| žlutá | oblíbený ano/ne | oblíbený ano/ne |
| modrá | poměr stran | poměr stran |
| ▶❚❚ | pauza / pokračovat | — |
| RETURN | 2× ukončí aplikaci | zavře seznam |

## Instalace na televizi

### 1. Developer mode na TV

1. `Apps` (Aplikace) → stiskni na ovladači **1 2 3 4 5**
2. `Developer mode` přepni na **On**
3. `Host PC IP` → IP adresa počítače, ze kterého budeš instalovat
4. Potvrď a televizi **vypni a zapni** (Developer mode se projeví až po restartu)

Televize i počítač musí být ve stejné síti. IP adresu televize najdeš
v `Nastavení → Obecné → Síť → Stav sítě → Nastavení IP`.

### 2. Tizen Studio na počítači

Stáhni [Tizen Studio](https://developer.tizen.org/development/tizen-studio/download)
a v `Package Manager` doinstaluj:

- **TV Extensions-5.0** (odpovídá firmwaru téhle televize)
- **Samsung Certificate Extension**

Pak přidej nástroje do PATH:

```bash
export PATH="$HOME/tizen-studio/tools/ide/bin:$HOME/tizen-studio/tools:$PATH"
```

### 3. Certifikát

V Tizen Studiu `Tools → Certificate Manager → + → Samsung → TV`:

1. přihlas se Samsung účtem,
2. vytvoř **author** certifikát,
3. u **distributor** certifikátu nech nástroj načíst DUID z připojené televize
   (musí odpovídat `7XCC27C7T4QTC`),
4. profil pojmenuj třeba `samsung` — jméno se předává skriptu jako `PROFILE`.

Bez distributor certifikátu s DUID téhle konkrétní televize balíček nepůjde
nainstalovat.

### 4. Sestavení a instalace

```bash
cd tv-iptv
./build.sh                                   # jen vyrobí .wgt
TV_IP=192.168.1.50 PROFILE=samsung ./build.sh install
```

Skript zavolá `sdb connect <TV_IP>:26101`, `tizen build-web`, `tizen package`
a `tizen install`. Televize se při prvním `sdb connect` zeptá, jestli povolit
spojení — potvrď na obrazovce.

Aplikace se pak objeví v `Apps` jako **IPTV Hrac**.

Po továrním resetu televize nebo vypnutí Developer mode je potřeba instalaci
zopakovat. Samsungský certifikát má omezenou platnost (obvykle 2 roky), po
vypršení se musí v Certificate Manageru obnovit a aplikace přeinstalovat.

## První spuštění

Bez nastavené adresy playlistu hraje přiložený vzorek (`assets/playlist.m3u`,
veřejné testovací streamy) — hned je vidět, jestli přehrávání na televizi
funguje. Vlastní playlist se zadá **červeným tlačítkem → Adresa playlistu**.
Adresa se píše klávesnicí na obrazovce, zkratky `http://` a `https://` jsou
v posledním řádku.

Pokud playlist obsahuje `url-tvg`, EPG se načte samo; jinak se adresa XMLTV
zadá v nastavení.

## Vývoj a kontrola na PC

Televize není potřeba, vzhled i ovládání se dají projít v prohlížeči:

```bash
node dev/test.js         # funkční test ovládání (Playwright, headless)
node dev/screenshot.js   # snímky obrazovek do dev/shots/
```

V prohlížeči jsou barevná tlačítka na **F1–F4**, INFO na **F5**, pauza na
**F6**, RETURN na **Esc**, CH+/CH− na **PageUp/PageDown**. HLS streamy Chrome
sám nepřehraje — na PC jde ověřit vzhled a ovládání, ne přehrávání.

## Struktura

```
config.xml          manifest Tizen aplikace (ID, oprávnění, verze platformy)
index.html          kostra obrazovek
css/app.css         vzhled, rozměry počítané z 1920×1080
js/util.js          pomocné funkce, XHR, skládání diakritiky
js/store.js         nastavení a oblíbené v localStorage
js/keys.js          kódy tlačítek ovladače a jejich registrace
js/m3u.js           parser playlistu
js/epg.js           parser XMLTV (regexem, kvůli paměti televize)
js/player.js        avplay na TV, <video> v prohlížeči
js/ui.js            obrazovky, fokus, vykreslování
js/app.js           propojení všeho
assets/playlist.m3u ukázkový playlist pro první spuštění
dev/                testy a snímky obrazovek, do balíčku se nebalí
build.sh            build + instalace přes tizen CLI
```

Kód je psaný pro Chromium 63 (Tizen 5.0) — žádné `?.`, `??` ani ES moduly,
protože widget běží z `file://`.
