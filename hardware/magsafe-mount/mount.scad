// =====================================================================
//  TSK-GIS :: MagSafe drzak  v4   (podle skici)
//
//  Jedno plynule teleso:
//     deska pod krabickou ZED-F9P  ->  uzky krk  ->  prstencova
//     objimka, do ktere zapadne GNSS antena HA-901A celem vzhuru.
//  Spodek je rovina = dosedaci plocha na zada telefonu, v ni jsou
//  zalisovane magnety v kruhu MagSafe.
//
//  Jednotky = mm.  Hodnoty  <<< ZMERIT  jsou zatim odhad.
// =====================================================================

$fn = 72;

/* [Krabicka se ZED-F9P] */
box_w        = 62;    // <<< ZMERIT  sirka krabicky (osa X)
box_d        = 70;    // <<< ZMERIT  delka krabicky (osa Y)
box_h        = 30;    // <<< ZMERIT  vyska krabicky (jen nahled)
box_hole_dx  = 40;    // <<< ZMERIT  rozted montaznich der
box_hole_dy  = 56;    // <<< ZMERIT
box_x        = 0;
box_y        = 0;
box_screw_d  = 3.4;
box_head_d   = 6.2;

/* [Antena HA-901A] */
ant_d        = 43.5;  // ze stitku
ant_h        = 40.8;  // ze stitku
ant_pcd      = 30;    // <<< ZMERIT  roztecna kruznice 3 der M3
ant_screw_d  = 3.4;
ant_conn_d   = 17;    // otvor pro konektor a kabel
cup_depth    = 14.0;  // jak hluboko antena zajede do objimky
cup_base     = 4.5;   // dno objimky
cup_wall     = 3.5;   // stena objimky

/* [Poloha anteny] */
ant_side     = 1;     // 1 = vpravo pri pohledu na zada telefonu
ant_x        = 0;     // 0 = automaticky tesne vedle krabicky
ant_gap      = 3.0;   // mezera krabicka <-> objimka
ant_y        = 22;    // posun k horni hrane telefonu (dal od ruky)

/* [Krk] */
neck_w       = 26;    // sirka krku (osa Y)
neck_h       = 16;    // vyska krku (osa Z)
neck_r       = 6.5;   // zaobleni krku
neck_pos     = 0.55;  // kde lezi nejuzsi misto (0 = u desky, 1 = u objimky)

/* [MagSafe magnety] */
magnet_mode  = "discs";
mag_pcd      = 50.5;
mag_d        = 6.0;
mag_t        = 3.0;
mag_n        = 12;    // sudy pocet, polarita se strida N-S-N-S
mag_fit      = 0.10;
mag_recess   = 0.3;
ring_od      = 56.5;
ring_id      = 42.0;
ring_t       = 1.3;

/* [Tvarovani] */
plate_t      = 5.0;
corner_r     = 12.0;  // zaobleni desky v pudorysu
edge_r       = 2.2;   // zaobleni hran desky
rim_r        = 2.5;   // zaobleni obvodu objimky
steps        = 80;

/* [Nahled] */
show_ghost   = true;
phone_w      = 72;
phone_l      = 148;
phone_t      = 8.5;
cam_w        = 36;
cam_d        = 36;
cam_x        = -17;
cam_y        = 50;
cam_h        = 4.0;

eps = 0.01;

// ---------------------------------------------------------------- //
//  ODVOZENE                                                         //
// ---------------------------------------------------------------- //
mag_pocket_d = mag_d + mag_fit;
cup_od  = ant_d + 2*cup_wall;        // vnejsi prumer objimky
cup_len = cup_base + cup_depth;      // delka objimky v ose Y

axp = (ant_x != 0) ? ant_x
                   : ant_side * (abs(box_x) + box_w/2 + ant_gap + cup_od/2);
az  = cup_od/2;                      // osa objimky; spodek lezi v rovine z=0

xA = box_x - ant_side * box_w/2;                  // vzdalena hrana desky
xC = box_x + ant_side * (box_w/2 - corner_r);     // odkud se deska vytahuje
xN = axp - ant_side * (cup_od/2 + 1.0);           // konec krku u objimky

function ss(t)  = (t <= 0) ? 0 : (t >= 1) ? 1 : t*t*(3 - 2*t);
function lerp(a, b, t) = a + (b - a)*t;

function sx(i)  = lerp(xA, xN, i/steps);
function dl(i)  = (sx(i) - xA) * ant_side;        // vzdalenost od hrany desky

// nabeh na vzdalene hrane desky
function leadY(i) = (dl(i) < corner_r)
    ? corner_r - sqrt(max(0, corner_r*corner_r - pow(corner_r - dl(i), 2))) : 0;
function leadZ(i) = (dl(i) < edge_r)
    ? edge_r - sqrt(max(0, edge_r*edge_r - pow(edge_r - dl(i), 2))) : 0;

// prechod deska -> krk
function tn(i) = ss(((sx(i) - xC) * ant_side) / ((xN - xC) * ant_side));

function sLy(i) = lerp(box_d - 2*leadY(i), neck_w, pow(tn(i), 1.5));
function sLz(i) = lerp(plate_t - leadZ(i), neck_h, pow(tn(i), 1.5));
function sR(i)  = min(lerp(edge_r, neck_r, tn(i)), min(sLy(i), sLz(i))/2 - 0.02);
function sRb(i) = lerp(edge_r, 0, min(1, tn(i)*2));
function sYc(i) = lerp(box_y, ant_y + cup_len/2, pow(tn(i), 1.6));

// kontroly
_hole_r = sqrt(pow(box_hole_dx/2,2) + pow(box_hole_dy/2,2));
_mag_r  = mag_pcd/2 + mag_d/2;
echo(str(">>> objimka: X=", axp, "  Y=", ant_y, "..", ant_y + cup_len,
         "  osa Z=", az, " mm"));
echo(str(">>> presah pres bocni hranu telefonu = ",
         max(abs(axp) + cup_od/2 - phone_w/2, 0), " mm"));
echo(str(">>> antena vycniva z objimky = ", ant_h - cup_depth, " mm"));
echo(str(">>> nejvyssi bod = ", az + cup_od/2, " mm nad zady telefonu"));
assert(_hole_r > _mag_r + 1.0 || magnet_mode != "discs",
       "KOLIZE: montazni diry krabicky zasahuji do prstence magnetu");
assert(plate_t - mag_t - mag_recess >= 0.8 || magnet_mode != "discs",
       "PRILIS TENKY STROP NAD MAGNETEM - zvets plate_t");
_cam_hit = (cam_x - cam_w/2 < box_x + box_w/2) && (cam_x + cam_w/2 > box_x - box_w/2) &&
           (cam_y - cam_d/2 < box_y + box_d/2) && (cam_y + cam_d/2 > box_y - box_d/2);
echo(str(">>> deska naraz i do vystupku fotoaparatu: ", _cam_hit ? "ANO - posun box_y" : "ne"));

// ---------------------------------------------------------------- //
//  TELESO                                                           //
// ---------------------------------------------------------------- //
module at_cup() { translate([axp, ant_y, az]) rotate([-90, 0, 0]) children(); }

// objimka se zaoblenym obvodem
module cup() {
    minkowski() {
        cylinder(d = cup_od - 2*rim_r, h = cup_len - 2*rim_r);
        sphere(r = rim_r);
    }
}

// prurez kolmo na osu X; dno lezi v rovine z = 0
module section(i) {
    Ly = sLy(i); Lz = sLz(i); r = sR(i); rb = sRb(i);
    translate([sx(i), sYc(i), (Lz - rb)/2])
        rotate([0, 90, 0])
            linear_extrude(height = eps)
                offset(r = r)
                    square([max(Lz + rb - 2*r, 0.02), max(Ly - 2*r, 0.02)], center = true);
}

module body() {
    intersection() {
        union() {
            for (i = [0 : steps - 1]) hull() { section(i); section(i + 1); }
            hull() { section(steps); at_cup() cup(); }   // nabeh krku do objimky
            at_cup() cup();
        }
        translate([0, 0, (az + cup_od)/2])
            cube([abs(axp)*2 + cup_od + 60, box_d + cup_len*2 + 60, az + cup_od], center = true);
    }
}

// ---------------------------------------------------------------- //
//  ODEBRANI                                                         //
// ---------------------------------------------------------------- //
module magnet_cuts() {
    if (magnet_mode == "discs")
        for (i = [0 : mag_n - 1])
            rotate([0, 0, i*360/mag_n + 180/mag_n])
                translate([mag_pcd/2, 0, -eps])
                    cylinder(d = mag_pocket_d, h = mag_t + mag_recess + eps);
    else
        translate([0, 0, -eps]) difference() {
            cylinder(d = ring_od + 0.4, h = ring_t + 0.2 + eps);
            translate([0,0,-eps]) cylinder(d = ring_id - 0.4, h = ring_t + 0.4 + eps);
        }
}

module box_screw_cuts() {
    for (x = [-1, 1], y = [-1, 1])
        translate([box_x + x*box_hole_dx/2, box_y + y*box_hole_dy/2, -eps]) {
            cylinder(d = box_screw_d, h = plate_t + 2*eps);
            cylinder(d1 = box_head_d, d2 = box_screw_d,
                     h = (box_head_d - box_screw_d)/2 + eps);
        }
}

module antenna_cuts() {
    at_cup() {
        // duti objimky pro telo anteny
        translate([0, 0, cup_base])
            cylinder(d = ant_d + 0.6, h = cup_depth + ant_h);
        // 3x M3 do patky anteny
        for (i = [0 : 2])
            rotate([0, 0, 120*i + 90])
                translate([ant_pcd/2, 0, -eps])
                    cylinder(d = ant_screw_d, h = cup_base + 2*eps);
        // konektor a kabel
        translate([0, 0, -eps]) cylinder(d = ant_conn_d, h = cup_base + 2*eps);
    }
}

module mount() {
    difference() {
        body();
        magnet_cuts();
        box_screw_cuts();
        antenna_cuts();
    }
}

// ---------------------------------------------------------------- //
//  NAHLED                                                           //
// ---------------------------------------------------------------- //
module rrect(w, d, r, h) {
    linear_extrude(height = h) offset(r = r) square([w - 2*r, d - 2*r], center = true);
}
module ghost() {
    %translate([box_x, box_y, plate_t]) rrect(box_w, box_d, corner_r, box_h);
    %at_cup() translate([0, 0, cup_base]) cylinder(d = ant_d, h = ant_h);
    %translate([0, 0, -phone_t]) rrect(phone_w, phone_l, 12, phone_t);
    %translate([cam_x, cam_y, 0]) rrect(cam_w, cam_d, 10, cam_h);
}

mount();
if (show_ghost) ghost();
