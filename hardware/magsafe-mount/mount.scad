// =====================================================================
//  TSK-GIS :: MagSafe drzak  v5   (podle skici - pohled shora)
//
//  DVA tistene dily, sesroubovane 2x M3:
//    DIL 1 "deska"   - lezi na zadech telefonu, magnety MagSafe,
//                      krabicka ZED-F9P se na ni prisroubuje 4x M3,
//                      v +X pokracuje jazykem pro spoj
//    DIL 2 "objimka" - objimka antény HA-901A + rameno s patkou,
//                      patka dosedne na jazyk dilu 1
//
//  Osa antény lezi ve VYSCE STREDU KRABICKY, ne nad ni. Objimka smi
//  presahovat pod rovinu zad telefonu, protoze je uz vedle telefonu.
//
//  Jednotky = mm.  Hodnoty  <<< ZMERIT  jsou zatim odhad.
// =====================================================================

$fn = 72;

/* [Co se tiskne] */
part = "sestava";     // "sestava" | "deska" | "objimka"

/* [Krabicka se ZED-F9P] */
box_w        = 62;    // <<< ZMERIT  sirka krabicky (osa X)
box_d        = 70;    // <<< ZMERIT  delka krabicky (osa Y)
box_h        = 16;    // <<< ZMERIT  vyska krabicky - urcuje vysku osy antény
box_hole_dx  = 40;    // <<< ZMERIT  rozted montaznich der
box_hole_dy  = 56;    // <<< ZMERIT
box_x        = 0;
box_y        = 0;
box_screw_d  = 3.4;
box_head_d   = 6.2;

/* [Antena HA-901A] */
ant_d        = 43.5;
ant_h        = 40.8;
ant_pcd      = 30;    // <<< ZMERIT  roztecna kruznice 3 der M3
ant_screw_d  = 3.4;
ant_conn_d   = 17;
cup_depth    = 14.0;  // jak hluboko antena zajede do objimky
cup_base     = 4.5;   // dno objimky
cup_wall     = 3.5;   // stena objimky

/* [Poloha anteny] */
ant_gap      = 3.0;   // mezera krabicka <-> objimka
ant_y        = 20;    // posun k horni hrane telefonu (dal od ruky)
ant_z        = 0;     // 0 = automaticky do osy krabicky

/* [Spoj obou dilu] */
tab_len      = 11;    // o kolik deska presahuje krabicku smerem k antene
tab_d        = 44;    // sirka jazyka (osa Y)
foot_t       = 4.5;   // tloustka patky dilu 2
joint_dy     = 14;    // rozted dvou spojovacich sroubu
joint_screw_d= 3.4;
joint_head_d = 6.2;
joint_boss_d = 4.2;   // dira v dilu 2 pro zalisovanou matici M3 / samorez

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
plate_t      = 5.0;
corner_r     = 12.0;
edge_r       = 2.2;
rim_r        = 2.5;
arm_r        = 5.0;   // zaobleni ramene

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
cup_od  = ant_d + 2*cup_wall;
cup_len = cup_base + cup_depth;

az  = (ant_z != 0) ? ant_z : plate_t + box_h/2;     // osa antény
axp = box_x + box_w/2 + ant_gap + cup_od/2;         // X osy antény

tab_x0 = box_x + box_w/2;
tab_x1 = tab_x0 + tab_len;
joint_x = (tab_x0 + tab_x1)/2;

// kontroly
_hole_r = sqrt(pow(box_hole_dx/2,2) + pow(box_hole_dy/2,2));
_mag_r  = mag_pcd/2 + mag_d/2;
_cup_bottom = az - cup_od/2;
echo(str(">>> osa antény: X=", axp, "  Y=", ant_y, "  Z=", az, " mm"));
echo(str(">>> objimka sahá od Z=", _cup_bottom, " do Z=", az + cup_od/2, " mm"));
echo(str(">>> presah pres bocni hranu telefonu = ", axp + cup_od/2 - phone_w/2, " mm"));
echo(str(">>> antena vycniva z objimky = ", ant_h - cup_depth, " mm"));
echo(str(">>> jazyk desky: X=", tab_x0, "..", tab_x1,
         " (pres hranu telefonu o ", max(0, tab_x1 - phone_w/2), " mm)"));
assert(_hole_r > _mag_r + 1.0 || magnet_mode != "discs",
       "KOLIZE: montazni diry krabicky zasahuji do prstence magnetu");
assert(plate_t - mag_t - mag_recess >= 0.8 || magnet_mode != "discs",
       "PRILIS TENKY STROP NAD MAGNETEM - zvets plate_t");
assert(ant_y > box_y - box_d/2, "antena mimo rozsah krabicky");

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
                offset(r = r_plan - r_edge)
                    square([w - 2*r_plan, d - 2*r_plan], center = true);
            sphere(r = r_edge);
        }
        translate([0, 0, t/2]) cube([w + 20, d + 20, t], center = true);
    }
}

module at_cup() { translate([axp, ant_y, az]) rotate([-90, 0, 0]) children(); }

module joint_screws(d, h, z0) {
    for (y = [-1, 1])
        translate([joint_x, y*joint_dy/2, z0]) cylinder(d = d, h = h);
}

// ---------------------------------------------------------------- //
//  DIL 1 - DESKA                                                    //
// ---------------------------------------------------------------- //
module plate_body() {
    hull() {
        translate([box_x, box_y, 0]) soft_slab(box_w, box_d, corner_r, plate_t, edge_r);
        translate([(tab_x0 + tab_x1)/2, box_y, 0])
            soft_slab(tab_len + 6, tab_d, 8, plate_t, edge_r);
    }
}

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

module deska() {
    difference() {
        plate_body();
        magnet_cuts();
        box_screw_cuts();
        // spojovaci srouby - zapustene ze strany telefonu
        joint_screws(joint_screw_d, plate_t + 2*eps, -eps);
        joint_screws(joint_head_d, (joint_head_d - joint_screw_d)/2 + eps, -eps);
    }
}

// ---------------------------------------------------------------- //
//  DIL 2 - OBJIMKA ANTENY                                           //
// ---------------------------------------------------------------- //
module cup_solid() {
    minkowski() {
        cylinder(d = cup_od - 2*rim_r, h = cup_len - 2*rim_r);
        sphere(r = rim_r);
    }
}

// zaoblene kvadr
module rbox(w, d, h, r) {
    minkowski() {
        cube([max(w - 2*r, 0.1), max(d - 2*r, 0.1), max(h - 2*r, 0.1)], center = true);
        sphere(r = r);
    }
}

module foot_solid() {
    translate([(tab_x0 + tab_x1)/2, box_y, plate_t])
        soft_slab(tab_len + 4, tab_d - 4, 8, foot_t, 1.8);
}

// rameno: retez zaoblenych kvadru od patky k dnu objimky
module arm_solid() {
    s0 = [[joint_x,      box_y,      plate_t + foot_t/2 ], [tab_len + 4, tab_d - 4, foot_t,      2.0]];
    s1 = [[joint_x + 7,  ant_y*0.40, plate_t + 5        ], [16,          tab_d - 8, foot_t + 6,  3.0]];
    s2 = [[joint_x + 15, ant_y*0.80, az - 3             ], [20,          22,        18,          4.0]];
    for (p = [[s0, s1], [s1, s2]])
        hull() for (q = p)
            translate(q[0]) rbox(q[1][0], q[1][1], q[1][2], q[1][3]);
    hull() {
        translate(s2[0]) rbox(s2[1][0], s2[1][1], s2[1][2], s2[1][3]);
        at_cup() cylinder(d = cup_od - 2*rim_r, h = 2);   // dno objimky
    }
}

module objimka_body() {
    difference() {
        union() {
            at_cup() cup_solid();
            foot_solid();
            arm_solid();
        }
        // nic nesmi zasahovat do desky ani do telefonu
        translate([-200, -200, -200 + plate_t])
            cube([200 + phone_w/2 + 2, 400, 200]);
    }
}

module objimka() {
    difference() {
        objimka_body();
        // duti pro telo antény
        at_cup() translate([0, 0, cup_base])
            cylinder(d = ant_d + 0.6, h = cup_depth + ant_h);
        // 3x M3 do patky antény
        at_cup() for (i = [0 : 2])
            rotate([0, 0, 120*i + 90])
                translate([ant_pcd/2, 0, -eps])
                    cylinder(d = ant_screw_d, h = cup_base + 2*eps);
        // konektor a kabel
        at_cup() translate([0, 0, -eps]) cylinder(d = ant_conn_d, h = cup_base + 2*eps);
        // diry pro spojovaci srouby (matice / samorez)
        joint_screws(joint_boss_d, foot_t + eps, plate_t - eps);
    }
}

// ---------------------------------------------------------------- //
//  NAHLED                                                           //
// ---------------------------------------------------------------- //
module ghost() {
    %translate([box_x, box_y, plate_t]) rrect(box_w, box_d, corner_r, box_h);
    %at_cup() translate([0, 0, cup_base]) cylinder(d = ant_d, h = ant_h);
    %translate([0, 0, -phone_t]) rrect(phone_w, phone_l, 12, phone_t);
    %translate([cam_x, cam_y, 0]) rrect(cam_w, cam_d, 10, cam_h);
}

if (part == "deska")        deska();
else if (part == "objimka") objimka();
else { deska(); objimka(); if (show_ghost) ghost(); }
