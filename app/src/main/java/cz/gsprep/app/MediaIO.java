package cz.gsprep.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Size;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Decoding of photos / video frames and saving results (MediaStore + EXIF). */
final class MediaIO {
    private MediaIO() {}

    static final String OUT_DIR = "GSPrep";

    static String displayName(ContentResolver cr, Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (name == null) {
            name = uri.getLastPathSegment();
            if (name == null) name = "obrazek";
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
        }
        return name;
    }

    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        String b = dot > 0 ? name.substring(0, dot) : name;
        return b.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Photo with EXIF orientation applied, sRGB, ARGB_8888, long side <= cap. */
    static Bitmap decodePhoto(ContentResolver cr, Uri uri, final int cap) throws IOException {
        ImageDecoder.Source src = ImageDecoder.createSource(cr, uri);
        Bitmap b = ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                Size sz = info.getSize();
                int w = sz.getWidth(), h = sz.getHeight();
                int l = Math.max(w, h);
                if (l > cap) {
                    double f = cap / (double) l;
                    d.setTargetSize(Math.max(1, (int) Math.round(w * f)), Math.max(1, (int) Math.round(h * f)));
                }
            }
        });
        return ensureArgb(b);
    }

    static Bitmap ensureArgb(Bitmap b) {
        if (b == null) return null;
        if (b.getConfig() == Bitmap.Config.ARGB_8888) return b;
        Bitmap c = b.copy(Bitmap.Config.ARGB_8888, false);
        b.recycle();
        if (c == null) throw new OutOfMemoryError("Nelze převést bitmapu");
        return c;
    }

    /** Scales down so that the long side is <= cap (no-op otherwise). */
    static Bitmap limit(Bitmap b, int cap) {
        int l = Math.max(b.getWidth(), b.getHeight());
        if (l <= cap) return b;
        double f = cap / (double) l;
        Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, (int) Math.round(b.getWidth() * f)),
                Math.max(1, (int) Math.round(b.getHeight() * f)), true);
        if (s != b) b.recycle();
        return s;
    }

    // ---------------------------------------------------------------- video

    static final class VideoInfo {
        long durationUs;
        int width, height, rotation;
    }

    static VideoInfo videoInfo(MediaMetadataRetriever r) {
        VideoInfo v = new VideoInfo();
        v.durationUs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000L;
        v.width = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        v.height = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        v.rotation = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        return v;
    }

    private static long parseLong(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Frame at tUs (exact). maxSide > 0 returns a scaled frame for analysis. */
    static Bitmap videoFrame(MediaMetadataRetriever r, VideoInfo vi, long tUs, int maxSide) {
        Bitmap b;
        if (maxSide > 0) {
            b = r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, maxSide, maxSide);
        } else {
            b = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST);
        }
        if (b == null) return null;
        b = fixRotation(b, vi);
        return ensureArgb(b);
    }

    /** The retriever normally returns display-oriented frames; rotate only if it clearly did not. */
    private static Bitmap fixRotation(Bitmap b, VideoInfo vi) {
        int rot = ((vi.rotation % 360) + 360) % 360;
        if ((rot == 90 || rot == 270) && vi.width != vi.height) {
            boolean storedLandscape = vi.width > vi.height;
            boolean bitmapLandscape = b.getWidth() > b.getHeight();
            if (storedLandscape == bitmapLandscape) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                return r;
            }
        }
        return b;
    }

    // ---------------------------------------------------------------- saving

    static final int FMT_JPEG95 = 0, FMT_JPEG100 = 1, FMT_PNG = 2;

    static String extension(int format) {
        return format == FMT_PNG ? ".png" : ".jpg";
    }

    /** Saves into Pictures/GSPrep/&lt;session&gt;/fileName. Copies camera EXIF from exifSource (JPEG only). */
    static Uri save(Context ctx, Bitmap bmp, String session, String fileName, int format, Uri exifSource)
            throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        boolean png = format == FMT_PNG;
        File tmp = null;
        if (!png) {
            tmp = new File(ctx.getCacheDir(), "gsprep_out.jpg");
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, format == FMT_JPEG100 ? 100 : 95, fos)) {
                    throw new IOException("Kódování JPEG selhalo");
                }
            } finally {
                fos.close();
            }
            if (exifSource != null) {
                try {
                    copyExif(cr, exifSource, tmp, bmp.getWidth(), bmp.getHeight());
                } catch (Exception ignored) {
                    // EXIF is a bonus; never fail the image because of it
                }
            }
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Images.Media.MIME_TYPE, png ? "image/png" : "image/jpeg");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + OUT_DIR + "/" + session);
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri out = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
        if (out == null) throw new IOException("Nelze vytvořit výstupní soubor");
        try {
            OutputStream os = cr.openOutputStream(out);
            if (os == null) throw new IOException("Nelze zapisovat do " + out);
            try {
                if (png) {
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new IOException("Kódování PNG selhalo");
                } else {
                    InputStream is = new FileInputStream(tmp);
                    try {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    } finally {
                        is.close();
                    }
                }
            } finally {
                os.close();
            }
        } catch (IOException e) {
            cr.delete(out, null, null);
            throw e;
        } finally {
            if (tmp != null) tmp.delete();
        }
        cv.clear();
        cv.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(out, cv, null, null);
        return out;
    }

    /** Tags that matter for SfM / georeferencing (COLMAP reads focal length + 35 mm equivalent). */
    private static final String[] EXIF_TAGS = {
            "Make", "Model", "FocalLength", "FocalLengthIn35mmFilm", "FNumber", "ApertureValue",
            "ExposureTime", "ISOSpeedRatings", "PhotographicSensitivity", "ExposureBiasValue",
            "WhiteBalance", "Flash", "DateTime", "DateTimeOriginal", "DateTimeDigitized",
            "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized", "OffsetTime",
            "OffsetTimeOriginal", "LensMake", "LensModel", "LensSpecification",
            "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
            "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp",
            "GPSImgDirection", "GPSImgDirectionRef", "GPSMapDatum", "GPSProcessingMethod",
            "GPSHPositioningError", "GPSDOP", "GPSSpeed", "GPSSpeedRef"};

    private static void copyExif(ContentResolver cr, Uri src, File dst, int w, int h) throws IOException {
        InputStream is = cr.openInputStream(src);
        if (is == null) return;
        ExifInterface in;
        try {
            in = new ExifInterface(is);
        } finally {
            is.close();
        }
        ExifInterface out = new ExifInterface(dst.getAbsolutePath());
        for (String tag : EXIF_TAGS) {
            String v = in.getAttribute(tag);
            if (v != null) out.setAttribute(tag, v);
        }
        // Pixels are already rotated upright by the decoder.
        out.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
        out.setAttribute("PixelXDimension", String.valueOf(w));
        out.setAttribute("PixelYDimension", String.valueOf(h));
        out.setAttribute(ExifInterface.TAG_SOFTWARE, "GS Foto Prep");
        out.saveAttributes();
    }
}
