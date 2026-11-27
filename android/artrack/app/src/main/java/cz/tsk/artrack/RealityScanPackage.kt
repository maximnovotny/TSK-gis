package cz.tsk.artrack

import java.io.File
import java.util.Locale

/** One saved photo, kept in memory so the RealityScan package can be built at stop. */
class PhotoRow(
    val name: String,
    val tRel: Double,
    val x: Double,
    val y: Double,
    val z: Double
)

/** One GPS fix, with the recording time it arrived at. */
class FixRow(
    val tRel: Double,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val accuracy: Float,
    /** How old the fix already was when it arrived [ms]. */
    val ageMs: Long = 0L
)

/**
 * Writes the files RealityScan can import directly:
 *
 *   realityscan/trajectory.csv        name,x,y,z in a projected CRS (UTM)
 *   realityscan/trajectory_space.txt  the same, space separated
 *   realityscan/trajectory_local.csv  name,x,y,z in plain metres, no georeferencing
 *   realityscan/jak-importovat.txt    the import steps plus this session's fit report
 *
 * The photos themselves carry their WGS84 position in EXIF, written at capture
 * time, so the EXIF route needs no files at all.
 */
object RealityScanPackage {

    const val MAX_GPS_ERROR_M = 20f

    /** A fix older than this says where the phone was, not where it is. */
    const val MAX_GPS_AGE_MS = 5_000L

    /** Above this the azimuth from GPS is noise, whatever the fit returns. */
    const val MAX_USEFUL_HEADING_SIGMA_DEG = 25.0

    class Report(
        val images: Int,
        val local: Boolean,
        val epsg: Int?,
        val zone: Int?,
        val fitFixes: Int,
        val fit: Geo.Fit?,
        val altOffset: Double
    ) {
        val crs: String
            get() = if (local) "lokální kartézský systém (metry)"
            else "EPSG:$epsg (UTM ${zone}N)"

        /** Whether the fitted azimuth is worth anything. */
        val headingReliable: Boolean
            get() {
                val sigma = fit?.sigmaDeg ?: return false
                return sigma <= MAX_USEFUL_HEADING_SIGMA_DEG
            }

        /** Just the coordinate system, for the one-line message. */
        fun shortSummary(): String = if (local) "lokální metry" else "EPSG:$epsg"

        /** One short line for the UI. */
        fun summary(): String = if (local) {
            "RealityScan: $images snímků, lokální metry"
        } else if (headingReliable) {
            "RealityScan: $images snímků, EPSG:$epsg" +
                String.format(Locale.US, ", azimut ±%.0f°", fit?.sigmaDeg ?: 0.0)
        } else {
            "RealityScan: $images snímků, EPSG:$epsg, azimut neurčitelný"
        }
    }

    fun write(dir: File, photos: List<PhotoRow>, fixes: List<FixRow>): Report? {
        if (photos.isEmpty()) return null
        val out = File(dir, "realityscan").apply { mkdirs() }

        // Always write the local variant: it needs no GPS and never lies about north.
        writeRows(out, "trajectory_local.csv", null, photos) { p ->
            val h = Geo.arHorizontal(p.x, p.z)
            doubleArrayOf(h[0], h[1], p.y)
        }

        val usable = fixes
            .filter { it.accuracy > 0f && it.accuracy <= MAX_GPS_ERROR_M && it.ageMs <= MAX_GPS_AGE_MS }
            .ifEmpty { fixes.filter { it.ageMs <= MAX_GPS_AGE_MS } }
            .ifEmpty { fixes }
        var report: Report
        if (usable.size < 2) {
            report = Report(photos.size, true, null, null, usable.size, null, 0.0)
        } else {
            val meanLon = usable.sumOf { it.lon } / usable.size
            val meanLat = usable.sumOf { it.lat } / usable.size
            val zone = Geo.utmZoneFor(meanLon)
            val epsg = Geo.epsgFor(meanLat, zone)

            val ar = ArrayList<DoubleArray>()
            val enu = ArrayList<DoubleArray>()
            val altDiffs = ArrayList<Double>()
            for (fix in usable) {
                val pos = interpolate(photos, fix.tRel) ?: continue
                ar.add(Geo.arHorizontal(pos[0], pos[2]))
                enu.add(Geo.latLonToUtm(fix.lat, fix.lon, zone))
                altDiffs.add(fix.alt - pos[1])
            }
            if (ar.size < 2) {
                report = Report(photos.size, true, null, null, ar.size, null, 0.0)
            } else {
                val fit = Geo.fitHeading(ar, enu)
                val altOffset = altDiffs.average()
                writeRows(out, "trajectory.csv", null, photos) { p ->
                    Geo.project(fit, p.x, p.y, p.z, altOffset)
                }
                writeRows(out, "trajectory_space.txt", " ", photos) { p ->
                    Geo.project(fit, p.x, p.y, p.z, altOffset)
                }
                report = Report(photos.size, false, epsg, zone, ar.size, fit, altOffset)
            }
        }

        File(out, "jak-importovat.txt").writeText(instructions(report))
        return report
    }

    private inline fun writeRows(
        dir: File,
        name: String,
        separator: String?,
        photos: List<PhotoRow>,
        project: (PhotoRow) -> DoubleArray
    ) {
        val sep = separator ?: ","
        val sb = StringBuilder()
        for (p in photos) {
            val xyz = project(p)
            sb.append(p.name).append(sep)
            sb.append(String.format(Locale.US, "%.4f", xyz[0])).append(sep)
            sb.append(String.format(Locale.US, "%.4f", xyz[1])).append(sep)
            sb.append(String.format(Locale.US, "%.4f", xyz[2])).append('\n')
        }
        File(dir, name).writeText(sb.toString())
    }

    /** AR position at recording time [t], linearly interpolated between photos. */
    private fun interpolate(photos: List<PhotoRow>, t: Double): DoubleArray? {
        if (photos.isEmpty()) return null
        if (t <= photos.first().tRel) {
            val p = photos.first()
            return doubleArrayOf(p.x, p.y, p.z)
        }
        if (t >= photos.last().tRel) {
            val p = photos.last()
            return doubleArrayOf(p.x, p.y, p.z)
        }
        var lo = 0
        var hi = photos.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (photos[mid].tRel <= t) lo = mid else hi = mid
        }
        val a = photos[lo]
        val b = photos[hi]
        val span = b.tRel - a.tRel
        val w = if (span > 0.0) (t - a.tRel) / span else 0.0
        return doubleArrayOf(
            a.x + (b.x - a.x) * w,
            a.y + (b.y - a.y) * w,
            a.z + (b.z - a.z) * w
        )
    }

    private fun instructions(r: Report): String {
        val fitBlock = if (r.local) {
            "Georeferencování: VYNECHÁNO (málo použitelných GPS fixů), trajektorie je\n" +
                "jen v lokálních metrech."
        } else {
            val fit = r.fit!!
            val sigma = fit.sigmaDeg
            buildString {
                append(String.format(Locale.US, "GPS fixů pro fit : %d\n", r.fitFixes))
                append(String.format(Locale.US, "azimut AR osy +X : %.2f° od východu\n", fit.headingDeg))
                append(String.format(Locale.US, "odchylka fitu    : %.2f m (RMS)\n", fit.rms))
                append(String.format(Locale.US, "délka trasy      : %.2f m\n", fit.extent))
                if (sigma != null) {
                    if (sigma > 45.0) {
                        append("nejistota azimutu: NEURČITELNÝ (> ±45°)\n")
                    } else {
                        append(String.format(Locale.US, "nejistota azimutu: ±%.1f°\n", sigma))
                    }
                }
                append(String.format(Locale.US, "posun výšky      : %+.2f m\n", r.altOffset))
                if (!r.headingReliable) {
                    append("\nPOZOR: azimut je z téhle trasy neurčitelný - trasa je krátká proti\n")
                    append("šumu GPS. Pozice kamer tím trpí jen málo (drží je AR trajektorie),\n")
                    append("ale otočení modelu k severu z těchto dat NEVĚŘ. Použij\n")
                    append("trajectory_local.csv a model natoč v GISu, nebo projdi při záznamu\n")
                    append("delší trasu - na ±5° je potřeba řádově 30 m.\n")
                }
            }
        }

        val crsStep = if (r.local) {
            "1) Nastav souřadnicový systém projektu na lokální kartézský v metrech.\n"
        } else {
            "1) Nastav souřadnicový systém projektu (project/output CRS) na " +
                "EPSG:${r.epsg}\n   (UTM zóna ${r.zone}N, WGS84). Musí to být PŘED importem trajektorie.\n"
        }

        val trajectoryFile = if (r.local) "trajectory_local.csv" else "trajectory.csv"

        return """
ARTrack -> RealityScan
======================

snímků           : ${r.images}
souřadnicový systém: ${r.crs}
$fitBlock

Co je v balíčku
---------------
frames/                fotky s GPS zapsaným přímo v EXIFu (plné rozlišení)
realityscan/$trajectoryFile
                       pozice kamer: name,x,y,z
realityscan/trajectory_space.txt
                       totéž oddělené mezerami (fallback, kdyby import nebral čárky)
realityscan/trajectory_local.csv
                       pozice v lokálních metrech, bez georeferencování
frames.csv, pose.csv   pózy a kalibrace ke každé fotce / každému snímku
gps.csv, track.gpx     GPS záznam
meta.json, README.txt  metadata a popis sloupců

Cesta A - trajektorie jako position priors (doporučeno)
-------------------------------------------------------
${crsStep}2) Načti fotky ze složky frames/.
3) WORKFLOW -> Import Metadata -> Trajectory (ve starších verzích Flight log).
4) Vyber realityscan/$trajectoryFile a jako popis sloupců zadej:

       name x y z

   Pozor: když popis sloupců nesedí, import neohlásí chybu - jen sloupce
   zahodí. Po importu zkontroluj u pár kamer, že mají prior pozici.
5) V nastavení alignmentu zapni použití camera priors (position prior).
6) Align Images.

Měřítko a tvar trasy drží AR tracking (relativně centimetry), absolutní poloha
je z GPS (jednotky metrů). Rotace se neimportují - orientaci si alignment
spočítá sám.

Cesta B - jen EXIF (nic neimportuješ)
-------------------------------------
Fotky už GPS v EXIFu mají, takže stačí načíst frames/ a RealityScan si polohy
vezme sám. Je to nejjednodušší, ale měřítko pak drží jen GPS, ne AR trasa.
Obě cesty jdou kombinovat.

Pozn.: v repozitáři je navíc tools/artrack_export.py, který ze stejných dat
umí COLMAP (cameras.txt/images.txt), ruční zadání azimutu (--heading) a
experimentální XMP sidecary s plnou pózou (--xmp).
""".trimStart()
    }
}
