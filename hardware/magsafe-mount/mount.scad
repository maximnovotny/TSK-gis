// =====================================================================
//  TSK-GIS :: MagSafe drzak  v6
//
//  DVA tistene dily (podle CAD nakresu uzivatele):
//    "deska"   - lezi na zadech telefonu, magnety MagSafe,
//                krabicka ZED-F9P se na ni prisroubuje 4x M3 zespodu,
//                na strane k antene pokracuje jazykem
//    "objimka" - objimka antény HA-901A s osou PODEL telefonu;
//                antena z ni vyleza nahoru nad horni hranu desky.
//                Na strane k desce ma plochou prirubu, ktera desku
//                PREKRYVA a je k ni pristazena 2x M3 kolmo k zadum
//                telefonu (imbusy videt zezadu).
//
//  Souradnice: pohled na ZADA telefonu, stred MagSafe = [0,0],
//              +X doprava, +Y k horni hrane, +Z od telefonu pryc.
//
//  Jednotky = mm.  Hodnoty  <<< ZMERIT  jsou zatim odhad.
// =====================================================================

$fn = 72;

/* [Co se tiskne] */
part = "sestava";     // "sestava" | "deska" | "objimka"

/* [Krabicka se ZED-F9P] */
box_w        = 62;    // <<< ZMERIT  sirka (osa X)
box_d        = 70;    // <<< ZMERIT  delka (osa Y)
box_h        = 16;    // <<< ZMERIT  vyska (jen nahled)
box_hole_dx  = 40;    // <<< ZMERIT  rozted montaznich der
box_hole_dy  = 56;    // <<< ZMERIT
box_screw_d  = 3.4;
box_head_d   = 6.2;

/* [Antena HA-901A] */
ant_d        = 43.5;
ant_h        = 40.8;
ant_pcd      = 30;    // <<< ZMERIT  roztecna kruznice 3 der M3
ant_screw_d  = 3.4;
ant_conn_d   = 17;
cup_depth    = 14.0;  // jak hluboko antena zajede do objimky
cup_base     = 5.0;   // dno objimky
cup_wall     = 3.5;   // stena objimky

/* [Poloha objimky] */
ant_side     = -1;    // -1 = vlevo pri pohledu na zada (jako na nakresu), 1 = vpravo
ant_gap      = 2.0;   // mezera mezi deskou a plastem objimky
cup_y0       = 0;     // 0 = automaticky tak, aby ustí objimky bylo u horni hrany desky
ant_z        = 0;     // 0 = automaticky, spodek objimky v rovine zad telefonu

/* [Priruba a spoj] */
fl_t         = 5.0;   // tloustka priruby (lezi NA desce)
fl_overlap   = 15.0;  // o kolik priruba prekryva desku
tab_len      = 13;    // o kolik deska presahuje krabicku na strane antény
joint_dy     = 18;    // svisla rozted dvou sroubu
joint_screw_d= 3.4;   // vule M3 v prirube
joint_head_d = 6.2;   // zahloubeni imbusove hlavy
joint_head_h = 3.2;
insert_d     = 4.0;   // dira v desce pro zalisovanou matici M3
insert_h     = 5.5;

/* [MagSafe magnety] */
magnet_mode  = "discs";
mag_pcd      = 50.5;
mag_d        = 6.0;
mag_t        = 3.0;
mag_n        = 12;
mag_fit      = 0.10;
mag_recess   = 0.3;
ring_od      = 56.5;
ring_id      = 42.0;
ring_t       = 1.3;

/* [Tvarovani] */
plate_t      = 6.5;   // deska musi unest zalisovanou matici
corner_r     = 10.0;
edge_r       = 2.0;
cup_r        = 3.0;   // zaobleni objimky

/* [Nahled] */
show_ghost   = true;
phone_w      = 72;
phone_l      = 148;
phone_t      = 8.5;

eps = 0.01;

// ---------------------------------------------------------------- //
//  ODVOZENE                                                         //
// ---------------------------------------------------------------- //
mag_pocket_d = mag_d + mag_fit;
cup_od  = ant_d + 2*cup_wall;
cup_len = cup_base + cup_depth;

// osa objimky
axp = ant_side * (box_w/2 + ant_gap + cup_od/2);
az  = (ant_z   != 0) ? ant_z   : cup_od/2;              // spodek objimky na z=0
cy0 = (cup_y0  != 0) ? cup_y0  : box_d/2 - cup_len;     // dno objimky

// jazyk desky pod prirubu - nese spojovaci srouby mimo prstenec magnetu
tab_x  = ant_side * (box_w/2 + tab_len/2);
tab_d  = cup_len + 10;
joint_x = tab_x;
joint_y = cy0 + cup_len/2;

// kontroly
_hole_r = sqrt(pow(box_hole_dx/2,2) + pow(box_hole_dy/2,2));
_mag_r  = mag_pcd/2 + mag_d/2;
_j_r1 = sqrt(pow(joint_x,2) + pow(joint_y - joint_dy/2, 2));
_j_r2 = sqrt(pow(joint_x,2) + pow(joint_y + joint_dy/2, 2));
echo(str(">>> osa objimky: X=", axp, "  Z=", az, "  objimka Y=", cy0, "..", cy0 + cup_len));
echo(str(">>> antena: Y=", cy0 + cup_base, "..", cy0 + cup_base + ant_h,
         "  (nad horni hranou desky o ", cy0 + cup_base + ant_h - box_d/2, " mm)"));
echo(str(">>> presah objimky pres bocni hranu telefonu = ",
         abs(axp) + cup_od/2 - phone_w/2, " mm"));
echo(str(">>> jazyk desky sahá do X=", abs(tab_x) + tab_len/2 + 2,
         " (pres hranu telefonu o ", abs(tab_x) + tab_len/2 + 2 - phone_w/2, " mm)"));
echo(str(">>> spojovaci srouby: X=", joint_x, "  Y=", joint_y - joint_dy/2,
         " a ", joint_y + joint_dy/2));
echo(str(">>> vzdalenost sroubu od stredu = ", _j_r1, " a ", _j_r2,
         " mm | vnejsi polomer magnetu = ", _mag_r, " mm"));
assert(_hole_r > _mag_r + 1.0 || magnet_mode != "discs",
       "KOLIZE: montazni diry krabicky zasahuji do prstence magnetu");
assert(min(_j_r1, _j_r2) > _mag_r + 2.0 || magnet_mode != "discs",
       "KOLIZE: spojovaci srouby zasahuji do prstence magnetu - zmen joint_dy / fl_overlap");

// ---------------------------------------------------------------- //
//  SPOLECNE                                                         //
// ---------------------------------------------------------------- //
module rrect(w, d, r, h) {
    linear_extrude(height = h) offset(r = r) square([w - 2*r, d - 2*r], center = true);
}

// zaobleny plochy dil s rovnym dnem
module soft_slab(w, d, r_plan, t, r_edge) {
    intersection() {
        minkowski() {
            linear_extrude(height = t - r_edge)
                offset(r = r_plan - r_edge) square([w - 2*r_plan, d - 2*r_plan], center = true);
            sphere(r = r_edge);
        }
        translate([0, 0, t/2]) cube([w + 20, d + 20, t], center = true);
    }
}

// souradny system objimky: osa +Z modulu = osa +Y telefonu
module at_cup() { translate([axp, cy0, az]) rotate([-90, 0, 0]) children(); }

module joint_holes(d, h, z0) {
    for (s = [-1, 1]) translate([joint_x, joint_y + s*joint_dy/2, z0]) cylinder(d = d, h = h);
}

// ---------------------------------------------------------------- //
//  DIL 1 - DESKA                                                    //
// ---------------------------------------------------------------- //
module plate_body() {
    hull() {
        soft_slab(box_w, box_d, corner_r, plate_t, edge_r);
        translate([tab_x, joint_y, 0]) soft_slab(tab_len + 4, tab_d, 7, plate_t, edge_r);
    }
}

module deska() {
    difference() {
        plate_body();
        // magnety - lisovane ze strany telefonu
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
        // 4x M3 pro krabicku, hlavy zapustene ze strany telefonu
        for (x = [-1, 1], y = [-1, 1])
            translate([x*box_hole_dx/2, y*box_hole_dy/2, -eps]) {
                cylinder(d = box_screw_d, h = plate_t + 2*eps);
                cylinder(d1 = box_head_d, d2 = box_screw_d,
                         h = (box_head_d - box_screw_d)/2 + eps);
            }
        // 2x dira pro zalisovanou matici M3 - shora, neprochazi skrz
        joint_holes(insert_d, insert_h + eps, plate_t - insert_h);
    }
}

// ---------------------------------------------------------------- //
//  DIL 2 - OBJIMKA S PRIRUBOU                                       //
// ---------------------------------------------------------------- //
module cup_solid() {
    minkowski() {
        cylinder(d = cup_od - 2*cup_r, h = cup_len - 2*cup_r);
        sphere(r = cup_r);
    }
}

// priruba lezi NA desce
module flange_solid() {
    fw = abs(axp - tab_x) + tab_len/2 + 2;         // od osy objimky az na jazyk
    fx = axp - ant_side * (fw/2 - 1);
    translate([fx, joint_y, plate_t])
        soft_slab(fw, tab_d - 2, 7, fl_t, 1.6);
}

module objimka() {
    difference() {
        union() {
            at_cup() cup_solid();
            flange_solid();
            hull() {                                // plynule napojeni priruby na objimku
                flange_solid();
                at_cup() cylinder(d = cup_od - 2*cup_r, h = cup_len - 2*cup_r);
            }
        }
        // duti pro telo antény
        at_cup() translate([0, 0, cup_base]) cylinder(d = ant_d + 0.6, h = cup_depth + ant_h);
        // 3x M3 do patky antény
        at_cup() for (i = [0 : 2])
            rotate([0, 0, 120*i + 90])
                translate([ant_pcd/2, 0, -eps]) cylinder(d = ant_screw_d, h = cup_base + 2*eps);
        // konektor a kabel
        at_cup() translate([0, 0, -eps]) cylinder(d = ant_conn_d, h = cup_base + 2*eps);
        // 2x M3 se zahloubenim pro imbusovou hlavu (shora, zezadu telefonu)
        joint_holes(joint_screw_d, fl_t + 2*eps, plate_t - eps);
        joint_holes(joint_head_d, joint_head_h + eps, plate_t + fl_t - joint_head_h);
        // vule: pod rovinou plochy desky smi byt jen to, co je uz ZA deskou
        tout = abs(tab_x) + tab_len/2 + 2;
        translate([ant_side < 0 ? -tout : -300, -200, -200])
            cube([300 + tout, 400, 200 + plate_t]);
    }
}

// ---------------------------------------------------------------- //
//  NAHLED                                                           //
// ---------------------------------------------------------------- //
module ghost() {
    %translate([0, 0, plate_t]) rrect(box_w, box_d, corner_r, box_h);
    %at_cup() translate([0, 0, cup_base]) cylinder(d = ant_d, h = ant_h);
    %translate([0, 0, -phone_t]) rrect(phone_w, phone_l, 12, phone_t);
}

if (part == "deska")        deska();
else if (part == "objimka") objimka();
else { deska(); objimka(); if (show_ghost) ghost(); }
