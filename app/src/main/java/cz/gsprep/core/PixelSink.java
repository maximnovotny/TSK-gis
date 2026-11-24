package cz.gsprep.core;

/** Receives the processed image in horizontal strips of ARGB ints (Android Bitmap colour layout). */
public interface PixelSink {
    void strip(int y, int rows, int width, int[] argb);
}
