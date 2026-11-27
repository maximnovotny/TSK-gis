# MagSafe držák: ZED-F9P + GNSS anténa HA-901A

Parametrický model držáku, který pevně spojuje šedou krabičku se SparkFun
GPS-RTK2 (ZED-F9P), GNSS anténu HA-901A a magnetické uchycení na záda telefonu.

**Stav: ladí se umístění a tvar. Rozměry krabičky a antény jsou zatím odhad –
viz „Co je potřeba změřit".**

| soubor | co to je |
|---|---|
| `mount.scad` | zdroj modelu – tady se mění všechny rozměry |
| `v4_dil.png`, `v4_dil2.png` | samotný díl |
| `v4_telefon.png`, `v4_iso.png` | sestava na zádech telefonu |

## Tvar

Jedno plynulé těleso bez hran a schodů:

```
deska pod krabičkou  →  úzký krk  →  prstencová objímka antény
```

- Spodek je rovina = dosedací plocha na záda telefonu, v ní jsou zalisované
  magnety v kruhu MagSafe (Ø 50,5 mm).
- Krabička se shora přišroubuje 4× M3, hlavy jsou zapuštěné ze strany telefonu.
- Anténa zajede do objímky (`cup_depth`) a míří čelem k horní hraně telefonu,
  takže při držení telefonu v ruce směřuje na oblohu.
- Objímka je v horním rohu na opačné straně než fotoaparát.

Průřez se podél osy X spojitě mění ze „širokého a plochého" (deska) na „úzký
a vysoký" (krk) a pak se rozevře do prstence. Celé je to vytvořené jako řetěz
`hull()` mezi 80 průřezy, takže tam nejsou žádné napojené plochy.

## Co je potřeba změřit

| parametr | co změřit | teď |
|---|---|---|
| `box_w`, `box_d`, `box_h` | šířka, délka a výška šedé krabičky | 62 × 70 × 30 |
| `box_hole_dx`, `box_hole_dy` | rozteč 4 montážních děr v jejím dně | 40 × 56 |
| `ant_pcd` | průměr roztečné kružnice 3 děr M3 na patce antény | 30 |
| `phone_w`, `phone_l`, `phone_t` | rozměry telefonu | 72 × 148 × 8,5 |
| `cam_x`, `cam_y`, `cam_w`, `cam_d`, `cam_h` | poloha a velikost výstupku foťáku | odhad |

Souřadnice: **střed MagSafe prstence = [0,0]**, `+X` doprava a `+Y` k horní
hraně při pohledu na **záda** telefonu, `+Z` od telefonu pryč.

## Ladění umístění

| parametr | význam |
|---|---|
| `ant_y` | posun objímky k horní hraně telefonu (dál od ruky) |
| `ant_gap` | mezera mezi krabičkou a objímkou |
| `ant_x` | 0 = spočítá se automaticky; jinak ruční poloha |
| `ant_side` | 1 = vpravo při pohledu na záda, -1 = vlevo |
| `cup_depth` | jak hluboko anténa zajede do objímky |
| `neck_w`, `neck_h`, `neck_r` | šířka, výška a zaoblení krku |
| `box_x`, `box_y` | posun krabičky vůči středu MagSafe (vyvážení, obejití foťáku) |

Render vypíše kontroly do konzole:

```
>>> objímka: X=…  Y=…  osa Z=…
>>> přesah přes boční hranu telefonu = … mm
>>> anténa vyčnívá z objímky = … mm
>>> deska naráží do výstupku fotoaparátu: ANO/ne
```

Náhled a export:

```bash
openscad -o drzak.stl mount.scad          # STL
openscad -o nahled.png --camera=15,10,22,0,0,0,560 mount.scad
```

## Materiál

- **12× neodymový disk Ø6 × 3 mm, N52.** Polarita se musí po kruhu střídat
  N-S-N-S; před zalepením vyzkoušet na telefonu. Alternativa: `magnet_mode =
  "ring"` a koupit hotový samolepicí MagSafe kroužek (Ø 56/42 mm), kde je
  polarita zaručeně správná.
- **4× M3 zápustný šroub** (DIN 7991) pro krabičku.
- **3× M3** pro anténu, délka = `cup_base` (4,5 mm) + hloubka závitu v patce.
- Samolepicí plsť 0,5 mm na spodní plochu – chrání telefon a zvyšuje tření.

## Tisk

- **PETG nebo ASA**, ne PLA.
- Deskou na podložku, objímkou nahoru. V téhle poloze jdou tahové síly
  z vyloženého ramene podél vrstev = nejpevnější orientace.
- 4–5 perimetrů, 40 % infill.
- Dělit na víc dílů nemá smysl: jediné dělicí místo je krk, a tam je zároveň
  největší ohybový moment.

## Na co si dát pozor

1. **Páka na magnetech.** Anténa je ~70 g ve vzdálenosti ~59 mm od středu
   magnetů. MagSafe drží kolmý tah dobře, klopný moment podstatně hůř.
   Na trvalé nošení doporučuju magnetický kryt s kovovým prstencem nebo
   mechanickou pojistku přes hranu telefonu.
2. **Kompas telefonu.** Magnety těsně u telefonu rozhodí magnetometr –
   azimut bude potřeba brát z GNSS, ne z kompasu.
3. **Širokoúhlý objektiv.** Pokud by roh krku zasahoval do záběru, zvětši
   `ant_y` nebo `ant_gap`.
